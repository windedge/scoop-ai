package com.scoopai.core.agent

import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.params.LLMParams
import com.scoopai.core.ManifestResult
import com.scoopai.core.RefineContext
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.nodes.assembleManifestSkeleton
import com.scoopai.core.agent.nodes.autoFillBinFromShortcuts
import com.scoopai.core.agent.nodes.normalizeEnvSet
import com.scoopai.core.agent.nodes.enforceArchitectureWrap
import com.scoopai.core.agent.nodes.normalizeHomepage
import com.scoopai.core.agent.nodes.removeInvalidTopLevelFields
import com.scoopai.core.agent.nodes.normalizeBin
import com.scoopai.core.agent.nodes.checkAutoupdateVersionPlaceholder
import com.scoopai.core.agent.nodes.validatePersistPaths
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

/** Shared Json instance for phase output parsing (lenient). */
internal val phaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Creates a graph strategy for generating Scoop manifests.
 *
 * Flow (v4 — prefetch + 3-phase + edge-based refine loop):
 * ```
 * nodeStart → prefetch(deterministic: pre-collect data)
 *   → research(LLM, research tools)
 *   [no_windows] → finishNoAssets → nodeFinish
 *   [ok] → analyze(LLM, analysis tools)
 *        → prepareManifest(deterministic: skeleton assembly)
 *        → generate(LLM, no tools: skeleton review + finalize)
 *        → verify(validate + review)
 *          [pass] → nodeFinish
 *          [fail + retry ≤ 3] → refine(LLM, no tools) → verify (loop)
 *          [fail + retry > 3] → nodeFinish (exhausted)
 * ```
 *
 * @param researchTools Tools available in the research phase.
 * @param analyzeTools Tools available in the analyze phase (hash/compute, archive listing, etc.).
 * @param llmParams LLM parameters (thinking mode, etc.).
 * @param researchPromptBuilder Builds the research prompt from [PrefetchResult].
 * @param analyzePromptBuilder Builds the analyze prompt from [ResearchOutput].
 * @param generatePromptBuilder Builds the generate prompt from (skeleton, analyze, research).
 * @param refinePromptBuilder Builds the refine prompt from [RefineContext].
 * @param manifestIo Structural validate tool (used in verify node).
 * @param reviewExecutor Semantic review executor (independent LLM agent, used in verify node).
 * @param gitHubApi GitHub API for prefetching project data before LLM phases.
 * @param archiveInspector Archive inspector for prefetching persist/search data before LLM phases.
 */
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (PrefetchResult) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    generatePromptBuilder: (suspend (String, AnalyzeOutput, ResearchOutput?) -> String)? = null,
    refinePromptBuilder: (suspend (RefineContext) -> String)? = null,
    manifestIo: ManifestIo? = null,
    reviewExecutor: (suspend (String) -> String)? = null,
    gitHubApi: GitHubApi? = null,
    archiveInspector: ArchiveInspector? = null,
    /** Callback invoked when a phase starts (name: "research", "analyze", "generate", "refine"). */
    onPhase: ((String) -> Unit)? = null,
    /** Observer for recording per-phase execution statistics. */
    observer: GenerationObserver = GenerationObserver(),
    /**
     * Callback invoked when a phase completes with its serialized output.
     * Parameters: (phaseName, serializedOutput).
     */
    onPhaseOutput: ((String, String) -> Unit)? = null,
) = strategy<String, ManifestResult>("scoop_manifest_v3") {

    // ── Storage keys ───────────────────────────────────────────
    val retryCountKey = createStorageKey<Int>("refine_retry_count")
    val maxRetries = 3
    val researchDataKey = createStorageKey<ResearchOutput>("research_data")
    val analyzeDataKey = createStorageKey<AnalyzeOutput>("analyze_data")
    val suggestedAppNameKey = createStorageKey<String>("suggested_app_name")
    val finalManifestKey = createStorageKey<String>("final_manifest")

    // ── Phase timing (for progress logging) ────────────────────
    var researchStart: TimeMark? = null
    var analyzeStart: TimeMark? = null
    var generateStart: TimeMark? = null
    var refineStart: TimeMark? = null

    // ── Prefetch node (deterministic: pre-collect project data before LLM phases) ──
    val prefetch by node<String, PrefetchResult>("prefetch") { url ->
        executePrefetch(url, gitHubApi, archiveInspector)
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
        logger.info { PhaseProgressLogger.completed("research", elapsed) }
        onPhaseOutput?.invoke("research", phaseJson.encodeToString(ResearchOutput.serializer(), output))
        output
    }

    // ── Finish when no Windows assets exist ──
    val finishNoAssets by node<ResearchOutput, ManifestResult>("finishNoAssets") { research ->
        ManifestResult(manifest = "", suggestedAppName = research.suggested_app_name, noAssets = true)
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
        assembleManifestSkeleton(storage.get(researchDataKey)!!, analyzeOutput)
    }

    // ── Generate phase (LLM, no tools: review skeleton + finalize) ──
    val generate by subgraphWithTask<String, String>(
        tools = emptyList<ToolBase<*, *>>(),
        llmParams = llmParams,
    ) { skeleton ->
        onPhase?.invoke("generate")
        observer.enterPhase("generate")
        logger.info { PhaseProgressLogger.starting("generate") }
        generateStart = TimeSource.Monotonic.markNow()
        storage.set(retryCountKey, 0)
        generatePromptBuilder?.invoke(skeleton, storage.get(analyzeDataKey)!!, storage.get(researchDataKey)) ?: skeleton
    }

    // ── Refine phase (LLM, no tools, lightweight fix loop) ──
    val refine by subgraphWithTask<RefineContext, String>(
        tools = emptyList<ToolBase<*, *>>(),
        llmParams = llmParams,
    ) { input ->
        onPhase?.invoke("refine")
        observer.enterPhase("refine")
        logger.info { PhaseProgressLogger.starting("refine") }
        refineStart = TimeSource.Monotonic.markNow()
        refinePromptBuilder?.invoke(input) ?: ""

    }

    // ── Normalize node (deterministic: post-process + normalize) ──
    val normalize by node<String, String>("normalize") { manifestJson ->
        logger.info { "[normalize] Running on manifest (${manifestJson.length} chars)" }
        val result = normalizeManifest(manifestJson)
        logger.info { "[normalize] Completed (${result.length} chars), modified=${result != manifestJson}" }
        result
    }

    // ── Verify node (deterministic: validate + review) ──
    val verify by node<String, RefineContext?>("verify") { manifest ->
        validateManifest(manifest, manifestIo, reviewExecutor, retryCountKey, storage, maxRetries) { storage.set(finalManifestKey, it) }
    }

    // ── Build final result node (reads from storage, replaces edge transform that used captured vars) ──
    val buildFinalResult by node<RefineContext?, ManifestResult>("buildFinalResult") { _ ->
        val manifest = storage.get(finalManifestKey) ?: ""
        ManifestResult(
            manifest = extractJsonBlock(manifest) ?: manifest,
            suggestedAppName = storage.get(suggestedAppNameKey),
        )
    }

    // ── Build exhausted result node ──
    val buildExhaustedResult by node<RefineContext?, ManifestResult>("buildExhaustedResult") {
        logger.warn { "[strategy] Refine exhausted after $maxRetries attempts. Returning last manifest with issues." }
        val manifest = storage.get(finalManifestKey) ?: ""
        ManifestResult(
            manifest = extractJsonBlock(manifest) ?: manifest,
            suggestedAppName = storage.get(suggestedAppNameKey),
        )
    }

    // ── Edges ──
    // Prefetch first, then research
    edge(nodeStart forwardTo prefetch)
    edge(prefetch forwardTo research)

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

    // Main path: analyze → prepareManifest → generate → verify
    edge(analyze forwardTo prepareManifest transformed { output ->
        val elapsed = analyzeStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase("analyze", elapsed)
        logger.info { PhaseProgressLogger.completed("analyze", elapsed) }
        onPhaseOutput?.invoke("analyze", phaseJson.encodeToString(AnalyzeOutput.serializer(), output))
        output
    })
    edge(prepareManifest forwardTo generate)
    edge(generate forwardTo normalize transformed { output ->
        val elapsed = generateStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase("generate", elapsed)
        logger.info { PhaseProgressLogger.completed("generate", elapsed) }
        onPhaseOutput?.invoke("generate", output)
        output
    })
    edge(normalize forwardTo verify)

    // Verify → pass → buildFinalResult → finish
    edge(verify forwardTo buildFinalResult onCondition { ctx ->
        ctx == null
    })
    edge(buildFinalResult forwardTo nodeFinish)

    // Verify → fail + retry ≤ 3 → refine loop
    edge(verify forwardTo refine onCondition { ctx ->
        ctx != null && ctx.retryCount <= maxRetries
    } transformed { ctx ->
        ctx!!
    })

    // Refine → back to verify (via normalize)
    edge(refine forwardTo normalize transformed { output ->
        val elapsed = refineStart?.elapsedNow()?.inWholeMilliseconds ?: 0L
        observer.exitPhase("refine", elapsed)
        logger.info { PhaseProgressLogger.completed("refine", elapsed) }
        onPhaseOutput?.invoke("refine", output)
        output
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
 * Applies deterministic normalization rules to a manifest after LLM generation.
 *
 * Currently: autoFillBinFromShortcuts → normalizeEnvSet → enforceArchitectureWrap
 * → normalizeHomepage → removeInvalidTopLevelFields → normalizeBin.
 * Order matters: autoFillBinFromShortcuts reads `url` and `architecture` from the
 * manifest, so it must run before enforceArchitectureWrap. See each function for
 * details on individual rules.
 */
internal fun normalizeManifest(manifestJson: String): String {
    var result = manifestJson
    val afterBin = autoFillBinFromShortcuts(result)
    if (afterBin != result) {
        logger.info { "[normalize] Manifest modified by autoFillBinFromShortcuts" }
        result = afterBin
    }
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
    return result
}

/**
 * Validates a manifest JSON string and returns a [RefineContext] with errors if found,
 * or null if validation passes.
 */
internal suspend fun validateManifest(
    manifest: String,
    manifestIo: ManifestIo?,
    reviewExecutor: (suspend (String) -> String)?,
    retryCountKey: AIAgentStorageKey<Int>,
    storage: AIAgentStorageAPI,
    maxRetries: Int,
    finalManifestSetter: suspend (String) -> Unit,
): RefineContext? {
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

    // Step 2: Semantic review (only when validate passes)
    if (errors.isEmpty() && reviewExecutor != null) {
        logger.info { "[verify] Starting semantic review" }
        try {
            val reviewResult = reviewExecutor(manifest)
            val reviewErrors = parseReviewErrors(reviewResult)
            errors.addAll(reviewErrors)
            logger.info { "[verify] Semantic review found ${reviewErrors.size} issues" }
        } catch (e: Exception) {
            logger.warn { "[verify] Review agent error: ${e.message}" }
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
        return RefineContext(manifest = manifest, feedback = errors.joinToString("\n- ", "- "), retryCount = currentRetry)
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
 * Returns empty list when approved=true.
 */

/**
 * Checks whether a manifest represents a portable archive (ZIP, tar.gz, 7z) vs an installer.
 *
 * Portable archives can have their exe files added to `bin` for CLI access.
 * Installers (innosetup/nsis/msi/msix) should keep their `shortcuts`-only judgment from the LLM.
 */


internal fun parseReviewErrors(reviewJson: String): List<String> {
    return try {
        val obj = Json.parseToJsonElement(reviewJson).jsonObject
        if (obj["approved"]?.jsonPrimitive?.content == "true") return emptyList()
        obj["issues"]?.jsonArray
            ?.filter { it.jsonObject["severity"]?.jsonPrimitive?.content == "error" }
            ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.content }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
