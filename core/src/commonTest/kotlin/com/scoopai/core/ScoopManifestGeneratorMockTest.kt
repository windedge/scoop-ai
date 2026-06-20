package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

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
                    hash = "abc123"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"b9264","architecture":{"64bit":{"url":"https://example.com/llama-sycl.zip","hash":"abc123"}},"bin":["llama-cli.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/ggml-org/llama.cpp")

        assertEquals("llama-cpp-sycl", result.suggestedAppName)
        assertTrue(result.manifest.contains("b9264"), "Result should contain version, got: $result")
    }


    @Test
    fun `full 3-phase flow with mock tool calls`() = runBlocking {
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

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.manifest.contains("10.2.0"), "Result should contain version, got: $result")
        assertTrue(result.manifest.contains("fd.exe"), "Result should contain bin entry, got: $result")
    }

    @Test
    fun `no Windows assets early-finishes via checkNoWindows branch`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )

        val promptExecutor = getMockExecutor {
            // Only research phase; analyze/generate are skipped by checkNoWindows → finishNoAssets branch
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 30,
        )

        val result = generator.generate("https://github.com/linux-only/tool")

        assertTrue(result.noAssets, "Should have noAssets=true")
        assertTrue(result.manifest.isBlank(), "Should return empty manifest, got: ${result.manifest}")
    }

    // =========================================================================
    // onPhase callback tests
    // =========================================================================

    @Test
    fun `onPhase callback is invoked for each phase in mock flow`() = runBlocking {
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

        val phases = mutableListOf<String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            onPhase = { name -> phases.add(name) },
        )

        generator.generate("https://github.com/sharkdp/fd")

        assertContentEquals(listOf("research", "analyze", "generate"), phases)
    }

    @Test
    fun `onPhase callback defaults to null without crashing`() = runBlocking {
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

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            // onPhase defaults to null
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.manifest.contains("10.2.0"), "Should produce manifest normally without onPhase")
    }

    // =========================================================================
    // Token usage tracking tests
    // =========================================================================

    @Test
    fun `ManifestResult tokenUsage is populated in mock flow`() = runBlocking {
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

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // Token usage should be present (even if 0 due to mock not providing token counts)
        assertNotNull(result.tokenUsage)
        assertEquals(0L, result.tokenUsage!!.totalInputTokens)
        assertEquals(0L, result.tokenUsage!!.totalOutputTokens)
    }

    // =========================================================================
    // T-3.09: ManifestResult.stages population
    // =========================================================================

    @Test
    fun `ManifestResult stages has 3 entries in full mock flow`() = runBlocking {
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

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertEquals(4, result.stages.size, "Should have 4 stages (research, analyze, generate, review)")
        assertEquals("research", result.stages[0].phaseName)
        assertEquals("analyze", result.stages[1].phaseName)
        assertEquals("generate", result.stages[2].phaseName)
        assertEquals("review", result.stages[3].phaseName, "Review stage should be present")
        result.stages.forEach { stage ->
            assertTrue(stage.durationMs >= 0, "durationMs should be non-negative")
            assertTrue(stage.inputTokens >= 0, "inputTokens should be non-negative")
            assertTrue(stage.outputTokens >= 0, "outputTokens should be non-negative")
        }
    }

    // =========================================================================
    // Review stage token tracking
    // =========================================================================

    @Test
    fun `review stage records tokens when review agent is called`() = runBlocking {
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

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // Verify review stage exists with non-negative token counts
        val reviewStage = result.stages.find { it.phaseName == "review" }
        assertNotNull(reviewStage, "Should have a review stage")
        assertTrue(reviewStage!!.inputTokens >= 0, "review inputTokens should be non-negative")
        assertTrue(reviewStage.outputTokens >= 0, "review outputTokens should be non-negative")
    }

    @Test
    fun `no Windows assets flow has 1 stage`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 30,
        )

        val result = generator.generate("https://github.com/linux-only/tool")

        assertEquals(1, result.stages.size, "Should have 1 stage (research only)")
        assertEquals("research", result.stages[0].phaseName)
    }

    @Test
    fun `RunArtifacts directory is created on success in mock flow`() = runBlocking {
        val dir = createTempDir()
        try {
            val artifacts = com.scoopai.core.artifacts.RunArtifacts(
                runId = "stages-ok",
                baseDir = dir.path,
            )
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

            val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

            val generator = ScoopManifestGenerator(
                promptExecutor = promptExecutor,
                model = model,
                toolRegistry = ToolRegistry {},
                maxIterations = 50,
                artifacts = com.scoopai.core.artifacts.SensitiveArtifactWriter(artifacts) { it },
            )

            generator.generate("https://github.com/sharkdp/fd")

            val base = java.io.File(dir, ".scoopai/runs/stages-ok")
            assertTrue(java.io.File(base, "research.json").exists(), "research.json should exist")
            assertTrue(java.io.File(base, "analyze.json").exists(), "analyze.json should exist")
            assertTrue(java.io.File(base, "generate.json").exists(), "generate.json should exist")
            assertTrue(java.io.File(base, "done.marker").exists(), "done.marker should exist on success")
        } finally {
            dir.deleteRecursively()
        }
    }

    // =========================================================================
    // T-3.11: observer.reset() prevents stage accumulation
    // =========================================================================

    @Test
    fun `observer reset prevents stage accumulation when generator is reused`() = runBlocking {
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

        val sharedObserver = GenerationObserver()
        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        // First run with generator 1
        val executor1 = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }
        val generator1 = ScoopManifestGenerator(
            promptExecutor = executor1,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            observer = sharedObserver,
        )
        val result1 = generator1.generate("https://github.com/sharkdp/fd")
        assertEquals(4, result1.stages.size, "first run should produce 4 stages (research, analyze, generate, review)")

        // Second run with generator 2 sharing the same observer
        val executor2 = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }
        val generator2 = ScoopManifestGenerator(
            promptExecutor = executor2,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            observer = sharedObserver,
        )
        val result2 = generator2.generate("https://github.com/sharkdp/fd")
        assertEquals(4, result2.stages.size, "second run should also produce 4 stages, not accumulated")
    }

    // =========================================================================
    // T-3.11: refine phase onPhaseOutput (missing invoke)
    // =========================================================================

    @Test
    fun `refine phase output is written to artifacts when verify fails`() = runBlocking {
        val dir = createTempDir()
        try {
            val runArtifacts = com.scoopai.core.artifacts.RunArtifacts(
                runId = "refine-output-test",
                baseDir = dir.path,
            )
            val sensitiveWriter = com.scoopai.core.artifacts.SensitiveArtifactWriter(runArtifacts) { it }

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

            // Mock ManifestIo that returns validation errors to trigger refine
            val mockManifestIo = object : ManifestIo {
                override fun validate(json: String?) = """{"valid":false,"errors":["test validation error"]}"""
                override fun write(json: String?, path: String?) = ""
                override fun read(path: String?) = ""
            }

            val promptExecutor = getMockExecutor {
                mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
                mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
                mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
                mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "Issues to Fix"
                mockLLMAnswer("").asDefaultResponse
            }

            val generator = ScoopManifestGenerator(
                promptExecutor = promptExecutor,
                model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
                toolRegistry = ToolRegistry {},
                maxIterations = 200,
                manifestIo = mockManifestIo,
                artifacts = sensitiveWriter,
            )

            // Generate will trigger refine (up to 3 retries) then exhaust
            generator.generate("https://github.com/sharkdp/fd")

            val base = java.io.File(dir, ".scoopai/runs/refine-output-test")
            assertTrue(java.io.File(base, "refine.json").exists(), "refine.json should exist when verify triggers refine")
            // Other phase files should still exist
            assertTrue(java.io.File(base, "research.json").exists())
            assertTrue(java.io.File(base, "analyze.json").exists())
            assertTrue(java.io.File(base, "generate.json").exists())
            // error.txt should be written (since the run exhausted retries... actually it returns normally via exhausted path)
            // The error.txt is written only on exception, so it should NOT exist
        } finally {
            dir.deleteRecursively()
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private fun createTempDir(): java.io.File {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "scoopai-mocktest-${System.nanoTime()}"
        )
        dir.mkdirs()
        return dir
    }
}

