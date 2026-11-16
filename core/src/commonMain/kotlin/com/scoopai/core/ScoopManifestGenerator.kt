package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.BinaryImage
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.PrefetchResult
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.artifacts.ResumeData
import com.scoopai.core.agent.prompt.buildGeneratorSystemPrompt
import com.scoopai.core.agent.prompt.buildReviewSystemPrompt
import com.scoopai.core.agent.prompt.reviewManifestWithRetestNote
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
import com.scoopai.core.agent.SaveToBucketArgs
import com.scoopai.core.agent.SaveToBucketTool
import com.scoopai.core.agent.InstallAppTool
import com.scoopai.core.agent.ReadManifestRefTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.ext.agent.subgraphWithTask
import com.scoopai.core.llm.KoogCompat
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * Koog wraps plain tool text in an escaped JSONLiteral(isString=true); unwrap it so
 * the raw content (e.g. full manifest JSON) reaches events/logger unescaped.
 */
internal fun koogToolResultToString(el: JSONElement?): String = when {
    el == null -> "null"
    el is JSONLiteral && el.isString -> el.content
    else -> el.toString()
}

/** Caps the tool-call intent text attached to Started/Completed/Failed events. */
private const val TOOL_INTENT_MAX_LENGTH = 200

/**
 * Extracts the model's self-declared tool-call intent from a completed LLM
 * response. Only mixed responses (text parts alongside at least one tool call)
 * carry an intent; a pure text response is a final answer or review verdict,
 * never a pending tool intent. Text is trimmed and capped at
 * [TOOL_INTENT_MAX_LENGTH] chars. Null when there is nothing to report.
 */
internal fun extractToolCallIntent(response: Message.Assistant?): String? {
    val parts = response?.parts ?: return null
    if (parts.none { it is MessagePart.Tool.Call }) return null
    val text = parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }.trim()
    if (text.isEmpty()) return null
    return text.take(TOOL_INTENT_MAX_LENGTH)
}

/**
 * The assist subgraph's `__ask_user__` tool, picked from the host registry when
 * the session is an Assist session: a save without an explicit bucket ends in a
 * needsBucket reply, and the model needs ask_user to offer the bucket choice.
 * A Generate session's repair loop must never ask the user, so it stays null.
 */
internal fun assistAskUserTool(assistOnly: Boolean, tools: List<ToolBase<*, *>>): ToolBase<*, *>? =
    if (assistOnly) tools.firstOrNull { it.name == PhaseTools.ASK_USER } else null

/**
 * Folds the assist-only tools into the agent registry. ToolRegistry.plus
 * dedupes by tool name (first wins), so a tool already present in the base
 * registry (e.g. ask_user) is not duplicated.
 */
internal fun assistToolsFold(base: ToolRegistry, assistTools: List<ToolBase<*, *>>): ToolRegistry =
    base + ToolRegistry { tools(assistTools) }

/**
 * Read-only network tools for the assist subgraph (Chat with AI). Gated on the session:
 * the Generate repair loop must stay network-free.
 */
internal fun assistNetworkTools(assistOnly: Boolean, tools: List<ToolBase<*, *>>): List<ToolBase<*, *>> =
    if (assistOnly) tools.filter { it.name in PhaseTools.ASSIST_NETWORK_TOOL_NAMES } else emptyList()

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
    /** Observer for recording per-phase execution statistics. */
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
    /**
     * When [assistVerifier] is null, the reason the assist-side scoop probe
     * failed (from ProcessScoopVerifier.tryCreateDetailed). Forwarded to the
     * assist prompt so the model can tell the user why install testing cannot
     * run in this session.
     */
    private val assistTestUnavailableReason: String? = null,
    /** Aggregate engine callbacks (phase start, phase output, tool activity). */
    private val callbacks: SessionCallbacks = SessionCallbacks.empty,
    /** Assist-subgraph host capabilities (save / install / read-reference tools). */
    private val host: AssistHost = AssistHost.NoOp,
    // ------------------------------------------------------------------
    // Deprecated flat callback forms, kept for source compatibility with
    // existing hosts/tests; merged into multicast dispatchers below. New
    // callers should pass [callbacks] / [host] instead.
    // ------------------------------------------------------------------
    /**
     * Callback invoked when a phase starts. Used by cli ConsoleProgress overlay.
     */
    onPhase: ((String) -> Unit)? = null,
    /**
     * In-memory listener invoked once per completed phase (research / analyze /
     * generate / assist / repair) with the raw, un-masked phase output JSON
     * (`phaseName, output`). Intended for in-memory checkpoints only (e.g.
     * [com.scoopai.core.artifacts.ResumeData.fromPhaseOutputs]); do NOT persist
     * this content in sensitive scenarios, as it is not masked.
     */
    phaseOutputListener: ((phaseName: String, output: String) -> Unit)? = null,
    /**
     * Callback invoked on tool call events (Started, Completed, Failed).
     * Used by hosts (e.g. Scooper) for transparent tool call progress reporting.
     */
    onToolActivity: ((ToolActivity) -> Unit)? = null,
    /**
     * Flat save callback backing the assist subgraph's save_to_bucket tool;
     * preferred form is [host]. A Generate session's repair loop must not save.
     */
    saveHandler: (suspend (SaveToBucketArgs) -> String)? = null,
    /**
     * Flat install callback backing the assist subgraph's install_app tool;
     * preferred form is [host]. A Generate session's repair loop must not install.
     */
    installHandler: (suspend (bucket: String?, name: String) -> String)? = null,
    /**
     * Flat read callback backing the assist subgraph's
     * read_manifest_reference tool; preferred form is [host].
     */
    readManifestRefHandler: (suspend (bucket: String, name: String) -> String)? = null,
    /**
     * UI language name used as the fallback reply language when the request
     * carries no natural language. Stored but not yet consumed; the prompt
     * side reads it in a later change.
     */
    private val replyLanguage: String? = null,
) {
    private val flatOnPhase = onPhase
    private val flatPhaseOutputListener = phaseOutputListener
    private val flatOnToolActivity = onToolActivity
    private val flatSaveHandler = saveHandler
    private val flatInstallHandler = installHandler
    private val flatReadManifestRefHandler = readManifestRefHandler

    /** Multicast phase-start dispatcher: flat listener first, then aggregate. */
    private fun emitPhaseStart(name: String) {
        flatOnPhase?.invoke(name)
        callbacks.onPhase?.invoke(name)
    }

    /** Multicast tool-activity dispatcher: flat listener first, then aggregate. */
    private fun emitToolActivity(activity: ToolActivity) {
        flatOnToolActivity?.invoke(activity)
        callbacks.onToolActivity?.invoke(activity)
    }

    /**
     * Shared tool-event wiring for both agents' handleEvents blocks: the outer
     * agent uses the "[tool]" log prefix, the review agent "[review-tool]".
     * [onCompletedExtra] lets the review agent track verify_install outcomes.
     */
    private fun EventHandlerConfig.toolEventWiring(
        logPrefix: String,
        pendingIntent: () -> String?,
        onCompletedExtra: ((toolName: String, raw: String) -> Unit)? = null,
    ) {
        onToolCallStarting { ctx ->
            observer.recordToolCall()
            val args = sanitizeForLog(ctx.toolArgs.toString())
            logger.info { "$logPrefix ${ctx.toolName}($args)" }
            emitToolActivity(ToolActivity.Started(ctx.toolName, args, intent = pendingIntent()))
        }
        onToolCallCompleted { ctx ->
            val raw = koogToolResultToString(ctx.toolResult)
            logger.info { "$logPrefix ${ctx.toolName} -> ${sanitizeForLog(raw, maxLen = 200)}" }
            emitToolActivity(ToolActivity.Completed(ctx.toolName, raw, intent = pendingIntent()))
            onCompletedExtra?.invoke(ctx.toolName, raw)
        }
        onToolCallFailed { ctx ->
            val errorMsg = sanitizeForLog(ctx.message.ifBlank { ctx.error?.message ?: "unknown tool error" }, maxLen = 200)
            logger.warn { "$logPrefix ${ctx.toolName} failed: $errorMsg" }
            emitToolActivity(ToolActivity.Failed(ctx.toolName, errorMsg, intent = pendingIntent()))
        }
    }

    /**
     * Shared bookkeeping for onLLMCallCompleted across both agents: token
     * accounting plus the pending tool-call intent capture. Returns the intent
     * for the caller to store; per-agent response logging stays at the call site.
     */
    private fun trackLlmCall(
        ctx: ai.koog.agents.core.feature.handler.llm.LLMCallCompletedContext,
        tokenAccumulator: TokenAccumulator,
    ): String? {
        val inputTokens = ctx.response?.metaInfo?.inputTokensCount?.toLong() ?: 0L
        val outputTokens = ctx.response?.metaInfo?.outputTokensCount?.toLong() ?: 0L
        tokenAccumulator.input += inputTokens
        tokenAccumulator.output += outputTokens
        observer.recordLlmCall(inputTokens, outputTokens)
        return extractToolCallIntent(ctx.response)
    }
    /**
     * Model with [LLMCapability.ToolChoice] stripped so that Koog uses its
     * internal re-prompt path instead of hard-throwing on text-only responses
     * (a common thinking-model quirk). Assembly owned by KoogCompat.
     */
    private val modelWithoutToolChoice: LLModel by lazy {
        KoogCompat.decorateModel(model, stripToolChoice = true)
    }

    /**
     * Executes a unified Scoop AI session (either Generate or Assist) through the
     * single unified agent strategy, ensuring all validation, normalization, and
     * error handling policies are enforced.
     */
    suspend fun runSession(
        request: SessionRequest,
        resumeData: ResumeData? = null,
        images: List<BinaryImage> = emptyList(),
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
        // The checksums filename is cross-checked against the release assets inside
        // buildGeneratePrompt (buildChecksumsNote), which owns the ignoreCase rule.
        val generatePrompt: suspend (String, AnalyzeOutput, ResearchOutput?, List<String>) -> String =
            { skeleton, analyze, research, releaseAssetNames ->
                buildGeneratePrompt(
                    skeleton,
                    analyze,
                    (request as? SessionRequest.Generate)?.outputPath,
                    (request as? SessionRequest.Generate)?.userPrompt,
                    research,
                    checksumsAssetNames = releaseAssetNames,
                )
            }

        // save_to_bucket/install_app are assist-only: a Generate session's repair
        // loop must not save or install an unverified manifest. Registered only
        // when the host provides a handler (flat param or AssistHost aggregate),
        // so CLI sessions run without them.
        val assistOnly = request is SessionRequest.Assist
        // The flat save handler takes the tool args while AssistHost takes the
        // raw (bucket, name, overwrite) triple; adapt whichever form was given.
        var effectiveSaveHandler: (suspend (SaveToBucketArgs) -> String)? = flatSaveHandler
        if (effectiveSaveHandler == null) {
            host.saveToBucket?.let { triple ->
                effectiveSaveHandler = { args: SaveToBucketArgs -> triple(args.bucket, args.name, args.overwrite) }
            }
        }
        val effectiveInstallHandler = flatInstallHandler ?: host.installApp
        val effectiveReadRefHandler = flatReadManifestRefHandler ?: host.readManifestRef
        val saveToBucketTool = if (assistOnly) {
            effectiveSaveHandler?.let { handler -> SaveToBucketTool(handler) }
        } else {
            null
        }
        val installAppTool = if (assistOnly) effectiveInstallHandler?.let(::InstallAppTool) else null
        val readManifestRefTool = if (assistOnly) effectiveReadRefHandler?.let(::ReadManifestRefTool) else null
        val assistPrompt: suspend (AssistContext) -> String =
            {
                if (it.explanatory) {
                    buildAssistPrompt(
                        it,
                        installTestActive = assistVerifier != null,
                        saveActive = saveToBucketTool != null,
                        installActive = installAppTool != null,
                        assistTestUnavailableReason = assistTestUnavailableReason,
                        readRefActive = readManifestRefTool != null,
                    )
                } else {
                    buildRepairPrompt(
                        it,
                        saveActive = saveToBucketTool != null,
                        installActive = installAppTool != null,
                        readRefActive = readManifestRefTool != null,
                    )
                }
            }

        // Tracks whether the last review-side verify_install failed. If so, the next
        // review round gets a retest note so the agent re-runs the install test.
        var lastInstallTestFailed = false

        // Model's self-declared intent for the current LLM response batch,
        // captured in onLLMCallCompleted and attached to every Started and
        // Completed event of the batch's tool calls. One response can carry
        // multiple tool calls, so the intent stays pending until the next
        // onLLMCallCompleted overwrites it; events read it without clearing.
        var pendingToolIntent: String? = null

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

        // All assist-subgraph tools, shared by the strategy registration and the
        // agent-level registry (schemas must resolve there at execution time).
        // __ask_user__ must be wired explicitly: the subgraph's tools whitelist
        // replaces the agent registry, and a save without a bucket ends in a
        // needsBucket reply that only ask_user can resolve into a user choice.
        val askUserTool = assistAskUserTool(assistOnly, allTools)
        val wiredAssistTools =
            listOfNotNull(assistVerifyInstallTool, saveToBucketTool, installAppTool, readManifestRefTool, askUserTool) +
                assistNetworkTools(assistOnly, allTools)

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
            // execute tools / send results) plus the finish node. 9 leaves room for
            // up to two tool rounds (tool call + result round trip) before finishing
            // and also fits a tool-free review with spare steps.
            maxIterations = 9,
        ) {
            handleEvents {
                onLLMCallCompleted { ctx ->
                    pendingToolIntent = trackLlmCall(ctx, tokenAccumulator)
                }
                // Mirror the outer agent's tool wiring so hosts see review-phase
                // verify_install activity too. With an empty registry nothing fires.
                toolEventWiring(
                    logPrefix = "[review-tool]",
                    pendingIntent = { pendingToolIntent },
                    onCompletedExtra = { toolName, raw ->
                        if (toolName == com.scoopai.core.agent.VerifyInstallTool.TOOL_NAME) {
                            when {
                                raw.contains("\"success\":false") -> lastInstallTestFailed = true
                                raw.contains("\"success\":true") -> lastInstallTestFailed = false
                            }
                        }
                    },
                )
                onToolValidationFailed { ctx ->
                    // Validation failures never reach Completed/Failed handlers; emit
                    // Failed so hosts do not see a hanging Started event.
                    val errorMsg = sanitizeForLog(
                        ctx.message.ifBlank { ctx.error.message ?: "invalid tool arguments" },
                        maxLen = 200,
                    )
                    logger.warn { "[review-tool] ${ctx.toolName} invalid args: $errorMsg" }
                    emitToolActivity(ToolActivity.Failed(ctx.toolName, errorMsg))
                }
            }
        }
        // The review agent runs inside the verify node's enter/exitPhase brackets;
        // the observer has no nesting, so its LLM/tool stats fold into "verify".
        val reviewExecutor: suspend (String) -> String = { manifest ->
            pendingManifest = manifest
            @Suppress("UNCHECKED_CAST")
            (reviewAgent as GraphAIAgent<String, String>).run(
                reviewManifestWithRetestNote(manifest, lastInstallTestFailed),
            )
        }
        val llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel)
        val phaseWrite: ((String, String) -> Unit)? = artifacts?.let { writer ->
            { phase, content -> writer.writePhase(phase, content) }
        }
        val onPhaseOutput = if (phaseWrite != null || flatPhaseOutputListener != null || callbacks.onPhaseOutput != null) {
            { phase: String, out: String ->
                phaseWrite?.invoke(phase, out)
                flatPhaseOutputListener?.invoke(phase, out)
                callbacks.onPhaseOutput?.invoke(phase, out)
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
            callbacks = SessionCallbacks(
                onPhase = { name ->
                    activePhase = name
                    emitPhaseStart(name)
                },
                onPhaseOutput = onPhaseOutput,
            ),
            observer = observer,
            usedAppNamesProvider = usedAppNamesProvider,
            assistTools = wiredAssistTools,
            assistImages = images,
            agentModel = modelWithoutToolChoice,
        )

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system(buildGeneratorSystemPrompt(replyLanguage))
            },
            model = modelWithoutToolChoice,
            maxAgentIterations = maxIterations,
        )

        // When assist subgraph tools are wired in, the agent-level registry must
        // also expose them so their schemas resolve at execution time. plus()
        // dedupes by name, so ask_user (already in the registry) is not duplicated.
        val outerToolRegistry = assistToolsFold(toolRegistry, wiredAssistTools)

        val agent: GraphAIAgent<SessionRequest, ManifestResult> = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = outerToolRegistry,
        ) {
            handleEvents {
                toolEventWiring(
                    logPrefix = "[tool]",
                    pendingIntent = { pendingToolIntent },
                )
                onLLMCallCompleted { ctx ->
                    pendingToolIntent = trackLlmCall(ctx, tokenAccumulator)
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
        images: List<BinaryImage> = emptyList(),
    ): ManifestResult = runSession(
        request = SessionRequest.Assist(
            currentManifest = manifestJson,
            instruction = instruction,
            history = history,
            evidence = evidence,
            suggestedAppName = suggestedAppName,
        ),
        images = images,
    )

}
