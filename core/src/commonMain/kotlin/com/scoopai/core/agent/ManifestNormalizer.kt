package com.scoopai.core.agent

import com.scoopai.core.agent.nodes.enforceArchitectureWrap
import com.scoopai.core.agent.nodes.normalizeBin
import com.scoopai.core.agent.nodes.normalizeEnvSet
import com.scoopai.core.agent.nodes.normalizeHomepage
import com.scoopai.core.agent.nodes.removeInvalidTopLevelFields
import com.scoopai.core.agent.nodes.resolveDualBinConflicts
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * Applies deterministic normalization rules to a manifest after LLM generation:
 * normalizeEnvSet, enforceArchitectureWrap, normalizeHomepage,
 * removeInvalidTopLevelFields, normalizeBin, resolveDualBinConflicts.
 *
 * Owned outside the strategy graph so the graph only carries nodes and edges;
 * the normalize node is the single caller.
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
 * Normalizes a manifest JSON string for semantic comparison, returning an empty
 * string when the input is not a parseable JSON object. Used by the question
 * escape hatch to compare the finalize result against the input manifest: both
 * sides run through [normalizeManifest] so cosmetic differences (whitespace,
 * key order, benign normalizations) do not prevent a pure-answer from being
 * recognized, while a genuinely modified manifest fails the comparison.
 */
internal fun normalizeIfValid(manifestJson: String): String =
    if (isValidJson(manifestJson)) normalizeManifest(manifestJson) else ""
