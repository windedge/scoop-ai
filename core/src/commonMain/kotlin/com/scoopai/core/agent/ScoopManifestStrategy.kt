package com.scoopai.core.agent

import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.typeToken
import com.scoopai.core.AssistContext
import com.scoopai.core.ManifestResult
import com.scoopai.core.SessionRequest
import com.scoopai.core.llm.KoogCompat
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.nodes.assembleManifestSkeleton
import com.scoopai.core.agent.nodes.finalizeManifestSkeleton
import com.scoopai.core.agent.nodes.autoFillBinFromShortcuts
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.agents.core.agent.entity.AIAgentStorageAPI
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import io.github.oshai.kotlinlogging.KotlinLogging
import com.scoopai.core.SessionCallbacks

private val logger = KotlinLogging.logger {}

/**
 * The strategy name used to register the Scoop manifest strategy graph with Koog.
 *
 * Single source of truth shared by [scoopManifestStrategy] (which registers the
 * graph under this name) and [com.scoopai.core.artifacts.ResumeData] (which builds
 * checkpoint node paths that must resolve against that same graph).
 */
public const val SCOOP_MANIFEST_STRATEGY_NAME: String = "unified_scoop_manifest"

/**
 * The single source of truth for the phase name emitted for the assist/repair
 * subgraph: a user follow-up ([AssistContext.explanatory] = true) is "assist",
 * the internal repair loop ([AssistContext.explanatory] = false) is "repair".
 */
internal fun assistPhaseName(ctx: AssistContext): String = if (ctx.explanatory) "assist" else "repair"

/**
 * Carrier produced by the [scoopManifestStrategy]'s checkAnswered node: the
 * finalize payload plus a flag telling whether the finalize was a pure-answer
 * escape hatch (the user message was a question, so the manifest is echoed back
 * unmodified).
 */
@kotlinx.serialization.Serializable
private data class CheckedFinalize(
    val payload: FinalizePayload,
    val isPureAnswer: Boolean,
)

/**
 * Normalizes a finalize explanation for use: trimmed, or null when blank.
 * Shared by the generate and assist/repair exit edges; only a usable (non-null)
 * result may overwrite the stored explanation.
 */
internal fun usableExplanation(explanation: String?): String? =
    explanation?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Non-blank overwrite rule: a usable [next] replaces [previous], otherwise
 * [previous] is kept (a repair round without an explanation must not erase the
 * last valid one, architecture proposal 4.3).
 */
internal fun keepExplanation(previous: String?, next: String?): String? = next ?: previous

// Repair rounds finalize with their own explanation; combine it under the generate
// summary when they differ, otherwise keep the single explanation as-is. An assist
// session never runs the generate edge, so generateExplanationKey stays null.
internal suspend fun AIAgentStorageAPI.getCombinedExplanation(generateKey: AIAgentStorageKey<String>, finalKey: AIAgentStorageKey<String>): String? {
    val gen = get(generateKey)
    val final = get(finalKey)
    return if (gen != null && final != null && final != gen) "$gen\n\nFix applied: $final" else final
}

/**
 * Builds the assist subgraph model: null when there are no images (inherit the
 * agent model unchanged) or when no agent model was provided; otherwise a copy
 * of [agentModel] with [LLMCapability.Vision.Image] appended so the
 * OpenAI-compatible client encodes image parts.
 */
internal fun assistModelWithVision(agentModel: LLModel?, images: List<BinaryImage>): LLModel? {
    if (images.isEmpty()) return null
    return agentModel?.copy(capabilities = agentModel.capabilities.orEmpty() + LLMCapability.Vision.Image)
}

/**
 * Creates a graph strategy for generating Scoop manifests.
 *
 * Flow (v4 -- prefetch + 3-phase + edge-based assist/repair loop):
 * ```
 * nodeStart → prefetch(deterministic: pre-collect data)
 *   → research(LLM, research tools)
 *   [no_windows] → finishNoAssets → nodeFinish
 *   [ok] → analyze(LLM, analysis tools)
 *        → prepareManifest(deterministic: skeleton assembly)
 *        → generate(LLM, no tools: skeleton review + finalize)
 *        → verify(validate + review)
 *          [pass] → nodeFinish
 *          [fail + retry ≤ 3] → assist(LLM, optional tools, repair) → verify (loop)
 *          [fail + retry > 3] → nodeFinish (exhausted)
 * ```
 *
 * User follow-ups route request → prepareAssistFromRequest → assist(explanatory=true)
 * → checkAnswered (question escape hatch) → normalize → verify → nodeFinish.
 *
 * @param researchTools Tools available in the research phase.
 * @param analyzeTools Tools available in the analyze phase (hash/compute, archive listing, etc.).
 * @param llmParams LLM parameters (thinking mode, etc.).
 * @param researchPromptBuilder Builds the research prompt from [PrefetchResult].
 * @param analyzePromptBuilder Builds the analyze prompt from [ResearchOutput].
 * @param generatePromptBuilder Builds the generate prompt from (skeleton, analyze, research).
 * @param assistPromptBuilder Builds the assist/repair prompt from [AssistContext].
 * @param manifestIo Structural validate backend handed to the [ManifestVerifier].
 * @param reviewExecutor Semantic review executor (independent LLM agent) handed to the [ManifestVerifier].
 * @param gitHubApi GitHub API for prefetching project data before LLM phases.
 * @param archiveInspector Archive inspector for prefetching persist/search data before LLM phases.
 */
@OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (PrefetchResult) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    generatePromptBuilder: (suspend (String, AnalyzeOutput, ResearchOutput?) -> String)? = null,
    assistPromptBuilder: (suspend (AssistContext) -> String)? = null,
    manifestIo: ManifestIo? = null,
    reviewExecutor: (suspend (String) -> String)? = null,
    gitHubApi: GitHubApi? = null,
    archiveInspector: ArchiveInspector? = null,
    /** Engine event callbacks (phase start, phase output, tool activity). */
    callbacks: SessionCallbacks = SessionCallbacks.empty,
    /** Phase start/duration store; pairs with the observer's enter/exit calls. */
    telemetry: PhaseTelemetry = PhaseTelemetry(),
    /** Observer for recording per-phase execution statistics. */
    observer: GenerationObserver = GenerationObserver(),
    /**
     * Provider for the set of existing manifest names in local Scoop buckets.
     * When non-null, the verify phase checks [suggestedAppName] against this set
     * and reports a conflict error if the name is already used.
     */
    usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    /**
     * Tools for the assist/repair subgraph. Their schemas are advertised to the LLM;
     * execution resolves by name against the agent-level registry, so each tool must
     * also be registered there. Empty (the default) keeps the subgraph tool-free.
     */
    assistTools: List<ToolBase<*, *>> = emptyList(),
    /**
     * User-supplied images attached to the first assist LLM call (explanatory=true only).
     * When non-empty, the subgraph model gains [LLMCapability.Vision.Image] so the
     * OpenAI-compatible client will encode image parts.
     */
    assistImages: List<BinaryImage> = emptyList(),
    /**
     * The agent-level model (without ToolChoice). Required when [assistImages] is
     * non-empty so the subgraph can build a Vision-capable model from it. When
     * null, the subgraph inherits the agent model unchanged.
     */
    agentModel: LLModel? = null,
) = strategy<SessionRequest, ManifestResult>(SCOOP_MANIFEST_STRATEGY_NAME) {

    // ── Storage keys ───────────────────────────────────────────
    val retryCountKey = createStorageKey<Int>("assist_retry_count")
    val maxRetries = 5
    val researchDataKey = createStorageKey<ResearchOutput>("research_data")
    val analyzeDataKey = createStorageKey<AnalyzeOutput>("analyze_data")
    val suggestedAppNameKey = createStorageKey<String>("suggested_app_name")
    val appNameConflictKey = createStorageKey<String>("app_name_conflict")
    // Base name the assist session started from (the referenced @bucket/app for in-place
    // updates): the verify conflict check exempts a conflict against it.
    val assistBaseNameKey = createStorageKey<String>("assist_base_name")
    val finalManifestKey = createStorageKey<String>("final_manifest")
    val explanationKey = createStorageKey<String>("final_explanation")
    val generateExplanationKey = createStorageKey<String>("generate_explanation")

    // The verify pipeline (structure + review + conflict + autofix), extracted from
    // this graph so it can be driven and tested independently of the graph engine.
    val manifestVerifier = ManifestVerifier(
        manifestIo = manifestIo,
        reviewExecutor = reviewExecutor,
        usedAppNamesProvider = usedAppNamesProvider,
        maxRetries = maxRetries,
    )

    val onPhase = callbacks.onPhase
    val onPhaseOutput = callbacks.onPhaseOutput

    // Captured at subgraph entry ("assist" or "repair") and used at the exit
    // edge/node so observer.enterPhase and exitPhase always use the same name.
    var assistPhaseNameForEvents: String = "assist"

    // Captured at subgraph entry: whether the current assist/repair round is a
    // user follow-up (explanatory=true). The question escape hatch (answered) is
    // only honored for a user follow-up, never for the internal repair loop (which
    // legitimately re-fixes the same manifest across rounds and must not be
    // mistaken fora pure-answer).
    var assistIsUserFollowUp: Boolean = false

    // ── Request routing and context initialization ──
    val routeRequest by node<SessionRequest, SessionRequest>("routeRequest") { request ->
        when (request) {
            is SessionRequest.Generate -> {
                storage.set(retryCountKey, 0)
                request
            }
            is SessionRequest.Assist -> {
                storage.set(retryCountKey, 1)
                request.suggestedAppName?.let { storage.set(suggestedAppNameKey, it) }
                // Doubles as the reference base: an in-place update keeps that name on purpose.
                request.suggestedAppName?.let { storage.set(assistBaseNameKey, it) }
                storage.set(finalManifestKey, request.currentManifest)
                request
            }
        }
    }

    // ── Prefetch node (deterministic: pre-collect project data before LLM phases) ──
    val prefetch by node<SessionRequest, PrefetchResult>("prefetch") { request ->
        val gen = request as SessionRequest.Generate
        executePrefetch(gen.url, gitHubApi, archiveInspector)
    }

    // ── Prepare Assist context directly from request ──
    // User-initiated assist: the finalize call is asked to produce an explanation.
    val prepareAssistFromRequest by node<SessionRequest, AssistContext>("prepareAssistFromRequest") { request ->
        val ref = request as SessionRequest.Assist
        AssistContext(
            manifest = ref.currentManifest,
            feedback = ref.instruction,
            history = ref.history,
            evidence = ref.evidence,
            retryCount = 1,
            explanatory = true,
        )
    }

    // ── Research phase (LLM) ──
    val research by subgraphWithTask<PrefetchResult, ResearchOutput>(
        tools = researchTools,
        llmParams = llmParams,
    ) { prefetchResult ->
        onPhase?.invoke("research")
        observer.enterPhase("research")
        logger.info { PhaseProgressLogger.starting("research") }
        telemetry.enter("research")
        researchPromptBuilder?.invoke(prefetchResult) ?: prefetchResult.originalUrl
    }

    // ── Research completion logging ──
    val researchDone by node<ResearchOutput, ResearchOutput>("researchDone") { output ->
        val elapsed = telemetry.exit("research")
        observer.exitPhase("research", elapsed)
        logger.info { PhaseProgressLogger.completed("research", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        onPhaseOutput?.invoke("research", phaseJson.encodeToString(ResearchOutput.serializer(), output))
        output
    }

    // ── Finish when no Windows assets exist ──
    // The explanation of a noAssets run can never come from an LLM finalize call,
    // so the engine deterministically synthesizes it from the research output.
    val finishNoAssets by node<ResearchOutput, ManifestResult>("finishNoAssets") { research ->
        ManifestResult(
            manifest = "",
            suggestedAppName = research.suggested_app_name,
            noAssets = true,
            explanation = buildNoAssetsExplanation(research),
        )
    }

    // ── Analyze phase (LLM) ──
    val analyze by subgraphWithTask<ResearchOutput, AnalyzeOutput>(
        tools = analyzeTools,
        llmParams = llmParams,
    ) { input ->
        onPhase?.invoke("analyze")
        observer.enterPhase("analyze")
        logger.info { PhaseProgressLogger.starting("analyze") }
        telemetry.enter("analyze")
        storage.set(researchDataKey, input)
        input.suggested_app_name?.let { storage.set(suggestedAppNameKey, it) }
        analyzePromptBuilder?.invoke(input) ?: input.toString()
    }

    // ── Deterministic: assemble JSON skeleton from research + analyze data ──
    val prepareManifest by node<AnalyzeOutput, String>("prepareManifest") { analyzeOutput ->
        storage.set(analyzeDataKey, analyzeOutput)
        val skeleton = assembleManifestSkeleton(storage.get(researchDataKey)!!, analyzeOutput)
        finalizeManifestSkeleton(skeleton, analyzeOutput)
    }

    // ── Generate phase (LLM, no tools: review skeleton + finalize) ──
    // Uses the lenient finalize_task_result tool (KoogCompat.finishTool) instead
    // of Koog's default FinishTool<String>: when the model passes the manifest as
    // a JSON object instead of a string, Koog 1.0.0 fails with "Index -1 out of
    // bounds" (see KoogCompat.finishTool kdoc).
    // The subgraph output is a FinalizePayload: manifest plus an optional explanation.
    // Note: subgraphWithTask's Output type parameter is the finish tool's args type
    // (FinalizePayload), not the raw String the model passes in `result`.
    val generate by subgraphWithTask<String, FinalizePayload, FinalizePayload>(
        tools = emptyList<ToolBase<*, *>>(),
        finishTool = KoogCompat.finishTool,
        llmParams = llmParams,
    ) { skeleton ->
        onPhase?.invoke("generate")
        observer.enterPhase("generate")
        logger.info { PhaseProgressLogger.starting("generate") }
        telemetry.enter("generate")
        storage.set(retryCountKey, 0)
        generatePromptBuilder?.invoke(skeleton, storage.get(analyzeDataKey)!!, storage.get(researchDataKey)) ?: skeleton
    }

    // ── Assist subgraph (LLM, optional tools, lightweight fix loop) ──
    // Same lenient finish tool as generate; the tools advertise only the schema
    // and resolve by name against the agent-level registry at execution time.
    //
    // When assistImages is non-empty, we use a custom subgraph (not subgraphWithTask)
    // so the first LLM call can attach image parts to the user message. The attach
    // is gated by shouldAttach: only the user follow-up (explanatory=true) round
    // gets images; the internal repair loop stays text-only.
    val assist by subgraph<AssistContext, FinalizePayload>(
        name = "assist",
        toolSelectionStrategy = ai.koog.agents.core.agent.entity.ToolSelectionStrategy.Tools(
            assistTools.map { it.descriptor }
        ),
        llmModel = assistModelWithVision(agentModel, assistImages),
        llmParams = llmParams,
    ) {
        setupAssistSubgraphWithTask(
            finishTool = KoogCompat.finishTool,
            inputType = typeToken<AssistContext>(),
            outputTransformedType = typeToken<FinalizePayload>(),
            parallelTools = false,
            images = assistImages,
            shouldAttach = { it.explanatory },
            enableTextOnlyAnswer = true,
        ) { input ->
            assistPhaseNameForEvents = assistPhaseName(input)
            assistIsUserFollowUp = input.explanatory
            onPhase?.invoke(assistPhaseNameForEvents)
            observer.enterPhase(assistPhaseNameForEvents)
            logger.info { PhaseProgressLogger.starting(assistPhaseNameForEvents) }
            telemetry.enter(assistPhaseNameForEvents)
            val base = assistPromptBuilder?.invoke(input) ?: ""
            if (assistImages.isNotEmpty() && input.explanatory) {
                base + "\n\n[${assistImages.size} user image(s) attached to this message]"
            } else {
                base
            }
        }
    }

    // ── Normalize node (deterministic: post-process + normalize) ──
    val normalize by node<String, String>("normalize") { manifestJson ->
        logger.info { "[normalize] Running on manifest (${manifestJson.length} chars)" }
        val result = normalizeManifest(manifestJson)
        logger.info { "[normalize] Completed (${result.length} chars), modified=${result != manifestJson}" }
        result
    }

    // ── Verify node (deterministic: validate + review + app name conflict) ──
    // Executed once per pass: the initial check plus every assist/repair-loop round.
    // enterPhase/exitPhase must stay strictly paired per execution (try/finally);
    // the semantic review inside the verifier folds into this phase's statistics
    // (architecture proposal 4.6).
    val verify by node<String, AssistContext?>("verify") { manifest ->
        onPhase?.invoke("verify")
        observer.enterPhase("verify")
        logger.info { PhaseProgressLogger.starting("verify") }
        telemetry.enter("verify")
        // Authoritative hashes from the analyze/research outputs let the verifier
        // deterministically repair LLM hash transcription errors before the
        // structural validation runs (empty for assist sessions: no phase outputs).
        val context = ManifestVerifier.VerifyContext(
            storage = storage,
            retryCountKey = retryCountKey,
            suggestedAppNameKey = suggestedAppNameKey,
            appNameConflictKey = appNameConflictKey,
            assistBaseNameKey = assistBaseNameKey,
            expectedHashes = expectedHashesFromOutputs(storage.get(analyzeDataKey), storage.get(researchDataKey)),
            finalManifestSetter = { storage.set(finalManifestKey, it) },
        )
        try {
            manifestVerifier.verify(manifest, context).toAssistContext(manifest)
        } finally {
            val elapsed = telemetry.exit("verify")
            observer.exitPhase("verify", elapsed)
            logger.info { PhaseProgressLogger.completed("verify", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        }
    }

    // ── Build final result node (reads from storage, replaces edge transform that used captured vars) ──
    val buildFinalResult by node<AssistContext?, ManifestResult>("buildFinalResult") { _ ->
        val manifest = storage.get(finalManifestKey) ?: ""
        val conflict = storage.get(appNameConflictKey)
        ManifestResult(
            manifest = extractJsonBlock(manifest)?.let { formatManifestJson(it) } ?: manifest,
            suggestedAppName = storage.get(suggestedAppNameKey),
            explanation = storage.getCombinedExplanation(generateExplanationKey, explanationKey),
            issues = listOfNotNull(conflict),
        )
    }

    // ── Build exhausted result node ──
    val buildExhaustedResult by node<AssistContext?, ManifestResult>("buildExhaustedResult") { ctx ->
        val issueList = ctx?.let { splitFeedback(it.feedback) } ?: emptyList()
        val conflict = storage.get(appNameConflictKey)
        val allIssues = if (conflict != null && conflict !in issueList) issueList + conflict else issueList
        logger.warn { "[strategy] repair loop exhausted after $maxRetries attempts. Returning last manifest with ${allIssues.size} issue(s)." }
        val manifest = storage.get(finalManifestKey) ?: ""
        ManifestResult(
            manifest = extractJsonBlock(manifest)?.let { formatManifestJson(it) } ?: manifest,
            suggestedAppName = storage.get(suggestedAppNameKey),
            exhausted = true,
            issues = allIssues,
            explanation = storage.getCombinedExplanation(generateExplanationKey, explanationKey),
        )
    }

    // ── Question escape hatch (assist → checkAnswered → buildAnswerResult | normalize) ──
    // Only used on the user-assist / repair path. The generate path keeps its original
    // generate → normalize edge and never reaches checkAnswered (finalManifestKey is
    // only written on the verify pass for a generate session).
    val checkAnswered by node<FinalizePayload, CheckedFinalize>("checkAnswered") { payload ->
        val answeredManifest = payload.answered == true &&
            normalizeIfValid(payload.manifest) == normalizeIfValid(storage.get(finalManifestKey) ?: "") &&
            normalizeIfValid(payload.manifest).isNotEmpty()
        // A blank manifest with a non-blank explanation is the assist subgraph's
        // text-only escape: the model replied in plain text without calling the
        // finish tool, so there is nothing to normalize or verify.
        val textOnlyAnswer = assistIsUserFollowUp &&
            payload.manifest.isBlank() &&
            !payload.explanation.isNullOrBlank()
        val isPureAnswer = assistIsUserFollowUp && (answeredManifest || textOnlyAnswer)
        CheckedFinalize(payload = payload, isPureAnswer = isPureAnswer)
    }

    val buildAnswerResult by node<CheckedFinalize, ManifestResult>("buildAnswerResult") { checked ->
        val manifest = storage.get(finalManifestKey) ?: ""
        ManifestResult(
            manifest = extractJsonBlock(manifest)?.let { formatManifestJson(it) } ?: manifest,
            suggestedAppName = storage.get(suggestedAppNameKey),
            answered = true,
            explanation = checked.payload.explanation?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ── Edges ──
    edge(nodeStart forwardTo routeRequest)

    // Generate branch
    edge(routeRequest forwardTo prefetch onCondition { it is SessionRequest.Generate })
    edge(prefetch forwardTo research)

    // Assist branch (user follow-up)
    edge(routeRequest forwardTo prepareAssistFromRequest onCondition { it is SessionRequest.Assist })
    edge(prepareAssistFromRequest forwardTo assist)

    // Research → researchDone (completion logging)
    edge(research forwardTo researchDone)

    // Conditional: no Windows assets → early finish
    edge(researchDone forwardTo finishNoAssets onCondition { output ->
        output.status == ResearchStatus.NO_WINDOWS_ASSETS
    })

    // Conditional: has Windows assets → analyze
    edge(researchDone forwardTo analyze onCondition { output ->
        output.status == ResearchStatus.OK
    })

    edge(finishNoAssets forwardTo nodeFinish)

    // Main path: analyze → prepareManifest → generate → normalize
    edge(analyze forwardTo prepareManifest transformed { output ->
        val elapsed = telemetry.exit("analyze")
        observer.exitPhase("analyze", elapsed)
        logger.info { PhaseProgressLogger.completed("analyze", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        onPhaseOutput?.invoke("analyze", phaseJson.encodeToString(AnalyzeOutput.serializer(), output))
        output
    })
    edge(prepareManifest forwardTo generate)
    edge(generate forwardTo normalize transformed { payload ->
        val elapsed = telemetry.exit("generate")
        observer.exitPhase("generate", elapsed)
        logger.info { PhaseProgressLogger.completed("generate", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        // _meta.activities rides only the onPhaseOutput channel; normalize still
        // receives the clean manifest (payload.manifest) below.
        onPhaseOutput?.invoke("generate", withPhaseMeta(payload.manifest, payload.activities))
        // Only write non-blank explanations: internal repair rounds do not require
        // an explanation, and a null/blank overwrite would lose the explanation from
        // the round that first produced it (architecture proposal 4.3).
        usableExplanation(payload.explanation)?.let { explanation ->
            storage.set(explanationKey, explanation)
            storage.set(generateExplanationKey, explanation)
        }
        payload.manifest          // normalize still receives a String, signature unchanged
    })

    // Assist/repair path: assist → checkAnswered → normalize (or answer escape)
    // The assist subgraph also serves the internal repair loop (built via the
    // verify → assist edge below), so the phase name for events is captured at
    // entry in assistPhaseNameForEvents and reused here at exit.
    edge(assist forwardTo checkAnswered transformed { payload ->
        val elapsed = telemetry.exit(assistPhaseNameForEvents)
        observer.exitPhase(assistPhaseNameForEvents, elapsed)
        logger.info { PhaseProgressLogger.completed(assistPhaseNameForEvents, elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        // Same _meta-only channel rule as the generate edge.
        onPhaseOutput?.invoke(assistPhaseNameForEvents, withPhaseMeta(payload.manifest, payload.activities))
        // Same non-blank-only rule as the generate edge: a repair round without a
        // usable explanation must not erase the last valid one, while a user assist
        // with a new explanation overwrites it.
        keepExplanation(storage.get(explanationKey), usableExplanation(payload.explanation))?.let {
            storage.set(explanationKey, it)
        }
        payload
    })

    // Pure-answer escape: manifest unchanged → answer result (no normalize/verify)
    edge(checkAnswered forwardTo buildAnswerResult onCondition { it.isPureAnswer })
    edge(buildAnswerResult forwardTo nodeFinish)

    // Change request / repair: route into normalize
    edge(checkAnswered forwardTo normalize onCondition { !it.isPureAnswer } transformed { it.payload.manifest })

    // Unified normalize → verify Guard
    edge(normalize forwardTo verify)

    // Verify → pass → buildFinalResult → finish
    edge(verify forwardTo buildFinalResult onCondition { ctx ->
        ctx == null
    })
    edge(buildFinalResult forwardTo nodeFinish)

    // Verify → fail + retry ≤ max → repair loop (explanatory=false)
    edge(verify forwardTo assist onCondition { ctx ->
        ctx != null && ctx.retryCount <= maxRetries
    } transformed { ctx ->
        ctx!!
    })

    // Verify → fail + retry > max → buildExhaustedResult → finish
    edge(verify forwardTo buildExhaustedResult onCondition { ctx ->
        ctx != null && ctx.retryCount > maxRetries
    })
    edge(buildExhaustedResult forwardTo nodeFinish)
}

