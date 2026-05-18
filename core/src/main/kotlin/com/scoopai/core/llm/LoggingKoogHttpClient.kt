package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

/**
 * A [KoogHttpClient] decorator that logs the full request body of every POST call.
 *
 * Usage: wrap any [KoogHttpClient] to capture the serialized JSON payloads
 * that Koog sends to the LLM API — invaluable for debugging proxy compatibility.
 *
 * Controlled by the `SCOOP_AI_DEBUG_HTTP` environment variable:
 * - `1` / `true` → log every request body to stdout
 * - A file path  → append request bodies to that file
 * - unset / empty → no logging (pass-through)
 */
public class LoggingKoogHttpClient(
    private val delegate: KoogHttpClient,
    private val logTarget: LogTarget = LogTarget.Stdout,
) : KoogHttpClient by delegate {

    public enum class LogTarget {
        Stdout, None
    }

    override val clientName: String = delegate.clientName

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        logRequestBody(path, requestBody, requestBodyType)
        return delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
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
        logRequestBody(path, requestBody, requestBodyType)
        return delegate.sse(
            path, requestBody, requestBodyType,
            dataFilter, decodeStreamingResponse, processStreamingChunk,
            parameters, headers,
        )
    }

    private fun <T : Any> logRequestBody(path: String, requestBody: T, requestBodyType: KClass<T>) {
        if (logTarget == LogTarget.None) return

        val body = if (requestBodyType == String::class) {
            @Suppress("UNCHECKED_CAST")
            requestBody as String
        } else {
            // Shouldn't happen for our use case, but handle gracefully
            requestBody.toString()
        }

        // Try to pretty-print if it looks like JSON
        val displayBody = try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
            val pretty = kotlinx.serialization.json.Json { prettyPrint = true }
            pretty.encodeToString(JsonElement.serializer(), parsed)
        } catch (_: Exception) {
            body
        }

        val separator = "═".repeat(60)
        val message = buildString {
            appendLine(separator)
            appendLine("POST $path")
            appendLine("Request Body:")
            appendLine(displayBody)
            appendLine(separator)
        }

        when (logTarget) {
            LogTarget.Stdout -> println(message)
            LogTarget.None -> {}
        }
    }

    public companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Create a [LoggingKoogHttpClient] if `SCOOP_AI_DEBUG_HTTP` env var is set,
         * otherwise return the delegate unchanged.
         */
        public fun wrapIfDebug(delegate: KoogHttpClient): KoogHttpClient {
            val env = System.getenv("SCOOP_AI_DEBUG_HTTP") ?: return delegate
            if (env.isBlank() || env.equals("false", ignoreCase = true) || env == "0") {
                return delegate
            }
            return LoggingKoogHttpClient(delegate, LogTarget.Stdout)
        }
    }
}
