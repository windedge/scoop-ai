package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoopManifestGeneratorMockTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    @Test
    fun `suggestedAppName is populated when research provides it`() = runBlocking {
        val researchResult = ResearchOutput(
            status = "ok",
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
            status = "ok",
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/llama-sycl.zip",
                    hash = "abc123"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"b9264","architecture":{"64bit":{"url":"https://example.com/llama-sycl.zip","hash":"abc123"}},"bin":["llama-cli.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "Research the project at"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "Based on the following research"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "generate a complete Scoop manifest"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            manifestIo = mockManifestIo(),
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/ggml-org/llama.cpp")

        assertEquals("llama-cpp-sycl", result.suggestedAppName)
        assertTrue(result.manifest.contains("b9264"), "Result should contain version, got: $result")
    }

    private fun mockManifestIo(): ManifestIo = object : ManifestIo {
        override fun validate(json: String?): String = """{"valid":true,"errors":[]}"""
        override fun write(json: String?, path: String?): String = "written"
        override fun read(path: String?): String = "{}"
    }

    @Test
    fun `full 3-phase flow with mock tool calls`() = runBlocking {
        val researchResult = ResearchOutput(
            status = "ok",
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
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
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    hash = "abc123",
                    extract_dir = "fd-v10.2.0-x86_64-pc-windows-msvc"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            // Research phase: prompt contains "Research the project at"
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "Research the project at"
            // Analyze phase: prompt contains "Based on the following research"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "Based on the following research"
            // Generate phase: prompt contains "generate a complete Scoop manifest"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "generate a complete Scoop manifest"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            manifestIo = mockManifestIo(),
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.manifest.contains("10.2.0"), "Result should contain version, got: $result")
        assertTrue(result.manifest.contains("fd.exe"), "Result should contain bin entry, got: $result")
    }

    @Test
    fun `no Windows assets flows through all phases`() = runBlocking {
        val researchResult = ResearchOutput(
            status = "no_windows_assets",
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )
        val analyzeResult = AnalyzeOutput(status = "ok", strategy = "portable_zip")
        val generateResult = """{"version":"0.0.0","note":"No Windows assets found."}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "Research the project at"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "Based on the following research"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "generate a complete Scoop manifest"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            manifestIo = mockManifestIo(),
            maxIterations = 30,
        )

        val result = generator.generate("https://github.com/linux-only/tool")

        assertTrue(result.manifest.isNotBlank(), "Should return some text, got: $result")
        assertTrue(result.manifest.contains("0.0.0"), "Should contain version from generate phase, got: $result")
    }
}
