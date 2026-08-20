package com.scoopai.core.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val logger = KotlinLogging.logger {}

private val fixJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

// ---------------------------------------------------------------------------
// Interceptor: strip tool_choice entirely from the request body
// ---------------------------------------------------------------------------

/**
 * Removes the whole `tool_choice` field from the request body.
 *
 * Koog's `setToolChoiceRequired()` has no capability gating, so the request
 * body always carries `"tool_choice": "required"`, and some OpenAI-compatible
 * gateways (e.g. proxies that route DeepSeek V4 Flash to a reasoner backend)
 * reject the field outright. The model still calls tools under the default
 * `auto` mode, so stripping the field is transparent.
 *
 * The field is removed for every value (`required`, `auto`, object form):
 * the interceptor is unconditional and must be included in openai-compatible
 * chains regardless of the model name. Invalid JSON is returned unchanged.
 */
public fun stripToolChoice(rawJson: String): String {
    val root = try {
        fixJson.parseToJsonElement(rawJson).jsonObject
    } catch (_: Exception) {
        return rawJson
    }

    if (!root.containsKey("tool_choice")) return rawJson

    logger.debug { "[stripToolChoice] Removing tool_choice field" }
    val fixed = JsonObject(root - "tool_choice")
    return fixJson.encodeToString(JsonElement.serializer(), fixed)
}