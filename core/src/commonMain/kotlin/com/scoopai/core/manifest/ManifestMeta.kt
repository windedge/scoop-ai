package com.scoopai.core.manifest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lenient JSON instance for parsing manifests. */
private val manifestMetaJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Minimal manifest metadata extracted from a Scoop manifest JSON string.
 *
 * @property version The value of the top-level `version` field, or null.
 * @property url The value of the top-level `url` field, or null.
 *   Falls back to `architecture.64bit.url` when no top-level url is present.
 */
data class ManifestMeta(
    val version: String?,
    val url: String?,
)

/**
 * Extract minimal metadata (version, url) from a Scoop manifest JSON.
 *
 * Uses kotlinx.serialization for robust parsing, unlike the old regex-based approach.
 * Returns [ManifestMeta] with null fields when the JSON is invalid or fields are absent.
 *
 * @param manifest The manifest JSON string.
 * @return [ManifestMeta] with version and/or url, null for each if not present or parse fails.
 */
fun parseManifestMeta(manifest: String): ManifestMeta {
    return try {
        val obj = manifestMetaJson.parseToJsonElement(manifest).jsonObject
        ManifestMeta(
            version = obj["version"]?.jsonPrimitive?.contentOrNull,
            url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: obj["architecture"]?.jsonObject
                    ?.get("64bit")?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) {
        ManifestMeta(null, null)
    }
}
