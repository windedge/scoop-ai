package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlin.math.pow
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/** Default per-request ceiling, matching JavaKoogHttpClient's own 15-minute request timeout. */
public const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 15 * 60 * 1000L

/**
 * Non-cancellation timeout failure for a post call or SSE stream that exceeded
 * [InterceptingHttpClient.requestTimeoutMillis]. Deliberately not a
 * [CancellationException]: the retry loop rethrows cancellations untouched, but
 * this failure must flow through the same retry path as transient HTTP errors.
 */
public class RequestTimeoutException(
    what: String,
    timeoutMillis: Long,
) : RuntimeException("$what request to LLM gateway timed out after ${timeoutMillis}ms")

/** Sink for retry log lines; hosts plug their own logger, the default uses the module logger. */
public fun interface RetryLog {
    public operator fun invoke(message: String)
}

/** Default [RetryLog] sinks shared by tests and production wiring. */
public object RetryLogs {
    /** Emits each line through the module logger at warn level. */
    public val default: RetryLog = RetryLog { message -> logger.warn { message } }
}

/**
 * Build the retryable timeout failure for a request or SSE stream that exceeded
 * its ceiling. [what] names the aborted call ("post" / "sse"), [timeoutMillis]
 * the applied ceiling. The message contains "timed out" so
 * [isRetryableHttpException] treats it as retryable.
 */
internal fun sseTimeoutException(what: String, timeoutMillis: Long): RequestTimeoutException =
    RequestTimeoutException(what, timeoutMillis)

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
 *
 * Every attempt (post and sse) is additionally bounded by
 * [requestTimeoutMillis] (default 15 minutes, matching JavaKoogHttpClient's
 * own request timeout): a stalled or slow-dripping gateway can otherwise hang
 * a call indefinitely, because Java HttpClient's timeout only covers response
 * headers, not the body stream. A wrapper timeout surfaces as a
 * [TimeoutCancellationException] whose message stays retryable, so it flows
 * through the same retry loop as transient HTTP errors.
 *
 * Each scheduled retry emits one log line (attempt, delayMs, cause summary)
 * through [onRetryLog] when provided, and to the module logger. Without it,
 * retries happen silently and hosts show a misleading "no tool calls" state.
 */
public class InterceptingHttpClient(
    private val delegate: KoogHttpClient,
    private val interceptors: List<(String) -> String> = emptyList(),
    private val responseRewriter: ((String) -> String)? = null,
    private val onResponse: ((String) -> Unit)? = null,
    public val retryConfig: HttpRetryConfig = HttpRetryConfig.DEFAULT,
    /** Total per-attempt ceiling for post calls and sse streams, in milliseconds. */
    public val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    /** Optional sink for retry log lines; null disables retry logging. */
    private val onRetryLog: RetryLog? = RetryLogs.default,
) : KoogHttpClient by delegate {

    override val clientName: String = delegate.clientName

    private fun logRetry(message: String) {
        onRetryLog?.invoke(message)
    }

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
                logRetry(
                    "LLM request retry attempt=$attempt delayMs=$delayMs " +
                        "client=$clientName cause=${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                delay(delayMs)
            }
        }
    }

    private suspend fun <R> withRequestTimeout(block: suspend () -> R): R =
        try {
            withTimeout(requestTimeoutMillis.milliseconds) { block() }
        } catch (e: TimeoutCancellationException) {
            throw sseTimeoutException("post", requestTimeoutMillis)
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
        val result = withRequestTimeout {
            delegate.post(path, fixedBody, requestBodyType, responseType, parameters, headers)
        }
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
        return flow {
            // Retry the whole stream (connect + collect) on retryable failures;
            // delegate.sse() builds a cold flow, so each attempt is a fresh HTTP call.
            executeWithRetry {
                sseTimeoutFlow {
                    delegate.sse(
                        path, fixedBody, requestBodyType,
                        dataFilter, rewriteAndLogDecode, processStreamingChunk,
                        parameters, headers,
                    )
                }.collect { emit(it) }
            }
        }
    }

    /**
     * Bounds the total lifetime of the delegate's SSE [Flow] by
     * [requestTimeoutMillis]. Collected inside the retry loop, so the timeout
     * failure goes through the same retryable path as transient HTTP errors.
     * The timeout covers the whole stream (headers plus body), which Java
     * HttpClient's per-request timeout alone does not.
     */
    private fun <O : Any> sseTimeoutFlow(delegateFlow: () -> Flow<O>): Flow<O> = flow {
        try {
            withTimeout(requestTimeoutMillis.milliseconds) {
                delegateFlow().collect { emit(it) }
            }
        } catch (e: TimeoutCancellationException) {
            throw sseTimeoutException("sse", requestTimeoutMillis)
        }
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> {
        val fixedBody = applyInterceptors(requestBody, requestBodyType)
        return flow {
            // Ollama uses Koog's lines() flow, the same unbounded-body-stream
            // shape as sse(): apply the identical timeout + retry envelope.
            executeWithRetry {
                sseTimeoutFlow {
                    delegate.lines(path, fixedBody, requestBodyType, parameters, headers)
                }.collect { emit(it) }
            }
        }
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
