package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.serialization.kotlinx.KotlinxSerializer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for [autoFixInvalidHashes]: deterministic repair of hashes the LLM
 * mangled while transcribing them from tool results (dropped characters turn
 * a 64-char hex hash into an invalid value that VERIFY cannot fix via repair).
 */
class HashAutoFixTest {

    private val goodHash64 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    private val goodHash64Alt = "f0e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1b2a3f4e5d6c7b8a9f0e1"
    private val truncatedHash = goodHash64.drop(3) // 61 chars, simulates LLM dropped tokens

    // ---------------------------------------------------------------
    // basic repair
    // ---------------------------------------------------------------

    @Test
    fun `truncated flat hash is replaced with same-url source hash`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app-1.0.zip","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app-1.0.zip" to goodHash64))
        assertEquals(goodHash64, Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncated hash inside architecture block is replaced`() {
        val manifest = """{"version":"1.0","architecture":{"64bit":{"url":"https://example.com/app-x64.zip","hash":"$truncatedHash"}}}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app-x64.zip" to goodHash64))
        val arch = Json.parseToJsonElement(fixed!!).jsonObject["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals(goodHash64, arch["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `other fields are preserved through the fix`() {
        val manifest = """{"version":"1.0","homepage":"https://example.com","url":"https://example.com/app.zip","hash":"$truncatedHash","bin":"app.exe"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64))
        val obj = Json.parseToJsonElement(fixed!!).jsonObject
        assertEquals("1.0", obj["version"]!!.jsonPrimitive.content)
        assertEquals("app.exe", obj["bin"]!!.jsonPrimitive.content)
        assertEquals(goodHash64, obj["hash"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------
    // multiple assets and architectures
    // ---------------------------------------------------------------

    @Test
    fun `multiple architectures are fixed per url`() {
        val manifest = """{"version":"1.0","architecture":{""" +
            """"64bit":{"url":"https://example.com/app-x64.zip","hash":"${goodHash64.drop(10)}"},""" +
            """"32bit":{"url":"https://example.com/app-x86.zip","hash":"${goodHash64Alt.drop(5)}"}}}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf(
            "https://example.com/app-x64.zip" to goodHash64,
            "https://example.com/app-x86.zip" to goodHash64Alt,
        ))
        val arch = Json.parseToJsonElement(fixed!!).jsonObject["architecture"]!!.jsonObject
        assertEquals(goodHash64, arch["64bit"]!!.jsonObject["hash"]!!.jsonPrimitive.content)
        assertEquals(goodHash64Alt, arch["32bit"]!!.jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hash array position is matched with url array position`() {
        val manifest = """{"version":"1.0","url":["https://example.com/a.zip","https://example.com/b.zip"],""" +
            """"hash":["${goodHash64.drop(2)}","${goodHash64Alt.drop(7)}"]}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf(
            "https://example.com/a.zip" to goodHash64,
            "https://example.com/b.zip" to goodHash64Alt,
        ))
        val obj = Json.parseToJsonElement(fixed!!).jsonObject
        assertEquals(goodHash64, obj["hash"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(goodHash64Alt, obj["hash"]!!.jsonArray[1].jsonPrimitive.content)
    }

    // ---------------------------------------------------------------
    // no-match cases return null
    // ---------------------------------------------------------------

    @Test
    fun `invalid hash with no matching source returns null`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/other.zip" to goodHash64))
        assertNull(fixed)
    }

    @Test
    fun `all valid hashes returns null`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64"}"""
        assertNull(autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64)))
    }

    @Test
    fun `empty expected hashes returns null`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$truncatedHash"}"""
        assertNull(autoFixInvalidHashes(manifest, emptyMap()))
    }

    @Test
    fun `missing hash is left to the normal repair path`() {
        // A missing hash is a different validation error ("No hash field found");
        // the autofix only handles values that are present but malformed.
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip"}"""
        assertNull(autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64)))
    }

    @Test
    fun `unparseable manifest returns null`() {
        assertNull(autoFixInvalidHashes("not json", mapOf("https://example.com/app.zip" to goodHash64)))
    }

    @Test
    fun `unfixable url blocks the whole fix even when other arch is fixable`() {
        // Fail-closed: a partial fix could pass validation with a wrong digest.
        val manifest = """{"version":"1.0","architecture":{""" +
            """"64bit":{"url":"https://example.com/app-x64.zip","hash":"$truncatedHash"},""" +
            """"32bit":{"url":"https://unknown.example.org/mystery.zip","hash":"${goodHash64Alt.drop(9)}"}}}"""
        assertNull(autoFixInvalidHashes(manifest, mapOf("https://example.com/app-x64.zip" to goodHash64)))
    }

    // ---------------------------------------------------------------
    // case handling
    // ---------------------------------------------------------------

    @Test
    fun `uppercase invalid hash is fixed with verbatim source hash`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"${truncatedHash.uppercase()}"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64))
        assertEquals(goodHash64, Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `uppercase source hash matches lowercase url lookup`() {
        val manifest = """{"version":"1.0","url":"https://Example.com/app.zip","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64.uppercase()))
        assertEquals(goodHash64.uppercase(), Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `valid uppercase hash is not touched`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"${goodHash64.uppercase()}"}"""
        assertNull(autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64)))
    }

    @Test
    fun `uppercase hash array elements are fixed per position`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":["${truncatedHash.uppercase()}","$goodHash64"]}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64))
        val hashes = Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonArray
        assertEquals(goodHash64, hashes[0].jsonPrimitive.content)
        assertEquals(goodHash64, hashes[1].jsonPrimitive.content)
    }

    // ---------------------------------------------------------------
    // url resolution variants
    // ---------------------------------------------------------------

    @Test
    fun `manifest url with scoop fragment matches source url without fragment`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip#/dl.7z","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://example.com/app.zip" to goodHash64))
        assertEquals(goodHash64, Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unique filename match resolves when urls differ`() {
        val manifest = """{"version":"1.0","url":"https://mirror.example.org/files/app-1.0.zip#/dl.7z","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf("https://github.com/ex/app/releases/download/v1.0/app-1.0.zip" to goodHash64))
        assertEquals(goodHash64, Json.parseToJsonElement(fixed!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ambiguous filename match returns null`() {
        val manifest = """{"version":"1.0","url":"https://mirror.example.org/app.zip","hash":"$truncatedHash"}"""
        val fixed = autoFixInvalidHashes(manifest, mapOf(
            "https://github.com/ex/app/releases/download/v1.0/app.zip" to goodHash64,
            "https://example.com/app.zip" to goodHash64Alt,
        ))
        assertNull(fixed)
    }

    // ---------------------------------------------------------------
    // expectedHashesFromOutputs
    // ---------------------------------------------------------------

    @Test
    fun `expectedHashesFromOutputs prefers analyze over research`() {
        val research = ResearchOutput(
            windows_assets = listOf(WindowsAsset(url = "https://example.com/app.zip", hash = goodHash64, filename = "app.zip")),
        )
        val analyze = AnalyzeOutput(entries = listOf(AssetEntry(architecture = "64bit", url = "https://example.com/app.zip", hash = goodHash64Alt, filename = "app.zip")))
        val expected = expectedHashesFromOutputs(analyze, research)
        assertEquals(goodHash64Alt, expected["https://example.com/app.zip"])
    }

    @Test
    fun `expectedHashesFromOutputs skips non-hash-shaped values`() {
        val research = ResearchOutput(
            windows_assets = listOf(WindowsAsset(url = "https://example.com/app.zip", hash = "TBD")),
        )
        assertTrue(expectedHashesFromOutputs(null, research).isEmpty())
    }

    @Test
    fun `expectedHashesFromOutputs indexes filename as well as url`() {
        val analyze = AnalyzeOutput(entries = listOf(AssetEntry(architecture = "64bit", url = "https://example.com/app-1.0.zip", hash = goodHash64, filename = "app-1.0.zip")))
        val expected = expectedHashesFromOutputs(analyze, null)
        assertEquals(goodHash64, expected["app-1.0.zip"])
    }

    // ---------------------------------------------------------------
    // ManifestVerifier wiring (autofix runs before validation and does
    // not consume a repair round)
    // ---------------------------------------------------------------

    private val recordingManifestIo = object : com.scoopai.core.tools.api.ManifestIo {
        var lastValidated: String? = null
        override fun validate(json: String?): String {
            lastValidated = json
            val valid = json?.contains(goodHash64) == true
            return if (valid) """{"valid":true,"errors":[]}"""
            else """{"valid":false,"errors":["Field 'hash' is not a valid SHA-256 hash (expected 64 hex chars): mangled"]}"""
        }
        override fun write(json: String?, path: String?): String = ""
        override fun read(path: String?): String = ""
    }

    private fun testStorage() = AIAgentStorage(KotlinxSerializer())

    private fun verifier(
        usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    ) = ManifestVerifier(
        manifestIo = recordingManifestIo,
        reviewExecutor = null,
        usedAppNamesProvider = usedAppNamesProvider,
        maxRetries = 3,
    )

    private fun verifyContext(
        storage: AIAgentStorage,
        retryKey: AIAgentStorageKey<Int>,
        finalKey: AIAgentStorageKey<String>,
        expectedHashes: Map<String, String> = emptyMap(),
    ) = ManifestVerifier.VerifyContext(
        storage = storage,
        retryCountKey = retryKey,
        expectedHashes = expectedHashes,
        finalManifestSetter = { storage.set(finalKey, it) },
    )

    @Test
    fun `verify autofixes hash and passes without consuming a repair round`() = runBlocking {
        val retryKey = createStorageKey<Int>("test_retry")
        val finalKey = createStorageKey<String>("test_final")
        val storage = testStorage()
        storage.set(retryKey, 0)
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$truncatedHash"}"""
        val outcome = verifier().verify(
            manifest,
            verifyContext(storage, retryKey, finalKey, expectedHashes = mapOf("https://example.com/app.zip" to goodHash64)),
        )
        assertTrue(outcome.passed, "fixed manifest must pass validation")
        assertEquals(0, storage.get(retryKey), "autofix must not consume a repair round")
        assertEquals(goodHash64, Json.parseToJsonElement(storage.get(finalKey)!!).jsonObject["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `verify without a resolvable source falls back to the repair loop`() = runBlocking {
        val retryKey = createStorageKey<Int>("test_retry")
        val finalKey = createStorageKey<String>("test_final")
        val storage = testStorage()
        storage.set(retryKey, 0)
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$truncatedHash"}"""
        val outcome = verifier().verify(
            manifest,
            verifyContext(storage, retryKey, finalKey, expectedHashes = mapOf("https://example.com/other.zip" to goodHash64)),
        )
        assertTrue(!outcome.passed, "unfixable hash must reach the repair loop")
        assertEquals(1, storage.get(retryKey))
        assertTrue(outcome.issues.single().contains("not a valid SHA-256 hash"))
    }

    @Test
    fun `verify passes valid manifest through unchanged`() = runBlocking {
        val retryKey = createStorageKey<Int>("test_retry")
        val finalKey = createStorageKey<String>("test_final")
        val storage = testStorage()
        storage.set(retryKey, 0)
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64"}"""
        val outcome = verifier().verify(
            manifest,
            verifyContext(storage, retryKey, finalKey, expectedHashes = mapOf("https://example.com/app.zip" to goodHash64)),
        )
        assertTrue(outcome.passed)
        assertEquals(0, storage.get(retryKey))
    }

    // ---------------------------------------------------------------
    // assist name-conflict exemption (referenced-manifest in-place update)
    // ---------------------------------------------------------------

    @Test
    fun `assist conflict matching the referenced base name is exempted`() = runBlocking {
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        val conflictKey = createStorageKey<String>("t_conflict")
        val suggestedKey = createStorageKey<String>("t_suggested")
        val baseNameKey = createStorageKey<String>("t_assist_base")
        val storage = testStorage()
        storage.set(retryKey, 1)
        storage.set(suggestedKey, "fd")
        storage.set(baseNameKey, "fd")
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64"}"""
        val ctx = verifier(usedAppNamesProvider = { setOf("fd") }).verify(
            manifest,
            ManifestVerifier.VerifyContext(
                storage = storage,
                retryCountKey = retryKey,
                suggestedAppNameKey = suggestedKey,
                appNameConflictKey = conflictKey,
                assistBaseNameKey = baseNameKey,
                finalManifestSetter = { storage.set(finalKey, it) },
            ),
        )
        assertTrue(ctx.passed, "name-identical in-place assist must pass verify")
        assertNull(storage.get(conflictKey), "no conflict notice for the referenced name itself")
    }

    @Test
    fun `assist conflict exemption is case-insensitive on the base name`() = runBlocking {
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        val conflictKey = createStorageKey<String>("t_conflict")
        val suggestedKey = createStorageKey<String>("t_suggested")
        val baseNameKey = createStorageKey<String>("t_assist_base")
        val storage = testStorage()
        storage.set(retryKey, 1)
        storage.set(suggestedKey, "FD")
        storage.set(baseNameKey, "fd")
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64"}"""
        val ctx = verifier(usedAppNamesProvider = { setOf("FD") }).verify(
            manifest,
            ManifestVerifier.VerifyContext(
                storage = storage,
                retryCountKey = retryKey,
                suggestedAppNameKey = suggestedKey,
                appNameConflictKey = conflictKey,
                assistBaseNameKey = baseNameKey,
                finalManifestSetter = { storage.set(finalKey, it) },
            ),
        )
        assertTrue(ctx.passed)
        assertNull(
            storage.get(conflictKey),
            "a case-only difference from the assist base name must be exempted, not reported",
        )
    }

    @Test
    fun `assist conflict against a different name is still reported and generate stays conflict-reporting`() = runBlocking {
        val retryKey = createStorageKey<Int>("t_retry")
        val finalKey = createStorageKey<String>("t_final")
        val conflictKey = createStorageKey<String>("t_conflict")
        val suggestedKey = createStorageKey<String>("t_suggested")
        val baseNameKey = createStorageKey<String>("t_assist_base")
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip","hash":"$goodHash64"}"""

        // Assist session that renamed the app away from the referenced base name.
        val assistStorage = testStorage()
        assistStorage.set(retryKey, 1)
        assistStorage.set(suggestedKey, "micro")
        assistStorage.set(baseNameKey, "fd")
        val assistCtx = verifier(usedAppNamesProvider = { setOf("micro") }).verify(
            manifest,
            ManifestVerifier.VerifyContext(
                storage = assistStorage,
                retryCountKey = retryKey,
                suggestedAppNameKey = suggestedKey,
                appNameConflictKey = conflictKey,
                assistBaseNameKey = baseNameKey,
                finalManifestSetter = { assistStorage.set(finalKey, it) },
            ),
        )
        assertTrue(assistCtx.passed, "conflict is a non-blocking notice, verify still passes")
        assertTrue(
            assistStorage.get(conflictKey)?.contains("micro") == true,
            "a real conflict (name differs from the reference) must still be recorded",
        )

        // Generate session without an assist base name keeps reporting the conflict.
        val generateStorage = testStorage()
        generateStorage.set(retryKey, 0)
        generateStorage.set(suggestedKey, "fd")
        val generateCtx = verifier(usedAppNamesProvider = { setOf("fd") }).verify(
            manifest,
            ManifestVerifier.VerifyContext(
                storage = generateStorage,
                retryCountKey = retryKey,
                suggestedAppNameKey = suggestedKey,
                appNameConflictKey = conflictKey,
                finalManifestSetter = { generateStorage.set(finalKey, it) },
            ),
        )
        assertTrue(generateCtx.passed, "conflict is a non-blocking notice, verify still passes")
        assertTrue(
            generateStorage.get(conflictKey)?.contains("fd") == true,
            "generate path must keep reporting the conflict",
        )
    }
}
