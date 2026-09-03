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
import com.scoopai.core.agent.prompt.buildReviewSystemPrompt
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildAssistPrompt
import com.scoopai.core.agent.prompt.buildRepairPrompt
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
import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.agent.VerifyInstallTool
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
 * (research → analyze → generate) and a deterministic verify + optional assist loop.
 *
 * Flow: research(LLM) → analyze(LLM) → prepareManifest(deterministic) → generate(LLM) → verify(validate+review)
 *                                                                                           ↓ pass        ↓ fail
 *                                                                                      nodeFinish    assist(LLM, optional)
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
     * generate / assist / repair) with the raw, un-masked phase output JSON
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
    /**
     * Real install verifier wired into the review agent as the `verify_install`
     * tool. When non-null, the review agent may run a real scoop install of the
     * manifest under review; when null, the review agent stays tool-free.
     */
    private val scoopVerifier: ScoopVerifier? = null,
    /**
     * Verifier for the assist/repair subgraph's `verify_install` tool. Kept
     * separate from [scoopVerifier] so assist and review have independent
     * install budgets. When null the assist subgraph stays tool-free.
     */
    private val assistVerifier: ScoopVerifier? = null,
) {
    /**
     * Model with [LLMCapability.ToolChoice] stripped so that Koog uses its
     * internal re-prompt path instead of hard-throwing on text-only responses
     * (a common thinking-model quirk).
     */
    private val modelWithoutToolChoice: LLModel by lazy {
        model.copy(capabilities = model.capabilities?.filter { it != LLMCapability.ToolChoice })
    }

    /**
     * Executes a unified Scoop AI session (either Generate or Assist) through the
     * single unified agent strategy, ensuring all validation, normalization, and
     * error handling policies are enforced.
     */
    suspend fun runSession(
        request: SessionRequest,
        resumeData: ResumeData? = null,
    ): ManifestResult {
        val startTime = TimeSource.Monotonic.markNow()
        observer.reset()
        var activePhase: String? = null
        val tokenAccumulator = TokenAccumulator()

        val allTools = toolRegistry.tools
        val researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }

        val researchPrompt: suspend (PrefetchResult) -> String =
            { buildResearchPrompt(it, (request as? SessionRequest.Generate)?.outputPath, (request as? SessionRequest.Generate)?.userPrompt) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it, (request as? SessionRequest.Generate)?.userPrompt) }
        val generatePrompt: suspend (String, AnalyzeOutput, ResearchOutput?) -> String =
            { skeleton, analyze, research ->
                buildGeneratePrompt(
                    skeleton,
                    analyze,
                    (request as? SessionRequest.Generate)?.outputPath,
                    (request as? SessionRequest.Generate)?.userPrompt,
                    research,
                )
            }
        val assistPrompt: suspend (AssistContext) -> String =
            {
                if (it.explanatory) buildAssistPrompt(it, installTestActive = assistVerifier != null)
                else buildRepairPrompt(it)
            }

        // The verify_install tool resolves the manifest at execution time so each
        // review round installs the manifest currently under review.
        var pendingManifest: String? = null
        val verifyInstallTool = scoopVerifier?.let { verifier ->
            VerifyInstallTool(verifier) { pendingManifest.orEmpty() }
        }

        // The assist/repair subgraph's verify_install tool resolves the manifest it
        // is editing at execution time. Only an Assist session has one (the current
        // manifest); a Generate session's repair loop leaves it null so the tool's
        // blank guard returns early instead of burning install budget.
        val assistManifest: String? = (request as? SessionRequest.Assist)?.currentManifest
        val assistVerifyInstallTool = assistVerifier?.let { verifier ->
            VerifyInstallTool(verifier) { assistManifest.orEmpty() }
        }

        val reviewAgent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = if (verifyInstallTool != null) {
                ToolRegistry { tool(verifyInstallTool) }
            } else {
                ToolRegistry.EMPTY
            },
            systemPrompt = buildReviewSystemPrompt(hasInstallTest = verifyInstallTool != null),
            // Koog counts nodes per iteration: one review round = 3 nodes (LLM /
            // execute tools / send results) plus the finish node. 5 lets a tool-free
            // review finish with one spare step; 9 leaves room for up to two tool
            // rounds (tool call + result round trip) before finishing.
            maxIterations = if (verifyInstallTool != null) 9 else 5,
        ) {
            handleEvents {
                onLLMCallCompleted { ctx ->
                    val inputTokens = ctx.response?.metaInfo?.inputTokensCount?.toLong() ?: 0L
                    val outputTokens = ctx.response?.metaInfo?.outputTokensCount?.toLong() ?: 0L
                    tokenAccumulator.input += inputTokens
                    tokenAccumulator.output += outputTokens
                    observer.recordLlmCall(inputTokens, outputTokens)
                }
                // Mirror the outer agent's tool wiring so hosts see review-phase
                // verify_install activity too. With an empty registry nothing fires.
                onToolCallStarting { ctx ->
                    observer.recordToolCall()
                    val args = sanitizeForLog(ctx.toolArgs.toString())
                    logger.info { "[review-tool] ${ctx.toolName}($args)" }
                    onToolActivity?.invoke(ToolActivity.Started(ctx.toolName, args))
                }
                onToolCallCompleted { ctx ->
                    val result = sanitizeForLog(ctx.toolResult?.toString() ?: "null", maxLen = 200)
                    logger.info { "[review-tool] ${ctx.toolName} -> $result" }
                    onToolActivity?.invoke(ToolActivity.Completed(ctx.toolName, result))
                }
                onToolCallFailed { ctx ->
                    val errorMsg = sanitizeForLog(
                        ctx.message.ifBlank { ctx.error?.message ?: "unknown tool error" },
                        maxLen = 200,
                    )
                    logger.warn { "[review-tool] ${ctx.toolName} failed: $errorMsg" }
                    onToolActivity?.invoke(ToolActivity.Failed(ctx.toolName, errorMsg))
                }
                onToolValidationFailed { ctx ->
                    // Validation failures never reach Completed/Failed handlers; emit
                    // Failed so hosts do not see a hanging Started event.
                    val errorMsg = sanitizeForLog(
                        ctx.message.ifBlank { ctx.error.message ?: "invalid tool arguments" },
                        maxLen = 200,
                    )
                    logger.warn { "[review-tool] ${ctx.toolName} invalid args: $errorMsg" }
                    onToolActivity?.invoke(ToolActivity.Failed(ctx.toolName, errorMsg))
                }
            }
        }
        // The review agent runs inside the verify node's enter/exitPhase brackets;
        // the observer has no nesting, so its LLM/tool stats fold into "verify".
        val reviewExecutor: suspend (String) -> String = { manifest ->
            pendingManifest = manifest
            @Suppress("UNCHECKED_CAST")
            (reviewAgent as GraphAIAgent<String, String>).run(manifest)
        }
        val llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel)
        val phaseWrite: ((String, String) -> Unit)? = artifacts?.let { writer ->
            { phase, content -> writer.writePhase(phase, content) }
        }
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
            assistPromptBuilder = assistPrompt,
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
            assistTools = listOfNotNull(assistVerifyInstallTool),
        )

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system("You are a Scoop manifest generator. You generate and assist Scoop bucket manifests for Windows software.")
            },
            model = modelWithoutToolChoice,
            maxAgentIterations = maxIterations,
        )

        // When an assist verifier is wired in, the agent-level registry must also
        // expose the tool so the assist subgraph's schema resolves at execution time.
        val outerToolRegistry = assistVerifyInstallTool?.let { assistTool ->
            toolRegistry + ToolRegistry { tool(assistTool) }
        } ?: toolRegistry

        val agent: GraphAIAgent<SessionRequest, ManifestResult> = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = outerToolRegistry,
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
            if (resumeData != null && request is SessionRequest.Generate) {
                logger.info { "[resume] Resuming from ${resumeData.fromStage} using runId=${resumeData.runId}" }
                val checkpoint = resumeData.toAgentCheckpointData(request.url)
                Persistence.runFromCheckpoint(
                    agent = agent,
                    input = request,
                    checkpoint = checkpoint,
                    sessionId = resumeData.runId,
                )
            } else {
                agent.run(request)
            }
        } catch (e: Exception) {
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

        artifacts?.let { it.finalize() }

        return result.copy(
            manifest = result.manifest,
            tokenUsage = TokenUsage(tokenAccumulator.input, tokenAccumulator.output),
            durationMs = elapsed,
            stages = observer.snapshot(),
        )
    }

    suspend fun generate(
        url: String,
        outputPath: String? = null,
        userPrompt: String? = null,
        resumeData: ResumeData? = null,
    ): ManifestResult = runSession(
        request = SessionRequest.Generate(url = url, outputPath = outputPath, userPrompt = userPrompt),
        resumeData = resumeData,
    )

    suspend fun assist(
        manifestJson: String,
        instruction: String,
        history: List<String> = emptyList(),
        evidence: String? = null,
        suggestedAppName: String? = null,
    ): ManifestResult = runSession(
        request = SessionRequest.Assist(
            currentManifest = manifestJson,
            instruction = instruction,
            history = history,
            evidence = evidence,
            suggestedAppName = suggestedAppName,
        ),
    )

}
