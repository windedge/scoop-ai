package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end mock test: a model that only ever returns plain Text (never a
 * tool call) makes Koog's subgraphWithTask throw its "Unable to finish
 * subgraph with task" IllegalStateException. [ScoopManifestGenerator] must
 * translate that into [ScoopAiGenerationException] carrying an actionable
 * hint instead of leaking the raw Koog error.
 */
class ScoopManifestGeneratorToolCallExhaustionMockTest {

    @Test
    fun `text-only model responses are translated to ScoopAiGenerationException with hint`() = runBlocking {
        // Every LLM request returns a plain text answer (never a tool call),
        // simulating a model/gateway without function calling support.
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("Sorry, I cannot call tools, here is my answer as text.").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val e = assertFailsWith<ScoopAiGenerationException> {
            generator.generate("https://github.com/sharkdp/fd")
        }

        val message = e.message ?: ""
        assertContains(message, "deepseek-v4-flash", message = "hint should name the model")
        assertContains(message, "research", message = "hint should name the failing phase")
        assertContains(message, "function calling", message = "hint should state the likely cause")
        assertContains(message, "SCOOP_AI_DEBUG_HTTP", message = "hint should tell how to diagnose")
        // Original Koog error must be preserved as cause for debugging
        assertTrue(
            e.cause?.message?.contains("Unable to finish subgraph with task") == true,
            "original Koog error should be kept as cause, got: ${e.cause}"
        )
    }
}
