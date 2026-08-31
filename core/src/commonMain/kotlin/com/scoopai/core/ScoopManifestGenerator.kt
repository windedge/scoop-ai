package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.PrefetchResult
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.artifacts.ResumeData
import com.scoopai.core.agent.prompt.REVIEW_AGENT_SYSTEM_PROMPT
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildRefinePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.normalizeManifest
import com.scoopai.core.agent.parseValidateErrors
import com.scoopai.core.agent.nodes.checkAutoupdateVersionPlaceholder
import com.scoopai.core.agent.nodes.validatePersistPaths
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.utils.logLlmResponse
import com.scoopai.core.utils.sanitizeForLog
import com.scoopai.core.llm.LlmParamsBuilder
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.ext.agent.subgraphWithTask
import com.scoopai.core.agent.LenientStringFinishTool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    /**
     * Callback invoked when a phase starts. Used by cli ConsoleProgress overlay.
     */
    private val onPhase: ((String) -> Unit)? = null,
    /**
     * In-memory listener invoked once per completed phase (research / analyze /
     * generate / refine) with the raw, un-masked phase output JSON
     * (`phaseName, output`). Intended for in-memory checkpoints only (e.g.
     * [com.scoopai.core.artifacts.ResumeData.fromPhaseOutputs]); do NOT persist
     * this content in sensitive scenarios, as it is not masked.
     */
    private val phaseOutputListener: ((phaseName: String, output: String) -> Unit)? = null,
    /** Observer for recording per-phase execution statistics. If null, creates a default instance. */
    private val observer: GenerationObserver = GenerationObserver(),
    /**
     * Debug artifacts writer (optional). Applies masking to all written content.
     * Phase outputs are recorded to .scoopai/runs/<runId>/ for debugging.
     */
    private val artifacts: com.scoopai.core.artifacts.SensitiveArtifactWriter? = null,
    /**
     * Provider for the set of existing manifest names in local Scoop buckets.
     * When non-null, the verify phase checks [suggestedAppName] against this set
     * and reports a conflict error if the name is already used.
     */
    private val usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    /**
     * Callback invoked on tool call events (Started, Completed, Failed).
     * Used by hosts (e.g. Scooper) for transparent tool call progress reporting.
     */
    private val onToolActivity: ((ToolActivity) -> Unit)? = null,
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
        /**
         * Optional checkpoint resume data. When present, skips completed phases
         * and resumes execution from the specified stage using Koog's
         * [Persistence.runFromCheckpoint] instead of a full [agent.run].
         */
        resumeData: ResumeData? = null,
    ): ManifestResult {
        val startTime = TimeSource.Monotonic.markNow()
        observer.reset()
        // Phase name last announced by the strategy; used to enrich typed errors
        // when a subgraph fails mid-phase (e.g. tool-call exhaustion).
        var activePhase: String? = null
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
                    val inputTokens = ctx.response?.metaInfo?.inputTokensCount?.toLong() ?: 0L
                    val outputTokens = ctx.response?.metaInfo?.outputTokensCount?.toLong() ?: 0L
                    tokenAccumulator.input += inputTokens
                    tokenAccumulator.output += outputTokens
                    // Also record to observer so review tokens appear in stages
                    observer.recordLlmCall(inputTokens, outputTokens)
                }
            }
        }
        // Wrap the review agent as a simple suspend function, with observer tracking
        val reviewExecutor: suspend (String) -> String = { manifest ->
            observer.enterPhase("review")
            val reviewStart = TimeSource.Monotonic.markNow()
            try {
                @Suppress("UNCHECKED_CAST")
                (reviewAgent as GraphAIAgent<String, String>).run(manifest)
            } finally {
                val elapsed = reviewStart.elapsedNow().inWholeMilliseconds
                observer.exitPhase("review", elapsed)
            }
        }

        val llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel)
        // Use the artifact writer's writePhase for phase output capture;
        // for finalize/markFailed we delegate directly.
        val artifactsWriter = artifacts?.delegate  // RunArtifacts for delegation
        val phaseWrite: ((String, String) -> Unit)? = artifacts?.let { writer ->
            { phase, content -> writer.writePhase(phase, content) }
        }
        // Wire both the artifact disk writer and the in-memory listener into the
        // strategy's onPhaseOutput so neither side is lost when both are present.
        val onPhaseOutput = if (phaseWrite != null || phaseOutputListener != null) {
            { phase: String, out: String ->
                phaseWrite?.invoke(phase, out)
                phaseOutputListener?.invoke(phase, out)
                Unit
            }
        } else {
            null
        }
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
            onPhase = { name ->
                activePhase = name
                onPhase?.invoke(name)
            },
            observer = observer,
            onPhaseOutput = onPhaseOutput,
            usedAppNamesProvider = usedAppNamesProvider,
        )

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system("You are a Scoop manifest generator. You generate Scoop bucket manifests for Windows software.")
            },
            model = modelWithoutToolChoice,
            maxAgentIterations = maxIterations,
        )

        // Record LLM and tool call events to the observer for per-phase tracking.
        // The observer's current phase is set by the strategy via enterPhase/exitPhase,
        // which matches the sequential execution of subgraphs.
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = toolRegistry,
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    observer.recordToolCall()
                    val args = sanitizeForLog(ctx.toolArgs.toString())
                    logger.info { "[tool] ${ctx.toolName}($args)" }
                    onToolActivity?.invoke(ToolActivity.Started(ctx.toolName, args))
                }
                onToolCallCompleted { ctx ->
                    val result = sanitizeForLog(ctx.toolResult?.toString() ?: "null", maxLen = 200)
                    logger.info { "[tool] ${ctx.toolName} -> $result" }
                    onToolActivity?.invoke(ToolActivity.Completed(ctx.toolName, result))
                }
                onToolCallFailed { ctx ->
                    val errorMsg = sanitizeForLog(ctx.message.ifBlank { ctx.error?.message ?: "unknown tool error" }, maxLen = 200)
                    logger.warn { "[tool] ${ctx.toolName} failed: $errorMsg" }
                    onToolActivity?.invoke(ToolActivity.Failed(ctx.toolName, errorMsg))
                }
                onLLMCallCompleted { ctx ->
                    val inputTokens = ctx.response?.metaInfo?.inputTokensCount?.toLong() ?: 0L
                    val outputTokens = ctx.response?.metaInfo?.outputTokensCount?.toLong() ?: 0L
                    observer.recordLlmCall(inputTokens, outputTokens)
                    tokenAccumulator.input += inputTokens
                    tokenAccumulator.output += outputTokens
                    logLlmResponse(ctx.response, tokenAccumulator)
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        val result: ManifestResult = try {
            if (resumeData != null) {
                logger.info { "[resume] Resuming from ${resumeData.fromStage} using runId=${resumeData.runId}" }
                val checkpoint = resumeData.toAgentCheckpointData(url)
                Persistence.runFromCheckpoint(
                    agent = agent,
                    input = url,
                    checkpoint = checkpoint,
                    sessionId = resumeData.runId,
                )
            } else {
                agent.run(url)
            }
        } catch (e: Exception) {
            // Koog's subgraphWithTask throws a bare IllegalStateException when the model
            // never calls a tool after the re-prompt budget is spent. Translate it into
            // a typed exception with an actionable hint (model may lack function calling).
            val toThrow: Exception = if (isSubgraphTaskToolCallExhaustion(e)) {
                ScoopAiGenerationException(
                    message = buildToolCallExhaustionMessage(
                        modelId = model.id,
                        phase = activePhase,
                        originalMessage = e.message ?: e::class.simpleName ?: "unknown",
                    ),
                    cause = e,
                )
            } else {
                e
            }
            artifacts?.let { it.markFailed("${toThrow::class.simpleName}: ${toThrow.message}") }
            throw toThrow
        }
        val elapsed = startTime.elapsedNow().inWholeMilliseconds

        // Mark successful run as completed
        artifacts?.let { it.finalize() }

        return result.copy(
            manifest = result.manifest,
            tokenUsage = TokenUsage(tokenAccumulator.input, tokenAccumulator.output),
            durationMs = elapsed,
            stages = observer.snapshot(),
        )
    }

    /**
     * Refines an existing Scoop manifest based on user instruction or feedback.
     * Runs a lightweight single-phase refine LLM step without re-executing research/analyze.
     *
     * @param manifestJson The current Scoop manifest JSON string.
     * @param instruction The user's prompt or review feedback requesting modifications.
     * @param suggestedAppName Optional application name.
     */
    suspend fun refine(
        manifestJson: String,
        instruction: String,
        history: List<String> = emptyList(),
        evidence: String? = null,
        suggestedAppName: String? = null,
    ): ManifestResult {
        val startTime = TimeSource.Monotonic.markNow()
        observer.reset()
        onPhase?.invoke("refine")
        observer.enterPhase("refine")
        val tokenAccumulator = TokenAccumulator()

        val finishTool = LenientStringFinishTool()
        val registry = ToolRegistry {
            tool(finishTool)
        }

        val refineStrategy = strategy<String, String>("refine_manifest") {
            val refineSubgraph by subgraphWithTask<String, String, String>(
                tools = emptyList<ToolBase<*, *>>(),
                finishTool = finishTool,
                llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel),
            ) { input ->
                buildRefinePrompt(
                    RefineContext(
                        manifest = manifestJson,
                        feedback = instruction,
                        history = history,
                        evidence = evidence,
                        retryCount = 1,
                    )
                )
            }
            nodeStart then refineSubgraph then nodeFinish
        }

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system("You are a Scoop manifest refiner. You modify existing Scoop bucket manifests based on instructions.")
            },
            model = modelWithoutToolChoice,
            maxAgentIterations = maxIterations,
        )

        val agent: GraphAIAgent<String, String> = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = refineStrategy,
            toolRegistry = registry,
        ) {
            handleEvents {
                onLLMCallCompleted { ctx ->
                    val inputTokens = ctx.response?.metaInfo?.inputTokensCount?.toLong() ?: 0L
                    val outputTokens = ctx.response?.metaInfo?.outputTokensCount?.toLong() ?: 0L
                    observer.recordLlmCall(inputTokens, outputTokens)
                    tokenAccumulator.input += inputTokens
                    tokenAccumulator.output += outputTokens
                    logLlmResponse(ctx.response, tokenAccumulator)
                }
            }
        }

        val refinedManifest: String = try {
            agent.run(manifestJson)
        } catch (e: Exception) {
            val toThrow: Exception = if (isSubgraphTaskToolCallExhaustion(e)) {
                ScoopAiGenerationException(
                    message = buildToolCallExhaustionMessage(
                        modelId = model.id,
                        phase = "refine",
                        originalMessage = e.message ?: e::class.simpleName ?: "unknown",
                    ),
                    cause = e,
                )
            } else {
                e
            }
            throw toThrow
        }

        val normalized = normalizeManifest(refinedManifest)
        val elapsed = startTime.elapsedNow().inWholeMilliseconds
        observer.exitPhase("refine", elapsed)
        phaseOutputListener?.invoke("refine", normalized)

        val errors = mutableListOf<String>()
        if (manifestIo != null) {
            try {
                val validateJson = manifestIo.validate(normalized)
                val validationErrors = parseValidateErrors(validateJson)
                errors.addAll(validationErrors)
            } catch (e: Exception) {
                errors.add("Validate error: ${e.message}")
            }
        }
        checkAutoupdateVersionPlaceholder(normalized)?.let { errors.add(it) }
        validatePersistPaths(normalized)?.let { errors.add(it) }

        return ManifestResult(
            manifest = normalized,
            suggestedAppName = suggestedAppName,
            exhausted = errors.isNotEmpty(),
            issues = errors,
            tokenUsage = TokenUsage(tokenAccumulator.input, tokenAccumulator.output),
            durationMs = elapsed,
            stages = observer.snapshot(),
        )
    }

}
