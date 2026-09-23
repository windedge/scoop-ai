package com.scoopai.tools

import com.scoopai.tools.interaction.SafeAskUser
import com.scoopai.tools.verify.ProcessScoopVerifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultScoopAiFactoryTest {

    @AfterEach
    fun tearDown() {
        SafeAskUser.nonInteractiveOverride = null
        DefaultToolRegistryFactory.cleanup()
    }

    // ---------------------------------------------------------------
    // createGenerator construction
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator returns non-null generator with fake api key`() {
        val generator = DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake-test-key",
                nonInteractive = true,
            )
        )
        assertNotNull(generator)
    }

    @Test
    fun `createGenerator with openai-compatible and baseUrl succeeds`() {
        val generator = DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "openai-compatible",
                apiKey = "sk-fake",
                baseUrl = "http://localhost:8080/v1",
                model = "opencode/deepseek-v4-flash",
                nonInteractive = true,
            )
        )
        assertNotNull(generator)
    }

    @Test
    fun `createGenerator with onPhase callback does not throw`() {
        var phaseSeen = false
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            onPhase = { phaseSeen = true },
        )
        assertNotNull(generator)
        assertTrue(!phaseSeen) // only invoked during generate()
    }

    @Test
    fun `createGenerator with onPhaseOutput callback constructs successfully`() {
        var outputSeen = false
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            onPhaseOutput = { _, _ -> outputSeen = true },
        )
        assertNotNull(generator)
        assertTrue(!outputSeen) // only invoked during generate()
    }

    @Test
    fun `createGenerator with onToolActivity callback constructs successfully`() {
        var activitySeen = false
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            onToolActivity = { activitySeen = true },
        )
        assertNotNull(generator)
        assertTrue(!activitySeen)
    }

    // ---------------------------------------------------------------
    // readManifestRefHandler wiring
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator accepts readManifestRefHandler and constructs successfully`() {
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            readManifestRefHandler = { _, _ -> "{\"success\":false,\"error\":\"nope\"}" },
        )
        assertNotNull(generator)
    }

    // ---------------------------------------------------------------
    // nonInteractive wiring
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator with nonInteractive true configures SafeAskUser`() {
        DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            )
        )
        assertEquals(true, SafeAskUser.nonInteractiveOverride)
    }

    @Test
    fun `createGenerator with nonInteractive false configures SafeAskUser`() {
        DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = false,
            )
        )
        assertEquals(false, SafeAskUser.nonInteractiveOverride)
    }

    // ---------------------------------------------------------------
    // Tool registry side effects
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator with nonInteractive true leaves downloadConfirmer null when no askUserHandler`() {
        DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            )
        )
        val cache = DefaultToolRegistryFactory.getCacheManager()
        assertNotNull(cache)
        assertEquals(null, cache?.downloadConfirmer)
    }

    @Test
    fun `createGenerator with nonInteractive false sets downloadConfirmer for CLI interactive mode`() {
        DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = false,
            )
        )
        val cache = DefaultToolRegistryFactory.getCacheManager()
        assertNotNull(cache)
        assertNotNull(cache?.downloadConfirmer)
    }

    @Test
    fun `createGenerator with questionnaireHandler sets downloadConfirmer`() {
        DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            questionnaireHandler = { "yes" },
        )
        val cache = DefaultToolRegistryFactory.getCacheManager()
        assertNotNull(cache)
        assertNotNull(cache?.downloadConfirmer)
    }
    @Test
    fun `createGenerator with skipDownloadConfirm true leaves downloadConfirmer null even with questionnaireHandler`() {
        DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
                skipDownloadConfirm = true,
            ),
            questionnaireHandler = { "yes" },
        )
        val cache = DefaultToolRegistryFactory.getCacheManager()
        assertNotNull(cache)
        assertEquals(null, cache?.downloadConfirmer)
    }

    @Test
    fun `createGenerator with skipDownloadConfirm true leaves downloadConfirmer null even with askUserHandler`() {
        DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = false,
                skipDownloadConfirm = true,
            ),
            askUserHandler = { "yes" },
        )
        val cache = DefaultToolRegistryFactory.getCacheManager()
        assertNotNull(cache)
        assertEquals(null, cache?.downloadConfirmer)
    }

    // ---------------------------------------------------------------
    // save_to_bucket host handler pass-through (P2)
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator accepts saveToBucketHandler without throwing`() {
        var invoked = false
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            saveToBucketHandler = { _, _, _ ->
                invoked = true
                """{"success":true,"path":"x"}"""
            },
        )
        assertNotNull(generator)
        assertEquals(false, invoked, "construction must not invoke the handler")
    }

    // ---------------------------------------------------------------
    // install_app host handler pass-through (P3)
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator accepts installAppHandler without throwing`() {
        var invoked = false
        val generator = DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            ),
            installAppHandler = { _, _ ->
                invoked = true
                """{"success":true,"app":"fd"}"""
            },
        )
        assertNotNull(generator)
        assertEquals(false, invoked, "construction must not invoke the handler")
    }

    // ---------------------------------------------------------------
    // Install verification is always on: no config switch gates the
    // verifier creation, so the wired verifiers resolve exactly when a
    // bare scoop probe succeeds on this machine.
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator wires verifiers unconditionally without a config switch`() {
        val generator = DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            )
        )
        val expected = ProcessScoopVerifier.tryCreate() != null
        for (fieldName in listOf("scoopVerifier", "assistVerifier")) {
            val field = generator.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            assertEquals(
                expected,
                field.get(generator) != null,
                "$fieldName must be attempted unconditionally; outcome matches the scoop probe",
            )
        }
    }
}
