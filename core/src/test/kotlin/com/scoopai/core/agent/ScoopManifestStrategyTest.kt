package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Strategy-level tests for the Scoop manifest generator graph strategy.
 *
 * These tests verify the graph structure and routing logic using mock LLMs.
 * They do NOT test prompt construction or tool execution — those belong
 * in ScoopManifestGeneratorMockTest.
 *
 * Subgraph internal structure (ReAct loop + thinking retry) is tested here
 * via text-only flows (mock returns text, so tool/retry edges are never taken).
 */
class ScoopManifestStrategyTest {

    @Test
    fun `no_windows_assets exits early via condition edge`() = runBlocking {
        val researchJson = """{"status":"no_windows_assets","project":"linux-only","alternatives":"Consider using WSL."}"""

        val promptExecutor = getMockExecutor {
            mockLLMAnswer(researchJson) onRequestContains "https://github.com/linux-only/tool"
            mockLLMAnswer("").asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 30,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/linux-only/tool")
        assertTrue(result.contains("WSL"), "Should return alternatives message, got: $result")
    }

    @Test
    fun `full 3-phase flow produces manifest`() = runBlocking {
        val researchJson = """{"status":"ok","project":"sharkdp/fd","version":"10.2.0","windows_assets":[{"url":"https://example.com/fd.zip","architecture":"64bit","asset_type":"zip","source":"release_api"}]}"""
        val analyzeJson = """{"status":"ok","strategy":"portable_zip","entries":[{"architecture":"64bit","url":"https://example.com/fd.zip","hash":"abc123","extract_dir":"fd-10.2.0"}],"bin":["fd.exe"]}"""
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMAnswer(researchJson) onRequestContains "https://github.com/sharkdp/fd"
            mockLLMAnswer(analyzeJson) onRequestContains researchJson.take(50)
            mockLLMAnswer("Here is the manifest:\n```json\n$manifestJson\n```") onRequestContains analyzeJson.take(50)
            mockLLMAnswer("").asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 50,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/sharkdp/fd")
        assertTrue(result.contains("10.2.0"), "Result should contain version, got: $result")
    }

    @Test
    fun `parseResearchStatus extracts status from JSON`() {
        assertEquals("ok", parseResearchStatus("""{"status":"ok"}"""))
        assertEquals("no_windows_assets", parseResearchStatus("""{"status":"no_windows_assets"}"""))
        assertEquals("unknown", parseResearchStatus("not json"))
        assertEquals("unknown", parseResearchStatus("""{"noStatus":true}"""))
        assertEquals("unknown", parseResearchStatus(""))
    }
}
