package com.scoopai.core.agent

import ai.koog.agents.core.agent.entity.AIAgentStorageAPI
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import com.scoopai.core.AssistContext
import com.scoopai.core.agent.nodes.checkAutoupdateVersionPlaceholder
import com.scoopai.core.agent.nodes.checkAppNameConflict
import com.scoopai.core.agent.nodes.validatePersistPaths
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Deterministic verify pipeline extracted from the strategy graph: structural
 * validation, deterministic backstops (autoupdate placeholder, persist paths),
 * app name conflict notice, semantic review, and the hash autofix pre-pass.
 *
 * The strategy graph owns when verify runs and what the outcome drives (repair
 * loop or finish); this class owns how a single pass produces that outcome.
 * Feedback wording, ordering, and retry-counter semantics are unchanged from
 * the former `validateManifest` function.
 */
internal class ManifestVerifier(
    /** Structural validation backend; null skips structural validation. */
    private val manifestIo: ManifestIo?,
    /** Independent semantic review agent; null skips semantic review. */
    private val reviewExecutor: (suspend (String) -> String)?,
    /** Existing manifest names in local Scoop buckets for the conflict notice. */
    private val usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    /** Repair loop bound; also reported in the retry log line. */
    private val maxRetries: Int = 5,
) {
    /**
     * Per-pass context: storage access, the storage keys verify reads and
     * writes, authoritative hashes for the autofix pre-pass, and the sink the
     * examined manifest is written to before validation.
     */
    internal data class VerifyContext(
        val storage: AIAgentStorageAPI,
        val retryCountKey: AIAgentStorageKey<Int>,
        val suggestedAppNameKey: AIAgentStorageKey<String>? = null,
        val appNameConflictKey: AIAgentStorageKey<String>? = null,
        val assistBaseNameKey: AIAgentStorageKey<String>? = null,
        val expectedHashes: Map<String, String> = emptyMap(),
        val finalManifestSetter: suspend (String) -> Unit,
    )

    /**
     * Result of one verify pass: the collected issues (empty when the manifest
     * passed) and the repair counter after the pass.
     */
    internal data class VerifyOutcome(
        val issues: List<String>,
        val retryCount: Int,
    ) {
        val passed: Boolean get() = issues.isEmpty()
    }

    /**
     * Runs one verify pass over [manifest]. The retry counter is incremented
     * only when issues were found; the hash autofix pre-pass repairs the
     * manifest and re-runs verification without consuming a repair round.
     */
    suspend fun verify(manifest: String, context: VerifyContext): VerifyOutcome {
        // Deterministic hash autofix: a mangled (e.g. character-dropped) hash cannot
        // be recovered by the model in the repair loop, so replace it with the hash
        // of the same asset from the analyze/research outputs and re-run validation
        // on the fixed manifest. The retry counter is only incremented by the pass
        // below, so this repair does not consume a repair round.
        if (context.expectedHashes.isNotEmpty()) {
            val fixed = autoFixInvalidHashes(manifest, context.expectedHashes)
            if (fixed != null) {
                logger.info { "[verify] Auto-fixed invalid hash(es) from phase output hashes" }
                return verify(fixed, context)
            }
        }
        context.finalManifestSetter(manifest)
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
        // (runs even when structural validation is skipped: it only needs the manifest JSON)
        checkAutoupdateVersionPlaceholder(manifest)?.let { msg ->
            errors.add(msg)
            logger.info { "[verify] Autoupdate placeholder check: hard-coded autoupdate.url found" }
        }

        // Step 1.6: Deterministic persist path validation
        // (runs even when structural validation is skipped: it only needs the manifest JSON)
        validatePersistPaths(manifest)?.let { msg ->
            errors.add(msg)
            logger.info { "[verify] Persist path check: invalid persist entry found" }
        }

        // Step 1.7: App name conflict check
        // (reads suggestedAppName from storage, checks against local bucket names)
        // NOTE: Name conflict is a non-blocking Notice; it must NOT trigger an assist/repair
        // loop because the LLM cannot change local bucket contents. It is recorded to be surfaced in ManifestResult.issues.
        if (usedAppNamesProvider != null && context.suggestedAppNameKey != null) {
            try {
                val suggestedName = context.storage.get(context.suggestedAppNameKey)
                val usedNames = usedAppNamesProvider()
                if (usedNames.isNotEmpty()) {
                    val conflictMsg = checkAppNameConflict(suggestedName, usedNames)
                    // An assist session keeps the referenced name on purpose (in-place update):
                    // a conflict against that base is expected, a renamed app still conflicts.
                    val exempt = context.assistBaseNameKey != null &&
                        suggestedName.equals(context.storage.get(context.assistBaseNameKey), ignoreCase = true)
                    if (conflictMsg != null && !exempt) {
                        if (context.appNameConflictKey != null) {
                            context.storage.set(context.appNameConflictKey, conflictMsg)
                        }
                        logger.info { "[verify] App name conflict detected (non-blocking notice): $suggestedName" }
                    } else if (conflictMsg != null) {
                        logger.info { "[verify] App name equals the assist reference base; conflict exempted: $suggestedName" }
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
            val currentRetry = (context.storage.get(context.retryCountKey) ?: 0) + 1
            context.storage.set(context.retryCountKey, currentRetry)
            logger.info { "[verify] Errors found, retry #$currentRetry (max $maxRetries)" }
            return VerifyOutcome(issues = errors, retryCount = currentRetry)
        } else {
            logger.info { "[verify] Validation passed, proceeding to finish" }
            return VerifyOutcome(issues = emptyList(), retryCount = context.storage.get(context.retryCountKey) ?: 0)
        }
    }
}

/**
 * Maps a verify outcome onto the strategy-graph contract: null when the
 * manifest passed (finish edge), otherwise an [AssistContext] whose feedback is
 * the issues joined as "- a\n- b" and whose retryCount drives the repair-loop
 * bound check.
 */
internal fun ManifestVerifier.VerifyOutcome.toAssistContext(manifest: String): AssistContext? =
    if (passed) {
        null
    } else {
        AssistContext(manifest = manifest, feedback = issues.joinToString("\n- ", "- "), retryCount = retryCount)
    }

/**
 * Splits an [AssistContext.feedback] string back into the individual issue messages.
 *
 * `feedback` is produced by `issues.joinToString("\n- ", "- ")`, so each issue line
 * is prefixed with "- ". This reverses that join so the issues can be surfaced as a
 * structured list on [com.scoopai.core.ManifestResult.issues].
 */
internal fun splitFeedback(feedback: String): List<String> {
    if (feedback.isBlank()) return emptyList()
    return feedback.lineSequence()
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
        .toList()
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
