package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

/**
 * A [KoogHttpClient] decorator that fixes Koog 1.0.0's double-encoding bug in
 * tool call arguments and tool result content before sending the request.
 *
 * ## The Bug
 *
 * In `AbstractOpenAILLMClient.convertPromptToMessages()`, tool call arguments are
 * serialized as `OpenAIFunction(it.tool, Json.encodeToString(it.args))` where
 * `it.args` is already a JSON string. `Json.encodeToString(String)` re-encodes
 * it, producing `"{\"url\":\"https://...\"}"` which is rejected by some backends
 * (e.g. GLM-4.7 via proxy) with "invalid tool call arguments".
 *
 * ## The Fix
 *
 * This decorator intercepts the POST request body (a JSON string), parses it,
 * finds double-encoded `arguments` and `content` fields, and unwraps them before
 * forwarding to the real HTTP client.
 */
public class DoubleEncodingFixHttpClient(
    private val delegate: KoogHttpClient,
) : KoogHttpClient by delegate {

    private val logger = KotlinLogging.logger {}

    override val clientName: String = delegate.clientName

    private companion object {
        private val fixJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        logger.debug { "[DoubleEncodingFix] POST path=$path requestBodyType=${requestBodyType.simpleName}" }
        val fixedBody = if (requestBodyType == String::class) {
            @Suppress("UNCHECKED_CAST")
            fixRequestBody(requestBody as String) as T
        } else {
            requestBody
        }
        return delegate.post(path, fixedBody, requestBodyType, responseType, parameters, headers)
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> {
        val fixedBody = if (requestBodyType == String::class) {
            @Suppress("UNCHECKED_CAST")
            fixRequestBody(requestBody as String) as T
        } else {
            requestBody
        }
        return delegate.sse(
            path, fixedBody, requestBodyType,
            dataFilter, decodeStreamingResponse, processStreamingChunk,
            parameters, headers,
        )
    }

    /**
     * Parse the JSON request body and fix double-encoded fields.
     */
    internal fun fixRequestBody(rawJson: String): String {
        val root = try {
            fixJson.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            logger.warn { "[DoubleEncodingFix] Failed to parse JSON: ${e.message}" }
            return rawJson // Not valid JSON, leave as-is
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
                logger.info { "[DoubleEncodingFix] Fixed double-encoded tool call args for '${tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content}'" }
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
            logger.debug { "Fixed double-encoded tool content: ${content.take(80)} -> ${fixedContent.take(80)}" }
            JsonObject(msg + ("content" to JsonPrimitive(fixedContent)))
        } else {
            msg
        }
    }

    /**
     * If [value] looks like a JSON string wrapping another JSON object/array, strip
     * the outer quotes and unescape.
     *
     * - `"{\"url\":\"https://...\"}"` → `{"url":"https://..."}`
     * - `{"url":"https://..."}` → unchanged
     * - `"hello"` → unchanged (a simple string is valid)
     */
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
}
