package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// Hash autofix: deterministic repair of LLM-transcription hash errors
// ---------------------------------------------------------------------------

/** A valid SHA-256 hash: exactly 64 hex characters. */
internal val SHA256_HASH_REGEX: Regex = Regex("^[a-fA-F0-9]{64}$")

/**
 * Builds the expected-hash lookup used by [autoFixInvalidHashes] from the phase
 * outputs that carry authoritative hashes.
 *
 * Analyze entries win over research assets: the analyze agent may have
 * recomputed the hash from the downloaded file, which is more authoritative
 * than the GitHub API digest the research phase recorded. Hashes that do not
 * look like SHA-256 (empty, placeholder text) are skipped.
 */
internal fun expectedHashesFromOutputs(analyze: AnalyzeOutput?, research: ResearchOutput?): Map<String, String> {
    val expected = LinkedHashMap<String, String>()
    if (research != null) {
        research.windows_assets.forEach { asset ->
            val hash = asset.hash.trim()
            if (hash.matches(SHA256_HASH_REGEX)) {
                expected[asset.url] = hash
                if (asset.filename.isNotBlank()) expected[asset.filename] = hash
            }
        }
    }
    if (analyze != null) {
        analyze.entries.forEach { entry ->
            val hash = entry.hash.trim()
            if (hash.matches(SHA256_HASH_REGEX)) {
                expected[entry.url] = hash
                if (entry.filename.isNotBlank()) expected[entry.filename] = hash
            }
        }
    }
    return expected
}

/**
 * Deterministically fixes hash transcription errors in a manifest JSON.
 *
 * LLMs occasionally drop characters when copying 64-character hex hashes from
 * tool results into the manifest; the resulting value fails structural
 * validation ("is not a valid SHA-256 hash") and the model cannot recover in
 * the repair loop. This function replaces every invalid hash value with the
 * authoritative hash of the asset that shares its URL (or archive filename).
 *
 * A hash position (flat, architecture, or hash-array position i) is repaired
 * only when a source hash can be resolved for the URL in the same position.
 * An unmatched URL never blocks fixing the other architecture blocks.
 * Resolution order: exact URL, URL without fragment (Scoop download URLs
 * carry a #/dl.7z fragment the source assets do not), then unique archive
 * filename. The source hash is preserved verbatim (including its case).
 * When any position had an invalid hash but no source could be resolved,
 * null is returned: an incomplete fix could pass validation with a wrong
 * digest, which is worse than surfacing the error to the repair loop.
 *
 * Returns the fixed manifest JSON, or null when there is nothing to fix
 * (all hashes valid, or unparseable manifest).
 */
internal fun autoFixInvalidHashes(manifestJson: String, expectedHashes: Map<String, String>): String? {
    val root = try {
        Json.parseToJsonElement(manifestJson).jsonObject
    } catch (_: Exception) {
        return null
    }
    if (expectedHashes.isEmpty()) return null
    var sawInvalidHash = false
    var sawUnresolvedHash = false
    var changed = false
    fun markInvalid() { sawInvalidHash = true }
    fun markUnresolved() { sawUnresolvedHash = true }
    fun markChanged() { changed = true }

    val fixedRoot = buildJsonObject {
        root.forEach { (key, value) ->
            when {
                key == "hash" -> put(key, fixHashElement(value, root["url"], ::markInvalid, ::markUnresolved, ::markChanged, expectedHashes))
                key == "architecture" && value is JsonObject -> put(
                    key,
                    fixArchitectureBlock(value, ::markInvalid, ::markUnresolved, ::markChanged, expectedHashes),
                )
                else -> put(key, value)
            }
        }
    }
    if (!sawInvalidHash) return null
    // Fail-closed: if any invalid hash had no resolvable source, return null so
    // the error surfaces to the repair loop instead of passing validation with
    // a wrong digest. An unmatched URL never blocks the fix itself being built.
    return if (changed && !sawUnresolvedHash) fixedRoot.toString() else null
}

/** Fixes one hash position (flat hash, or per-architecture hash). */
private fun fixHashElement(
    hashElement: JsonElement,
    urlElement: JsonElement?,
    onInvalid: () -> Unit,
    onUnresolved: () -> Unit,
    onChanged: () -> Unit,
    expectedHashes: Map<String, String>,
): JsonElement {
    return when (hashElement) {
        is JsonArray -> buildJsonArray {
            hashElement.forEachIndexed { index, item ->
                val url = urlAt(urlElement, index)
                when (val fixed = fixSingleHash(item, url, onInvalid, onUnresolved, expectedHashes)) {
                    null -> add(item)
                    else -> {
                        add(fixed)
                        onChanged()
                    }
                }
            }
        }
        else -> fixSingleHash(hashElement, urlOf(urlElement), onInvalid, onUnresolved, expectedHashes)?.also { onChanged() } ?: hashElement
    }
}

/** Fixes the architecture block: per-arch url/hash pairs. */
private fun fixArchitectureBlock(
    architecture: JsonObject,
    onInvalid: () -> Unit,
    onUnresolved: () -> Unit,
    onChanged: () -> Unit,
    expectedHashes: Map<String, String>,
): JsonObject = buildJsonObject {
    architecture.forEach { (arch, block) ->
        if (block is JsonObject) {
            put(arch, buildJsonObject {
                block.forEach { (key, value) ->
                    if (key == "hash") {
                        put(key, fixHashElement(value, block["url"], onInvalid, onUnresolved, onChanged, expectedHashes))
                    } else {
                        put(key, value)
                    }
                }
            })
        } else {
            put(arch, block)
        }
    }
}

/**
 * Returns the fixed primitive for one hash position, or null when the value
 * should stay unchanged. Calls [onInvalid] when the value is an invalid hash,
 * whether or not a fix was possible.
 */
private fun fixSingleHash(
    item: JsonElement,
    url: String?,
    onInvalid: () -> Unit,
    onUnresolved: () -> Unit,
    expectedHashes: Map<String, String>,
): JsonPrimitive? {
    val content = when (item) {
        is JsonPrimitive -> item.contentOrNull
        is JsonNull -> ""
        else -> null
    } ?: return null
    if (content.matches(SHA256_HASH_REGEX)) return null
    if (content.isBlank()) {
        // Missing hash is a distinct validation error with its own repair path.
        return null
    }
    // The value is invalid whether or not we can repair it below.
    onInvalid()
    val source = if (url != null) resolveSourceHash(url, expectedHashes) else null
    if (source == null) {
        onUnresolved()
        return null
    }
    return JsonPrimitive(source)
}

/** Reads the flat url primitive (noarch format). */
private fun urlOf(urlElement: JsonElement?): String? =
    (urlElement as? JsonPrimitive)?.contentOrNull

/** Reads the url for hash-array position [index] (url may be scalar or array). */
private fun urlAt(urlElement: JsonElement?, index: Int): String? = when (urlElement) {
    is JsonArray -> (urlElement.getOrNull(index) as? JsonPrimitive)?.contentOrNull
    else -> urlOf(urlElement)
}

/**
 * Resolves the authoritative hash for [url]: exact match first, then the URL
 * without its fragment (Scoop URLs carry #/dl.7z while source assets do not),
 * then a unique archive-filename match. Case-insensitive on the lookup keys
 * because hosts normalize URLs to lowercase while the LLM may transcribe
 * either case.
 */
internal fun resolveSourceHash(url: String, expectedHashes: Map<String, String>): String? {
    val noFragment = url.substringBefore('#')
    lookupHash(url, expectedHashes)?.let { return it }
    if (noFragment != url) {
        lookupHash(noFragment, expectedHashes)?.let { return it }
    }
    val filename = noFragment.substringAfterLast('/')
    if (filename.isNotBlank()) {
        val matches = expectedHashes.entries.filter { it.key.substringAfterLast('/') == filename }
        if (matches.size == 1) return matches.first().value
    }
    return null
}

private fun lookupHash(url: String, expectedHashes: Map<String, String>): String? {
    expectedHashes[url]?.let { return it }
    val lower = url.lowercase()
    return expectedHashes.entries
        .firstOrNull { it.key.lowercase() == lower }
        ?.value
}
