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
 * This replaces separate decorator classes (DoubleEncodingFixHttpClient,
 * LoggingKoogHttpClient, ToolChoiceDowngradeHttpClient) with a single
 * reusable decorator + composable interceptor functions.
 *
 * Usage:
 * ```kotlin
 * val httpClient = InterceptingHttpClient(
 *     delegate = baseClient,
 *     interceptors = buildList {
 *         add(::fixDoubleEncoding)
 *         if (isDeepSeekModel) add(::downgradeToolChoice)
 *     }
 * )
 * ```
 */
public class InterceptingHttpClient(
    private val delegate: KoogHttpClient,
    private val interceptors: List<(String) -> String> = emptyList(),
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
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        return delegate.sse(
            path, fixedBody, requestBodyType,
            dataFilter, decodeStreamingResponse, processStreamingChunk,
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