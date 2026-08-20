package com.scoopai.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.WindowsAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 方案二：验证 Koog subgraphWithTask 在模型缺少 ToolChoice 能力、且 LLM 返回纯 Text（无 tool call）
 * 时会追加 "DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD." 用户消息并重试。
 *
 * 这模拟了 reasoningFallbackToContent 把 reasoning 文本写入 content 后的形状（parts 只有 Text）：
 * research 阶段第一次返回 Text-only，Koog 重试，第二次以 finalize tool call 收尾。
 *
 * 注意：ScoopManifestGenerator 内部使用 modelWithoutToolChoice（剥离 ToolChoice 能力），
 * 与 Koog 1.0.0 的 subgraphWithTask 重试路径一致，因此 mock 下可触发重试边。
 */
class ScoopManifestGeneratorRetryMockTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    /**
     * 包装一个 delegate [PromptExecutor]，记录每次 [execute] 请求中最后一条消息的文本内容，
     * 以便断言 Koog 在重试时实际发送了 "DO NOT CHAT WITH ME DIRECTLY" 重新提示。
     */
    private class CountingPromptExecutor(
        private val delegate: PromptExecutor,
    ) : PromptExecutor() {
        /** 每次 execute 调用时，请求中最后一条消息的纯文本（拼接所有 Text part）。 */
        val requestLastMessages = mutableListOf<String>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            requestLastMessages.add(prompt.messages.lastOrNull()?.textContent().orEmpty())
            return delegate.execute(prompt, model, tools)
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            delegate.moderate(prompt, model)

        override fun close() = delegate.close()
    }

    @Test
    fun `research phase retries once on text-only response then finalizes`() = runBlocking {
        // 模拟 reasoningFallbackToContent 改写后的形状：纯 Text（无 tool call）。
        val textOnlyReasoning = "Let me analyze the repository structure and identify the Windows assets to download."

        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    hash = "abc123",
                    extract_dir = "fd-v10.2.0-x86_64-pc-windows-msvc"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123","extract_dir":"fd-v10.2.0-x86_64-pc-windows-msvc"}},"bin":["fd.exe"]}"""

        val mockExecutor = getMockExecutor {
            // 第 2 次 research 调用：请求含 Koog 重提示 "DO NOT CHAT WITH ME DIRECTLY" -> finalize tool
            // 必须放在 RESEARCH phase 规则之前，因为第 2 次请求的会话中仍包含 "RESEARCH phase" 文本。
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "DO NOT CHAT WITH ME DIRECTLY"
            // 第 1 次 research 调用：纯 Text（无 tool call）-> Koog 追加重提示并重试
            mockLLMAnswer(textOnlyReasoning) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val counting = CountingPromptExecutor(mockExecutor)

        val model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash

        val generator = ScoopManifestGenerator(
            promptExecutor = counting,
            model = model,
            toolRegistry = ToolRegistry {},
            maxIterations = 50,
        )

        val result = generator.generate("https://github.com/sharkdp/fd")

        // 整体流程完成且产出 manifest
        assertTrue(result.manifest.contains("10.2.0"), "Should produce manifest, got: ${result.manifest}")
        assertTrue(result.manifest.contains("fd.exe"), "Should contain bin entry, got: ${result.manifest}")

        // research 阶段恰好 2 次 LLM 调用（第 1 次 Text-only，第 2 次 finalize tool）
        val researchCalls = counting.requestLastMessages.filter {
            it.contains("RESEARCH phase") || it.contains("DO NOT CHAT WITH ME DIRECTLY")
        }
        assertEquals(
            2,
            researchCalls.size,
            "research phase should have exactly 2 LLM calls (text-only then retry), got: $researchCalls"
        )
        // 第 2 次 research 请求携带 Koog 重提示，是 Koog retry 的铁证
        assertTrue(
            researchCalls[1].contains("DO NOT CHAT WITH ME DIRECTLY"),
            "2nd research request should contain Koog re-prompt, got: ${researchCalls[1]}"
        )
    }
}
