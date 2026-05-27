package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.typeToken
import com.scoopai.core.GenerateResult
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildRefinePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
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
    private val refineFinishTool = FinishTool<String>(typeToken<String>())

    private fun createTestAgent(
        promptExecutor: PromptExecutor,
        maxIterations: Int = 50,
    ): AIAgent<String, GenerateResult> {
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { buildResearchPrompt(it, null) },
            analyzePromptBuilder = { buildAnalyzePrompt(it) },
            refinePromptBuilder = { skeleton, analyze, _ -> buildRefinePrompt(skeleton, analyze, null) },
        )
        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = maxIterations,
            ),
        ) {
            withTesting()
        }
    }

    @Test
    fun `no_windows_assets early-finishes without analyze or generate`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor, maxIterations = 30)
            .run("https://github.com/linux-only/tool")
        assertTrue(result.noAssets, "Should have noAssets=true")
        assertTrue(result.manifest.isBlank(), "Should return empty manifest, got: ${result.manifest}")
    }

    @Test
    fun `full 3-phase flow produces manifest`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
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
            status = AnalyzeStatus.OK,
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
        val refineResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(refineFinishTool, refineResult) onRequestContains "REFINE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .run("https://github.com/sharkdp/fd")
        assertTrue(result.manifest.contains("10.2.0"), "Result should contain version, got: $result")
    }

    @Test
    fun `suggested_app_name flows from research to GenerateResult`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "ggml-org/llama.cpp",
            version = "b9264",
            suggested_app_name = "llama-cpp-sycl",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/llama-sycl.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/llama-sycl.zip",
                    hash = "abc123",
                    extract_dir = "llama-sycl"
                )
            ),
            bin = emptyList()
        )
        val refineResult = """{"version":"b9264","architecture":{"64bit":{"url":"https://example.com/llama-sycl.zip","hash":"abc123"}},"bin":["llama-cli.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(refineFinishTool, refineResult) onRequestContains "REFINE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .run("https://github.com/ggml-org/llama.cpp")
        assertEquals("llama-cpp-sycl", result.suggestedAppName)
        assertTrue(result.manifest.contains("b9264"), "Result should contain version, got: ${result.manifest}")
    }

    @Test
    fun `suggested_app_name is null when research does not provide it`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
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
            status = AnalyzeStatus.OK,
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
        val refineResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(refineFinishTool, refineResult) onRequestContains "REFINE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .run("https://github.com/sharkdp/fd")
        assertEquals(null, result.suggestedAppName)
    }

    @Test
    fun `parseResearchStatus extracts status from JSON`() {
        assertEquals(ResearchStatus.OK, parseResearchStatus("""{"status":"ok"}"""))
        assertEquals(ResearchStatus.NO_WINDOWS_ASSETS, parseResearchStatus("""{"status":"no_windows_assets"}"""))
        assertEquals(ResearchStatus.OK, parseResearchStatus("not json"))
        assertEquals(ResearchStatus.OK, parseResearchStatus("""{"noStatus":true}"""))
        assertEquals(ResearchStatus.OK, parseResearchStatus(""))
    }
}