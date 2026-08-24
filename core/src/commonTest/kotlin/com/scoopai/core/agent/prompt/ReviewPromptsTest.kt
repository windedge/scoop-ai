package com.scoopai.core.agent.prompt

import kotlin.test.Test
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
}
