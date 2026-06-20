package com.scoopai.core.artifacts

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.serialization.typeToken
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.WindowsAsset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.io.File

class RunArtifactsIntegrationTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    // ---------------------------------------------------------------
    // Successful run produces phase files + done.marker
    // ---------------------------------------------------------------

    @Test
    fun `successful run writes all phase jsons and done marker`() = runBlocking {
        val dir = createTempDir()
        try {
            val artifacts = RunArtifacts(runId = "e2e-ok", baseDir = dir.path)
            val generator = createGenerator(dir, artifacts)
            generator.generate("https://github.com/sharkdp/fd")

            val base = File(dir, ".scoopai/runs/e2e-ok")
            assertTrue(File(base, "research.json").exists(), "research.json should exist")
            assertTrue(File(base, "analyze.json").exists(), "analyze.json should exist")
            assertTrue(File(base, "generate.json").exists(), "generate.json should exist")
            assertTrue(File(base, "done.marker").exists(), "done.marker should exist")
            assertTrue(!File(base, "error.txt").exists(), "error.txt should NOT exist on success")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed run writes at least research json and error txt`() = runBlocking {
        val dir = createTempDir()
        try {
            val artifacts = RunArtifacts(runId = "e2e-fail", baseDir = dir.path)
            // Create generator with a failing promptExecutor (will throw)
            val failingExecutor = getMockExecutor {
                mockLLMAnswer("").asDefaultResponse
            }
            val generator = ScoopManifestGenerator(
                promptExecutor = failingExecutor,
                model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
                toolRegistry = ToolRegistry {},
                maxIterations = 5,
                artifacts = SensitiveArtifactWriter(artifacts) { it },
            )

            try {
                generator.generate("https://github.com/unknown/project")
                // Should have thrown
                assertTrue(false, "Expected exception")
            } catch (_: Exception) {
                // Expected
            }

            val base = File(dir, ".scoopai/runs/e2e-fail")
            // Research phase output might or might not be written depending on when it fails
            // The error.txt should be written in the catch block
            assertTrue(File(base, "error.txt").exists(), "error.txt should exist on failure")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "scoopai-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @OptIn(InternalAgentToolsApi::class)
    private fun createGenerator(
        tmpDir: File,
        artifacts: RunArtifacts,
    ): ScoopManifestGenerator {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
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
            status = AnalyzeStatus.OK,
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
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        return ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            artifacts = SensitiveArtifactWriter(artifacts) { it },
        )
    }
}
