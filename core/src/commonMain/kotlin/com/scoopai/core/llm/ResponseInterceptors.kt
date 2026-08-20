package com.scoopai.core.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private val responseJson = Json { prettyPrint = false }
private val responseLogger = KotlinLogging.logger {}

/** Keys that may hold a reasoner's auxiliary thinking text, in priority order. */
private val reasoningContentKeys = listOf("reasoning_content", "reasoning", "reasoningContent")

/**
 * Parses [rawJson] as a JSON object, returning null on any JSON error or
 * when the top-level value is not a JSON object. Keeps the "invalid JSON is
 * left untouched" contract for the response rewriters.
 */
private fun parseJsonObjectOrNull(rawJson: String): JsonObject? = try {
    responseJson.parseToJsonElement(rawJson) as? JsonObject
} catch (_: Exception) {
    null
}

/**
 * True only when [element] holds non-blank content.
 * `JsonNull` is excluded explicitly: it serializes as the literal "null"
 * text but must be treated as blank content. A non-empty `JsonArray`
 * (e.g. chat-completions content parts `[{"type":"text","text":"..."}]`)
 * or a non-empty `JsonObject` also counts as existing content, so the
 * reasoning fallback must not overwrite it.
 */
private fun isNonBlankContent(element: JsonElement?): Boolean {
    if (element == null || element is JsonNull) return false
    return when (element) {
        is JsonArray -> element.isNotEmpty()
        is JsonObject -> element.isNotEmpty()
        is JsonPrimitive -> !element.content.isNullOrBlank()
    }
}

/**
 * True when the message carries at least one tool call. An empty `tool_calls`
 * array is treated as "no calls" so a reasoning-only response is not mistaken
 * for a real tool request.
 */
private fun hasToolCalls(message: JsonObject): Boolean {
    val toolCalls = message["tool_calls"]
    return toolCalls != null && toolCalls !is JsonNull &&
        (toolCalls !is JsonArray || toolCalls.isNotEmpty())
}

/**
 * Maps a reasoning-only OpenAI-compatible response to one Koog can decode.
 *
 * Some reasoner backends (e.g. DeepSeek V4 Flash behind an OpenAI-compatible
 * gateway) emit a pure-thinking response: `choices[0].message.content` is
 * null/blank and there are no `tool_calls`, with the model's reasoning held in
 * an auxiliary key (`reasoning_content` / `reasoning` / `reasoningContent`,
 * checked in that priority order). Koog ignores the auxiliary key and parses
 * the message as `parts=[]`, which (when the model lacks ToolChoice capability)
 * makes the subgraph throw "Subgraph with task must always call tools".
 *
 * This rewriter copies the non-blank reasoning text into `message.content`,
 * which Koog then decodes as `MessagePart.Text` so the strategy graph can
 * recover. It is deliberately conservative: any of the following leaves the
 * JSON untouched — non chat-completions shape, invalid JSON, a non-blank
 * `content` already present, a non-empty `tool_calls`, or an empty `choices`
 * array. Only the complete `message.content` layer is handled (SSE chunks are
 * rewritten individually by [com.scoopai.core.llm.InterceptingHttpClient.sse]).
 */
public fun reasoningFallbackToContent(rawJson: String): String {
    val root = parseJsonObjectOrNull(rawJson) ?: return rawJson

    // Must be a chat-completions response with at least one choice.
    val choices = root["choices"] as? JsonArray ?: return rawJson
    if (choices.isEmpty()) return rawJson

    val firstChoice = choices.firstOrNull() as? JsonObject ?: return rawJson
    val message = firstChoice["message"] as? JsonObject ?: return rawJson

    // Already has non-blank content -> do not touch.
    if (isNonBlankContent(message["content"])) return rawJson

    // Has tool_calls -> do not touch (a real tool request).
    if (hasToolCalls(message)) return rawJson

    // Find a non-blank reasoning value across the supported keys. JsonNull is
    // excluded explicitly: it is a JsonPrimitive whose content is the literal
    // "null", which must be treated as absent (matching isNonBlankContent).
    val reasoningText = reasoningContentKeys.firstNotNullOfOrNull { key ->
        val element = message[key]
        if (element == null || element is JsonNull) {
            null
        } else {
            (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    } ?: return rawJson

    val updatedMessage = JsonObject(message + ("content" to JsonPrimitive(reasoningText)))
    val updatedChoice = JsonObject(firstChoice + ("message" to updatedMessage))
    val updatedChoices = JsonArray(
        choices.mapIndexed { index, choice -> if (index == 0) updatedChoice else choice }
    )
    val updatedRoot = JsonObject(root + ("choices" to updatedChoices))
    val summary = reasoningText.replace("\n", " ").take(80)
    responseLogger.info {
        "[reasoningFallbackToContent] Mapped reasoning text into content (first ${reasoningText.length} chars, preview: $summary)"
    }
    return responseJson.encodeToString(JsonElement.serializer(), updatedRoot)
}

/**
 * Adds the locally observed Unix timestamp to an OpenAI Chat Completions
 * response when an OpenAI-compatible gateway omitted the required `created`
 * field. Other response shapes and invalid JSON are deliberately untouched.
 */
public fun normalizeMissingCreated(rawJson: String, createdEpochSeconds: Long): String {
    val root = parseJsonObjectOrNull(rawJson) ?: return rawJson

    if (root["choices"] !is kotlinx.serialization.json.JsonArray) return rawJson
    if (root["created"] != null && root["created"] !is JsonNull) return rawJson

    responseLogger.warn { "[normalizeMissingCreated] Added missing created field to OpenAI-compatible response" }
    val normalized = JsonObject(root + ("created" to JsonPrimitive(createdEpochSeconds)))
    return responseJson.encodeToString(JsonElement.serializer(), normalized)
}

/** Gateway finish_reason signalling a rejected/malformed function call. */
private const val MALFORMED_FUNCTION_CALL_FINISH_REASON = "malformed_function_call"

/**
 * Hint injected into `message.content` so Koog can route a malformed tool-call
 * response onto its text retry edge, prompting the model to re-emit the call.
 */
private const val MALFORMED_FUNCTION_CALL_RETRY_HINT =
    "My previous function call was malformed. I will retry the tool call with valid JSON arguments."

/**
 * Recovers from a Gemini-family model emitting
 * `finish_reason: "malformed_function_call"` (via an OpenAI-compatible
 * gateway) with no `tool_calls` and no `content` (outputTokens=0).
 *
 * Background: when the model's tool call is rejected as malformed the gateway
 * returns an empty assistant turn, which Koog decodes as `parts=[]`. That
 * lands on the `subgraphWithTask` Unhandled fallback edge
 * (AIAgentSubgraphExt.kt) and throws "Unhandled response from LLM" directly,
 * without even triggering Koog's 3-text-retry loop (retry requires a Text
 * part). Such corruption is often transient: if we rewrite the response so it
 * carries a Text `content`, Koog routes it onto the text/`handleAssistantMessage`
 * edge which appends "DO NOT CHAT... CALL TOOLS" and re-prompts the model,
 * letting it re-issue the tool call (which usually succeeds).
 *
 * Trigger is strict: `choices[0].finish_reason == "malformed_function_call"`
 * AND `content` null/blank AND `tool_calls` missing/empty (reusing
 * [isNonBlankContent] / [hasToolCalls]). Whether a reasoning key is present is
 * irrelevant (it triggers in both cases); in the live chain it runs after
 * [reasoningFallbackToContent] so a non-blank content already wins. Anything
 * else (other finish_reason such as length/safety/recitation, non-blank
 * content, real tool_calls, non chat-completions shape, invalid JSON) is left
 * untouched -- those are deliberately out of scope and can be extended on demand.
 */
public fun malformedFunctionCallRetryHint(rawJson: String): String {
    val root = parseJsonObjectOrNull(rawJson) ?: return rawJson

    // Must be a chat-completions response with at least one choice.
    val choices = root["choices"] as? JsonArray ?: return rawJson
    if (choices.isEmpty()) return rawJson

    val firstChoice = choices.firstOrNull() as? JsonObject ?: return rawJson
    val finishReason = (firstChoice["finish_reason"] as? JsonPrimitive)?.content
    if (finishReason != MALFORMED_FUNCTION_CALL_FINISH_REASON) return rawJson

    val message = firstChoice["message"] as? JsonObject ?: return rawJson

    // Already has non-blank content -> do not touch.
    if (isNonBlankContent(message["content"])) return rawJson

    // Has tool_calls -> do not touch (a real tool request survived).
    if (hasToolCalls(message)) return rawJson

    val updatedMessage = JsonObject(message + ("content" to JsonPrimitive(MALFORMED_FUNCTION_CALL_RETRY_HINT)))
    val updatedChoice = JsonObject(firstChoice + ("message" to updatedMessage))
    val updatedChoices = JsonArray(
        choices.mapIndexed { index, choice -> if (index == 0) updatedChoice else choice }
    )
    val updatedRoot = JsonObject(root + ("choices" to updatedChoices))
    responseLogger.info {
        "[malformedFunctionCallRetryHint] Injected tool-call retry hint into content for malformed_function_call"
    }
    return responseJson.encodeToString(JsonElement.serializer(), updatedRoot)
}
