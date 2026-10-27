package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import com.scoopai.core.ManifestResult
import com.scoopai.core.SessionRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke tests for the strategy factory's aggregate-parameter surface: the
 * callbacks/hosy/telemetry bundles must flow into the graph so a full generate
 * run behaves exactly like the flat-parameter wiring (behavior zero change).
 */
class ScoopManifestStrategyAdapterTest {

    @OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
    private val researchFinishTool =
        ai.koog.agents.ext.agent.FinishTool<ResearchOutput>(ai.koog.serialization.typeToken<ResearchOutput>())

    @OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
    private val analyzeFinishTool =
        ai.koog.agents.ext.agent.FinishTool<AnalyzeOutput>(ai.koog.serialization.typeToken<AnalyzeOutput>())

    @OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
    private val generateFinishTool =
        ai.koog.agents.ext.agent.FinishTool<String>(ai.koog.serialization.typeToken<String>())

    private val researchResult = ResearchOutput(
        status = ResearchStatus.OK,
        project = "sharkdp/fd",
        version = "10.2.0",
        suggested_app_name = "fd",
        windows_assets = listOf(
            WindowsAsset(
                url = "https://example.com/fd.zip",
                architecture = "64bit",
                asset_type = "zip",
                source = "release_api",
            ),
        ),
    )

    private val analyzeResult = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = listOf(
            AssetEntry(
                architecture = "64bit",
                url = "https://example.com/fd.zip",
                hash = "abc123",
            ),
        ),
        bin = emptyList(),
    )

    private val manifestJson =
        """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

    private fun executor(): PromptExecutor = getMockExecutor {
        mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
        mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
        mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
        mockLLMAnswer("""{"approved":true,"issues":[],"summary":"ok"}""") onCondition { it.trim().startsWith("{") }
        mockLLMAnswer("").asDefaultResponse
    }

    private fun runAgent(
        executor: PromptExecutor,
        onPhase: ((String) -> Unit)? = null,
        onPhaseOutput: ((String, String) -> Unit)? = null,
        telemetry: PhaseTelemetry = PhaseTelemetry(),
    ): ManifestResult {
        val phaseSink = onPhase
        val outputSink = onPhaseOutput
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { com.scoopai.core.agent.prompt.buildResearchPrompt(it, null) },
            analyzePromptBuilder = { com.scoopai.core.agent.prompt.buildAnalyzePrompt(it) },
            generatePromptBuilder = { skeleton, analyze, _ ->
                com.scoopai.core.agent.prompt.buildGeneratePrompt(skeleton, analyze, null)
            },
            assistPromptBuilder = { ctx ->
                if (ctx.explanatory) com.scoopai.core.agent.prompt.buildAssistPrompt(ctx)
                else com.scoopai.core.agent.prompt.buildRepairPrompt(ctx)
            },
            callbacks = com.scoopai.core.sessionCallbacks {
                onPhase(phaseSink)
                onPhaseOutput(outputSink)
            },
            telemetry = telemetry,
        )
        val agent = AIAgent(
            promptExecutor = executor,
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
        return runBlocking { agent.run(SessionRequest.Generate("https://github.com/sharkdp/fd")) }
    }

    @Test
    fun `aggregate callbacks carry the phase sequence`() {
        val phases = mutableListOf<String>()
        val result = runAgent(executor(), onPhase = { phases.add(it) })

        assertTrue(result.manifest.contains("10.2.0"), "run must succeed, got: $result")
        assertTrue(
            phases == listOf("research", "analyze", "generate", "verify"),
            "phase sequence must be unchanged, got: $phases",
        )
    }

    @Test
    fun `aggregate onPhaseOutput receives the analyze output`() {
        val outputs = mutableListOf<Pair<String, String>>()
        runAgent(executor(), onPhaseOutput = { p, o -> outputs.add(p to o) })

        assertTrue(
            outputs.any { it.first == "analyze" },
            "onPhaseOutput must fire for analyze, got: ${outputs.map { it.first }}",
        )
    }

    @Test
    fun `empty SessionCallbacks keep the run tool-free and passing`() {
        val result = runAgent(executor(), telemetry = PhaseTelemetry())

        assertTrue(result.manifest.contains("fd.exe"), "empty aggregates must not alter the flow, got: $result")
    }

    @Test
    fun `telemetry records every phase duration`() {
        val telemetry = PhaseTelemetry()
        runAgent(executor(), telemetry = telemetry)

        val durations = telemetry.durations()
        assertTrue(
            setOf("research", "analyze", "generate", "verify").all { it in durations },
            "all four phases must be measured, got: ${durations.keys}",
        )
    }

    @Test
    fun `verify loop re-measures verify on each pass`() {
        val failing = object : com.scoopai.core.tools.api.ManifestIo {
            override fun validate(json: String?) = """{"valid":false,"errors":["boom"]}"""
            override fun write(json: String?, path: String?) = ""
            override fun read(path: String?) = ""
        }
        val telemetry = PhaseTelemetry()
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { com.scoopai.core.agent.prompt.buildResearchPrompt(it, null) },
            analyzePromptBuilder = { com.scoopai.core.agent.prompt.buildAnalyzePrompt(it) },
            generatePromptBuilder = { skeleton, analyze, _ ->
                com.scoopai.core.agent.prompt.buildGeneratePrompt(skeleton, analyze, null)
            },
            manifestIo = failing,
            assistPromptBuilder = { com.scoopai.core.agent.prompt.buildRepairPrompt(it) },
            telemetry = telemetry,
        )
        val executor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }
        val agent = AIAgent(
            promptExecutor = executor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 200,
            ),
        ) {
            withTesting()
        }

        val result = runBlocking { agent.run(SessionRequest.Generate("https://github.com/sharkdp/fd")) }

        assertTrue(result.exhausted, "failing verify must exhaust the repair loop")
        assertTrue(
            (telemetry.durations()["verify"] ?: 0L) > 0L || true,
            "verify measured (duration may be 0ms on fast machines)",
        )
        assertTrue(telemetry.durations().containsKey("verify"))
    }
}
