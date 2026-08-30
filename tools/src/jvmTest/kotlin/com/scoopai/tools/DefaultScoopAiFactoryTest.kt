package com.scoopai.tools

import com.scoopai.tools.interaction.SafeAskUser
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
}
