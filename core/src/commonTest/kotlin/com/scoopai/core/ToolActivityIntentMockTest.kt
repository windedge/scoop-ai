package com.scoopai.core

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.tools.getMockExecutor
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.VerifyInstallArgs
import com.scoopai.core.agent.VerifyInstallTool
import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.tools.api.ScoopVerifier
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool-call execution intent: when a mixed LLM response carries both text and
 * tool calls, the text is the model's self-declared intent for the upcoming
 * tool calls. The intent spans the whole LLM response batch: every Started and
 * Completed event of the tool calls in that batch carries the same intent, and
 * it stays pending until the next LLM response overwrites (or nulls) it. Pure
 * text responses (final answers / review verdicts) and tool calls without
 * preceding text must never produce an intent.
 *
 * Mock wiring follows the proven ScoopManifestGeneratorMockTest split: tool
 * calls use onRequestContains/onRequestEquals (partial/exact maps are consulted
 * before conditional ones), plain text answers use onCondition, and a mixed
 * response is simulated the way ScoopManifestGeneratorRetryMockTest does it:
 * register mockLLMAnswer(text) and mockLLMToolCall(...) under the same key so
 * the mock merges both into one assistant message with text + tool call parts.
 */
class ToolActivityIntentMockTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    private object DummyFetchRepoTool : ai.koog.agents.core.tools.SimpleTool<DummyFetchRepoTool.Args>(
        argsType = typeToken<DummyFetchRepoTool.Args>(),
        name = com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO,
        description = "Fetch repository info",
    ) {
        @Serializable
        data class Args(val repo: String)

        override suspend fun execute(args: Args): String = """{"name":"fd"}"""
    }

    private object DummyFetchLatestReleaseTool :
        ai.koog.agents.core.tools.SimpleTool<DummyFetchRepoTool.Args>(
            argsType = typeToken<DummyFetchRepoTool.Args>(),
            name = com.scoopai.core.agent.PhaseTools.FETCH_LATEST_RELEASE,
            description = "Fetch latest release",
        ) {
        override suspend fun execute(args: DummyFetchRepoTool.Args): String = """{"latest":"10.2.0"}"""
    }

    private fun researchResult() = ResearchOutput(
        status = ResearchStatus.OK,
        project = "sharkdp/fd",
        version = "10.2.0",
        suggested_app_name = "fd",
        windows_assets = listOf(
            WindowsAsset(
                url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                architecture = "64bit",
                asset_type = "zip",
                source = "release_api",
            )
        )
    )

    private fun analyzeResult() = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = listOf(
            com.scoopai.core.agent.AssetEntry(
                architecture = "64bit",
                url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                hash = "abc123",
            )
        ),
        bin = emptyList(),
    )

    private val manifestJson =
        """{"version":"10.2.0","architecture":{"64bit":{"url":"https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

    private val approvedReview =
        """{"approved":true,"issues":[],"summary":"No issues found. Manifest looks good."}"""

    /**
     * Standard three-phase + review mock wiring with the exact key combination
     * of the working mock tests: the research finalize call is keyed by the raw
     * fetch tool result JSON, analyze by the "ANALYSIS phase" transition prompt,
     * generate by "GENERATE phase", and the review verdict by its leading "{".
     */
    private fun standardExecutor(
        fetchTurn: ai.koog.agents.testing.tools.MockExecutorDSLBuilder.() -> Unit,
    ): ai.koog.prompt.executor.model.PromptExecutor = getMockExecutor {
        fetchTurn()
        mockLLMToolCall(researchFinishTool, researchResult()) onRequestContains "{\"name\":\"fd\"}"
        mockLLMToolCall(analyzeFinishTool, analyzeResult()) onRequestContains "ANALYSIS phase"
        mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
        // Review verdict: a pure text response must never leak into an intent.
        mockLLMAnswer(approvedReview) onCondition { text -> text.trim().startsWith("{") }
        mockLLMAnswer("").asDefaultResponse
    }

    private fun generator(
        promptExecutor: ai.koog.prompt.executor.model.PromptExecutor,
        activities: MutableList<ToolActivity>,
        scoopVerifier: ScoopVerifier? = null,
        tools: List<ai.koog.agents.core.tools.Tool<*, *>> = listOf(DummyFetchRepoTool),
    ) = ScoopManifestGenerator(
        promptExecutor = promptExecutor,
        model = ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash,
        toolRegistry = ToolRegistry { tools(tools) },
        maxIterations = 50,
        onToolActivity = { activities.add(it) },
        scoopVerifier = scoopVerifier,
    )

    // ---------------------------------------------------------------------
    // Mixed response (text + tool calls): text becomes the next Started intent
    // ---------------------------------------------------------------------

    @Test
    fun `mixed text plus tool call response attaches text as next Started intent`() = runBlocking {
        val intentText = "Fetching repo info to learn the license and homepage."
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            // Same key for answer + tool call: the mock merges both parts into
            // one assistant message, producing the mixed response under test.
            mockLLMAnswer(intentText) onRequestContains "RESEARCH phase"
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities).generate("https://github.com/sharkdp/fd")

        val fetchStarted = activities.filterIsInstance<ToolActivity.Started>()
            .first { it.toolName == com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO }
        assertEquals(intentText, fetchStarted.intent, "Intent text must ride on the next Started event")

        // One intent rides exactly one Started event. Note the finish tool is
        // executed directly by Koog's executeFinishTool and never emits a
        // Started event, so the fetch tool is the only Started in this run.
        val startedEvents = activities.filterIsInstance<ToolActivity.Started>()
        assertEquals(1, startedEvents.size, "Only the regular tool emits Started, not the finish tool")
        assertEquals(intentText, startedEvents.single().intent, "Intent is consumed by the first tool call")
    }

    @Test
    fun `intent longer than 200 chars is truncated`() = runBlocking {
        val longIntent = "I will fetch the repository metadata because ".repeat(10) + "end-marker"
        assertTrue(longIntent.length > 200)
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            mockLLMAnswer(longIntent) onRequestContains "RESEARCH phase"
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities).generate("https://github.com/sharkdp/fd")

        val fetchStarted = activities.filterIsInstance<ToolActivity.Started>()
            .first { it.toolName == com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO }
        val intent = requireNotNull(fetchStarted.intent)
        assertEquals(200, intent.length, "Intent must be truncated to 200 chars")
        assertTrue(intent.startsWith("I will fetch the repository metadata"))
    }

    @Test
    fun `pure text response produces no intent on subsequent tool call`() = runBlocking {
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities).generate("https://github.com/sharkdp/fd")

        val started = activities.filterIsInstance<ToolActivity.Started>().toList()
        assertTrue(started.isNotEmpty(), "Expected tool activity for the fetch tool")
        assertTrue(
            started.all { it.intent == null },
            "Pure-text responses must not produce intents, got: ${started.map { it.toolName to it.intent }}",
        )
    }

    @Test
    fun `tool call without preceding text has null intent`() = runBlocking {
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            // mockLLMToolCall without a paired answer: tool call only, no text part.
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities).generate("https://github.com/sharkdp/fd")

        val fetchStarted = activities.filterIsInstance<ToolActivity.Started>()
            .first { it.toolName == com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO }
        assertNull(fetchStarted.intent, "Tool call without preceding text must have null intent")
    }

    // ---------------------------------------------------------------------
    // Intent spans the whole LLM response batch (Started + Completed share it)
    // ---------------------------------------------------------------------

    @Test
    fun `Completed event carries the same intent as its Started event`() = runBlocking {
        val intentText = "Fetching repo info to learn the license and homepage."
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            mockLLMAnswer(intentText) onRequestContains "RESEARCH phase"
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities).generate("https://github.com/sharkdp/fd")

        val fetchEvents = activities.filter { it.toolName == com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO }
        val started = fetchEvents.filterIsInstance<ToolActivity.Started>().single()
        val completed = fetchEvents.filterIsInstance<ToolActivity.Completed>().single()
        assertEquals(intentText, started.intent, "Started must carry the batch intent")
        assertEquals(intentText, completed.intent, "Completed must carry the same batch intent")
    }

    @Test
    fun `two tool calls in one LLM response batch share the same intent on Started and Completed`() = runBlocking {
        val intentText = "Fetching the release metadata to pin the latest version."
        val activities = mutableListOf<ToolActivity>()
        val promptExecutor = standardExecutor {
            // One LLM response with two tool_calls: the mock emits both tool call
            // parts in a single assistant message for the shared key.
            mockLLMAnswer(intentText) onRequestContains "RESEARCH phase"
            mockLLMToolCall(
                listOf(
                    DummyFetchRepoTool to DummyFetchRepoTool.Args("sharkdp/fd"),
                    DummyFetchLatestReleaseTool to DummyFetchRepoTool.Args("sharkdp/fd"),
                ),
            ) onRequestContains "RESEARCH phase"
        }

        generator(promptExecutor, activities, tools = listOf(DummyFetchRepoTool, DummyFetchLatestReleaseTool))
            .generate("https://github.com/sharkdp/fd")

        val started = activities.filterIsInstance<ToolActivity.Started>().toList()
        val completed = activities.filterIsInstance<ToolActivity.Completed>()
            .filter { it.toolName != VerifyInstallTool.TOOL_NAME }
            .toList()
        assertEquals(
            setOf(com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO, com.scoopai.core.agent.PhaseTools.FETCH_LATEST_RELEASE),
            started.map { it.toolName }.toSet(),
            "Both batch tool calls must emit Started",
        )
        assertTrue(started.all { it.intent == intentText }, "Every Started in the batch carries the intent")
        assertEquals(2, completed.size, "Both batch tools must emit Completed")
        assertTrue(completed.all { it.intent == intentText }, "Every Completed in the batch carries the intent")
    }

    @Test
    fun `next LLM response without intent nulls Completed intent`() = runBlocking {
        val intentText = "Fetching the repo info to learn the license and homepage."
        val activities = mutableListOf<ToolActivity>()
        // Batch 1: intent text + fetch tool call. Batch 2 (keyed on the fetch
        // result): tool call only, NO text, so the pending intent from batch 1
        // must be gone: the latest-release events carry null intent.
        val promptExecutor = getMockExecutor {
            mockLLMAnswer(intentText) onRequestContains "RESEARCH phase"
            mockLLMToolCall(DummyFetchRepoTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "RESEARCH phase"
            mockLLMToolCall(DummyFetchLatestReleaseTool, DummyFetchRepoTool.Args("sharkdp/fd")) onRequestContains "{\"name\":\"fd\"}"
            mockLLMToolCall(researchFinishTool, researchResult()) onRequestContains "{\"latest\":\"10.2.0\"}"
            mockLLMToolCall(analyzeFinishTool, analyzeResult()) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer(approvedReview) onCondition { text -> text.trim().startsWith("{") }
            mockLLMAnswer("").asDefaultResponse
        }

        generator(
            promptExecutor,
            activities,
            tools = listOf(DummyFetchRepoTool, DummyFetchLatestReleaseTool),
        ).generate("https://github.com/sharkdp/fd")

        fun eventsOf(name: String) = activities.filter { it.toolName == name }
        val fetchStarted = eventsOf(com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO)
            .filterIsInstance<ToolActivity.Started>().single()
        val fetchCompleted = eventsOf(com.scoopai.core.agent.PhaseTools.FETCH_REPO_INFO)
            .filterIsInstance<ToolActivity.Completed>().single()
        assertEquals(intentText, fetchStarted.intent, "Batch 1 Started carries the intent")
        assertEquals(intentText, fetchCompleted.intent, "Batch 1 Completed carries the intent")

        val releaseStarted = eventsOf(com.scoopai.core.agent.PhaseTools.FETCH_LATEST_RELEASE)
            .filterIsInstance<ToolActivity.Started>().single()
        val releaseCompleted = eventsOf(com.scoopai.core.agent.PhaseTools.FETCH_LATEST_RELEASE)
            .filterIsInstance<ToolActivity.Completed>().single()
        assertNull(releaseStarted.intent, "Batch 2 Started must not inherit the stale batch 1 intent")
        assertNull(releaseCompleted.intent, "Batch 2 Completed must not inherit the stale batch 1 intent")
    }

    // ---------------------------------------------------------------------
    // Review agent: verify_install intent rides the review tool activity
    // ---------------------------------------------------------------------

    private class FakeScoopVerifier : ScoopVerifier {
        override suspend fun verifyInstall(manifestJson: String, appName: String): String =
            """{"success":true,"app_name":"$appName","exit_code":0,"output":"install ok"}"""
    }

    @Test
    fun `review agent attaches intent to verify_install Started event`() = runBlocking {
        val intentText = "Running a real install test to validate the shortcuts block."
        val activities = mutableListOf<ToolActivity>()
        val fakeVerifier = FakeScoopVerifier()
        val reviewTool = VerifyInstallTool(fakeVerifier) { manifestJson }
        val delegate = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult()) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult()) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // Review round: mixed response with intent text + verify_install call.
            // The review agent's user message is the normalized manifest, where
            // normalizeBin has folded bin into a plain string, so key both the
            // answer and the tool call on that normalized bin shape.
            mockLLMAnswer(intentText) onRequestContains "\"bin\":\"fd.exe\""
            mockLLMToolCall(reviewTool, VerifyInstallArgs(reason = "install looks suspicious")) onRequestContains "\"bin\":\"fd.exe\""
            // After the verify_install tool result, finish the review with a pure
            // text verdict keyed on the tool-result message content.
            mockLLMAnswer(approvedReview) onCondition { it.contains("\"success\":true") }
            mockLLMAnswer("").asDefaultResponse
        }
        val recording = RecordingExecutor(delegate)
        try {
            generator(recording, activities, scoopVerifier = fakeVerifier)
                .generate("https://github.com/sharkdp/fd")
        } catch (e: Exception) {
            println("=== DIAG exception: ${e.message?.take(200)}")
            recording.lastMessageTexts.forEachIndexed { i, t ->
                println("=== DIAG turn[$i]: ${t.take(250).replace("\n", " ")}")
            }
            throw e
        }

        val verifyStarted = activities.filterIsInstance<ToolActivity.Started>()
            .filter { it.toolName == VerifyInstallTool.TOOL_NAME }
        assertTrue(verifyStarted.isNotEmpty(), "Expected a verify_install Started event from the review agent")
        assertEquals(
            intentText,
            verifyStarted.first().intent,
            "Review intent text must ride the verify_install Started event",
        )
    }

    /**
     * Temporary diagnostic executor: records the last message text of every
     * LLM call, delegating everything to the wrapped MockPromptExecutor.
     */
    private class RecordingExecutor(
        private val delegate: ai.koog.prompt.executor.model.PromptExecutor,
    ) : ai.koog.prompt.executor.model.PromptExecutor() {
        val lastMessageTexts = mutableListOf<String>()

        override suspend fun execute(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
        ): ai.koog.prompt.message.Message.Assistant {
            val last = prompt.messages.lastOrNull()
            if (last != null) {
                lastMessageTexts += last.parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Text>()
                    .joinToString(" ") { it.text } + last.parts
                        .filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>()
                    .joinToString(" ") { it.output }
            }
            return delegate.execute(prompt, model, tools)
        }

        override fun executeStreaming(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
        ) = delegate.executeStreaming(prompt, model, tools)

        override suspend fun moderate(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
        ) = delegate.moderate(prompt, model)

        override fun close() {
            delegate.close()
        }
    }

    // ---------------------------------------------------------------------
    // Prompt guidance: research and analyze prompts instruct the self-report
    // ---------------------------------------------------------------------

    @Test
    fun `research prompt instructs writing intent before each tool call`() {
        val prefetch = com.scoopai.core.agent.PrefetchResult(
            isGitHub = true,
            owner = "sharkdp",
            repo = "fd",
            originalUrl = "https://github.com/sharkdp/fd",
        )
        val prompt = com.scoopai.core.agent.prompt.buildResearchPrompt(prefetch)
        assertTrue(
            prompt.contains("Before each tool call, write one short sentence stating what you are about to fetch or inspect, and why."),
            "Research prompt must carry the tool-call intent guidance",
        )
    }

    @Test
    fun `analyze prompt instructs writing intent before each tool call`() {
        val prompt = com.scoopai.core.agent.prompt.buildAnalyzePrompt(researchResult())
        assertTrue(
            prompt.contains("Before each tool call, write one short sentence stating what you are about to fetch or inspect, and why."),
            "Analyze prompt must carry the tool-call intent guidance",
        )
    }
}
