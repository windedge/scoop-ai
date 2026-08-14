package com.scoopai.core.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val responseJson = Json { prettyPrint = false }
private val responseLogger = KotlinLogging.logger {}

/**
 * Adds the locally observed Unix timestamp to an OpenAI Chat Completions
 * response when an OpenAI-compatible gateway omitted the required `created`
 * field. Other response shapes and invalid JSON are deliberately untouched.
 */
public fun normalizeMissingCreated(rawJson: String, createdEpochSeconds: Long): String {
    val root = try {
        responseJson.parseToJsonElement(rawJson) as? JsonObject ?: return rawJson
    } catch (_: Exception) {
        return rawJson
    }

    if (root["choices"] !is kotlinx.serialization.json.JsonArray) return rawJson
    if (root["created"] != null && root["created"] !is JsonNull) return rawJson

    responseLogger.warn { "[normalizeMissingCreated] Added missing created field to OpenAI-compatible response" }
    val normalized = JsonObject(root + ("created" to JsonPrimitive(createdEpochSeconds)))
    return responseJson.encodeToString(JsonElement.serializer(), normalized)
}
