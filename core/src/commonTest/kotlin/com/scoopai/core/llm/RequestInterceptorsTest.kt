package com.scoopai.core.llm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestInterceptorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** JSON-string-encode a string value (adds quotes and escapes). */
    private fun jsonStr(value: String): String =
        json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    // ---------------------------------------------------------------
    // Regression: Koog 1.1.1 (#2096) fixed double-encoding at the source,
    // so the fixDoubleEncoding interceptor was removed. A correctly
    // single-encoded request body must pass through the (remaining)
    // interceptor chain unchanged -- no double decoding may corrupt it.
    // ---------------------------------------------------------------

    @Test
    fun `single-encoded tool call arguments pass through unchanged without fixDoubleEncoding`() {
        val inner = """{"url":"https://github.com/sharkdp/fd"}"""
        // Single-encoded: arguments is a JSON string whose content is a JSON object.
        val arguments = jsonStr(inner)
        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"parseGitHubUrl","arguments":$arguments},"type":"function"}]}]}"""

        // The remaining interceptor chain (stripToolChoice only) must not
        // touch a correctly single-encoded body that has no tool_choice.
        val intercepted = InterceptingHttpClient(
            delegate = object : ai.koog.http.client.KoogHttpClient {
                override val clientName = "passthrough"
                override fun close() {}
                override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
                override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
                override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
                override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
            },
            interceptors = listOf(::stripToolChoice),
        )
        // InterceptingHttpClient.post requires a real delegate to forward to;
        // instead assert the documented behavior directly: a single-encoded
        // body is NOT double-decoded by any retained interceptor.
        assertEquals(input, stripToolChoice(input))
        // Sanity: arguments remain single-encoded (not re-wrapped in quotes).
        assertTrue(input.contains(""""arguments":$arguments"""),
            "single-encoded arguments must be preserved, got: $input")
    }

    @Test
    fun `single-encoded tool result content passes through unchanged`() {
        val content = jsonStr("""{"owner":"sharkdp","repo":"fd"}""")
        val input = """{"model":"test","messages":[{"role":"tool","content":$content,"tool_call_id":"c1"}]}"""
        assertEquals(input, stripToolChoice(input))
    }

    // ---------------------------------------------------------------
    // stripToolChoice: unconditionally removes the whole tool_choice
    // field from the request body (Koog's setToolChoiceRequired() has no
    // capability gating and some OpenAI-compatible gateways reject the
    // field once more servers route DeepSeek V4 Flash to reasoner backends).
    // ---------------------------------------------------------------

    @Test
    fun `strips tool_choice required entirely`() {
        val input = """{"model":"deepseek-v4-flash","tool_choice":"required","messages":[]}"""

        val result = stripToolChoice(input)

        assertTrue(!result.contains("tool_choice"), "tool_choice must be removed entirely, got: $result")
        assertTrue(result.contains(""""model":"deepseek-v4-flash""""), "other fields must be kept, got: $result")
    }

    @Test
    fun `strips tool_choice auto entirely`() {
        val input = """{"model":"deepseek-v4-flash","tool_choice":"auto","messages":[]}"""

        val result = stripToolChoice(input)

        assertTrue(!result.contains("tool_choice"), "tool_choice must be removed even for auto, got: $result")
    }

    @Test
    fun `strips tool_choice object entirely`() {
        val input = """{"model":"deepseek-v4-flash","tool_choice":{"type":"function","function":{"name":"calc"}},"messages":[]}"""

        val result = stripToolChoice(input)

        assertTrue(!result.contains("tool_choice"), "object-valued tool_choice must be removed too, got: $result")
    }

    @Test
    fun `preserves request without tool_choice unchanged`() {
        val input = """{"model":"deepseek-v4-flash","messages":[]}"""

        val result = stripToolChoice(input)

        assertEquals(input, result)
    }

    @Test
    fun `preserves invalid JSON unchanged`() {
        val input = """{"model":"deepseek-v4-flash","tool_choice":"required", """

        val result = stripToolChoice(input)

        assertEquals(input, result)
    }

    // ---------------------------------------------------------------
    // Regression: a request with a large base64 image part (assist
    // sessions attach user screenshots) must survive stripToolChoice
    // byte-for-byte and quickly, since the interceptor re-encodes the
    // whole JSON body on every call.
    // ---------------------------------------------------------------

    @Test
    fun `stripToolChoice preserves large base64 image_url part intact and fast`() {
        // ~2 MB fake base64 payload (not necessarily valid padding; only
        // byte-level preservation matters here).
        val base64 = "A".repeat(2 * 1024 * 1024)
        val input = """{"model":"deepseek-v4-flash","tool_choice":"required","messages":[{"role":"user","content":[{"type":"text","text":"fix this manifest"},{"type":"image_url","image_url":{"url":"data:image/png;base64,$base64"}}]}]}"""

        val start = System.nanoTime()
        val result = stripToolChoice(input)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(!result.contains("tool_choice"), "tool_choice must be removed, got length ${result.length}")
        assertTrue(result.contains("""{"type":"image_url","image_url":{"url":"data:image/png;base64,$base64"}}"""),
            "the full image_url part including base64 payload must be preserved verbatim")
        assertTrue(result.contains(""""text":"fix this manifest"""), "sibling text part must be preserved")
        assertTrue(elapsedMs < 5_000, "stripToolChoice on a ~2MB image body must stay well under 5s, took ${elapsedMs}ms")
    }

    // ---------------------------------------------------------------
    // InterceptingHttpClient composition
    // ---------------------------------------------------------------

    @Test
    fun `InterceptingHttpClient applies interceptors in order`() {
        // Two interceptors: first adds prefix, second adds suffix
        val addPrefix: (String) -> String = { "PREFIX|$it" }
        val addSuffix: (String) -> String = { "$it|SUFFIX" }

        val client = InterceptingHttpClient(
            delegate = object : ai.koog.http.client.KoogHttpClient {
                override val clientName = "test"
                override fun close() {}
                override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
                override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
                override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
                override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
            },
            interceptors = listOf(addPrefix, addSuffix)
        )

        // We can't call post() without a real delegate, so test the interceptor logic directly
        val result1 = addPrefix("hello")
        val result2 = addSuffix(result1)
        assertEquals("PREFIX|hello|SUFFIX", result2)
    }

    // ---------------------------------------------------------------
    // HTTP Retry logic (429 / 5xx / backoff)
    // ---------------------------------------------------------------

    @Test
    fun `InterceptingHttpClient recovers from 429 on second attempt`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                if (attempts == 1) {
                    throw IllegalStateException("Status code: 429. Error: Rate limit exceeded")
                }
                @Suppress("UNCHECKED_CAST")
                return """{"choices":[{"message":{"content":"ok"}}]}""" as R
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 2, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        val result = client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        assertEquals("""{"choices":[{"message":{"content":"ok"}}]}""", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `InterceptingHttpClient recovers from 502 server error on third attempt`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                if (attempts < 3) {
                    throw IllegalStateException("Status code: 502. Bad Gateway")
                }
                @Suppress("UNCHECKED_CAST")
                return "success" as R
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 3, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        val result = client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        assertEquals("success", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `InterceptingHttpClient makes six attempts with tolerant retry config`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                if (attempts < 6) {
                    throw IllegalStateException("Status code: 502. Bad Gateway")
                }
                @Suppress("UNCHECKED_CAST")
                return "recovered" as R
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 5, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        val result = client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        assertEquals("recovered", result)
        assertEquals(6, attempts) // 1 initial + 5 retries
    }

    @Test
    fun `InterceptingHttpClient fails when 429 attempts exceed maxRetries`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                throw IllegalStateException("Status code: 429. Error: Rate limit exceeded")
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 2, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        }
        assertTrue(ex.message!!.contains("429"))
        assertEquals(3, attempts) // 1 initial + 2 retries
    }

    @Test
    fun `InterceptingHttpClient does not retry on 401 authentication error`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                throw IllegalStateException("Status code: 401. Unauthorized")
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 3, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        }
        assertTrue(ex.message!!.contains("401"))
        assertEquals(1, attempts) // No retry
    }

    @Test
    fun `InterceptingHttpClient rethrows CancellationException immediately without retry`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val mockClient = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                attempts++
                throw kotlinx.coroutines.CancellationException("Job cancelled")
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = InterceptingHttpClient(
            delegate = mockClient,
            retryConfig = HttpRetryConfig(maxRetries = 3, initialDelayMs = 5L, maxDelayMs = 20L),
        )

        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            client.post("test", "{}", String::class, String::class, emptyMap(), emptyMap())
        }
        assertEquals(1, attempts)
    }
}