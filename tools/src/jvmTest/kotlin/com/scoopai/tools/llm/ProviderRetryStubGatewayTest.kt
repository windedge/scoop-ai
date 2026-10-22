package com.scoopai.tools.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior level test for the retry chain of the Anthropic and Ollama branches.
 *
 * Both providers build their HTTP client through their own Koog secondary
 * constructor, so the shared retry policy has to be applied in the
 * [KoogHttpClient.Factory] layer. The stub server answers the first two
 * requests with `500` and the third one with a valid provider response:
 *
 * ```
 * LlmProviderFactory.create("anthropic" | "ollama")
 *   -> AnthropicLLMClient / OllamaClient (secondary constructor)
 *      -> retrying factory -> InterceptingHttpClient -> JavaKoogHttpClient
 *         -> stub HTTP server (500, 500, 200)
 * ```
 *
 * Without the retry wrapper the third request would never happen and the
 * execution would fail with the provider exception.
 */
class ProviderRetryStubGatewayTest {

    private companion object {
        const val API_KEY = "sk-ant-test-key"
        const val FAILURES_BEFORE_SUCCESS = 2
    }

    private var server: StubProviderServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    // ---------------------------------------------------------------
    // anthropic
    // ---------------------------------------------------------------

    @Test
    fun `anthropic retries a 500 response and keeps the protocol headers`() = runBlocking {
        val stub = StubProviderServer("/v1/messages", anthropicResponse()).also { server = it }
        stub.start()

        val executor = LlmProviderFactory.create(
            provider = "anthropic",
            apiKey = API_KEY,
            baseUrl = stub.baseUrl,
        )

        val response = executor.execute(
            prompt = prompt("retry-test") { user("ping") },
            model = LlmProviderFactory.defaultModel("anthropic"),
        )

        val text = response.parts.filterIsInstance<MessagePart.Text>().single().text
        assertEquals("pong", text)
        assertEquals(FAILURES_BEFORE_SUCCESS + 1, stub.requestCount.get())

        val headers = stub.lastHeaders()
        assertEquals(API_KEY, headers["x-api-key"], "anthropic must send the key in the x-api-key header")
        assertEquals("2023-06-01", headers["anthropic-version"], "anthropic must send the protocol version header")
        assertNull(headers["authorization"], "anthropic must not send a bearer token")
    }

    // ---------------------------------------------------------------
    // ollama
    // ---------------------------------------------------------------

    @Test
    fun `ollama retries a 500 response and needs no authentication header`() = runBlocking {
        val stub = StubProviderServer("/api/chat", ollamaResponse()).also { server = it }
        stub.start()

        val executor = LlmProviderFactory.create(
            provider = "ollama",
            apiKey = "",
            baseUrl = stub.baseUrl,
        )

        val response = executor.execute(
            prompt = prompt("retry-test") { user("ping") },
            model = LlmProviderFactory.defaultModel("ollama"),
        )

        val text = response.parts.filterIsInstance<MessagePart.Text>().single().text
        assertEquals("pong", text)
        assertEquals(FAILURES_BEFORE_SUCCESS + 1, stub.requestCount.get())

        val headers = stub.lastHeaders()
        assertFalse(headers.containsKey("authorization"), "ollama must not send an authorization header")
        assertFalse(headers.containsKey("x-api-key"), "ollama must not send an api key header")
    }

    // ===============================================================
    // stub provider server
    // ===============================================================

    /** Stub endpoint that fails [FAILURES_BEFORE_SUCCESS] times before answering normally. */
    private class StubProviderServer(
        private val path: String,
        private val successBody: String,
    ) {
        private val httpServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { isDaemon = true }
        }
        private val receivedHeaders: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

        val requestCount = AtomicInteger(0)

        val baseUrl: String get() = "http://127.0.0.1:${httpServer.address.port}"

        fun start() {
            httpServer.createContext(path, ::handle)
            httpServer.executor = executor
            httpServer.start()
        }

        fun stop() {
            httpServer.stop(0)
            executor.shutdownNow()
        }

        fun lastHeaders(): Map<String, String> = receivedHeaders.last()

        private fun handle(exchange: HttpExchange) {
            exchange.requestBody.readBytes()
            receivedHeaders += exchange.requestHeaders
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            val attempt = requestCount.incrementAndGet()

            if (attempt <= FAILURES_BEFORE_SUCCESS) {
                val error = """{"error":"boom"}""".encodeToByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(500, error.size.toLong())
                exchange.responseBody.use { it.write(error) }
                return
            }

            val bytes = successBody.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun anthropicResponse(): String = """
        {"id":"msg_stub","type":"message","role":"assistant",
        "content":[{"type":"text","text":"pong"}],
        "model":"claude-opus-4-1","stop_reason":"end_turn",
        "usage":{"input_tokens":1,"output_tokens":1}}
    """.trimIndent().replace("\n", "")

    private fun ollamaResponse(): String = """
        {"model":"llama3.2:latest","message":{"role":"assistant","content":"pong"},
        "done":true,"prompt_eval_count":1,"eval_count":1}
    """.trimIndent().replace("\n", "")
}
