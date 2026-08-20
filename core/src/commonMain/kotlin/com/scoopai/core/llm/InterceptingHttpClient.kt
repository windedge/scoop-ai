package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * A [KoogHttpClient] decorator that applies a chain of [interceptors] to
 * the request body of every POST/SSE call before forwarding to the [delegate].
 *
 * Each interceptor is a pure function `(String) -> String` that receives the
 * raw JSON request body and returns the (possibly modified) body.
 * Interceptors are applied in list order.
 *
 * [responseRewriter] is an optional pure function applied to every String
 * response body (POST when [responseType] is String, and each raw SSE chunk)
 * before Koog decodes it. Used for response-side fixes such as filling in
 * required fields that an OpenAI-compatible gateway omitted.
 *
 * [onResponse] is an optional callback invoked with the (possibly rewritten)
 * response body of every POST call (when [responseType] is String) and with
 * every raw SSE chunk (after rewriting, before decoding). Used for debug
 * logging of the response direction. Rewriting happens first, so the logged
 * body reflects the final JSON that Koog will decode.
 *
 * This replaces separate decorator classes (logging + per-issue rewriters)
 * LoggingKoogHttpClient, ToolChoiceDowngradeHttpClient) with a single
 * reusable decorator + composable interceptor functions.
 *
 * Usage:
 * ```kotlin
 * val httpClient = InterceptingHttpClient(
 *     delegate = baseClient,
 *     interceptors = buildList {
 *         // Koog's setToolChoiceRequired() has no capability gating; some
 *         // gateways reject the field, so strip it unconditionally.
 *         add(::stripToolChoice)
 *     },
 *     responseRewriter = { json -> normalizeMissingCreated(json, 0L) },
 *     onResponse = { body -> println(body) },
 * )
 * ```
 */
public class InterceptingHttpClient(
    private val delegate: KoogHttpClient,
    private val interceptors: List<(String) -> String> = emptyList(),
    private val responseRewriter: ((String) -> String)? = null,
    private val onResponse: ((String) -> Unit)? = null,
) : KoogHttpClient by delegate {

    override val clientName: String = delegate.clientName

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        val result = delegate.post(path, fixedBody, requestBodyType, responseType, parameters, headers)
        if (responseType == String::class) {
            @Suppress("UNCHECKED_CAST")
            val raw = result as String
            val rewritten = responseRewriter?.invoke(raw) ?: raw
            onResponse?.invoke(rewritten)
            @Suppress("UNCHECKED_CAST")
            return rewritten as R
        }
        return result
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
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        // Wrap the decode callback so every raw SSE chunk is observable
        // before Koog decodes it: rewrite first, then log (debug), then decode.
        val rewriteAndLogDecode: (String) -> R = { raw ->
            val rewritten = responseRewriter?.invoke(raw) ?: raw
            onResponse?.invoke(rewritten)
            decodeStreamingResponse(rewritten)
        }
        return delegate.sse(
            path, fixedBody, requestBodyType,
            dataFilter, rewriteAndLogDecode, processStreamingChunk,
            parameters, headers,
        )
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> {
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        return delegate.lines(path, fixedBody, requestBodyType, parameters, headers)
    }

    /**
     * Apply all interceptors to the request body if it's a String.
     * Non-String bodies are passed through unchanged.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> applyInterceptors(requestBody: T, requestBodyType: KClass<T>): T {
        if (interceptors.isEmpty()) return requestBody
        if (requestBodyType != String::class) return requestBody

        var body = requestBody as String
        for (interceptor in interceptors) {
            body = interceptor(body)
        }
        return body as T
    }
}
