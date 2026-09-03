package com.scoopai.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPromptsTest {

    @Test
    fun `review prompt explains NSIS dl-7z extraction and GUI-only shortcuts`() {
        val prompt = REVIEW_AGENT_SYSTEM_PROMPT
        assertTrue(prompt.contains("#/dl.7z"), "prompt must mention '#/dl.7z'")
        assertTrue(prompt.contains("7-Zip"), "prompt must mention '7-Zip' extraction")
        assertTrue(
            prompt.contains("GUI-only"),
            "prompt must mention GUI-only apps may define shortcuts without bin",
        )
    }

    // -----------------------------------------------------------------------
    // buildReviewSystemPrompt: install-test switch
    // -----------------------------------------------------------------------

    @Test
    fun `buildReviewSystemPrompt without install test is byte-identical to the legacy constant`() {
        assertEquals(
            REVIEW_AGENT_SYSTEM_PROMPT,
            buildReviewSystemPrompt(hasInstallTest = false),
            "the no-tool variant must be exactly the legacy review prompt",
        )
        assertFalse(
            buildReviewSystemPrompt(hasInstallTest = false).contains("verify_install"),
            "no-tool variant must not mention the verify_install tool",
        )
    }

    @Test
    fun `buildReviewSystemPrompt keeps all legacy review dimensions when install test is on`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = true)
        assertTrue(prompt.contains("#/dl.7z"), "install-test variant must keep the legacy dimensions")
        assertTrue(prompt.contains("GUI-only"), "install-test variant must keep the legacy dimensions")
        assertTrue(
            prompt.contains("Review Strictness Rules"),
            "install-test variant must keep the severity rules",
        )
    }

    @Test
    fun `buildReviewSystemPrompt with install test explains the verify_install tool usage`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = true)

        assertTrue(prompt.contains("verify_install"), "must name the verify_install tool")
        assertTrue(prompt.contains("reason"), "must explain the reason parameter")
        assertTrue(
            prompt.contains("once", ignoreCase = true),
            "must cap tool usage at most once per review",
        )
        assertTrue(
            prompt.contains("error", ignoreCase = true),
            "must require reporting install failures as error-severity issues",
        )
        assertTrue(
            prompt.contains("JSON"),
            "final reply must still be only the review JSON",
        )
    }

    @Test
    fun `buildReviewSystemPrompt with install test warns against testing every manifest`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = true)
        assertTrue(
            prompt.contains("not for every manifest") || prompt.contains("Do NOT call it for every manifest"),
            "guidance must discourage calling the tool on every manifest, got:\n$prompt",
        )
    }
}
