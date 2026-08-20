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
 * 方案一：stub OpenAI 网关集成测试。
 *
 * 用 JDK 内置 [HttpServer] 在 127.0.0.1 随机端口起一个"脚本式"的 OpenAI
 * chat-completions 网关（零新依赖），让真实的
 * [LlmProviderFactory.create]("openai-compatible", ...) 走完整链路：
 *
 * ```
 * ScoopManifestGenerator (research → analyze → generate → verify/review)
 *   → OpenAILLMClient
 *     → InterceptingHttpClient
 *        请求侧: stripToolChoice（无条件剥离 tool_choice 字段）
 *        响应侧: reasoningFallbackToContent → normalizeMissingCreated   (A 层)
 *       → JavaKoogHttpClient → stub HTTP 网关
 * ```
 *
 * 确定性触发两层修复：
 *
 * - **A 层（reasoning 改写 + created 补齐）**：research 阶段第一个响应是
 *   "纯推理"响应（`content: null`、无 `tool_calls`、reasoning 文本放在
 *   `reasoning_content`，且**故意缺 `created` 字段**）。
 *   - 若没有 `normalizeMissingCreated`：Koog 解码 `OpenAIChatCompletionResponse`
 *     会因缺少非空字段 `created` 抛 `MissingFieldException`。
 *   - 若没有 `reasoningFallbackToContent`：Koog 只会产出
 *     `parts=[MessagePart.Reasoning]`，`subgraphWithTask` 的 `onToolCalls` /
 *     `onTextMessage` 边都不匹配，直接抛
 *     `"Unhandled response from LLM. Subgraph with task must always call tools"`。
 *   两者任一失效，本测试的第 1 条断言（成功返回 manifest）就会失败。
 *
 * - **B 层（Koog 内建 retry）**：A 层把 reasoning 写进 `content` 后，Koog 走
 *   `handleAssistantMessage`（模型缺 `ToolChoice` 能力，由
 *   `ScoopManifestGenerator.modelWithoutToolChoice` 保证），追加
 *   `"# DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD."` 用户消息后重试。
 *   第 2 个 research 请求携带该重提示且 messages 数量增加，即 B 层铁证。
 *
 * 完全离线：不传 `gitHubApi` / `archiveInspector` / `manifestIo`，
 * `executePrefetch` 在 `gitHubApi == null` 时直接返回空 `PrefetchResult`。
 */
class ReasoningRetryStubGatewayTest {

    private companion object {
        /** openai-compatible 能力已统一：不再按模型名特判 DeepSeek；请求侧无条件 strip tool_choice。 */
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

        /** generate 阶段 FinishTool 的 Output 是 String → result 必须是"字符串化的 manifest"。 */
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
    // A 层（reasoning 改写 + created 补齐）+ B 层（Koog retry）全链路
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

        // -- 1. 全链路成功：A 层任一环节失效都会在 research 阶段抛异常 --------
        assertTrue(
            result.manifest.contains("10.2.0"),
            "manifest should contain the version. manifest=${result.manifest}${stub.dump()}",
        )
        assertTrue(
            result.manifest.contains("fd.exe"),
            "manifest should contain the bin entry. manifest=${result.manifest}${stub.dump()}",
        )
        assertEquals("fd-stub", result.suggestedAppName, "suggestedAppName should come from the stub ResearchOutput")

        // stub 真的发出了"纯推理 + 缺 created"响应（防止路径被静默跳过）
        assertEquals(
            1,
            stub.reasoningOnlyServed.get(),
            "stub must have served exactly one reasoning-only response (no created field)${stub.dump()}",
        )

        // -- 2. 请求数量：research×2 + analyze + generate + review -----------
        assertTrue(
            stub.requests.size >= 5,
            "expected at least 5 gateway requests (research x2, analyze, generate, review), got ${stub.requests.size}${stub.dump()}",
        )

        // -- 3. B 层铁证：第 2 个请求携带 Koog 重提示且历史更长 ---------------
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

        // -- 4. 第 2 个请求确实是 research 阶段的重试 ------------------------
        assertTrue(
            RESEARCH_MARKER in stub.requests[1],
            "second request should still carry the RESEARCH phase context${stub.dump()}",
        )
        assertTrue(
            KOOG_RETRY_PROMPT in stub.lastMessages[1],
            "the retry re-prompt must be the last message of the second request${stub.dump()}",
        )

        // -- 5. 阶段顺序：research, research-retry, analyze, generate, review -
        assertEquals(
            listOf("research", "research-retry", "analyze", "generate", "review"),
            stub.servedRoutes.take(5),
            "unexpected phase routing${stub.dump()}",
        )

        // -- 6. 请求侧拦截器链也生效：tool_choice 字段被整体剥离 ------------
        // subgraphWithTask 调用 setToolChoiceRequired()，stripToolChoice（无条件
        // 拦截）把请求体里的 tool_choice 字段整个删掉，避免网关报错。
        assertFalse(
            "\"tool_choice\"" in stub.requests[0],
            "stripToolChoice should have removed the tool_choice field entirely${stub.dump()}",
        )

        // -- 7. 双重编码回归：Koog 1.1.1 (#2096) 修复了 tool call arguments 的
        //        二次编码。删除 fixDoubleEncoding 拦截器后，网关收到的请求体应当
        //        保持单编码。双重编码会在 arguments 字符串开头产生转义引号：
        //        正确: "arguments":"{...}"    错误: "arguments":"\"{...}"
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

    // ===============================================================
    // Stub OpenAI chat-completions 网关
    // ===============================================================

    /**
     * 脚本式 stub 网关：按每个请求的**最后一条消息**分发响应。
     *
     * 之所以看最后一条消息而不是整个 body：Koog 的 prompt 历史跨 subgraph 累积，
     * analyze / generate 请求的 body 里仍然含有 "RESEARCH phase" 与重提示文本。
     */
    private class StubOpenAiGateway {

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** 每个请求的完整 body（按到达顺序）。 */
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每个请求最后一条消息的 JSON 文本。 */
        val lastMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每个请求的 messages 数组长度。 */
        val messageCounts: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        /** 每个请求命中的分支名。 */
        val servedRoutes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 已发出的"纯推理 + 缺 created"响应数量。 */
        val reasoningOnlyServed = AtomicInteger(0)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        /**
         * The gateway's request executor. Kept as a field so [stop] can
         * shut it down explicitly: an executor owning a non-daemon thread
         * would otherwise keep the JVM alive after the test finishes.
         */
        private var executor: ExecutorService? = null

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

        /** 失败时的诊断输出。 */
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
                // research 重试（Koog 追加的重提示是最后一条消息）→ 正常 tool_calls
                KOOG_RETRY_PROMPT in lastMessage -> {
                    servedRoutes += "research-retry"
                    toolCallResponse("chatcmpl-research-retry", RESEARCH_RESULT_JSON)
                }
                // research 第一次 → 纯推理响应，且故意缺 created
                RESEARCH_MARKER in lastMessage && reasoningOnlyServed.get() == 0 -> {
                    servedRoutes += "research"
                    reasoningOnlyServed.incrementAndGet()
                    reasoningOnlyResponse()
                }
                // 兜底：research 再次被问到时直接 finalize，避免无限循环
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
                // review agent（无 tools，纯文本 JSON 即可）
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

        // -- 响应构造（严格 OpenAI chat.completion 形状） ------------------

        /**
         * 纯推理响应：`content: null`、无 `tool_calls`、reasoning 在
         * `reasoning_content`，`finish_reason = "stop"`，**不带 created**
         * （一次性命中 reasoningFallbackToContent + normalizeMissingCreated）。
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

        /** 正常 tool_calls 响应：arguments 是"字符串化的 arguments 对象"。 */
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

        /** 纯文本响应（review agent）。 */
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
                // created 只在非 research-first 响应中给出；缺失时由
                // normalizeMissingCreated 补 0，否则 Koog 解码直接抛 MissingFieldException。
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
