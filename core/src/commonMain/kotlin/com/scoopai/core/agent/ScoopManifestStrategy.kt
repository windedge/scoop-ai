package com.scoopai.core.agent

import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.params.LLMParams
import com.scoopai.core.ManifestResult
import com.scoopai.core.RefineContext
import com.scoopai.core.agent.nodes.assembleManifestSkeleton
import com.scoopai.core.agent.nodes.autoFillBinFromShortcuts
import com.scoopai.core.agent.nodes.normalizeEnvSet
import com.scoopai.core.agent.nodes.enforceArchitectureWrap
import com.scoopai.core.agent.nodes.normalizeHomepage
import com.scoopai.core.agent.nodes.removeInvalidTopLevelFields
import com.scoopai.core.agent.nodes.normalizeBin
import com.scoopai.core.tools.api.ManifestIo
import ai.koog.agents.core.agent.entity.AIAgentStorageAPI
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.oshai.kotlinlogging.KotlinLogging

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
 * Flow (v3 — 3-phase + edge-based refine loop):
 * ```
 * nodeStart → research(LLM, research tools)
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
 * @param researchPromptBuilder Builds the research prompt from a URL string.
 * @param analyzePromptBuilder Builds the analyze prompt from [ResearchOutput].
 * @param generatePromptBuilder Builds the generate prompt from (skeleton, analyze, research).
 * @param refinePromptBuilder Builds the refine prompt from [RefineContext].
 * @param manifestIo Structural validate tool (used in verify node).
 * @param reviewExecutor Semantic review executor (independent LLM agent, used in verify node).
 */
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (String) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    generatePromptBuilder: (suspend (String, AnalyzeOutput, ResearchOutput?) -> String)? = null,
    refinePromptBuilder: (suspend (RefineContext) -> String)? = null,
    manifestIo: ManifestIo? = null,
    reviewExecutor: (suspend (String) -> String)? = null,
) = strategy<String, ManifestResult>("scoop_manifest_v3") {

    // ── Storage keys & captured state ─────────────────────────────
    val retryCountKey = createStorageKey<Int>("refine_retry_count")
    val maxRetries = 3
    var researchData: ResearchOutput? = null
    var analyzeData: AnalyzeOutput? = null
    var suggestedAppName: String? = null
    var finalManifest: String? = null

    // ── Research phase (LLM) ──
    val research by subgraphWithTask<String, ResearchOutput>(
        tools = researchTools,
        llmParams = llmParams,
    ) { input ->
        researchPromptBuilder?.invoke(input) ?: input
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
        researchData = input
        suggestedAppName = input.suggested_app_name
        analyzePromptBuilder?.invoke(input) ?: input.toString()
    }

    // ── Deterministic: assemble JSON skeleton from research + analyze data ──
    val prepareManifest by node<AnalyzeOutput, String>("prepareManifest") { analyzeOutput ->
        analyzeData = analyzeOutput
        assembleManifestSkeleton(researchData!!, analyzeOutput)
    }

    // ── Generate phase (LLM, no tools: review skeleton + finalize) ──
    val generate by subgraphWithTask<String, String>(
        tools = emptyList<ToolBase<*, *>>(),
        llmParams = llmParams,
    ) { skeleton ->
        storage.set(retryCountKey, 0)
        generatePromptBuilder?.invoke(skeleton, analyzeData!!, researchData) ?: skeleton
    }

    // ── Refine phase (LLM, no tools, lightweight fix loop) ──
    val refine by subgraphWithTask<RefineContext, String>(
        tools = emptyList<ToolBase<*, *>>(),
        llmParams = llmParams,
    ) { input ->
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
        validateManifest(manifest, manifestIo, reviewExecutor, retryCountKey, storage, maxRetries) { finalManifest = it }
    }

    // ── Edges ──
    edge(nodeStart forwardTo research)

    // Conditional: no Windows assets → early finish
    edge(research forwardTo finishNoAssets onCondition { output ->
        output.status == ResearchStatus.NO_WINDOWS_ASSETS
    })

    // Conditional: has Windows assets → analyze
    edge(research forwardTo analyze onCondition { output ->
        output.status == ResearchStatus.OK
    })

    edge(finishNoAssets forwardTo nodeFinish)

    // Main path: analyze → prepareManifest → generate → verify
    edge(analyze forwardTo prepareManifest)
    edge(prepareManifest forwardTo generate)
    edge(generate forwardTo normalize)
    edge(normalize forwardTo verify)

    // Verify → pass → finish
    edge(verify forwardTo nodeFinish onCondition { ctx ->
        ctx == null
    } transformed { _ ->
        ManifestResult(
            manifest = extractJsonBlock(finalManifest!!) ?: finalManifest!!,
            suggestedAppName = suggestedAppName,
        )
    })

    // Verify → fail + retry ≤ 3 → refine loop
    edge(verify forwardTo refine onCondition { ctx ->
        ctx != null && ctx.retryCount <= maxRetries
    } transformed { ctx ->
        ctx!!
    })

    // Refine → back to verify
    edge(refine forwardTo normalize)

    // Verify → fail + retry > 3 → exhausted finish
    edge(verify forwardTo nodeFinish onCondition { ctx ->
        ctx != null && ctx.retryCount > maxRetries
    } transformed {
        logger.warn { "[strategy] Refine exhausted after $maxRetries attempts. Returning last manifest with issues." }
        ManifestResult(
            manifest = extractJsonBlock(finalManifest!!) ?: finalManifest!!,
            suggestedAppName = suggestedAppName,
        )
    })
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
    finalManifestSetter: (String) -> Unit,
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
