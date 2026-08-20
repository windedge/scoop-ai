package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseInterceptorsTest {

    private val json = Json

    private val createdZero: (String) -> String = { raw -> normalizeMissingCreated(raw, 0L) }

    private fun createdOf(rawJson: String): Long? =
        json.parseToJsonElement(rawJson).jsonObject["created"]?.jsonPrimitive?.content?.toLong()

    // ---------------------------------------------------------------
    // normalizeMissingCreated pure function
    // ---------------------------------------------------------------

    @Test
    fun `adds created to OpenAI-compatible chat completion response`() {
        val input = """{"id":"chatcmpl-test","model":"test-model","choices":[{"index":0}]}"""

        val result = normalizeMissingCreated(input, createdEpochSeconds = 0)

        assertEquals(0, createdOf(result))
    }

    @Test
    fun `replaces null created value`() {
        val input = """{"id":"chatcmpl-test","model":"test-model","created":null,"choices":[]}"""

        val result = normalizeMissingCreated(input, createdEpochSeconds = 0)

        assertEquals(0, createdOf(result))
    }

    @Test
    fun `preserves existing created value`() {
        val input = """{"id":"chatcmpl-test","model":"test-model","created":123,"choices":[]}"""

        val result = normalizeMissingCreated(input, createdEpochSeconds = 0)

        assertEquals(123, createdOf(result))
    }

    @Test
    fun `does not modify non chat completion responses`() {
        val input = """{"error":{"message":"bad request"}}"""

        assertEquals(input, normalizeMissingCreated(input, createdEpochSeconds = 0))
    }

    @Test
    fun `returns invalid JSON unchanged`() {
        val input = "not json"

        assertEquals(input, normalizeMissingCreated(input, createdEpochSeconds = 0))
    }

    // ---------------------------------------------------------------
    // reasoningFallbackToContent pure function
    // ---------------------------------------------------------------

    private fun contentOf(rawJson: String): String? {
        val choices = json.parseToJsonElement(rawJson).jsonObject["choices"]?.jsonArray ?: return null
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return null
        val contentElement = message["content"]
        // Mirror reasoningFallbackToContent semantics: JsonNull content is treated as absent.
        if (contentElement == null || contentElement is kotlinx.serialization.json.JsonNull) return null
        return (contentElement as? JsonPrimitive)?.content
    }

    @Test
    fun `reasoningFallbackToContent fills content from reasoning_content when content missing`() {
        val input = """{"id":"chatcmpl-test","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"let me think"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals("let me think", contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent prefers reasoning_content over reasoning and reasoningContent`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"rc","reasoning":"r","reasoningContent":"rc2"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals("rc", contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent falls back to reasoning when reasoning_content absent`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"thinking hard"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals("thinking hard", contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent falls back to reasoningContent last`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":null,"reasoningContent":"last resort"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals("last resort", contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent does not overwrite existing non-blank content`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":"real answer","reasoning_content":"ignore me"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals("real answer", contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent does not overwrite when tool_calls present`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","function":{"name":"f","arguments":"{}"}}],"reasoning_content":"ignore me"}}]}"""

        val result = reasoningFallbackToContent(input)

        assertEquals(null, contentOf(result))
    }

    @Test
    fun `reasoningFallbackToContent leaves invalid JSON unchanged`() {
        val input = "not json"

        assertEquals(input, reasoningFallbackToContent(input))
    }

    @Test
    fun `reasoningFallbackToContent leaves non chat completion responses unchanged`() {
        val input = """{"error":{"message":"bad request"}}"""

        assertEquals(input, reasoningFallbackToContent(input))
    }

    @Test
    fun `reasoningFallbackToContent leaves empty choices array unchanged`() {
        val input = """{"id":"chatcmpl-test","model":"test-model","choices":[]}"""

        assertEquals(input, reasoningFallbackToContent(input))
    }

    @Test
    fun `reasoningFallbackToContent leaves message without reasoning keys unchanged`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":null}}]}"""

        assertEquals(input, reasoningFallbackToContent(input))
    }

    @Test
    fun `reasoningFallbackToContent leaves JsonNull reasoning_content unchanged`() {
        // JsonNull submits an is JsonPrimitive and would otherwise be read as
        // the literal string "null" and written into content. It must be
        // treated as absent, matching the isNonBlankContent semantics of the
        // content field.
        val input = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":null}}]}"""

        assertEquals(input, reasoningFallbackToContent(input))
    }

    @Test
    fun `reasoningFallbackToContent leaves JsonNull reasoning and reasoningContent keys unchanged`() {
        val input = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":null,"reasoningContent":null}}]}"""

        assertEquals(input, reasoningFallbackToContent(input))
    }

    // ---------------------------------------------------------------
    // InterceptingHttpClient responseRewriter (post)
    // ---------------------------------------------------------------

    @Test
    fun `post returns rewritten JSON with created 0 when created missing`() = runBlocking {
        val input = """{"id":"chatcmpl-test","model":"test-model","choices":[]}"""
        val client = InterceptingHttpClient(
            delegate = FakeHttpClient(postResponse = input),
            responseRewriter = createdZero,
        )

        val result = client.post(
            path = "chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            responseType = String::class,
        )

        assertEquals(0, createdOf(result))
    }

    @Test
    fun `post preserves existing created value`() = runBlocking {
        val input = """{"id":"chatcmpl-test","model":"test-model","created":99,"choices":[]}"""
        val client = InterceptingHttpClient(
            delegate = FakeHttpClient(postResponse = input),
            responseRewriter = createdZero,
        )

        val result = client.post(
            path = "chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            responseType = String::class,
        )

        assertEquals(99, createdOf(result))
    }

    @Test
    fun `post invokes onResponse with rewritten body after rewriting`() = runBlocking {
        val input = """{"id":"chatcmpl-test","model":"test-model","choices":[]}"""
        var observed: String? = null
        val client = InterceptingHttpClient(
            delegate = FakeHttpClient(postResponse = input),
            responseRewriter = createdZero,
            onResponse = { observed = it },
        )

        client.post(
            path = "chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            responseType = String::class,
        )

        // Debug logging sees the rewritten body (created already filled in).
        assertEquals(0, createdOf(requireNotNull(observed)))
    }

    // ---------------------------------------------------------------
    // InterceptingHttpClient responseRewriter (sse)
    // ---------------------------------------------------------------

    @Test
    fun `sse rewrites every chunk before decode`() = runBlocking {
        val inputs = listOf(
            """{"id":"chunk-1","model":"test-model","choices":[]}""",
            """{"id":"chunk-2","model":"test-model","choices":[]}""",
        )
        val client = InterceptingHttpClient(
            delegate = FakeHttpClient(sseResponses = inputs),
            responseRewriter = createdZero,
        )

        val results = client.sse(
            path = "chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
        ).toList()

        assertEquals(listOf(0L, 0L), results.map { createdOf(it) })
    }

    @Test
    fun `sse invokes onResponse with rewritten chunk`() = runBlocking {
        val inputs = listOf(
            """{"id":"chunk-1","model":"test-model","choices":[]}""",
        )
        val observed = mutableListOf<String>()
        val client = InterceptingHttpClient(
            delegate = FakeHttpClient(sseResponses = inputs),
            responseRewriter = createdZero,
            onResponse = { observed.add(it) },
        )

        client.sse(
            path = "chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
        ).toList()

        assertEquals(listOf(0L), observed.map { createdOf(it) })
    }

    // ---------------------------------------------------------------
    // Fake delegate
    // ---------------------------------------------------------------

    private class FakeHttpClient(
        private val postResponse: String? = null,
        private val sseResponses: List<String> = emptyList(),
    ) : KoogHttpClient {
        override val clientName: String = "fake"
        override fun close() = Unit

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("not used")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = requireNotNull(postResponse) as R

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = flow {
            for (response in sseResponses) {
                if (dataFilter(response)) {
                    processStreamingChunk(decodeStreamingResponse(response))?.let { emit(it) }
                }
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = emptyFlow()
    }
}
