package com.scoopai.tools.llm

import ai.koog.agents.core.tools.ToolRegistry
import com.scoopai.core.ScoopManifestGenerator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan 1: stub OpenAI gateway integration test.
 *
 * Uses the JDK built-in [HttpServer] to run a "scripted" OpenAI chat-completions gateway
 * on a random 127.0.0.1 port (zero new dependencies), so the real
 * [LlmProviderFactory.create]("openai-compatible", ...) walks the full chain:
 *
 * ```
 * ScoopManifestGenerator (research -> analyze -> generate -> verify/review)
 *   -> OpenAILLMClient
 *     -> InterceptingHttpClient
 *        request side: stripToolChoice (unconditionally strips the tool_choice field)
 *        response side: reasoningFallbackToContent -> normalizeMissingCreated   (Layer A)
 *       -> JavaKoogHttpClient -> stub HTTP gateway
 * ```
 *
 * Deterministically triggers two layers of fixes:
 *
 * - **Layer A (reasoning rewrite + created backfill)**: the first research response is a
 *   "reasoning-only" response (`content: null`, no `tool_calls`, reasoning text in
 *   `reasoning_content`, and **deliberately missing the `created` field**).
 *   - Without `normalizeMissingCreated`: Koog's decoding of `OpenAIChatCompletionResponse`
 *     throws `MissingFieldException` for the missing non-null `created` field.
 *   - Without `reasoningFallbackToContent`: Koog only produces
 *     `parts=[MessagePart.Reasoning]`; neither of `subgraphWithTask`'s `onToolCalls` /
 *     `onTextMessage` edges matches and it throws
 *     "Unhandled response from LLM. Subgraph with task must always call tools".
 *   If either fix regresses, this test's first assertion (manifest produced) fails.
 *
 * - **Layer B (Koog built-in retry)**: after Layer A writes reasoning into `content`, Koog
 *   goes through `handleAssistantMessage` (the model lacks the `ToolChoice` capability,
 *   guaranteed by `ScoopManifestGenerator.modelWithoutToolChoice`), appends the
 *   "# DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD." user message and retries.
 *   The 2nd research request carries that re-prompt and a larger message count, i.e. hard
 *   proof of Layer B.
 *
 * Fully offline: `gitHubApi` / `archiveInspector` / `manifestIo` are not passed,
 * `executePrefetch` returns an empty `PrefetchResult` when `gitHubApi == null`.
 */
class ReasoningRetryStubGatewayTest {

    private companion object {
        /** openai-compatible capabilities are unified: no DeepSeek name special-case; requests unconditionally strip tool_choice. */
        const val MODEL_ID = "opencode/deepseek-v4-flash"
        const val KOOG_RETRY_PROMPT = "DO NOT CHAT WITH ME DIRECTLY"
        const val FINISH_TOOL = "finalize_task_result"

        const val RESEARCH_MARKER = "RESEARCH phase"
        const val ANALYZE_MARKER = "ANALYSIS phase"
        const val GENERATE_MARKER = "GENERATE phase"

        const val REASONING_TEXT = "Let me think about this repo..."

        val RESEARCH_RESULT_JSON = """
            {"result":{"status":"ok","project":"sharkdp/fd","description":"A simple, fast alternative to find",
            "homepage":"https://github.com/sharkdp/fd","version":"10.2.0","suggested_app_name":"fd-stub",
            "windows_assets":[{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
            "filename":"fd-v10.2.0-x86_64-pc-windows-msvc.zip","architecture":"64bit","asset_type":"zip",
            "source":"release_api","hash":"abc123"}]}}
        """.trimIndent().replace("\n", "")

        val ANALYZE_RESULT_JSON = """
            {"result":{"status":"ok","strategy":"portable_zip",
            "entries":[{"architecture":"64bit","url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
            "hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}],"bin":["fd.exe"]}}
        """.trimIndent().replace("\n", "")

        /** generate-phase FinishTool's Output is String, so result must be a "stringified manifest". */
        const val MANIFEST_JSON =
            """{"version":"10.2.0","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}},"bin":["fd.exe"]}"""

        val json = Json { prettyPrint = false }
    }

    private var gateway: StubOpenAiGateway? = null

    @AfterTest
    fun tearDown() {
        gateway?.stop()
        gateway = null
    }

    // ---------------------------------------------------------------
    // Layer A (reasoning rewrite + created backfill) + Layer B (Koog retry) full chain
    // ---------------------------------------------------------------

    @Test
    fun `reasoning-only response is rewritten and Koog retry drives the full 3-phase flow`() = runBlocking {
        val stub = StubOpenAiGateway().also { gateway = it }
        stub.start()

        val generator = ScoopManifestGenerator(
            promptExecutor = LlmProviderFactory.create(
                provider = "openai-compatible",
                apiKey = "sk-test",
                baseUrl = stub.baseUrl,
                modelName = MODEL_ID,
            ),
            model = LlmProviderFactory.defaultModel(
                provider = "openai-compatible",
                modelName = MODEL_ID,
                baseUrl = stub.baseUrl,
            ),
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // -- 1. Full chain succeeds: any Layer A regression throws during the research phase --------
        assertTrue(
            result.manifest.contains("10.2.0"),
            "manifest should contain the version. manifest=${result.manifest}${stub.dump()}",
        )
        assertTrue(
            result.manifest.contains("fd.exe"),
            "manifest should contain the bin entry. manifest=${result.manifest}${stub.dump()}",
        )
        assertEquals("fd-stub", result.suggestedAppName, "suggestedAppName should come from the stub ResearchOutput")

        // The stub really served a "reasoning-only + missing created" response (prevents the path from being silently skipped)
        assertEquals(
            1,
            stub.reasoningOnlyServed.get(),
            "stub must have served exactly one reasoning-only response (no created field)${stub.dump()}",
        )

        // -- 2. Request counts: research x2 + analyze + generate + review -----------
        assertTrue(
            stub.requests.size >= 5,
            "expected at least 5 gateway requests (research x2, analyze, generate, review), got ${stub.requests.size}${stub.dump()}",
        )

        // -- 3. Layer B hard proof: the 2nd request carries the Koog re-prompt and a longer history ---------------
        assertFalse(
            KOOG_RETRY_PROMPT in stub.requests[0],
            "first request must not contain the Koog re-prompt${stub.dump()}",
        )
        assertTrue(
            KOOG_RETRY_PROMPT in stub.requests[1],
            "second request must contain the Koog re-prompt ($KOOG_RETRY_PROMPT)${stub.dump()}",
        )
        assertTrue(
            stub.messageCounts[1] > stub.messageCounts[0],
            "retry request should carry a longer history: ${stub.messageCounts[1]} vs ${stub.messageCounts[0]}${stub.dump()}",
        )

        // -- 4. The 2nd request really is the research-phase retry ------------------------
        assertTrue(
            RESEARCH_MARKER in stub.requests[1],
            "second request should still carry the RESEARCH phase context${stub.dump()}",
        )
        assertTrue(
            KOOG_RETRY_PROMPT in stub.lastMessages[1],
            "the retry re-prompt must be the last message of the second request${stub.dump()}",
        )

        // -- 5. Phase order: research, research-retry, analyze, generate, review -
        assertEquals(
            listOf("research", "research-retry", "analyze", "generate", "review"),
            stub.servedRoutes.take(5),
            "unexpected phase routing${stub.dump()}",
        )

        // -- 6. The request-side interceptor chain works too: the tool_choice field is stripped entirely ------------
        // subgraphWithTask calls setToolChoiceRequired(); stripToolChoice (unconditional
        // interception) deletes the tool_choice field from the request body to keep the gateway from erroring.
        assertFalse(
            "\"tool_choice\"" in stub.requests[0],
            "stripToolChoice should have removed the tool_choice field entirely${stub.dump()}",
        )

        // -- 7. Double-encoding regression: Koog 1.1.1 (#2096) fixed the double
        //        encoding of tool call arguments. With the fixDoubleEncoding interceptor removed,
        //        the body the gateway receives must stay single-encoded. Double encoding produces
        //        an escaped quote at the start of the arguments string:
        //        correct: "arguments":"{...}"    wrong: "arguments":"\"{...}"
        val toolCallRequests = stub.requests.filter { "\"arguments\"" in it }
        assertTrue(
            toolCallRequests.isNotEmpty(),
            "expected at least one request carrying tool call arguments${stub.dump()}",
        )
        for ((index, req) in toolCallRequests.withIndex()) {
            assertFalse(
                "\"arguments\":\"\\\"" in req,
                "request $index must not contain double-encoded tool call arguments " +
                    "(Koog 1.1.1 #2096 fix). body=${req.take(400)}${stub.dump()}",
            )
        }
    }

    // ---------------------------------------------------------------
    // malformed_function_call recovery: malformedFunctionCallRetryHint injects content
    // -> Koog retry loop kicks in -> full chain succeeds
    // ---------------------------------------------------------------

    @Test
    fun `malformed function call is rewritten and Koog retry drives the full 3-phase flow`() = runBlocking {
        val stub = StubOpenAiGateway(firstResearch = FirstResearch.Malformed).also { gateway = it }
        stub.start()

        val generator = ScoopManifestGenerator(
            promptExecutor = LlmProviderFactory.create(
                provider = "openai-compatible",
                apiKey = "sk-test",
                baseUrl = stub.baseUrl,
                modelName = MODEL_ID,
            ),
            model = LlmProviderFactory.defaultModel(
                provider = "openai-compatible",
                modelName = MODEL_ID,
                baseUrl = stub.baseUrl,
            ),
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // -- 1. Full chain succeeds: any malformed rewrite regression throws during research --
        assertTrue(
            result.manifest.contains("10.2.0"),
            "manifest should contain the version. manifest=${result.manifest}${stub.dump()}",
        )
        assertEquals("fd-stub", result.suggestedAppName, "suggestedAppName should come from the stub ResearchOutput")

        // The stub really served a "malformed_function_call + missing created" response (prevents the path from being silently skipped)
        assertEquals(
            1,
            stub.malformedServed.get(),
            "stub must have served exactly one malformed_function_call response${stub.dump()}",
        )

        // -- 2. Layer B hard proof: the 2nd request carries the Koog re-prompt (after the
        //       malformed rewrite injects content as Text, Koog goes through the handleAssistantMessage retry loop) ----
        assertTrue(
            KOOG_RETRY_PROMPT in stub.requests[1],
            "second request must contain the Koog re-prompt ($KOOG_RETRY_PROMPT)${stub.dump()}",
        )
        assertFalse(
            KOOG_RETRY_PROMPT in stub.requests[0],
            "first request must not contain the Koog re-prompt${stub.dump()}",
        )

        // -- 3. Phase order: research, research-retry, analyze, generate, review --
        assertEquals(
            listOf("research", "research-retry", "analyze", "generate", "review"),
            stub.servedRoutes.take(5),
            "unexpected phase routing${stub.dump()}",
        )
    }

    // ===============================================================
    // Stub OpenAI chat-completions gateway
    // ===============================================================

    /** Which "empty assistant response" the first research response should simulate. */
    private enum class FirstResearch { Reasoning, Malformed }

    /**
     * Scripted stub gateway: dispatches responses by each request's **last message**.
     *
     * Why the last message rather than the whole body: Koog's prompt history accumulates
     * across subgraphs, so analyze / generate request bodies still contain the
     * "RESEARCH phase" and re-prompt texts.
     */
    private class StubOpenAiGateway(
        private val firstResearch: FirstResearch = FirstResearch.Reasoning,
    ) {

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** Full body of each request (in arrival order). */
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** JSON text of each request's last message. */
        val lastMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** messages array length of each request. */
        val messageCounts: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        /** Branch name hit by each request. */
        val servedRoutes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Number of "reasoning-only + missing created" responses served. */
        val reasoningOnlyServed = AtomicInteger(0)

        /** Number of "malformed_function_call + missing created" responses served. */
        val malformedServed = AtomicInteger(0)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        /**
         * The gateway's request executor. Kept as a field so [stop] can
         * shut it down explicitly: an executor owning a non-daemon thread
         * would otherwise keep the JVM alive after the test finishes.
         */
        private var executor: ExecutorService? = null

        /** Whether the first research has been served (both scenarios share this counter). */
        private val researchFirstServed = AtomicInteger(0)

        fun start() {
            server.createContext("/v1/chat/completions", ::handle)
            // Daemon thread: never blocks JVM exit, and stop() additionally
            // calls shutdownNow() to release the thread promptly.
            executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
            server.executor = executor
            server.start()
        }

        fun stop() {
            server.stop(0)
            executor?.shutdownNow()
            executor = null
        }

        /** Diagnostic output on failure. */
        fun dump(): String = buildString {
            appendLine()
            appendLine("--- stub gateway requests (${requests.size}) ---")
            servedRoutes.forEachIndexed { index, route ->
                appendLine("[$index] route=$route messages=${messageCounts.getOrNull(index)}")
                appendLine("      last=${lastMessages.getOrNull(index)?.take(300)}")
            }
        }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += body

            val messages = runCatching {
                json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            }.getOrNull()
            messageCounts += messages?.size ?: 0
            val lastMessage = messages?.lastOrNull()?.toString().orEmpty()
            lastMessages += lastMessage

            val response = when {
                // research retry (Koog's appended re-prompt is the last message) -> normal tool_calls
                KOOG_RETRY_PROMPT in lastMessage -> {
                    servedRoutes += "research-retry"
                    toolCallResponse("chatcmpl-research-retry", RESEARCH_RESULT_JSON)
                }
                // research first call -> serve the scenario's "empty assistant response", deliberately missing created
                RESEARCH_MARKER in lastMessage && researchFirstServed.get() == 0 -> {
                    servedRoutes += "research"
                    researchFirstServed.incrementAndGet()
                    when (firstResearch) {
                        FirstResearch.Malformed -> {
                            malformedServed.incrementAndGet()
                            malformedFunctionCallResponse()
                        }
                        FirstResearch.Reasoning -> {
                            reasoningOnlyServed.incrementAndGet()
                            reasoningOnlyResponse()
                        }
                    }
                }
                // Fallback: when research is asked again, finalize directly to avoid an infinite loop
                RESEARCH_MARKER in lastMessage -> {
                    servedRoutes += "research-extra"
                    toolCallResponse("chatcmpl-research-extra", RESEARCH_RESULT_JSON)
                }
                ANALYZE_MARKER in lastMessage -> {
                    servedRoutes += "analyze"
                    toolCallResponse("chatcmpl-analyze", ANALYZE_RESULT_JSON)
                }
                GENERATE_MARKER in lastMessage -> {
                    servedRoutes += "generate"
                    toolCallResponse(
                        "chatcmpl-generate",
                        json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject { put("result", MANIFEST_JSON) },
                        ),
                    )
                }
                // review agent (no tools, a plain-text JSON body suffices)
                else -> {
                    servedRoutes += "review"
                    textResponse("chatcmpl-review", """{"approved":true}""")
                }
            }

            val bytes = response.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        // -- Response construction (strict OpenAI chat.completion shape) ------------------

        /**
         * Reasoning-only response: `content: null`, no `tool_calls`, reasoning in
         * `reasoning_content`, `finish_reason = "stop"`, **no created**
         * (hits reasoningFallbackToContent + normalizeMissingCreated in one shot).
         */
        private fun reasoningOnlyResponse(): String = chatCompletion(
            id = "chatcmpl-research-reasoning",
            finishReason = "stop",
            includeCreated = false,
        ) {
            put("role", "assistant")
            put("content", JsonNull)
            put("reasoning_content", REASONING_TEXT)
        }

        /**
         * malformed_function_call response: `content: null`, no `tool_calls`, no reasoning,
         * `finish_reason = "malformed_function_call"`, **no created**
         * (hits malformedFunctionCallRetryHint + normalizeMissingCreated in one shot).
         */
        private fun malformedFunctionCallResponse(): String = chatCompletion(
            id = "chatcmpl-research-malformed",
            finishReason = "malformed_function_call",
            includeCreated = false,
        ) {
            put("role", "assistant")
            put("content", JsonNull)
        }

        /** Normal tool_calls response: arguments is a "stringified arguments object". */
        private fun toolCallResponse(id: String, argumentsJson: String): String = chatCompletion(
            id = id,
            finishReason = "tool_calls",
            includeCreated = true,
        ) {
            put("role", "assistant")
            put("content", JsonNull)
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("id", "call_$id")
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", FINISH_TOOL)
                        put("arguments", argumentsJson)
                    }
                }
            }
        }

        /** Plain text response (review agent). */
        private fun textResponse(id: String, content: String): String = chatCompletion(
            id = id,
            finishReason = "stop",
            includeCreated = true,
        ) {
            put("role", "assistant")
            put("content", content)
        }

        private fun chatCompletion(
            id: String,
            finishReason: String,
            includeCreated: Boolean,
            message: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ): String {
            val root: JsonElement = buildJsonObject {
                put("id", id)
                put("object", "chat.completion")
                put("model", MODEL_ID)
                // created is only provided in non-research-first responses; when missing,
                // normalizeMissingCreated backfills 0, otherwise Koog decoding throws MissingFieldException directly.
                if (includeCreated) put("created", 1_700_000_000L)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        put("finish_reason", finishReason)
                        putJsonObject("message", message)
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 128)
                    put("completion_tokens", 32)
                    put("total_tokens", 160)
                }
            }
            return json.encodeToString(JsonElement.serializer(), root)
        }
    }
}
