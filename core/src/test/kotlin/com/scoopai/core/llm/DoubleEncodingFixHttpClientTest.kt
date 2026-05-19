package com.scoopai.core.llm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoubleEncodingFixHttpClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** JSON-string-encode a string value (adds quotes and escapes). */
    private fun jsonStr(value: String): String =
        json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    private fun createFixer(): DoubleEncodingFixHttpClient {
        val noop = object : ai.koog.http.client.KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }
        return DoubleEncodingFixHttpClient(noop)
    }

    // ---------------------------------------------------------------
    // Core fix: double-encoded arguments are unwrapped to single-encoded
    //
    // Koog does TWO levels of string encoding:
    //   1. Json.encodeToString(String.serializer(), args) — wraps in quotes
    //   2. Request serializer encodes the String property — wraps in quotes again
    //
    // So the JSON text has: "arguments": "\"{\"url\":\"...\"}\""
    // fixRequestBody strips one layer → "arguments": "{\"url\":\"...\"}"
    // This single-encoded form is what the proxy accepts.
    // ---------------------------------------------------------------

    @Test
    fun `unwraps double-encoded tool call arguments`() {
        val inner = """{"url":"https://github.com/sharkdp/fd"}"""
        val encoded = jsonStr(jsonStr(inner)) // double-encoded

        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"parseGitHubUrl","arguments":$encoded},"type":"function"}]}]}"""

        val result = createFixer().fixRequestBody(input)

        // After fix: one encoding layer removed
        // Result should have single-encoded arguments
        val expectedArgs = jsonStr(inner)  // single-encoded form
        assertTrue(result.contains(expectedArgs),
            "Expected result to contain single-encoded args $expectedArgs, got: $result")
    }

    @Test
    fun `unwraps multiple double-encoded tool calls`() {
        val a1 = jsonStr(jsonStr("""{"owner":"sharkdp","repo":"fd"}"""))
        val a2 = jsonStr(jsonStr("""{"url":"https://example.com"}"""))
        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"f1","arguments":$a1},"type":"function"},{"id":"c2","function":{"name":"f2","arguments":$a2},"type":"function"}]}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains(""""arguments":${jsonStr("""{"owner":"sharkdp","repo":"fd"}""")}"""))
        assertTrue(result.contains(""""arguments":${jsonStr("""{"url":"https://example.com"}""")}"""))
    }

    @Test
    fun `unwraps double-encoded tool result content`() {
        val encoded = jsonStr(jsonStr("""{"owner":"sharkdp","repo":"fd"}"""))
        val input = """{"model":"test","messages":[{"role":"tool","content":$encoded,"tool_call_id":"c1"}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains(""""content":${jsonStr("""{"owner":"sharkdp","repo":"fd"}""")}"""),
            "Expected single-encoded content, got: $result")
    }

    @Test
    fun `unwraps double-encoded JSON array in arguments`() {
        val encoded = jsonStr(jsonStr("""["file1.exe","file2.exe"]"""))
        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"findExecutables","arguments":$encoded},"type":"function"}]}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains(""""arguments":${jsonStr("""["file1.exe","file2.exe"]""")}"""),
            "Expected single-encoded array, got: $result")
    }

    // ---------------------------------------------------------------
    // Non-double-encoded values pass through unchanged
    // ---------------------------------------------------------------

    @Test
    fun `preserves single-encoded string arguments`() {
        // Single encoding (what curl sends): arguments is a JSON string containing a JSON object
        val encoded = jsonStr("""{"url":"https://github.com/sharkdp/fd"}""")
        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"parseGitHubUrl","arguments":$encoded},"type":"function"}]}]}"""

        val result = createFixer().fixRequestBody(input)

        // Single-encoded string should pass through unchanged
        assertTrue(result.contains(""""arguments":$encoded"""))
    }

    @Test
    fun `preserves simple string tool content`() {
        val input = """{"model":"test","messages":[{"role":"tool","content":"hello world","tool_call_id":"c1"}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains(""""content":"hello world"""))
    }

    @Test
    fun `preserves system and user messages`() {
        val input = """{"model":"test","messages":[{"role":"system","content":"You are helpful."},{"role":"user","content":"Research fd"}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains("You are helpful."))
        assertTrue(result.contains("Research fd"))
    }

    @Test
    fun `preserves assistant text message without tool calls`() {
        val input = """{"model":"test","messages":[{"role":"assistant","content":"Done."}]}"""

        val result = createFixer().fixRequestBody(input)

        assertTrue(result.contains("Done."))
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    fun `returns invalid JSON unchanged`() {
        val input = "not json"
        assertEquals(input, createFixer().fixRequestBody(input))
    }

    @Test
    fun `returns JSON without messages unchanged`() {
        val input = """{"model":"test","stream":false}"""
        assertEquals(input, createFixer().fixRequestBody(input))
    }

    @Test
    fun `preserves string arguments that are not JSON objects or arrays`() {
        // Single-encoded "hello" — starts with quote but inner is not JSON
        val encoded = jsonStr("hello")
        val input = """{"model":"test","messages":[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"echo","arguments":$encoded},"type":"function"}]}]}"""

        val result = createFixer().fixRequestBody(input)

        // "hello" after unwrap attempt: starts with quote, but inner is not object/array → preserved
        assertTrue(result.contains(""""arguments":"hello"""))
    }
}
