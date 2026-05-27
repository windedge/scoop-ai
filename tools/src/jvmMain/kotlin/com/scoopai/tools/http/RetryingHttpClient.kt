package com.scoopai.tools.http

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Unified HTTP client with retry, timeout, and error-handling strategy.
 *
 * All tools that make HTTP requests should use this class instead of
 * creating their own [HttpClient] instances. This ensures consistent
 * behavior across the codebase:
 *
 * - **Connect/read timeout**: 30 seconds by default
 * - **Retry**: up to [maxRetries] attempts with exponential backoff
 * - **Transient error detection**: retries on 5xx status codes and IOExceptions
 *
 * @param connectTimeout  Timeout for establishing the connection
 * @param readTimeout     Timeout for reading the response body
 * @param maxRetries      Maximum number of retry attempts (0 = no retry)
 */
open class RetryingHttpClient(
    connectTimeout: Duration = Duration.ofSeconds(30),
    private val readTimeout: Duration = Duration.ofSeconds(60),
    private val maxRetries: Int = 3,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(connectTimeout)
        .build()

    /**
     * Executes a GET request with retry logic.
     *
     * Retries on:
     * - HTTP 429 (Too Many Requests)
     * - HTTP 5xx (Server Error)
     * - IOExceptions (network failures)
     *
     * @throws HttpException if all retries are exhausted
     */
    open fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        var lastException: Exception? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(readTimeout)
                    .GET()
                headers.forEach { (k, v) -> requestBuilder.header(k, v) }

                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() in 200..299) {
                    return response
                }

                // Transient server errors: retry
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    lastException = HttpException(response.statusCode(), url, response.body())
                    logger.warn { "[http] Attempt ${attempt + 1}/${maxRetries + 1} failed: HTTP ${response.statusCode()} for $url" }
                } else {
                    // Client errors (4xx except 429): don't retry
                    throw HttpException(response.statusCode(), url, response.body())
                }
            } catch (e: java.io.IOException) {
                lastException = e
                logger.warn { "[http] Attempt ${attempt + 1}/${maxRetries + 1} failed: ${e.message} for $url" }
            }

            attempt++
            if (attempt <= maxRetries) {
                val delayMs = calculateBackoffMs(attempt)
                logger.info { "[http] Retrying in ${delayMs}ms..." }
                Thread.sleep(delayMs)
            }
        }

        throw lastException ?: HttpException(-1, url, "All retries exhausted")
    }

    private fun calculateBackoffMs(attempt: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s...
        val base = 1000L
        return (base * (1L shl (attempt - 1))).coerceAtMost(30_000L)
    }

    class HttpException(
        val statusCode: Int,
        val url: String,
        val body: String,
    ) : Exception("HTTP $statusCode for $url: ${body.take(200)}") {
        /** Whether this is a GitHub API rate limit (403 with rate-limit markers). */
        val isRateLimited: Boolean
            get() = statusCode == 403 && (
                body.contains("rate limit", ignoreCase = true) ||
                    body.contains("API rate limit exceeded", ignoreCase = true) ||
                    body.contains("secondary rate limit", ignoreCase = true)
            )
    }

}
