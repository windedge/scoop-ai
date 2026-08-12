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
    fun `createGenerator registers cache manager on DefaultToolRegistryFactory`() {
        DefaultScoopAiFactory.createGenerator(
            ScoopAiConfig(
                provider = "deepseek",
                apiKey = "sk-fake",
                nonInteractive = true,
            )
        )
        assertNotNull(DefaultToolRegistryFactory.getCacheManager())
        assertNotNull(DefaultToolRegistryFactory.getToolBudget())
    }
}
