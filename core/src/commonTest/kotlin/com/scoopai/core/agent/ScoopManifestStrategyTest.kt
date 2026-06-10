package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.typeToken
import com.scoopai.core.ManifestResult
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    /**
     * Model without ToolChoice capability, used to simulate the generate subgraph environment
     * where normalize/verify must execute without tool_choice forced.
     */
    private val modelWithoutToolChoice: LLModel = DeepSeekModels.DeepSeekV4Flash.copy(
        capabilities = DeepSeekModels.DeepSeekV4Flash.capabilities?.filter { it != LLMCapability.ToolChoice }
    )

    private fun createTestAgent(
        promptExecutor: PromptExecutor,
        maxIterations: Int = 50,
        model: LLModel = DeepSeekModels.DeepSeekV4Flash,
    ): AIAgent<String, ManifestResult> {
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { buildResearchPrompt(it, null) },
            analyzePromptBuilder = { buildAnalyzePrompt(it) },
            generatePromptBuilder = { skeleton, analyze, _ -> buildGeneratePrompt(skeleton, analyze, null) },
        )
        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = model,
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
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .run("https://github.com/sharkdp/fd")
        assertTrue(result.manifest.contains("10.2.0"), "Result should contain version, got: $result")
    }

    @Test
    fun `suggested_app_name flows from research to ManifestResult`() = runBlocking {
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
        val generateResult = """{"version":"b9264","architecture":{"64bit":{"url":"https://example.com/llama-sycl.zip","hash":"abc123"}},"bin":["llama-cli.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
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
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .run("https://github.com/sharkdp/fd")
        assertEquals(null, result.suggestedAppName)
    }

    // =========================================================================
    // Post-process & verify execution tests
    //
    // normalize calls enforceArchitectureWrap which transforms a flat manifest
    // (url/hash at top level) into architecture-wrapped format.
    // verify returns null (pass) when manifestIo is null, allowing nodeFinish.
    //
    // If either node is bypassed, the manifest stays flat or the agent fails.
    // =========================================================================

    @Test
    fun `flat manifest from generate triggers enforceArchitectureWrap in normalize`() = runBlocking {
        // Given: research + analyze produce valid structured data, but the generate
        // phase returns a FLAT manifest (url at top level, no architecture block).
        // normalize should invoke enforceArchitectureWrap to fix this.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "test/app",
            version = "1.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/app-x64.zip",
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
                    url = "https://example.com/app-x64.zip",
                    hash = "abc123",
                )
            ),
            bin = listOf("app.exe"),
        )
        // Flat manifest: url/hash at top level, no architecture block
        val flatManifest = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, flatManifest) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        // When: agent runs through all three phases (with modelWithoutToolChoice)
        val result = createTestAgent(promptExecutor, model = modelWithoutToolChoice)
            .run("https://github.com/test/app")

        // Then: manifest must have been transformed by enforceArchitectureWrap
        val parsed = Json.parseToJsonElement(result.manifest).jsonObject
        assertTrue(parsed.containsKey("architecture"),
            "Manifest should have architecture block after normalize.enforceArchitectureWrap\nGot: ${result.manifest}")
        assertTrue("url" !in parsed,
            "Top-level 'url' key should NOT exist; it should be moved inside architecture block. Manifest: ${result.manifest}")
        val archBlock = parsed["architecture"]!!.jsonObject
        assertTrue(archBlock.containsKey("64bit"),
            "Architecture block should contain 64bit entry. Got keys: ${archBlock.keys}")
        assertTrue(result.manifest.isNotBlank(),
            "Result manifest should not be blank (proves verify->nodeFinish completed)")
    }

    @Test
    fun `generate mixed response with text and tool call still triggers normalize and verify`() = runBlocking {
        // Given: the LLM returns both text AND a finalize_task_result tool call
        // (common with thinking models that explain then call tools).
        // normalize and verify must still execute after the subgraph finishes.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "test/app",
            version = "1.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/app-x64.zip",
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
                    url = "https://example.com/app-x64.zip",
                    hash = "abc123",
                )
            ),
            bin = listOf("app.exe"),
        )
        val flatManifest = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            // Mixed response: text + tool call (simulates thinking model behavior)
            mockLLMMixedResponse(
                toolCalls = listOf(generateFinishTool to flatManifest),
                responses = listOf("Here is the manifest I generated for you:")
            ) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        // When: agent runs through all three phases (with modelWithoutToolChoice)
        val result = createTestAgent(promptExecutor, model = modelWithoutToolChoice)
            .run("https://github.com/test/app")

        // Then: manifest must have architecture block (proves normalize executed)
        val parsed = Json.parseToJsonElement(result.manifest).jsonObject
        assertTrue(parsed.containsKey("architecture"),
            "Mixed response: Manifest should have architecture block after normalize\nGot: ${result.manifest}")
        assertTrue("url" !in parsed,
            "Mixed response: Top-level 'url' key should NOT exist. Manifest: ${result.manifest}")
        assertTrue(result.manifest.isNotBlank(),
            "Mixed response: Result manifest should not be blank (proves verify->nodeFinish)")
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