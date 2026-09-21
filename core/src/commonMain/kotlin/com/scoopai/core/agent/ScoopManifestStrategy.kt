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
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.nodes.assembleManifestSkeleton
import com.scoopai.core.agent.nodes.finalizeManifestSkeleton
import com.scoopai.core.agent.nodes.autoFillBinFromShortcuts
import com.scoopai.core.agent.nodes.normalizeEnvSet
import com.scoopai.core.agent.nodes.enforceArchitectureWrap
import com.scoopai.core.agent.nodes.normalizeHomepage
import com.scoopai.core.agent.nodes.removeInvalidTopLevelFields
import com.scoopai.core.agent.nodes.normalizeBin
import com.scoopai.core.agent.nodes.resolveDualBinConflicts
import com.scoopai.core.agent.nodes.checkAutoupdateVersionPlaceholder
import com.scoopai.core.agent.nodes.validatePersistPaths
import com.scoopai.core.agent.nodes.checkAppNameConflict
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.agents.core.agent.entity.AIAgentStorageAPI
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * The strategy name used to register the Scoop manifest strategy graph with Koog.
 *
 * Single source of truth shared by [scoopManifestStrategy] (which registers the
 * graph under this name) and [com.scoopai.core.artifacts.ResumeData] (which builds
 * checkpoint node paths that must resolve against that same graph).
 */
public const val SCOOP_MANIFEST_STRATEGY_NAME: String = "unified_scoop_manifest"

/** Shared Json instance for phase output parsing (lenient). */
internal val phaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * The single source of truth for the phase name emitted for the assist/repair
 * subgraph: a user follow-up ([AssistContext.explanatory] = true) is "assist",
 * the internal repair loop ([AssistContext.explanatory] = false) is "repair".
 */
internal fun assistPhaseName(ctx: AssistContext): String = if (ctx.explanatory) "assist" else "repair"

/**
 * Carrier produced by the [ScoopManifestStrategy]'s checkAnswered node: the
 * finalize payload plus a flag telling whether the finalize was a pure-answer
 * escape hatch (the user message was a question, so the manifest is echoed back
 * unmodified).
 */
@kotlinx.serialization.Serializable
private data class CheckedFinalize(
    val payload: FinalizePayload,
    val isPureAnswer: Boolean,
)

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
 * Flow (v4 — prefetch + 3-phase + edge-based assist/repair loop):
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
 * @param manifestIo Structural validate tool (used in verify node).
 * @param reviewExecutor Semantic review executor (independent LLM agent, used in verify node).
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
    /** Callback invoked when a phase starts (name: "research", "analyze", "generate", "assist", "repair"). */
    onPhase: ((String) -> Unit)? = null,
    /** Observer for recording per-phase execution statistics. */
    observer: GenerationObserver = GenerationObserver(),
    /**
     * Callback invoked when a phase completes with its serialized output.
     * Parameters: (phaseName, serializedOutput).
     */
    onPhaseOutput: ((String, String) -> Unit)? = null,
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
    val finalManifestKey = createStorageKey<String>("final_manifest")
    val explanationKey = createStorageKey<String>("final_explanation")
    val generateExplanationKey = createStorageKey<String>("generate_explanation")

    // ── Phase timing (for progress logging) ────────────────────
    var researchStart: TimeMark? = null
    var analyzeStart: TimeMark? = null
    var generateStart: TimeMark? = null
    var assistStart: TimeMark? = null
    var verifyStart: TimeMark? = null

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
        researchStart = TimeSource.Monotonic.markNow()
        researchPromptBuilder?.invoke(prefetchResult) ?: prefetchResult.originalUrl
    }

    // ── Research completion logging ──
    val researchDone by node<ResearchOutput, ResearchOutput>("researchDone") { output ->
        val elapsed = researchStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
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
        analyzeStart = TimeSource.Monotonic.markNow()
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
    // Uses LenientStringFinishTool instead of Koog's default FinishTool<String>:
    // when the model passes the manifest as a JSON object instead of a string,
    // Koog 1.0.0 fails with "Index -1 out of bounds" (see LenientStringFinishTool kdoc).
    // The subgraph output is a FinalizePayload: manifest plus an optional explanation.
    // Note: subgraphWithTask's Output type parameter is the finish tool's args type
    // (FinalizePayload), not the raw String the model passes in `result`.
    val generate by subgraphWithTask<String, FinalizePayload, FinalizePayload>(
        tools = emptyList<ToolBase<*, *>>(),
        finishTool = LenientStringFinishTool(),
        llmParams = llmParams,
    ) { skeleton ->
        onPhase?.invoke("generate")
        observer.enterPhase("generate")
        logger.info { PhaseProgressLogger.starting("generate") }
        generateStart = TimeSource.Monotonic.markNow()
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
            finishTool = LenientStringFinishTool(),
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
            assistStart = TimeSource.Monotonic.markNow()
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
    // the semantic review inside validateManifest folds into this phase's
    // statistics (architecture proposal 4.6).
    val verify by node<String, AssistContext?>("verify") { manifest ->
        onPhase?.invoke("verify")
        observer.enterPhase("verify")
        logger.info { PhaseProgressLogger.starting("verify") }
        verifyStart = TimeSource.Monotonic.markNow()
        // Authoritative hashes from the analyze/research outputs let the verifier
        // deterministically repair LLM hash transcription errors before the
        // structural validation runs (empty for assist sessions: no phase outputs).
        val expectedHashes = expectedHashesFromOutputs(storage.get(analyzeDataKey), storage.get(researchDataKey))
        try {
            validateManifest(manifest, manifestIo, reviewExecutor, retryCountKey, storage, maxRetries, usedAppNamesProvider, suggestedAppNameKey, appNameConflictKey, expectedHashes) { storage.set(finalManifestKey, it) }
        } finally {
            val elapsed = verifyStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
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
        val elapsed = analyzeStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase("analyze", elapsed)
        logger.info { PhaseProgressLogger.completed("analyze", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        onPhaseOutput?.invoke("analyze", phaseJson.encodeToString(AnalyzeOutput.serializer(), output))
        output
    })
    edge(prepareManifest forwardTo generate)
    edge(generate forwardTo normalize transformed { payload ->
        val elapsed = generateStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase("generate", elapsed)
        logger.info { PhaseProgressLogger.completed("generate", elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        onPhaseOutput?.invoke("generate", payload.manifest)
        // Only write non-blank explanations: internal repair rounds do not require
        // an explanation, and a null/blank overwrite would lose the explanation from
        // the round that first produced it (architecture proposal 4.3).
        payload.explanation?.trim()?.takeIf { it.isNotEmpty() }?.let { explanation ->
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
        val elapsed = assistStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase(assistPhaseNameForEvents, elapsed)
        logger.info { PhaseProgressLogger.completed(assistPhaseNameForEvents, elapsed, llmCalls = observer.lastCompletedLlmCallCount()) }
        onPhaseOutput?.invoke(assistPhaseNameForEvents, payload.manifest)
        // Same non-blank-only rule as the generate edge: a repair round without a
        // usable explanation must not erase the last valid one, while a user assist
        // with a new explanation overwrites it.
        payload.explanation?.trim()?.takeIf { it.isNotEmpty() }?.let { explanation -> storage.set(explanationKey, explanation) }
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

    // Verify → fail + retry ≤ 3 → repair loop (explanatory=false)
    edge(verify forwardTo assist onCondition { ctx ->
        ctx != null && ctx.retryCount <= maxRetries
    } transformed { ctx ->
        ctx!!
    })

    // Verify → fail + retry > 3 → buildExhaustedResult → finish
    edge(verify forwardTo buildExhaustedResult onCondition { ctx ->
        ctx != null && ctx.retryCount > maxRetries
    })
    edge(buildExhaustedResult forwardTo nodeFinish)
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

/**
 * Applies deterministic normalization rules to a manifest after LLM generation:
 * normalizeEnvSet → enforceArchitectureWrap → normalizeHomepage →
 * removeInvalidTopLevelFields → normalizeBin → resolveDualBinConflicts.
 */
internal fun normalizeManifest(manifestJson: String): String {
    var result = manifestJson
    val afterEnv = normalizeEnvSet(result)
    if (afterEnv != result) {
        logger.info { "[normalize] Manifest modified by normalizeEnvSet" }
        result = afterEnv
    }
    val afterArch = enforceArchitectureWrap(result)
    if (afterArch != result) {
        logger.info { "[normalize] Manifest modified by enforceArchitectureWrap" }
        result = afterArch
    }
    val afterHomepage = normalizeHomepage(result)
    if (afterHomepage != result) {
        logger.info { "[normalize] Manifest modified by normalizeHomepage" }
        result = afterHomepage
    }
    val afterInvalid = removeInvalidTopLevelFields(result)
    if (afterInvalid != result) {
        logger.info { "[normalize] Manifest modified by removeInvalidTopLevelFields" }
        result = afterInvalid
    }
    val afterBinNorm = normalizeBin(result)
    if (afterBinNorm != result) {
        logger.info { "[normalize] Manifest modified by normalizeBin" }
        result = afterBinNorm
    }
    val afterBinConflicts = try {
        Json.parseToJsonElement(result) as? JsonObject
    } catch (_: Exception) {
        null
    }?.let { resolveDualBinConflicts(it) }
    if (afterBinConflicts != null && afterBinConflicts.toString() != result) {
        logger.info { "[normalize] Manifest modified by resolveDualBinConflicts" }
        result = afterBinConflicts.toString()
    }
    return result
}

/**
 * Validates a manifest JSON string and returns an [AssistContext] with errors if found,
 * or null if validation passes.
 */
internal suspend fun validateManifest(
    manifest: String,
    manifestIo: ManifestIo?,
    reviewExecutor: (suspend (String) -> String)?,
    retryCountKey: AIAgentStorageKey<Int>,
    storage: AIAgentStorageAPI,
    maxRetries: Int,
    usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    suggestedAppNameKey: AIAgentStorageKey<String>? = null,
    appNameConflictKey: AIAgentStorageKey<String>? = null,
    expectedHashes: Map<String, String> = emptyMap(),
    finalManifestSetter: suspend (String) -> Unit,
): AssistContext? {
    // Deterministic hash autofix: a mangled (e.g. character-dropped) hash cannot
    // be recovered by the model in the repair loop, so replace it with the hash
    // of the same asset from the analyze/research outputs and re-run validation
    // on the fixed manifest. The retry counter is only incremented by the pass
    // below, so this repair does not consume a repair round.
    if (expectedHashes.isNotEmpty()) {
        val fixed = autoFixInvalidHashes(manifest, expectedHashes)
        if (fixed != null) {
            logger.info { "[verify] Auto-fixed invalid hash(es) from phase output hashes" }
            return validateManifest(fixed, manifestIo, reviewExecutor, retryCountKey, storage, maxRetries, usedAppNamesProvider, suggestedAppNameKey, appNameConflictKey, expectedHashes, finalManifestSetter)
        }
    }
    finalManifestSetter(manifest)
    val errors = mutableListOf<String>()

    // Step 1: Structural validation
    if (manifestIo != null) {
        try {
            val validateJson = manifestIo.validate(manifest)
            val validationErrors = parseValidateErrors(validateJson)
            errors.addAll(validationErrors)
            if (validationErrors.isEmpty()) {
                logger.info { "[verify] Structural validation passed" }
            } else {
                logger.info { "[verify] Structural validation found ${validationErrors.size} errors" }
            }
        } catch (e: Exception) {
            errors.add("Validate error: ${e.message}")
            logger.warn { "[verify] Structural validation threw: ${e.message}" }
        }
    } else {
        logger.info { "[verify] No manifestIo, skipping structural validation" }
    }

    // Step 1.5: Deterministic autoupdate placeholder backstop
    // (runs even when structural validation is skipped — it only needs the manifest JSON)
    checkAutoupdateVersionPlaceholder(manifest)?.let { msg ->
        errors.add(msg)
        logger.info { "[verify] Autoupdate placeholder check: hard-coded autoupdate.url found" }
    }

    // Step 1.6: Deterministic persist path validation
    // (runs even when structural validation is skipped — it only needs the manifest JSON)
    validatePersistPaths(manifest)?.let { msg ->
        errors.add(msg)
        logger.info { "[verify] Persist path check: invalid persist entry found" }
    }

    // Step 1.7: App name conflict check
    // (reads suggestedAppName from storage, checks against local bucket names)
    // NOTE: Name conflict is a non-blocking Notice; it must NOT trigger an assist/repair
    // loop because the LLM cannot change local bucket contents. It is recorded to be surfaced in ManifestResult.issues.
    if (usedAppNamesProvider != null && suggestedAppNameKey != null) {
        try {
            val suggestedName = storage.get(suggestedAppNameKey)
            val usedNames = usedAppNamesProvider()
            if (usedNames.isNotEmpty()) {
                val conflictMsg = checkAppNameConflict(suggestedName, usedNames)
                if (conflictMsg != null) {
                    if (appNameConflictKey != null) {
                        storage.set(appNameConflictKey, conflictMsg)
                    }
                    logger.info { "[verify] App name conflict detected (non-blocking notice): $suggestedName" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "[verify] App name conflict check error: ${e.message}" }
        }
    }

    // Step 2: Semantic review (only when validate passes)
    if (errors.isEmpty() && reviewExecutor != null) {
        logger.info { "[verify] Starting semantic review" }
        try {
            val reviewResult = reviewExecutor(manifest)
            val reviewErrors = parseReviewErrors(reviewResult)
            errors.addAll(reviewErrors)
            logger.info { "[verify] Semantic review found ${reviewErrors.size} issues" }
        } catch (e: Exception) {
            val message = "Review agent error: ${e.message}"
            errors.add(message)
            logger.warn { "[verify] $message" }
        }
    } else if (errors.isNotEmpty()) {
        logger.info { "[verify] Skipping semantic review due to ${errors.size} validation errors" }
    } else {
        logger.info { "[verify] No reviewExecutor, skipping semantic review" }
    }

    logger.info { "[verify] Total errors: ${errors.size}" }

    if (errors.isNotEmpty()) {
        val currentRetry = (storage.get(retryCountKey) ?: 0) + 1
        storage.set(retryCountKey, currentRetry)
        logger.info { "[verify] Errors found, retry #$currentRetry (max $maxRetries)" }
        return AssistContext(manifest = manifest, feedback = errors.joinToString("\n- ", "- "), retryCount = currentRetry)
    } else {
        logger.info { "[verify] Validation passed, proceeding to finish" }
        return null
    }
}

private val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

internal fun extractJsonBlock(text: String): String? {
    codeBlockRegex.find(text)?.let { match ->
        val candidate = match.groupValues[1].trim()
        if (isValidJson(candidate)) return candidate
    }
    val trimmed = text.trim()
    if (isValidJson(trimmed)) return trimmed
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) {
        val sub = trimmed.substring(start, end + 1)
        if (isValidJson(sub)) return sub
    }
    return null
}

internal fun isValidJson(text: String): Boolean = try {
    Json.parseToJsonElement(text) is JsonObject
} catch (_: Exception) {
    false
}

/**
 * Normalizes a manifest JSON string for semantic comparison, returning an empty
 * string when the input is not a parseable JSON object. Used by the question
 * escape hatch to compare the finalize result against the input manifest: both
 * sides run through [normalizeManifest] so cosmetic differences (whitespace,
 * key order, benign normalizations) do not prevent a pure-answer from being
 * recognized, while a genuinely modified manifest fails the comparison.
 */
internal fun normalizeIfValid(manifestJson: String): String =
    if (isValidJson(manifestJson)) normalizeManifest(manifestJson) else ""

/**
 * Splits an [AssistContext.feedback] string back into the individual issue messages.
 *
 * `feedback` is produced by `errors.joinToString("\n- ", "- ")`, so each issue line
 * is prefixed with "- ". This reverses that join so the issues can be surfaced as a
 * structured list on [ManifestResult.issues].
 */
internal fun splitFeedback(feedback: String): List<String> {
    if (feedback.isBlank()) return emptyList()
    return feedback.lineSequence()
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
        .toList()
}

/** Soft cap for the detail sentence taken from alternatives/notes. */
private const val NO_ASSETS_DETAIL_LIMIT = 140

/** Hard cap for the whole synthesized explanation, ellipsis included. */
private const val NO_ASSETS_EXPLANATION_LIMIT = 220

private const val NO_ASSETS_ELLIPSIS = "..."

/**
 * Extracts the first sentence of [text], truncated to [limit] characters.
 *
 * The excerpt ends at the first sentence boundary (a "." or full-width period
 * followed by whitespace, or the end of the text) found within the first [limit]
 * characters. Abbreviation periods such as the one in "e.g." (a single letter
 * between two periods) do not count as a boundary. When no boundary exists
 * within [limit], the excerpt is backed off to the last space so words are not
 * cut in half.
 */
internal fun firstSentence(text: String, limit: Int = NO_ASSETS_DETAIL_LIMIT): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val window = trimmed.take(limit)
    window.forEachIndexed { index, c ->
        if (c != '.' && c != '\u3002') return@forEachIndexed
        val next = window.getOrNull(index + 1)
        if (next != null && !next.isWhitespace()) return@forEachIndexed
        // Skip abbreviation periods such as "e.g." / "i.e." (letter between two periods).
        if (c == '.' && index >= 2 && window[index - 2] == '.' && window[index - 1].isLetter()) return@forEachIndexed
        return window.take(index + 1)
    }
    // No sentence boundary within the limit: keep short text whole, otherwise
    // back off to the last space to avoid splitting a word.
    if (trimmed.length <= limit) return trimmed
    val lastSpace = window.lastIndexOf(' ')
    return if (lastSpace > 0) window.take(lastSpace) else window
}

/**
 * Deterministically synthesizes the noAssets explanation from the research output
 * (English fallback text, architecture proposal 4.7).
 *
 * The noAssets early-finish branch never reaches the generate/assist/repair subgraphs, so
 * the explanation of such a run can never come from a finalize_task_result call;
 * the engine produces it from the fields the research phase already gathered
 * ([ResearchOutput.alternatives], [ResearchOutput.notes], [ResearchOutput.project]).
 *
 * Output style is one or two short user-facing sentences: a fixed lead-in with the
 * project name, plus at most the first sentence of alternatives (preferred) or notes
 * (fallback). The remaining field content is not listed, and the total length is
 * capped at [NO_ASSETS_EXPLANATION_LIMIT] characters with an ellipsis on overflow.
 */
internal fun buildNoAssetsExplanation(research: ResearchOutput): String? {
    val builder = StringBuilder("No Windows assets found for this project")
    if (research.project.isNotBlank()) {
        builder.append(" (").append(research.project.trim()).append(")")
    }
    builder.append(".")
    // One short detail sentence at most: prefer alternatives, fall back to notes.
    val detailSource = when {
        research.alternatives.isNotBlank() -> research.alternatives
        research.notes.isNotBlank() -> research.notes
        else -> null
    }
    if (detailSource != null) {
        val detail = firstSentence(detailSource)
        if (detail.isNotBlank()) {
            builder.append(" ").append(detail)
        }
    }
    var explanation = builder.toString()
    if (explanation.length > NO_ASSETS_EXPLANATION_LIMIT) {
        explanation = explanation.take(NO_ASSETS_EXPLANATION_LIMIT - NO_ASSETS_ELLIPSIS.length).trimEnd() + NO_ASSETS_ELLIPSIS
    }
    return explanation
}

@kotlinx.serialization.Serializable
private data class StatusWrapper(val status: ResearchStatus = ResearchStatus.OK)

/**
 * Extracts the `status` field from a JSON string.
 * Returns [ResearchStatus.OK] if the JSON cannot be parsed or the field is absent.
 */
internal fun parseResearchStatus(output: String): ResearchStatus {
    return try {
        phaseJson.decodeFromString(StatusWrapper.serializer(), output).status
    } catch (_: Exception) {
        ResearchStatus.OK
    }
}

/**
 * Extracts error messages from a validate JSON result.
 * Returns empty list when valid=true.
 */
internal fun parseValidateErrors(validateJson: String): List<String> {
    return try {
        val obj = Json.parseToJsonElement(validateJson).jsonObject
        if (obj["valid"]?.jsonPrimitive?.content == "true") return emptyList()
        obj["errors"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf { c -> c.isNotBlank() } }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Extracts error-severity issue messages from a review JSON result.
 *
 * Fail-closed semantics: `approved == "true"` short-circuits to an empty list
 * (the manifest passes), but anything that cannot be positively interpreted as
 * a rejected-but-actionable review returns [REVIEW_UNPARSEABLE] so an
 * unexpected review output cannot silently pass the manifest. In particular,
 * parse failures, pure narrative text, `approved: false` with no error-severity
 * issue, or a JSON object that declares nothing interpretable all fail closed.
 * A markdown fence around the JSON is tolerated via [extractJsonBlock].
 */
internal fun parseReviewErrors(reviewJson: String): List<String> {
    val jsonText = extractJsonBlock(reviewJson) ?: return listOf(REVIEW_UNPARSEABLE)
    try {
        val obj = Json.parseToJsonElement(jsonText).jsonObject
        if (obj["approved"]?.jsonPrimitive?.content == "true") return emptyList()
        val errorIssues = obj["issues"]?.jsonArray
            ?.filter { it.jsonObject["severity"]?.jsonPrimitive?.content == "error" }
            ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.content }
            .orEmpty()
        return if (errorIssues.isEmpty()) listOf(REVIEW_UNPARSEABLE) else errorIssues
    } catch (_: Exception) {
        return listOf(REVIEW_UNPARSEABLE)
    }
}

/**
 * Message returned by [parseReviewErrors] when the review output cannot be
 * interpreted (unparseable JSON, or not approved and no error-severity issue
 * could be extracted). Keeps semantic review fail-closed: an unexpected review
 * result must not silently pass the manifest.
 */
internal const val REVIEW_UNPARSEABLE =
    "Semantic review returned an unparseable or unexpected result"
