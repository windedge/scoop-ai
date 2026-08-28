package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.pow
import kotlin.reflect.KClass

/**
 * Configuration for HTTP request retries on transient errors (429, 5xx, timeouts).
 */
public data class HttpRetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 8000L,
    val backoffFactor: Double = 2.0,
) {
    public companion object {
        public val DEFAULT: HttpRetryConfig = HttpRetryConfig()
        public val DISABLED: HttpRetryConfig = HttpRetryConfig(maxRetries = 0)
    }
}

private val SERVER_ERROR_REGEX = Regex("""\b5\d{2}\b""")

internal fun isRetryableHttpException(e: Throwable): Boolean {
    if (e is CancellationException) return false
    val message = e.message ?: return false
    val lower = message.lowercase()
    if ("429" in lower || "rate limit" in lower) return true
    if ("401" in lower || "403" in lower || "404" in lower || "400" in lower) return false
    return SERVER_ERROR_REGEX.containsMatchIn(message) ||
        "timeout" in lower ||
        "timed out" in lower ||
        "connection reset" in lower ||
        "connect timeout" in lower
}

/**
 * A [KoogHttpClient] decorator that applies a chain of [interceptors] to
 * the request body of every POST/SSE call before forwarding to the [delegate],
 * with automatic exponential backoff retry on transient HTTP errors (429, 5xx).
 */
public class InterceptingHttpClient(
    private val delegate: KoogHttpClient,
    private val interceptors: List<(String) -> String> = emptyList(),
    private val responseRewriter: ((String) -> String)? = null,
    private val onResponse: ((String) -> Unit)? = null,
    private val retryConfig: HttpRetryConfig = HttpRetryConfig.DEFAULT,
) : KoogHttpClient by delegate {

    override val clientName: String = delegate.clientName

    private suspend fun <R> executeWithRetry(block: suspend () -> R): R {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (attempt > retryConfig.maxRetries || !isRetryableHttpException(e)) {
                    throw e
                }
                val factor = retryConfig.backoffFactor.pow((attempt - 1).toDouble())
                val delayMs = (retryConfig.initialDelayMs * factor).toLong().coerceIn(0L, retryConfig.maxDelayMs)
                delay(delayMs)
            }
        }
    }

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = executeWithRetry {
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        val result = delegate.post(path, fixedBody, requestBodyType, responseType, parameters, headers)
        if (responseType == String::class) {
            @Suppress("UNCHECKED_CAST")
            val raw = result as String
            val rewritten = responseRewriter?.invoke(raw) ?: raw
            onResponse?.invoke(rewritten)
            @Suppress("UNCHECKED_CAST")
            rewritten as R
        } else {
            result
        }
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
