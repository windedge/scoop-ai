package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.FinalizePayload
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.agent.normalizeManifest
import com.scoopai.core.agent.formatManifestJson
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class ScoopManifestGeneratorMockTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    private val lenientFinishTool = com.scoopai.core.agent.LenientStringFinishTool()

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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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

        assertContentEquals(listOf("research", "analyze", "generate", "verify"), phases)
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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

        assertEquals(4, result.stages.size, "Should have 4 stages (research, analyze, generate, verify)")
        assertEquals("research", result.stages[0].phaseName)
        assertEquals("analyze", result.stages[1].phaseName)
        assertEquals("generate", result.stages[2].phaseName)
        assertEquals("verify", result.stages[3].phaseName, "Verify stage should be present")
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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

        // Verify stage exists with non-negative token counts. The review agent's
        // LLM calls are recorded under the verify stage (architecture proposal 4.6:
        // the review sub-phase is folded into the verify phase statistics).
        val verifyStage = result.stages.find { it.phaseName == "verify" }
        assertNotNull(verifyStage, "Should have a verify stage")
        assertTrue(verifyStage!!.inputTokens >= 0, "verify inputTokens should be non-negative")
        assertTrue(verifyStage.outputTokens >= 0, "verify outputTokens should be non-negative")
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
    fun `checkpoint resume from ANALYZE skips research and resolves the node path against the live strategy`() = runBlocking {
        // End-to-end proof of the checkpoint-resume contract: the node path built by
        // CheckpointResume (strategy/phase/finalizeTask) must resolve inside the real
        // strategy graph at runtime. Regression: it used to embed the stale strategy
        // name "scoop_manifest_v3" and fail with "Node ... not found".
        val dir = createTempDir()
        try {
            val artifacts = com.scoopai.core.artifacts.RunArtifacts(
                runId = "resume-analyze-e2e",
                baseDir = dir.path,
            )
            val researchResult = ResearchOutput(
                status = ResearchStatus.OK,
                project = "sharkdp/fd",
                version = "10.2.0",
                suggested_app_name = "fd",
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
            val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

            // ── Pass 1: full generation writes research/analyze artifacts ──
            val executor1 = getMockExecutor {
                mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
                mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
                mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
                mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
                mockLLMAnswer("").asDefaultResponse
            }
            val generator1 = ScoopManifestGenerator(
                promptExecutor = executor1,
                model = model,
                toolRegistry = ToolRegistry {},
                maxIterations = 50,
                artifacts = com.scoopai.core.artifacts.SensitiveArtifactWriter(artifacts) { it },
            )
            val result1 = generator1.generate("https://github.com/sharkdp/fd")
            assertTrue(result1.manifest.contains("fd.exe"), "Pass 1 must succeed before resuming")

            // ── Pass 2: resume from ANALYZE. Research must be skipped: the mock only
            // serves analyze/generate/review prompts, and any accidental research LLM
            // call falls through to the empty default and fails the subgraph. ──
            val phases = mutableListOf<String>()
            val executor2 = getMockExecutor {
                mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
                mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
                mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
                mockLLMAnswer("").asDefaultResponse
            }
            val generator2 = ScoopManifestGenerator(
                promptExecutor = executor2,
                model = model,
                toolRegistry = ToolRegistry {},
                maxIterations = 50,
                onPhase = { name -> phases.add(name) },
            )
            val runDir = java.io.File(dir, ".scoopai/runs/resume-analyze-e2e")
            val resumeData = com.scoopai.core.artifacts.CheckpointResume(runDir)
                .loadResumePoint(com.scoopai.core.artifacts.CheckpointResume.ResumeStage.ANALYZE)
            assertNotNull(resumeData, "research.json artifact must produce a resume point")

            val result2 = generator2.generate(
                "https://github.com/sharkdp/fd",
                resumeData = resumeData,
            )

            // The resumed run must reach generate + verify without a research phase.
            assertTrue(result2.manifest.contains("fd.exe"), "Resumed run must still produce the manifest")
            assertEquals(
                listOf("analyze", "generate", "verify"),
                phases,
                "Resumed from ANALYZE must skip research and run analyze/generate/verify",
            )
        } finally {
            dir.deleteRecursively()
        }
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
                mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
        assertEquals(4, result1.stages.size, "first run should produce 4 stages (research, analyze, generate, verify)")

        // Second run with generator 2 sharing the same observer
        val executor2 = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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
        assertEquals(4, result2.stages.size, "second run should also produce 4 stages, not accumulated")    }

    // =========================================================================
    // T-3.11: assist phase onPhaseOutput (missing invoke)
    // =========================================================================

    @Test
    fun `repair phase output is written to artifacts when verify fails`() = runBlocking {
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

            // Mock ManifestIo that returns validation errors to trigger repair
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

            // Generate will trigger repair (up to 3 retries) then exhaust
            generator.generate("https://github.com/sharkdp/fd")

            val base = java.io.File(dir, ".scoopai/runs/refine-output-test")
            assertTrue(java.io.File(base, "repair.json").exists(), "repair.json should exist when verify triggers repair")
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
    // Review fail-closed: an unparseable review result must NOT silently pass.
    // =========================================================================

    @Test
    fun `unparseable review result triggers repair instead of silently passing`() = runBlocking {
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
            // Review agent receives the bare manifest JSON as its last message;
            // it returns narrative garbage (no parseable JSON): fail-closed, so
            // verify fails and the run must go through the repair loop.
            mockLLMAnswer("This manifest looks perfectly fine to me!") onCondition { text -> text.trim().startsWith("{") }
            // Repair repairs (returns the same manifest), but review keeps failing,
            // so the loop runs to exhaustion.
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            onPhase = { name -> phases.add(name) },
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // Must NOT silently pass: the run iterates through repair...
        assertTrue("repair" in phases, "unparseable review should drive the repair loop, phases=$phases")
        // ...and exhausts retries with the review issue surfaced.
        assertTrue(result.exhausted, "exhausted should be true after repair gives up")
        assertTrue(
            result.issues.any { it.contains("Semantic review returned an unparseable or unexpected result") },
            "issues should carry the unparseable-review error, got: ${result.issues}"
        )
    }

    // =========================================================================
    // Storage key refactoring verification (S5 batch 2)
    // =========================================================================

    @Test
    fun `storage-backed state propagates researchData analyzeData suggestedAppName and finalManifest correctly`() = runBlocking {
        // Verifies that the 4 storage keys (research_data, analyze_data, suggested_app_name, final_manifest)
        // correctly propagate values across phases in the strategy.
        // This test validates the S5 batch 2 refactoring: var -> storage key.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            suggested_app_name = "fd",
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
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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

        // Verify storage-backed suggestedAppNameKey was populated
        assertEquals("fd", result.suggestedAppName, "suggestedAppName should propagate via storage")
        // Verify storage-backed finalManifestKey was populated and extractJsonBlock works
        assertTrue(result.manifest.contains("10.2.0"), "manifest should contain version (storage-backed finalManifestKey)")
        assertTrue(result.manifest.contains("fd.exe"), "manifest should contain bin entry (storage-backed)")
        assertTrue(result.manifest.isNotBlank(), "manifest should not be blank (buildFinalResult via storage)")
    }

    // =========================================================================
    // phaseOutputListener: in-memory phase output callback (0.1.5)
    // =========================================================================

    @Test
    fun `phaseOutputListener is invoked once per phase with parseable research json`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            suggested_app_name = "fd",
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

        val captured = mutableMapOf<String, String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            phaseOutputListener = { phase, out -> captured[phase] = out },
        )

        generator.generate("https://github.com/sharkdp/fd")

        // research / analyze / generate each fired once; repair did not run.
        assertEquals("research", captured.keys.firstOrNull { it == "research" })
        assertEquals("analyze", captured.keys.firstOrNull { it == "analyze" })
        assertEquals("generate", captured.keys.firstOrNull { it == "generate" })
        assertEquals(setOf("research", "analyze", "generate"), captured.keys)

        // The research payload is raw, un-masked phase JSON decodeable to ResearchOutput.
        val researchJson = captured.getValue("research")
        val decoded = com.scoopai.core.agent.phaseJson
            .decodeFromString<ResearchOutput>(researchJson)
        assertEquals("sharkdp/fd", decoded.project)
        assertEquals("fd", decoded.suggested_app_name)
    }

    // =========================================================================
    // onToolActivity: real-time tool event callback (0.1.5)
    // =========================================================================

    private object DummyFetchRepoTool : ai.koog.agents.core.tools.SimpleTool<DummyFetchRepoTool.Args>(
        argsType = typeToken<DummyFetchRepoTool.Args>(),
        name = com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO,
        description = "Fetch repository info",
    ) {
        @Serializable
        data class Args(val repo: String)

        override suspend fun execute(args: Args): String = """{"name":"fd"}"""
    }

    @Test
    fun `onToolActivity callback captures Started and Completed events for tool calls`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            suggested_app_name = "fd",
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

        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains """{"name":"fd"}"""
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text ->
                !text.contains("GENERATE phase") && text.contains("extract_dir")
            }
            mockLLMAnswer("").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry {
                tool(DummyFetchRepoTool)
            },
            maxIterations = 50,
            onToolActivity = { activities.add(it) },
        )

        generator.generate("https://github.com/sharkdp/fd")

        assertTrue(activities.isNotEmpty(), "Tool activities should be recorded")
        val startedNames = activities.filterIsInstance<ToolActivity.Started>().map { it.toolName }
        val completedNames = activities.filterIsInstance<ToolActivity.Completed>().map { it.toolName }

        assertTrue(startedNames.contains(com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO), "Should record fetchRepoInfo Started, got $startedNames")
        assertTrue(completedNames.contains(com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO), "Should record fetchRepoInfo Completed, got $completedNames")

        val startedEvent = activities.filterIsInstance<ToolActivity.Started>().first { it.toolName == com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO }
        assertTrue(startedEvent.toolArgs.contains("sharkdp/fd"), "Started event should contain args")
    }

    @Test
    fun `assist modifies existing manifest and returns updated ManifestResult`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""

        val phases = mutableListOf<String>()
        val phaseOutputs = mutableListOf<Pair<String, String>>()

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(lenientFinishTool, FinalizePayload(refinedManifest)) onRequestContains "manifest assistant"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
            onPhase = { phases.add(it) },
            phaseOutputListener = { p, out -> phaseOutputs.add(p to out) },
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertEquals(listOf("assist", "verify"), phases, "Should only run assist and verify phases")
        val expectedManifest = formatManifestJson(normalizeManifest(refinedManifest))
        assertEquals(expectedManifest, result.manifest)
        assertEquals("initial-app", result.suggestedAppName)
        assertTrue(phaseOutputs.any { it.first == "assist" })
    }

    @Test
    fun `assist returns issues and exhausted true when refined manifest contains validation errors`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        // Refined manifest with hardcoded autoupdate version placeholder issue
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"autoupdate":{"url":"https://example.com/1.0.0/app.zip"}}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(lenientFinishTool, FinalizePayload(refinedManifest)) onRequestContains "manifest assistant"
            // After verify fails, internal repair rounds (explanatory=false) run up to maxRetries=3 times.
            mockLLMToolCall(lenientFinishTool, FinalizePayload(refinedManifest)) onRequestContains "manifest repairer"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add autoupdate",
            suggestedAppName = "initial-app",
        )

        assertTrue(result.exhausted, "Should flag exhausted when issues exist")
        assertTrue(result.issues.isNotEmpty(), "Should return issues list")
        assertTrue(result.issues.any { it.contains("autoupdate") }, "Issues should describe the autoupdate placeholder error")
    }

    @Test
    fun `assist allows removing bin field without autoFillBin adding it back`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""
        // User requested removing bin; refined manifest has only shortcuts and no bin
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","shortcuts":[["app.exe","Initial App"]]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(lenientFinishTool, FinalizePayload(refinedManifest)) onRequestContains "manifest assistant"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Remove bin attribute, keep only shortcuts",
            suggestedAppName = "initial-app",
        )

        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(result.manifest) as kotlinx.serialization.json.JsonObject
        assertNull(parsed["bin"], "bin field should remain absent when user removes it")
        assertNotNull(parsed["shortcuts"], "shortcuts field should remain present")
    }

    @Test
    fun `assist passes evidence to assist subgraph and receives updated manifest`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","App Launcher"]]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(lenientFinishTool, FinalizePayload(refinedManifest)) onRequestContains "Detected Package & Extraction Facts"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add shortcut for main executable",
            evidence = "Archive contains: app.exe, config.ini",
            suggestedAppName = "app",
        )

        val expected = formatManifestJson(normalizeManifest(refinedManifest))
        assertEquals(expected, result.manifest)
    }

    // =========================================================================
    // Finalize two-parameter protocol: explanation end-to-end flow (1.1 + 1.2)
    // =========================================================================

    /** Standard research/analyze/generate fixtures for the explanation tests. */
    private fun explanationTestFixtures(): Triple<ResearchOutput, AnalyzeOutput, String> {
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
        return Triple(researchResult, analyzeResult, manifestJson)
    }

    @Test
    fun `ManifestResult explanation flows end-to-end from finalize explanation parameter`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val explanationText = "Generated a portable zip manifest and kept fd.exe on PATH."

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = explanationText)
            ) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""") onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertEquals(explanationText, result.explanation, "explanation must flow from finalize_task_result to ManifestResult")
        assertTrue(result.manifest.contains("10.2.0"), "manifest should still be produced normally")
    }

    @Test
    fun `explanation is null when finalize omits the explanation parameter`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = manifestJson)) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""") onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertNull(result.explanation, "old-format finalize without explanation must yield explanation=null")
    }

    @Test
    fun `internal repair round with null explanation does not erase the generate round explanation`() = runBlocking {
        // The generate round finalizes with an explanation, verify keeps failing
        // (failing manifestIo) so internal repair rounds (explanatory=false) run,
        // and those rounds finalize WITHOUT an explanation. The non-null-only
        // storage write must keep the generate-round explanation on the exhausted result.
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val explanationText = "Portable zip with 64bit block."

        val mockManifestIo = object : ManifestIo {
            override fun validate(json: String?) = """{"valid":false,"errors":["test validation error"]}"""
            override fun write(json: String?, path: String?) = ""
            override fun read(path: String?) = ""
        }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = explanationText)
            ) onRequestContains "GENERATE phase"
            // Internal repair rounds: no explanation parameter.
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = manifestJson)) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            manifestIo = mockManifestIo,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.exhausted, "verify keeps failing, so the run must exhaust via the repair loop")
        assertEquals(
            explanationText,
            result.explanation,
            "repair rounds without explanation must not erase the generate-round explanation",
        )
    }

    @Test
    fun `repair round with empty-string explanation does not erase the generate round explanation`() = runBlocking {
        // The generate round finalizes with an explanation, verify keeps failing
        // (failing manifestIo) so internal repair rounds run, and one repair round
        // finalizes with an EMPTY STRING explanation. An empty string is not a valid
        // explanation: writing it to storage would erase the generate-round text.
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val explanationText = "Portable zip with 64bit block."

        val mockManifestIo = object : ManifestIo {
            override fun validate(json: String?) = """{"valid":false,"errors":["test validation error"]}"""
            override fun write(json: String?, path: String?) = ""
            override fun read(path: String?) = ""
        }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = explanationText)
            ) onRequestContains "GENERATE phase"
            // Repair rounds: empty-string explanation must not overwrite.
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = "")
            ) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            manifestIo = mockManifestIo,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.exhausted, "verify keeps failing, so the run must exhaust via the repair loop")
        assertEquals(
            explanationText,
            result.explanation,
            "empty-string repair explanation must not erase the generate-round explanation",
        )
    }

    @Test
    fun `repair round with whitespace-only explanation does not erase the generate round explanation`() = runBlocking {
        // Same protection for a whitespace-only explanation: blank text is not a
        // valid explanation and must not overwrite the earlier valid one.
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val explanationText = "Portable zip with 64bit block."

        val mockManifestIo = object : ManifestIo {
            override fun validate(json: String?) = """{"valid":false,"errors":["test validation error"]}"""
            override fun write(json: String?, path: String?) = ""
            override fun read(path: String?) = ""
        }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = explanationText)
            ) onRequestContains "GENERATE phase"
            // Repair rounds: whitespace-only explanation must not overwrite.
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson, explanation = "   ")
            ) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            manifestIo = mockManifestIo,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(result.exhausted, "verify keeps failing, so the run must exhaust via the repair loop")
        assertEquals(
            explanationText,
            result.explanation,
            "whitespace-only repair explanation must not erase the generate-round explanation",
        )
    }

    @Test
    fun `assist flows the finalize explanation into ManifestResult explanation`() = runBlocking {
        // User-driven assist (generator.assist, explanatory=true): the finalize
        // explanation must flow end-to-end into ManifestResult.explanation.
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""
        val explanationText = "Added the requested start menu shortcut."

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = refinedManifest, explanation = explanationText)
            ) onRequestContains "manifest assistant"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertEquals(
            explanationText,
            result.explanation,
            "assist finalize explanation must flow into ManifestResult.explanation",
        )
        assertTrue(result.manifest.contains("shortcuts"), "refined manifest should still be produced")
    }

    // =========================================================================
    // Question escape hatch (answer): end-to-end flow (part 2)
    // =========================================================================

    @Test
    fun `pure question assist returns answered result with unchanged manifest and no verify`() = runBlocking {
        // A user follow-up that is only a question (no change request): the model
        // finalizes with answered=true and returns the input manifest verbatim.
        val inputManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val explanationText = "The manifest already contains the shortcuts you asked about."

        val phases = mutableListOf<String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = inputManifest, explanation = explanationText, answered = true)
            ) onRequestContains "manifest assistant"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
            onPhase = { phases.add(it) },
        )

        val result = generator.assist(
            manifestJson = inputManifest,
            instruction = "Does this manifest include a start menu shortcut?",
            suggestedAppName = "initial-app",
        )

        assertTrue(result.answered, "pure question must produce answered=true")
        assertEquals(explanationText, result.explanation)
        // manifest is the input echoed back verbatim (formatManifestJson handled)
        assertEquals(formatManifestJson(inputManifest), result.manifest)
        assertTrue(result.issues.isEmpty())
        // phases: only assist, no verify (escape bypasses normalize/verify)
        assertEquals(listOf("assist"), phases)
    }

    @Test
    fun `answered true but modified manifest is not treated as a pure answer`() = runBlocking {
        // Guard: the model sets answered=true but DID modify the manifest. The
        // checkAnswered node compares the finalize manifest against the input and
        // must route into normalize/verify (answered=false), not the escape.
        val inputManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val modifiedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""

        val phases = mutableListOf<String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = modifiedManifest, answered = true)
            ) onRequestContains "manifest assistant"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
            onPhase = { phases.add(it) },
        )

        val result = generator.assist(
            manifestJson = inputManifest,
            instruction = "Add a start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertTrue(!result.answered, "modified manifest must NOT be treated as a pure answer")
        assertEquals(listOf("assist", "verify"), phases)
        assertTrue(result.manifest.contains("shortcuts"), "modified manifest must be preserved through normalize/verify")
    }

    @Test
    fun `assist without answered follows the normal modify behavior`() = runBlocking {
        // Regression: a user change request that omits `answered` (null) must behave
        // exactly like the old refine flow: assist -> normalize -> verify.
        val inputManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""

        val phases = mutableListOf<String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = refinedManifest)
            ) onRequestContains "manifest assistant"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry.EMPTY,
            maxIterations = 50,
            onPhase = { phases.add(it) },
        )

        val result = generator.assist(
            manifestJson = inputManifest,
            instruction = "Add a start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertTrue(!result.answered, "change request without answered must not be answered")
        assertEquals(listOf("assist", "verify"), phases)
        assertEquals(formatManifestJson(normalizeManifest(refinedManifest)), result.manifest)
    }

    @Test
    fun `internal repair loop emits repair phase and does not trigger the answer escape`() = runBlocking {
        // A generate session whose verify keeps failing drives the internal repair
        // loop (explanatory=false): onPhase must emit "repair", and a finalize with
        // answered=true but a fixed manifest must NOT be treated as a pure answer.
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val repairedManifest = """{"version":"10.2.0","description":"Fast find","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}},"bin":["fd.exe"],"shortcuts":[["fd.exe","fd"]]}"""

        val mockManifestIo = object : ManifestIo {
            override fun validate(json: String?) = """{"valid":false,"errors":["test validation error"]}"""
            override fun write(json: String?, path: String?) = ""
            override fun read(path: String?) = ""
        }

        val phases = mutableListOf<String>()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = manifestJson)
            ) onRequestContains "GENERATE phase"
            // Internal repair rounds finalize with answered=true but a DIFFERENT manifest:
            // the repair loop is never a pure-answer escape.
            mockLLMToolCall(
                lenientFinishTool,
                FinalizePayload(manifest = repairedManifest, answered = true)
            ) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            onPhase = { phases.add(it) },
            manifestIo = mockManifestIo,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue("repair" in phases, "internal repair loop must emit the repair phase, phases=$phases")
        assertTrue(!result.answered, "repair loop must not set answered")
        assertTrue(result.exhausted, "verify keeps failing so the run must exhaust")
    }

    // =========================================================================
    // verify_install: review agent install-test tool (agentic, fake verifier)
    // =========================================================================

    /** Deterministic in-memory ScoopVerifier capturing every verifyInstall call. */
    private class FakeScoopVerifier : com.scoopai.core.tools.api.ScoopVerifier {
        var calls: Int = 0
        var lastManifest: String? = null
        var lastAppName: String? = null
        var response: String =
            """{"success":true,"app_name":"scoop-ai-verify","exit_code":0,"output":"install ok"}"""

        override suspend fun verifyInstall(manifestJson: String, appName: String): String {
            calls++
            lastManifest = manifestJson
            lastAppName = appName
            return response
        }
    }

    private val installFailureVerifierResponse =
        """{"success":false,"app_name":"scoop-ai-verify","exit_code":1,"output":"verify-stub-install-failed: checksum mismatch"}"""

    private val reviewErrorJson =
        """{"approved":false,"issues":[{"severity":"error","field":"install","message":"Install test failed: the manifest did not install successfully"}],"summary":"Install test failed"}"""

    private val reviewApprovedJson =
        """{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}"""

    @Test
    fun `install test failure surfaces as error issue and drives the repair loop to exhaustion`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val fakeVerifier = FakeScoopVerifier().apply { response = installFailureVerifierResponse }
        val phases = mutableListOf<String>()

        // Tool instance used ONLY for mock LLM response registration; the review
        // agent executes its own instance from its injected registry.
        val mockToolRef = com.scoopai.core.agent.VerifyInstallTool(fakeVerifier) { "" }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // Review round 2 (last message = fake verifier output): always report the failure.
            mockLLMAnswer(reviewErrorJson) onCondition { it.contains("verify-stub-install-failed") }
            // Review round 1 (last message = bare manifest): model calls verify_install.
            mockLLMToolCall(mockToolRef, com.scoopai.core.agent.VerifyInstallArgs("checking installability")) onCondition { it.trim().startsWith("{") }
            // Repair loop finalizes with the same manifest; review keeps failing.
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = manifestJson)) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            onPhase = { phases.add(it) },
            scoopVerifier = fakeVerifier,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue(fakeVerifier.calls >= 1, "review agent must have invoked the verify_install tool, calls=${fakeVerifier.calls}")
        assertEquals("scoop-ai-verify", fakeVerifier.lastAppName, "appName must be the fixed whitelist-safe test name")
        assertTrue("repair" in phases, "install failure must trigger the repair loop, phases=$phases")
        assertTrue(result.exhausted, "persistent install failure must exhaust retries")
        assertTrue(
            result.issues.any { it.contains("Install test failed") },
            "the install failure must surface as an issue, got: ${result.issues}",
        )
    }

    @Test
    fun `install test failure recovered by repair produces a passing result`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val fakeVerifier = FakeScoopVerifier().apply { response = installFailureVerifierResponse }
        val phases = mutableListOf<String>()
        var reviewRounds = 0

        val mockToolRef = com.scoopai.core.agent.VerifyInstallTool(fakeVerifier) { "" }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // Review round 2: first run reports the failure, later runs approve.
            mockLLMAnswer(reviewErrorJson) onCondition {
                it.contains("verify-stub-install-failed") && reviewRounds++ == 0
            }
            mockLLMAnswer(reviewApprovedJson) onCondition { it.contains("verify-stub-install-failed") }
            // Review round 1: model calls verify_install with a stated reason.
            mockLLMToolCall(mockToolRef, com.scoopai.core.agent.VerifyInstallArgs("checking installability")) onCondition { it.trim().startsWith("{") }
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = manifestJson)) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 200,
            onPhase = { phases.add(it) },
            scoopVerifier = fakeVerifier,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertTrue("repair" in phases, "first failing review must trigger repair, phases=$phases")
        assertTrue(!result.exhausted, "recovered review must pass without exhausting")
        assertTrue(result.manifest.contains("10.2.0"), "passing result must carry the manifest")
        assertTrue(fakeVerifier.calls >= 2, "each verify round must re-run the install test, calls=${fakeVerifier.calls}")
    }

    @Test
    fun `install test tool success and approved review pass with answered false`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val fakeVerifier = FakeScoopVerifier()
        val phases = mutableListOf<String>()

        val mockToolRef = com.scoopai.core.agent.VerifyInstallTool(fakeVerifier) { "" }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // Review round 2 (tool result present): approve.
            mockLLMAnswer(reviewApprovedJson) onCondition { it.contains("install ok") }
            // Review round 1 (bare manifest): call verify_install.
            mockLLMToolCall(mockToolRef, com.scoopai.core.agent.VerifyInstallArgs("checking installability")) onCondition { it.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            onPhase = { phases.add(it) },
            scoopVerifier = fakeVerifier,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertEquals(listOf("research", "analyze", "generate", "verify"), phases, "must pass without a repair phase")
        assertTrue(!result.exhausted, "approved review must not exhaust")
        assertTrue(!result.answered, "generate flow must never set answered")
        assertTrue(result.issues.isEmpty(), "no issues expected, got: ${result.issues}")
        assertEquals(1, fakeVerifier.calls, "review agent should call verify_install exactly once")
        assertEquals("scoop-ai-verify", fakeVerifier.lastAppName)
        assertTrue(
            fakeVerifier.lastManifest?.contains("10.2.0") == true,
            "verifier must receive the manifest under review, got: ${fakeVerifier.lastManifest}",
        )
    }

    @Test
    fun `without scoopVerifier the review runs tool-free and still passes`() = runBlocking {
        val (researchResult, analyzeResult, manifestJson) = explanationTestFixtures()
        val phases = mutableListOf<String>()

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // No verify tool registered: review must answer with JSON on first call.
            mockLLMAnswer(reviewApprovedJson) onCondition { it.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            onPhase = { phases.add(it) },
            // scoopVerifier defaults to null: the no-tool path must stay intact.
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        assertEquals(listOf("research", "analyze", "generate", "verify"), phases)
        assertTrue(!result.exhausted)
        assertTrue(result.manifest.contains("10.2.0"))
    }

    // =========================================================================
    // helpers
    // =========================================================================

    @Test
    fun `runSession delegates to generate and assist correctly`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            suggested_app_name = "fd",
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
                    hash = "abc123"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"10.2.0","description":"Fast find","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"Looks good."}""") onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val request = SessionRequest.Generate("https://github.com/sharkdp/fd")
        val result = generator.runSession(request)

        assertEquals("fd", result.suggestedAppName)
        assertTrue(result.manifest.contains("10.2.0"))
    }

    // =========================================================================
    // assistVerifier (B1): the assist/repair subgraph gets a verify_install tool
    // and the outer agent registry is extended so the schema resolves at runtime.
    // =========================================================================

    @Test
    fun `assist session with assistVerifier can call verify_install and still finalize`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""
        val assistVerifier = FakeScoopVerifier()
        val phases = mutableListOf<String>()

        // The assist subgraph schema exposes verify_install; the mock drives the
        // model through a verify_install call then a finalize call, proving B1
        // (the outer registry resolves the tool by name at execution time).
        val mockAssistToolRef = com.scoopai.core.agent.VerifyInstallTool(assistVerifier) { "" }

        val promptExecutor = getMockExecutor {
            // Assist subgraph turn 1: model calls verify_install (prompt says "manifest assistant").
            mockLLMToolCall(mockAssistToolRef, com.scoopai.core.agent.VerifyInstallArgs("user asked to install-test")) onCondition { it.contains("manifest assistant") }
            // Assist subgraph turn 2: tool result ("install ok") is the last message; model finalizes.
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = refinedManifest, explanation = "Added a Start Menu shortcut.")) onCondition { it.contains("install ok") }
            // Review agent approves the refined manifest (last message = manifest JSON).
            mockLLMAnswer(reviewApprovedJson) onCondition { text -> text.trim().startsWith("{") && !text.contains("install ok") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            onPhase = { phases.add(it) },
            assistVerifier = assistVerifier,
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertTrue(assistVerifier.calls >= 1, "assist subgraph must have invoked verify_install, calls=${assistVerifier.calls}")
        assertEquals(initialManifest, assistVerifier.lastManifest, "assist verifier must receive the ctx.manifest (currentManifest)")
        assertEquals(com.scoopai.core.agent.VerifyInstallTool.VERIFY_APP_NAME, assistVerifier.lastAppName)
        assertTrue(!result.exhausted, "assist must pass after the install-test round")
        assertEquals(listOf("assist", "verify"), phases, "must run assist then verify, phases=$phases")
    }

    @Test
    fun `assist session without assistVerifier stays tool-free and still passes`() = runBlocking {
        val initialManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"]}"""
        val refinedManifest = """{"version":"1.0.0","description":"Initial app","url":"https://example.com/app.zip","bin":["app.exe"],"shortcuts":[["app.exe","Initial App"]]}"""

        val promptExecutor = getMockExecutor {
            // No verify_install tool: the assist LLM finalizes directly.
            mockLLMToolCall(lenientFinishTool, FinalizePayload(manifest = refinedManifest)) onRequestContains "manifest assistant"
            mockLLMAnswer(reviewApprovedJson) onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        val generator = ScoopManifestGenerator(
            promptExecutor = promptExecutor,
            model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
            // assistVerifier defaults to null: tool-free assist path must stay intact.
        )

        val result = generator.assist(
            manifestJson = initialManifest,
            instruction = "Add start menu shortcut for app.exe",
            suggestedAppName = "initial-app",
        )

        assertTrue(!result.exhausted, "tool-free assist must pass")
        assertTrue(result.manifest.contains("shortcuts"), "assist must still apply the change")
    }

    private fun createTempDir(): java.io.File {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "scoopai-mocktest-${System.nanoTime()}"
        )
        dir.mkdirs()
        return dir
    }
}
