package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import com.scoopai.core.tools.api.ManifestIo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoopManifestGeneratorMockTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockManifestIo(): ManifestIo = object : ManifestIo {
        override fun validate(json: String?): String = """{"valid":true,"errors":[]}"""
        override fun write(json: String?, path: String?): String = "written"
        override fun read(path: String?): String = "{}"
    }

    @Test
    fun `full 3-phase flow with mock LLM text responses`() = runBlocking {
        val researchJson = """{"status":"ok","project":"sharkdp/fd","version":"10.2.0","windows_assets":[{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","architecture":"64bit","asset_type":"zip","source":"release_api"}]}"""
        val analyzeJson = """{"status":"ok","strategy":"portable_zip","entries":[{"architecture":"64bit","url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}]}"""
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            // Research: prompt builder creates "Research the project at: https://github.com/sharkdp/fd"
            mockLLMAnswer(researchJson) onRequestContains "Research the project at"
            // Analyze: prompt builder creates "Based on the following research JSON..."
            mockLLMAnswer(analyzeJson) onRequestContains "Based on the following research"
            // Generate: prompt builder creates "generate a complete Scoop manifest"
            mockLLMAnswer("Here is the manifest:\n```json\n$manifestJson\n```") onRequestContains "generate a complete Scoop manifest"
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

        assertTrue(result.contains("10.2.0"), "Result should contain version, got: $result")
        assertTrue(result.contains("fd.exe"), "Result should contain bin entry, got: $result")
    }

    @Test
    fun `no Windows assets flows through all phases`() = runBlocking {
        val noAssetResearch = """{"status":"no_windows_assets","project":"linux-only/tool","alternatives":"Consider using WSL or a Linux VM."}"""
        val noAssetAnalyze = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":[]}"""
        val noAssetGenerate = """{"version":"0.0.0","note":"No Windows assets found."}"""

        val promptExecutor = getMockExecutor {
            mockLLMAnswer(noAssetResearch) onRequestContains "Research the project at"
            mockLLMAnswer(noAssetAnalyze) onRequestContains "Based on the following research"
            mockLLMAnswer(noAssetGenerate) onRequestContains "generate a complete Scoop manifest"
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

        // Linear flow: all 3 phases run, generate returns extracted JSON
        assertTrue(result.isNotBlank(), "Should return some text, got: $result")
        assertTrue(result.contains("0.0.0"), "Should contain version from generate phase, got: $result")
    }

    @Test
    fun `extractJson extracts JSON from code block`() {
        val generator = createMinimalGenerator()
        val result = generator.extractJson("Here is the manifest:\n```json\n{\"version\":\"1.0.0\"}\n```")
        assertEquals("""{"version":"1.0.0"}""", result)
    }

    @Test
    fun `extractJson returns null for non-JSON text`() {
        val generator = createMinimalGenerator()
        val result = generator.extractJson("Just some plain text without JSON")
        assertEquals(null, result)
    }

    private fun createMinimalGenerator(): ScoopManifestGenerator {
        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash
        return ScoopManifestGenerator(
            promptExecutor = getMockExecutor { mockLLMAnswer("").asDefaultResponse },
            model = model,
            toolRegistry = ToolRegistry {},
            manifestIo = mockManifestIo(),
            maxIterations = 10,
        )
    }
}
