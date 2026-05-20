package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Strategy-level tests for the Scoop manifest generator graph strategy.
 *
 * Uses subgraphWithTask: mock LLM returns `finalize_task_result` tool calls
 * with structured data, simulating the real LLM behavior.
 */
class ScoopManifestStrategyTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    @Test
    fun `no_windows_assets flows through all phases and returns text`() = runBlocking {
        val researchResult = ResearchOutput(
            status = "no_windows_assets",
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )
        val analyzeResult = AnalyzeOutput(status = "ok", strategy = "portable_zip")
        val generateResult = """{"version":"0.0.0"}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "https://github.com/linux-only/tool"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "no_windows_assets"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "portable_zip"
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
        assertTrue(result.isNotBlank(), "Should return some text, got: $result")
    }

    @Test
    fun `full 3-phase flow produces manifest`() = runBlocking {
        val researchResult = ResearchOutput(
            status = "ok",
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/fd.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = "ok",
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/fd.zip",
                    hash = "abc123",
                    extract_dir = "fd-10.2.0"
                )
            ),
            bin = emptyList()
        )
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "https://github.com/sharkdp/fd"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "sharkdp/fd"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "portable_zip"
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
