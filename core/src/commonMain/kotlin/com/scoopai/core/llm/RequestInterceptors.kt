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
 * Owned by [com.scoopai.core.llm.KoogCompat.decorateHttpClient]; kept as a
 * top-level function for direct testing.
 *
 * Trigger: OpenAI-compatible gateways (e.g. proxies that route DeepSeek V4
 * Flash to a reasoner backend) that reject an explicit `tool_choice` field
 * outright. Koog's `setToolChoiceRequired()` has no capability gating, so the
 * request body always carries `"tool_choice": "required"` even when the
 * gateway cannot accept it.
 *
 * Caveat: stripping the field is only transparent when the gateway's default
 * `tool_choice` is `auto`. If the default is not `auto` (e.g. `none`), the
 * model may stop emitting tool calls entirely, and the run must rely on
 * Koog's retry/re-prompt path (e.g. the ToolChoice-less model built by
 * [com.scoopai.core.llm.KoogCompat.decorateModel]) to recover.
 *
 * The field is removed for every value (`required`, `auto`, object form):
 * the interceptor is unconditional and must be included in openai-compatible
 * chains regardless of the model name. Invalid JSON is returned unchanged.
 *
 * Retire when Koog stops hard-coding `tool_choice` into every request (or
 * gates it on a declared capability gateways actually honor).
 */
internal fun stripToolChoice(rawJson: String): String {
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