package com.scoopai.core.llm

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.FinalizePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Thin assembly-semantics tests for the KoogCompat seam only. Deep behavior
 * tests stay with the owned implementations (RequestInterceptorsTest,
 * ResponseInterceptorsTest, LenientStringFinishToolTest, stub gateway).
 */
class KoogCompatTest {

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    private val retryHint =
        "My previous function call was malformed. I will retry the tool call with valid JSON arguments."

    private val toolChoiceRequest =
        """{"model":"test-model","tool_choice":"required","messages":[]}"""

    private val malformedNoCreatedResponse =
        """{"id":"chatcmpl-1","model":"test-model","choices":[{"index":0,"finish_reason":"malformed_function_call","message":{"role":"assistant","content":null}}]}"""

    private val reasoningNoCreatedResponse =
        """{"id":"chatcmpl-2","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"thinking"}}]}"""

    private class CapturingHttpClient(
        private val postResponse: String,
        private val onPostBody: (String) -> Unit = {},
    ) : KoogHttpClient {
        override val clientName: String = "capturing"
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
        ): R {
            if (requestBodyType == String::class) onPostBody(requestBody as String)
            return postResponse as R
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
        ): Flow<O> = emptyFlow()

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = emptyFlow()
    }

    private fun contentOf(rawJson: String): String? {
        val message = Json.parseToJsonElement(rawJson).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val content = message?.get("content")
        if (content == null || content is JsonNull) return null
        return (content as? JsonPrimitive)?.content
    }

    private fun createdOf(rawJson: String): Long? =
        Json.parseToJsonElement(rawJson).jsonObject["created"]
            ?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }

    // ---------------------------------------------------------------
    // decorateHttpClient: assembly semantics
    // ---------------------------------------------------------------

    @Test
    fun `decorateHttpClient without openaiCompatibleOnly leaves requests and responses untouched`() = runBlocking {
        var seenBody: String? = null
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = reasoningNoCreatedResponse) { seenBody = it },
        )

        val out = client.post("chat/completions", toolChoiceRequest, String::class, String::class, emptyMap(), emptyMap())

        // Default retry-only decoration: no workaround chain.
        assertEquals(toolChoiceRequest, seenBody, "tool_choice must not be stripped without openaiCompatibleOnly")
        assertEquals(reasoningNoCreatedResponse, out, "response must not be rewritten without openaiCompatibleOnly")
    }

    @Test
    fun `decorateHttpClient with openaiCompatibleOnly strips tool_choice from requests`() = runBlocking {
        var seenBody: String? = null
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = "{}") { seenBody = it },
            openaiCompatibleOnly = true,
        )

        client.post("chat/completions", toolChoiceRequest, String::class, String::class, emptyMap(), emptyMap())

        assertNotNull(seenBody)
        assertTrue(!seenBody!!.contains("tool_choice"), "tool_choice must be stripped, got: $seenBody")
    }

    @Test
    fun `decorateHttpClient with openaiCompatibleOnly rewrites responses in factory order`() = runBlocking {
        // malformed_function_call body without created: exercises all three
        // rewriters in one pass (reasoning fallback is a no-op here, malformed
        // hint injects content, created is filled last).
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = malformedNoCreatedResponse),
            openaiCompatibleOnly = true,
        )

        val out = client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())

        assertEquals(retryHint, contentOf(out), "malformed hint must be injected into content")
        assertEquals(0L, createdOf(out), "missing created must be filled")
    }

    @Test
    fun `decorateHttpClient maps reasoning into content on openai-compatible chains`() = runBlocking {
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = reasoningNoCreatedResponse),
            openaiCompatibleOnly = true,
        )

        val out = client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())

        assertEquals("thinking", contentOf(out))
        assertEquals(0L, createdOf(out))
    }

    @Test
    fun `decorateHttpClient applies extra request interceptors after stripToolChoice`() = runBlocking {
        var extraSaw: String? = null
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = "{}"),
            openaiCompatibleOnly = true,
            extraRequestInterceptors = listOf { body -> extraSaw = body; body },
        )

        client.post("chat/completions", toolChoiceRequest, String::class, String::class, emptyMap(), emptyMap())

        // Mirrors the live factory order: stripToolChoice runs first, debug
        // logging (extra interceptor) sees the already-stripped body.
        assertNotNull(extraSaw)
        assertTrue(!extraSaw!!.contains("tool_choice"), "extra interceptors must run after stripToolChoice, got: $extraSaw")
    }

    @Test
    fun `decorateHttpClient passes onResponse the rewritten body`() = runBlocking {
        var observed: String? = null
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = malformedNoCreatedResponse),
            openaiCompatibleOnly = true,
            onResponse = { observed = it },
        )

        client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())

        // Debug logging observes the rewritten body (hint injected, created filled).
        assertEquals(retryHint, contentOf(requireNotNull(observed)))
        assertEquals(0L, createdOf(requireNotNull(observed)))
    }

    @Test
    fun `decorateHttpClient keeps the retry config on the decorated client`() {
        val config = HttpRetryConfig(maxRetries = 7, initialDelayMs = 1L)
        val client = KoogCompat.decorateHttpClient(
            delegate = CapturingHttpClient(postResponse = "{}"),
            retryConfig = config,
        )
        assertEquals(config, client.retryConfig)
    }

    // ---------------------------------------------------------------
    // decorateModel: runtime ToolChoice strip
    // ---------------------------------------------------------------

    @Test
    fun `decorateModel strips only the ToolChoice capability`() {
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = "test-model",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.ToolChoice,
                LLMCapability.Tools,
                LLMCapability.Completion,
            ),
        )

        val decorated = KoogCompat.decorateModel(model, stripToolChoice = true)

        assertTrue(decorated.capabilities?.contains(LLMCapability.ToolChoice) != true)
        assertEquals(
            listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Completion),
            decorated.capabilities,
        )
    }

    @Test
    fun `decorateModel preserves provider and id`() {
        val model = LLModel(
            provider = LLMProvider.DeepSeek,
            id = "deepseek-chat",
            capabilities = listOf(LLMCapability.ToolChoice),
        )

        val decorated = KoogCompat.decorateModel(model, stripToolChoice = true)

        assertEquals(LLMProvider.DeepSeek, decorated.provider)
        assertEquals("deepseek-chat", decorated.id)
    }

    @Test
    fun `decorateModel with stripToolChoice false returns the same instance`() {
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = "test-model",
            capabilities = listOf(LLMCapability.ToolChoice),
        )

        assertSame(model, KoogCompat.decorateModel(model, stripToolChoice = false))
    }

    @Test
    fun `decorateModel tolerates null capabilities`() {
        val model = LLModel(provider = LLMProvider.OpenAI, id = "test-model")

        val decorated = KoogCompat.decorateModel(model, stripToolChoice = true)

        assertEquals(model, decorated)
    }

    // ---------------------------------------------------------------
    // finishTool: lenient FinalizePayload finish tool
    // ---------------------------------------------------------------

    @OptIn(InternalAgentToolsApi::class)
    @Test
    fun `finishTool exposes the finalize_task_result contract`() {
        val tool: Tool<FinalizePayload, FinalizePayload> = KoogCompat.finishTool

        assertEquals(SubgraphWithTaskUtils.FINALIZE_SUBGRAPH_TOOL_NAME, tool.descriptor.name)
        assertEquals("result", tool.descriptor.requiredParameters.single().name)
        assertTrue(tool.descriptor.optionalParameters.map { it.name }.containsAll(listOf("explanation", "answered", "activities")))
    }

    @Test
    fun `finishTool returns a fresh instance per access`() {
        // Each subgraph must own its instance; a shared mutable tool across
        // concurrent agents would be risky.
        assertTrue(KoogCompat.finishTool !== KoogCompat.finishTool)
    }

    @Test
    fun `finishTool decodes a bare object result leniently`() {
        // The regression that motivated the tool: Koog's FinishTool throws
        // IndexOutOfBoundsException(-1) here. The seam must serve the lenient
        // implementation.
        val tool = KoogCompat.finishTool
        val payload = FinalizePayload(manifest = "{}")
        val encoded = tool.encodeResult(payload, ai.koog.serialization.kotlinx.KotlinxSerializer())
        val decoded = tool.decodeResult(encoded, ai.koog.serialization.kotlinx.KotlinxSerializer())
        assertEquals(payload, decoded)
    }
}
