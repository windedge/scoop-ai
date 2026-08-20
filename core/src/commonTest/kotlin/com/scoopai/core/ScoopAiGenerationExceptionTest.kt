package com.scoopai.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Koog subgraphWithTask tool-call exhaustion detection
 * and the translated error message builder.
 *
 * Background: when the model lacks ToolChoice capability (scoop-ai strips it
 * for all openai-compatible models) and only returns Text responses, Koog
 * re-prompts "DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD." up to
 * ASSISTANT_RESPONSE_REPEAT_MAX = 3 times, then throws IllegalStateException.
 * scoop-ai translates that into [ScoopAiGenerationException] with an
 * actionable hint.
 */
class ScoopAiGenerationExceptionTest {

    // -----------------------------------------------------------------
    // isSubgraphTaskToolCallExhaustion
    // -----------------------------------------------------------------

    @Test
    fun `detects Koog 1 1 1 exhaustion message with backticked tool name`() {
        val e = IllegalStateException(
            "Unable to finish subgraph with task. Reason: the model 'nv/muse-glimmer-30bl' does not support tool choice, " +
                "and was not able to call `finalize_task_result` tool after <3> attempts."
        )
        assertTrue(isSubgraphTaskToolCallExhaustion(e), "Koog 1.1.1 exact message should be detected")
    }

    @Test
    fun `detects exhaustion message without backticked tool name`() {
        val e = IllegalStateException(
            "Unable to finish subgraph with task. Reason: the model 'nv/muse-glimmer-30bl' does not support tool choice, " +
                "and was not able to call tool after <3> attempts."
        )
        assertTrue(isSubgraphTaskToolCallExhaustion(e), "user-reported wording (no tool name) should be detected")
    }

    @Test
    fun `detects ToolChoice-capable variant message`() {
        val e = IllegalStateException(
            "Subgraph with task must always call tools, but no Tool.Call was generated, got instead: hello"
        )
        assertTrue(isSubgraphTaskToolCallExhaustion(e), "ToolChoice branch message should be detected")
    }

    @Test
    fun `detects unhandled response variant from stripped-ToolChoice path`() {
        // AIAgentSubgraphExt.kt:770 unhandled fallback edge. With ToolChoice
        // stripped (see ScoopManifestGenerator.modelWithoutToolChoice), a
        // reasoning-only / empty response that Koog cannot route lands on
        // this edge, so it must be treated as tool-call exhaustion too.
        val e = IllegalStateException(
            "Unhandled response from LLM. Subgraph with task must always call tools, " +
                "but no Tool.Call was generated, got instead: [Reasoning]"
        )
        assertTrue(isSubgraphTaskToolCallExhaustion(e), "unhandled fallback edge message should be detected")
    }

    @Test
    fun `detects exhaustion through cause chain`() {
        val root = IllegalStateException(
            "Unable to finish subgraph with task. Reason: the model 'm' does not support tool choice, " +
                "and was not able to call `finalize_task_result` tool after <3> attempts."
        )
        val wrapper = RuntimeException("Agent run failed", root)
        assertTrue(isSubgraphTaskToolCallExhaustion(wrapper), "wrapper exception should be unwrapped")
    }

    @Test
    fun `network timeout message is not exhaustion`() {
        val e = RuntimeException("Connect timeout: 30s elapsed")
        assertFalse(isSubgraphTaskToolCallExhaustion(e))
    }

    @Test
    fun `HTTP error message is not exhaustion`() {
        val e = RuntimeException("HTTP 400 Bad Request")
        assertFalse(isSubgraphTaskToolCallExhaustion(e))
    }

    @Test
    fun `null-message exception is not exhaustion`() {
        val e = object : Exception() {}
        assertFalse(isSubgraphTaskToolCallExhaustion(e))
    }

    @Test
    fun `circular cause chain terminates without stack overflow`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isSubgraphTaskToolCallExhaustion(a))
    }

    // -----------------------------------------------------------------
    // buildToolCallExhaustionMessage
    // -----------------------------------------------------------------

    @Test
    fun `translated message includes model id phase hint and original error`() {
        val original =
            "Unable to finish subgraph with task. Reason: the model 'nv/muse-glimmer-30bl' does not support tool choice, " +
                "and was not able to call `finalize_task_result` tool after <3> attempts."
        val msg = buildToolCallExhaustionMessage(
            modelId = "nv/muse-glimmer-30bl",
            phase = "research",
            originalMessage = original,
        )
        assertContains(msg, "nv/muse-glimmer-30bl")
        assertContains(msg, "research")
        assertContains(msg, "function calling")
        assertContains(msg, "finalize_task_result")
        assertContains(msg, "SCOOP_AI_DEBUG_HTTP")
        assertContains(msg, "tool_calls")
        assertContains(msg, original)
    }

    @Test
    fun `translated message omits phase clause when phase unknown`() {
        val msg = buildToolCallExhaustionMessage(
            modelId = "m",
            phase = null,
            originalMessage = "orig",
        )
        assertFalse(
            " during the '" in msg,
            "no phase clause should appear when phase is null, got: $msg"
        )
        assertContains(msg, "Manifest generation failed: the model 'm'")
    }
}
