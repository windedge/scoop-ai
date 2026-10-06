package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Shared Json instance for pretty-printing manifests with Scoop-bucket 4-space indentation. */
internal val manifestPrettyJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "    "
}

private val codeFenceRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

/**
 * Formats a raw LLM-produced manifest string into a canonical 4-space pretty-printed
 * JSON object string.
 *
 * Unwraps, in order:
 * 1. a surrounding ```json code fence,
 * 2. a {"manifest":"{...}"} wrapper object (when it has no `version` key),
 * 3. one level of double-encoding (a JSON string whose content is itself a JSON object).
 *
 * Returns the input unchanged when the content cannot be parsed as a JSON object,
 * so callers never receive a mangled manifest.
 */
internal fun formatManifestJson(raw: String): String {
    // Step 1: strip a surrounding code fence if present, otherwise trim
    val fenced = codeFenceRegex.find(raw)?.groupValues?.get(1)?.trim() ?: raw.trim()

    // Step 2: parse the candidate content; anything unparseable stays as-is
    val element = try {
        Json.parseToJsonElement(fenced)
    } catch (_: Exception) {
        return raw
    }

    // Step 3: unwrap {"manifest":"{...}"} wrapper the LLM sometimes produces
    val unwrapped = when {
        element is JsonObject && element.containsKey("manifest") && !element.containsKey("version") -> {
            val inner = element["manifest"]?.jsonPrimitive?.contentOrNull ?: return raw
            try {
                Json.parseToJsonElement(inner)
            } catch (_: Exception) {
                return raw
            }
        }
        else -> element
    }

    // Step 4: unwrap one level of double-encoding (string whose content is itself JSON)
    val decoded = when {
        unwrapped is JsonPrimitive && unwrapped.isString -> {
            try {
                Json.parseToJsonElement(unwrapped.content)
            } catch (_: Exception) {
                return raw
            }
        }
        else -> unwrapped
    }

    // Step 5: require a JSON object, then re-encode with 4-space pretty printing
    if (decoded !is JsonObject) return raw
    return try {
        manifestPrettyJson.encodeToString(JsonElement.serializer(), decoded)
    } catch (_: Exception) {
        raw
    }
}

/**
 * Injects a top-level `"_meta": {"activities": [...]}` entry into a manifest JSON
 * string before it is forwarded over the [com.scoopai.core.ScoopManifestGenerator]
 * phase output channel (generate/assist/repair rounds).
 *
 * Returns the input unchanged when [activities] is null/empty or when [manifestJson]
 * cannot be parsed as a JSON object, so a mangled manifest is never forwarded and
 * the normalize/verify path always receives clean JSON.
 */
internal fun withPhaseMeta(manifestJson: String, activities: List<String>?): String {
    if (activities.isNullOrEmpty()) return manifestJson
    val obj = try {
        Json.parseToJsonElement(manifestJson).jsonObject
    } catch (_: Exception) {
        return manifestJson
    }
    val meta = buildJsonObject {
        putJsonArray("activities") {
            activities.forEach { add(it) }
        }
    }
    val merged = JsonObject(obj + ("_meta" to meta))
    return merged.toString()
}
