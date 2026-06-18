package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.PrefetchResult
import com.scoopai.core.agent.ResearchOutput

import com.scoopai.core.agent.prompt.REVIEW_AGENT_SYSTEM_PROMPT
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildRefinePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.utils.logLlmResponse
import com.scoopai.core.utils.sanitizeForLog
import com.scoopai.core.llm.LlmParamsBuilder
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.ManifestIo
import com.scoopai.core.utils.sanitizeForLog
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Generates a Scoop manifest using a Koog graph strategy with three LLM phases
 * (research → analyze → generate) and a deterministic verify + optional refine loop.
 *
 * Flow: research(LLM) → analyze(LLM) → prepareManifest(deterministic) → generate(LLM) → verify(validate+review)
 *                                                                                           ↓ pass        ↓ fail
 *                                                                                      nodeFinish    refine(LLM, optional)
 *                                                                                                        ↓ (max 3)
 *                                                                                                     verify
 */
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int,
    private val thinkingLevel: String? = null,
    /** Validator for manifest JSON. Used by the verify node. */
    private val manifestIo: ManifestIo? = null,
    /** GitHub API interface for prefetching research data before the LLM phase. */
    private val gitHubApi: GitHubApi? = null,
    /** Archive inspector for prefetching persist/search data before the LLM phase. */
    private val archiveInspector: ArchiveInspector? = null,
    /** Callback invoked when a phase starts. Used by cli ConsoleProgress overlay. */
    private val onPhase: ((String) -> Unit)? = null,
) {
    /**
     * Model with [LLMCapability.ToolChoice] stripped so that Koog uses its
     * internal re-prompt path instead of hard-throwing on text-only responses
     * (a common thinking-model quirk).
     */
    private val modelWithoutToolChoice: LLModel by lazy {
        model.copy(capabilities = model.capabilities?.filter { it != LLMCapability.ToolChoice })
    }

    suspend fun generate(
        url: String,
        outputPath: String? = null,
        userPrompt: String? = null,
    ): ManifestResult {
        val startTime = System.currentTimeMillis()
        // -- Track token usage across all LLM calls ------------------------
        val tokenAccumulator = TokenAccumulator()

        // -- Phase-specific tool subsets -----------------------------------
        val allTools = toolRegistry.tools
        var researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}" }

        // -- Build prompt constructors for each phase ----------------------
        val researchPrompt: suspend (PrefetchResult) -> String =
            { buildResearchPrompt(it, outputPath, userPrompt) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it, userPrompt) }
        val generatePrompt: suspend (String, AnalyzeOutput, ResearchOutput?) -> String =
            { skeleton, analyze, research -> buildGeneratePrompt(skeleton, analyze, outputPath, userPrompt, research) }
        val refinePrompt: suspend (RefineContext) -> String =
            { buildRefinePrompt(it) }

        // -- Create review executor (independent LLM agent) ----------------
        val reviewAgent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = ToolRegistry.EMPTY,
            systemPrompt = REVIEW_AGENT_SYSTEM_PROMPT,
            maxIterations = 5,
        ) {
            handleEvents {
                onLLMCallCompleted { ctx ->
                    ctx.response?.metaInfo?.inputTokensCount?.let { tokenAccumulator.input += it.toLong() }
                    ctx.response?.metaInfo?.outputTokensCount?.let { tokenAccumulator.output += it.toLong() }
                }
            }
        }
        // Wrap the review agent as a simple suspend function
        val reviewExecutor: suspend (String) -> String = { manifest ->
            @Suppress("UNCHECKED_CAST")
            (reviewAgent as GraphAIAgent<String, String>).run(manifest)
        }

        val llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel)
        val strategy = scoopManifestStrategy(
            researchTools = researchTools,
            analyzeTools = analyzeTools,
            llmParams = llmParams,
            researchPromptBuilder = researchPrompt,
            analyzePromptBuilder = analyzePrompt,
            generatePromptBuilder = generatePrompt,
            refinePromptBuilder = refinePrompt,
            manifestIo = manifestIo,
            reviewExecutor = reviewExecutor,
            gitHubApi = gitHubApi,
            archiveInspector = archiveInspector,
            onPhase = onPhase,
        )

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system("You are a Scoop manifest generator. You generate Scoop bucket manifests for Windows software.")
            },
            model = modelWithoutToolChoice,
            maxAgentIterations = maxIterations,
        )

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = toolRegistry,
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    val args = sanitizeForLog(ctx.toolArgs.toString())
                    logger.info { "[tool] ${ctx.toolName}($args)" }
                }
                onToolCallCompleted { ctx ->
                    val result = sanitizeForLog(ctx.toolResult?.toString() ?: "null", maxLen = 200)
                    logger.info { "[tool] ${ctx.toolName} -> $result" }
                }
                onLLMCallCompleted { ctx ->
                    ctx.response?.metaInfo?.inputTokensCount?.let { tokenAccumulator.input += it.toLong() }
                    ctx.response?.metaInfo?.outputTokensCount?.let { tokenAccumulator.output += it.toLong() }
                    logLlmResponse(ctx.response, tokenAccumulator)
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        val result: ManifestResult = agent.run(url)
        val elapsed = System.currentTimeMillis() - startTime

        return result.copy(
            manifest = result.manifest,
            tokenUsage = TokenUsage(tokenAccumulator.input, tokenAccumulator.output),
            durationMs = elapsed,
        )
    }

}
