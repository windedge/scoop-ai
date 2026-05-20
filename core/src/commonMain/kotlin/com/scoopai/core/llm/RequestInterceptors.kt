package com.scoopai.core.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

private val fixJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

// ---------------------------------------------------------------------------
// Interceptor: fix double-encoded tool call arguments and tool result content
// ---------------------------------------------------------------------------

/**
 * Fixes Koog 1.0.0's double-encoding bug in tool call arguments and tool
 * result content before the request is sent to the LLM API.
 *
 * In `AbstractOpenAILLMClient.convertPromptToMessages()`, tool call
 * arguments are serialized as `OpenAIFunction(it.tool,
 * Json.encodeToString(it.args))` where `it.args` is already a JSON string.
 * `Json.encodeToString(String)` re-encodes it, producing
 * `"{\"url\":\"https://...\"}"` which is rejected by some backends
 * with "invalid tool call arguments".
 */
public fun fixDoubleEncoding(rawJson: String): String {
    val root = try {
        fixJson.parseToJsonElement(rawJson).jsonObject
    } catch (e: Exception) {
        logger.warn { "[fixDoubleEncoding] Failed to parse JSON: ${e.message}" }
        return rawJson
    }

    val messagesArray = root["messages"]?.jsonArray ?: return rawJson

    var modified = false
    val fixedMessages = messagesArray.map { msgElement ->
        val msg = msgElement.jsonObject
        val role = msg["role"]?.jsonPrimitive?.content ?: return@map msgElement

        when (role) {
            "assistant" -> fixAssistantMessage(msg).also {
                if (it !== msgElement) modified = true
            }
            "tool" -> fixToolMessage(msg).also {
                if (it !== msgElement) modified = true
            }
            else -> msgElement
        }
    }

    return if (modified) {
        val result = JsonObject(root + ("messages" to JsonArray(fixedMessages)))
        fixJson.encodeToString(JsonElement.serializer(), result)
    } else {
        rawJson
    }
}

private fun fixAssistantMessage(msg: JsonObject): JsonElement {
    val toolCalls = msg["tool_calls"]?.jsonArray ?: return msg
    var toolCallsModified = false

    val fixedToolCalls = toolCalls.map { tcElement ->
        val tc = tcElement.jsonObject
        val func = tc["function"]?.jsonObject ?: return@map tcElement
        val argsPrimitive = func["arguments"]?.jsonPrimitive ?: return@map tcElement
        val args = argsPrimitive.content
        val fixedArgs = unwrapIfDoubleEncoded(args)
        if (fixedArgs != args) {
            toolCallsModified = true
            logger.debug { "[fixDoubleEncoding] Fixed double-encoded tool call args for '${tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content}'" }
            JsonObject(tc + ("function" to JsonObject(
                func + ("arguments" to JsonPrimitive(fixedArgs))
            )))
        } else {
            tcElement
        }
    }

    return if (toolCallsModified) {
        JsonObject(msg + ("tool_calls" to JsonArray(fixedToolCalls)))
    } else {
        msg
    }
}

private fun fixToolMessage(msg: JsonObject): JsonElement {
    val contentPrimitive = msg["content"]?.jsonPrimitive ?: return msg
    val content = contentPrimitive.content

    val fixedContent = unwrapIfDoubleEncoded(content)
    return if (fixedContent != content) {
        logger.debug { "[fixDoubleEncoding] Fixed double-encoded tool content: ${content.take(80)} -> ${fixedContent.take(80)}" }
        JsonObject(msg + ("content" to JsonPrimitive(fixedContent)))
    } else {
        msg
    }
}

private fun unwrapIfDoubleEncoded(value: String): String {
    if (!value.startsWith("\"") || !value.endsWith("\"")) return value

    return try {
        val parsed = fixJson.parseToJsonElement(value)
        if (parsed is kotlinx.serialization.json.JsonPrimitive && parsed.isString) {
            val inner = parsed.content
            try {
                val innerParsed = fixJson.parseToJsonElement(inner)
                if (innerParsed is JsonObject || innerParsed is JsonArray) {
                    return fixJson.encodeToString(JsonElement.serializer(), innerParsed)
                }
            } catch (_: Exception) {
                // inner is not valid JSON, outer quotes are legitimate
            }
        }
        value
    } catch (_: Exception) {
        value
    }
}

// ---------------------------------------------------------------------------
// Interceptor: downgrade tool_choice: required → auto for DeepSeek models
// ---------------------------------------------------------------------------

/**
 * Downgrades `"tool_choice": "required"` to `"tool_choice": "auto"`.
 *
 * Some API gateways route DeepSeek V4 Flash to a reasoner backend that
 * rejects `tool_choice: required`. The model still calls tools automatically
 * under `auto`, so the downgrade is transparent.
 *
 * Should only be included in the interceptor chain for DeepSeek models.
 */
public fun downgradeToolChoice(rawJson: String): String {
    val root = try {
        fixJson.parseToJsonElement(rawJson).jsonObject
    } catch (_: Exception) {
        return rawJson
    }

    val toolChoiceElement = root["tool_choice"] ?: return rawJson
    val toolChoice = (toolChoiceElement as? JsonPrimitive)?.content
    if (toolChoice != "required") return rawJson

    logger.debug { "[downgradeToolChoice] Downgrading tool_choice: required → auto" }
    val fixed = JsonObject(root + ("tool_choice" to JsonPrimitive("auto")))
    return fixJson.encodeToString(JsonElement.serializer(), fixed)
}
