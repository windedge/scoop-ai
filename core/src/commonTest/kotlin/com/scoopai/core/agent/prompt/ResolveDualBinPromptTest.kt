package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.AnalyzeOutput
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that the generate and review prompts instruct the LLM to never define
 * both a top-level `bin` AND architecture-specific `bin` in the same manifest.
 * This guards against the Scoop CLI install-time error:
 * "Both top-level 'bin' and architecture-specific 'bin' are defined".
 */
class ResolveDualBinPromptTest {

    private val minimalAnalyze = AnalyzeOutput(
        status = com.scoopai.core.agent.AnalyzeStatus.OK,
        strategy = "portable_zip",
        suggested_app_name = "testapp",
    )

    @Test
    fun `generate prompt forbids defining both top-level bin and architecture bin`() {
        val prompt = buildGeneratePrompt(
            skeleton = """{"version":"1.0","description":"test","homepage":"https://example.com","license":"MIT","url":"https://example.com/a.zip","hash":"abc"}""",
            analyze = minimalAnalyze,
            outputPath = null,
        )
        assertTrue(
            prompt.contains("never both"),
            "generate prompt must contain mutual-exclusion keyword 'never both'",
        )
        assertTrue(
            prompt.contains("top-level 'bin'"),
            "generate prompt must mention top-level 'bin'",
        )
        assertTrue(
            prompt.contains("architecture-specific 'bin'"),
            "generate prompt must mention architecture-specific 'bin'",
        )
    }

    @Test
    fun `review prompt forbids defining both top-level bin and architecture bin`() {
        assertTrue(
            REVIEW_AGENT_SYSTEM_PROMPT.contains("never both"),
            "review prompt must contain mutual-exclusion keyword 'never both'",
        )
    }
}
