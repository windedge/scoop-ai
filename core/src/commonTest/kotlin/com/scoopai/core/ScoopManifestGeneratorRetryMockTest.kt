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
 * Plan 2: verifies that Koog's subgraphWithTask appends a
 * "DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD." user message and retries when
 * the model lacks the ToolChoice capability and the LLM returns pure Text (no tool call).
 *
 * This simulates the shape after reasoningFallbackToContent writes reasoning text into
 * content (parts contain only Text): the first research response is Text-only, Koog retries,
 * and the second one ends with a finalize tool call.
 *
 * Note: ScoopManifestGenerator internally uses modelWithoutToolChoice (strips the ToolChoice
 * capability), matching Koog 1.0.0's subgraphWithTask retry path, so the retry edge is
 * reachable under the mock.
 */
class ScoopManifestGeneratorRetryMockTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    /**
     * Wraps a delegate [PromptExecutor] and records the text of the last message of each
     * [execute] request, so tests can assert that Koog actually sent the
     * "DO NOT CHAT WITH ME DIRECTLY" re-prompt when retrying.
     */
    private class CountingPromptExecutor(
        private val delegate: PromptExecutor,
    ) : PromptExecutor() {
        /** Plain text of the last message in each execute request (concatenated Text parts). */
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
        // Simulates the shape after reasoningFallbackToContent rewrites: pure Text (no tool call).
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
            // 2nd research call: request contains the Koog re-prompt "DO NOT CHAT WITH ME DIRECTLY" -> finalize tool
            // Must be registered before the RESEARCH phase rule because the 2nd request's session still
            // contains the "RESEARCH phase" text.
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "DO NOT CHAT WITH ME DIRECTLY"
            // 1st research call: pure Text (no tool call) -> Koog appends the re-prompt and retries
            mockLLMAnswer(textOnlyReasoning) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("""{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}""") onCondition { text -> text.trim().startsWith("{") }
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

        // Full flow completes and produces a manifest
        assertTrue(result.manifest.contains("10.2.0"), "Should produce manifest, got: ${result.manifest}")
        assertTrue(result.manifest.contains("fd.exe"), "Should contain bin entry, got: ${result.manifest}")

        // Exactly 2 research LLM calls (1st Text-only, 2nd finalize tool)
        val researchCalls = counting.requestLastMessages.filter {
            it.contains("RESEARCH phase") || it.contains("DO NOT CHAT WITH ME DIRECTLY")
        }
        assertEquals(
            2,
            researchCalls.size,
            "research phase should have exactly 2 LLM calls (text-only then retry), got: $researchCalls"
        )
        // The 2nd research request carries the Koog re-prompt, hard proof of the Koog retry
        assertTrue(
            researchCalls[1].contains("DO NOT CHAT WITH ME DIRECTLY"),
            "2nd research request should contain Koog re-prompt, got: ${researchCalls[1]}"
        )
    }
}
