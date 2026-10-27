package com.scoopai.core.agent

import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.scoopai.core.agent.ManifestVerifier.VerifyContext
import com.scoopai.core.tools.api.ManifestIo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ManifestVerifier]: the verify pass extracted out of the strategy
 * graph. Ownership under test is the pipeline itself (structure, deterministic
 * backstops, name-conflict notice, semantic review, retry counter), not the
 * graph edges that drive it.
 *
 * Feedback wording and retry-counter semantics must match what the former
 * in-graph `validateManifest` produced, so the assertions pin the exact texts
 * the backstops and the review parser emit.
 */
class ManifestVerifierTest {

    private val goodHash64 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

    private val validManifest =
        """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64","bin":"app.exe"}"""

    /** Structural validation backend that always accepts the manifest. */
    private val passingIo = object : ManifestIo {
        var validated: String? = null
        override fun validate(json: String?): String {
            validated = json
            return """{"valid":true,"errors":[]}"""
        }
        override fun write(json: String?, path: String?): String = ""
        override fun read(path: String?): String = ""
    }

    /** Structural validation backend that reports the sample error used elsewhere in the suite. */
    private val failingIo = object : ManifestIo {
        override fun validate(json: String?): String =
            """{"valid":false,"errors":["sample structural validation error"]}"""
        override fun write(json: String?, path: String?): String = ""
        override fun read(path: String?): String = ""
    }

    private fun verifier(
        manifestIo: ManifestIo? = null,
        reviewExecutor: (suspend (String) -> String)? = null,
        usedAppNamesProvider: (suspend () -> Set<String>)? = null,
        maxRetries: Int = 5,
    ) = ManifestVerifier(
        manifestIo = manifestIo,
        reviewExecutor = reviewExecutor,
        usedAppNamesProvider = usedAppNamesProvider,
        maxRetries = maxRetries,
    )

    private fun storage() = AIAgentStorage(KotlinxSerializer())

    private fun context(
        storage: AIAgentStorage,
        retryKey: AIAgentStorageKey<Int>,
        finalKey: AIAgentStorageKey<String>,
        suggestedAppNameKey: AIAgentStorageKey<String>? = null,
        appNameConflictKey: AIAgentStorageKey<String>? = null,
        assistBaseNameKey: AIAgentStorageKey<String>? = null,
        expectedHashes: Map<String, String> = emptyMap(),
    ) = VerifyContext(
        storage = storage,
        retryCountKey = retryKey,
        suggestedAppNameKey = suggestedAppNameKey,
        appNameConflictKey = appNameConflictKey,
        assistBaseNameKey = assistBaseNameKey,
        expectedHashes = expectedHashes,
        finalManifestSetter = { storage.set(finalKey, it) },
    )

    // ---------------------------------------------------------------
    // VerifyContext construction
    // ---------------------------------------------------------------

    @Test
    fun `VerifyContext defaults leave optional keys and hashes absent`() {
        val store = storage()
        val retryKey = createStorageKey<Int>("ctx_retry")
        val finalKey = createStorageKey<String>("ctx_final")
        val ctx = context(store, retryKey, finalKey)

        assertNull(ctx.suggestedAppNameKey)
        assertNull(ctx.appNameConflictKey)
        assertNull(ctx.assistBaseNameKey)
        assertTrue(ctx.expectedHashes.isEmpty())
        assertEquals(retryKey, ctx.retryCountKey)
    }

    // ---------------------------------------------------------------
    // structural validation
    // ---------------------------------------------------------------

    @Test
    fun `verify passes a valid manifest without touching the retry counter`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        store.set(retryKey, 0)

        val outcome = verifier(manifestIo = passingIo).verify(validManifest, context(store, retryKey, finalKey))

        assertTrue(outcome.passed)
        assertEquals(emptyList(), outcome.issues)
        assertEquals(0, outcome.retryCount)
        assertEquals(0, store.get(retryKey))
        assertEquals(validManifest, store.get(finalKey), "the examined manifest must be stored before validation")
    }

    @Test
    fun `verify reports structural errors and increments the retry counter`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        store.set(retryKey, 0)

        val outcome = verifier(manifestIo = failingIo).verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(listOf("sample structural validation error"), outcome.issues)
        assertEquals(1, outcome.retryCount)
        assertEquals(1, store.get(retryKey))
    }

    @Test
    fun `verify accumulates the retry counter across failing passes`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        store.set(retryKey, 0)
        val v = verifier(manifestIo = failingIo)

        assertEquals(1, v.verify(validManifest, context(store, retryKey, finalKey)).retryCount)
        assertEquals(2, v.verify(validManifest, context(store, retryKey, finalKey)).retryCount)
        assertEquals(2, store.get(retryKey))
    }

    @Test
    fun `verify surfaces a validator exception as an issue`() = runBlocking {
        val throwingIo = object : ManifestIo {
            override fun validate(json: String?): String = error("validator exploded")
            override fun write(json: String?, path: String?): String = ""
            override fun read(path: String?): String = ""
        }
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = throwingIo).verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(1, outcome.issues.size)
        assertTrue(outcome.issues.single().startsWith("Validate error:"))
        assertEquals(1, outcome.retryCount)
    }

    // ---------------------------------------------------------------
    // repair-prompt feedback mapping
    // ---------------------------------------------------------------

    @Test
    fun `toAssistContext formats issues as a dash list and is null on pass`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        store.set(retryKey, 1)

        val failed = verifier(manifestIo = failingIo).verify(validManifest, context(store, retryKey, finalKey))
        val assist = assertNotNull(failed.toAssistContext(validManifest))
        assertEquals("- sample structural validation error", assist.feedback)
        assertEquals(2, assist.retryCount)
        assertEquals(validManifest, assist.manifest)

        val passed = ManifestVerifier.VerifyOutcome(issues = emptyList(), retryCount = 2)
        assertNull(passed.toAssistContext(validManifest))
    }

    // ---------------------------------------------------------------
    // deterministic backstops
    // ---------------------------------------------------------------

    @Test
    fun `verify runs autoupdate and persist backstops without a manifestIo`() = runBlocking {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64",""" +
            """"autoupdate":{"url":"https://example.com/app-1.0.zip"},"persist":["~/.config/app"]}"""
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = null).verify(manifest, context(store, retryKey, finalKey))

        assertEquals(2, outcome.issues.size, "both backstops must fire, got: ${outcome.issues}")
        assertTrue(
            outcome.issues[0].contains("no \$version placeholder"),
            "autoupdate backstop wording must be unchanged, got: ${outcome.issues[0]}",
        )
        assertTrue(
            outcome.issues[1].contains("is not a valid \$dir-relative path"),
            "persist backstop wording must be unchanged, got: ${outcome.issues[1]}",
        )
        assertEquals(1, outcome.retryCount)
    }

    @Test
    fun `verify passes with no manifestIo and no review executor`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier().verify(validManifest, context(store, retryKey, finalKey))

        assertTrue(outcome.passed)
        assertEquals(0, store.get(retryKey) ?: 0)
    }

    // ---------------------------------------------------------------
    // app name conflict notice
    // ---------------------------------------------------------------

    @Test
    fun `verify records an app name conflict as a non-blocking notice`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        val suggestedKey = createStorageKey<String>("t_suggested")
        val conflictKey = createStorageKey<String>("t_conflict")
        store.set(suggestedKey, "app")

        val outcome = verifier(manifestIo = passingIo, usedAppNamesProvider = { setOf("app") })
            .verify(validManifest, context(store, retryKey, finalKey, suggestedKey, conflictKey))

        assertTrue(outcome.passed, "a name conflict must not trigger the repair loop")
        assertTrue(
            store.get(conflictKey)?.contains("conflicts with an existing manifest") == true,
            "conflict notice must be recorded for ManifestResult.issues",
        )
    }

    // ---------------------------------------------------------------
    // semantic review
    // ---------------------------------------------------------------

    @Test
    fun `verify skips the semantic review when structural validation fails`() = runBlocking {
        var reviewCalls = 0
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = failingIo, reviewExecutor = {
            reviewCalls++
            """{"approved":true,"issues":[]}"""
        }).verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(0, reviewCalls, "review must not run once structural validation failed")
        assertEquals(listOf("sample structural validation error"), outcome.issues)
    }

    @Test
    fun `verify appends error-severity review issues when structural validation passes`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = passingIo, reviewExecutor = {
            """```json
            {"approved":false,"issues":[{"severity":"error","message":"checkver is missing"}]}
            ```"""
        }).verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(listOf("checkver is missing"), outcome.issues)
        assertEquals(1, outcome.retryCount)
    }

    @Test
    fun `verify fails closed on an unparseable review result`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = passingIo, reviewExecutor = { "looks good to me" })
            .verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(listOf(REVIEW_UNPARSEABLE), outcome.issues)
        assertEquals(1, outcome.retryCount)
    }

    @Test
    fun `verify surfaces a review agent exception as an issue`() = runBlocking {
        val store = storage()
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")

        val outcome = verifier(manifestIo = passingIo, reviewExecutor = { error("review boom") })
            .verify(validManifest, context(store, retryKey, finalKey))

        assertEquals(1, outcome.issues.size)
        assertEquals("Review agent error: review boom", outcome.issues.single())
        assertEquals(1, outcome.retryCount)
    }
}
