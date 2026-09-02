package com.scoopai.core.agent.prompt

import com.scoopai.core.AssistContext
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompt tests for the finalize explanation protocol (architecture proposal 4.5)
 * as applied to the ASSIST phase (user follow-up, explanatory=true):
 * - assist prompt: conditional explanation guidance plus the Question Escape Hatch.
 */
class AssistPromptTest {

    private fun assistContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = true,
    )

    @Test
    fun `buildAssistPrompt uses the assistant identity and mentions the explanation parameter`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("You are a Scoop manifest assistant."),
            "assist prompt must open with the assistant identity, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("explanation"),
            "explanatory assist prompt must mention the explanation parameter",
        )
    }

    @Test
    fun `buildAssistPrompt requires answering which fields were added removed or modified`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("added") && prompt.contains("removed") && prompt.contains("modified"),
            "explanation guidance must require stating which fields were added/removed/modified, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt requires the explanation to follow the user language`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("language"),
            "explanation guidance must require following the user's language",
        )
    }

    @Test
    fun `buildAssistPrompt caps the explanation at 3 short sentences`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("3 short sentences"),
            "explanatory assist prompt must cap the explanation length, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("60 words"),
            "explanatory assist prompt must give a word budget, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt includes the question escape hatch and answered guidance`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("Question Escape Hatch"),
            "assist prompt must include the Question Escape Hatch section, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("answered"),
            "assist prompt must mention the Boolean `answered` parameter, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // buildGeneratePrompt: optional explanation parameter note
    // ---------------------------------------------------------------

    private fun minimalAnalyze() = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = emptyList(),
        bin = emptyList(),
    )

    @Test
    fun `buildGeneratePrompt mentions the optional explanation parameter`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation"),
            "generate prompt must mention the optional explanation parameter",
        )
    }

    @Test
    fun `buildGeneratePrompt explains what the explanation should cover and its language`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("portable zip") || prompt.contains("shortcuts"),
            "explanation note should give packaging-decision examples",
        )
        assertTrue(
            prompt.contains("language"),
            "explanation note must require the user's language",
        )
    }

    @Test
    fun `buildGeneratePrompt states result is required and explanation optional`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("required parameter result plus optional explanation"),
            "Finalize section must describe the two-parameter contract, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt shows a two-parameter CORRECT finalize example`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation=\""),
            "CORRECT example must show explanation as an independent string parameter, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt warns against putting the explanation inside result`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation") && prompt.contains("separate parameter"),
            "WRONG list must forbid embedding the explanation inside the result manifest JSON, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt caps the explanation at 3 short sentences and about 60 words`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("3 short sentences"),
            "generate prompt must cap the explanation at 3 short sentences, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("60 words"),
            "generate prompt must give a word budget of about 60 words, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt forbids a field-by-field recap in the explanation`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("Do NOT recap") && prompt.contains("field-by-field"),
            "generate prompt must forbid recapping extract_dir/bin/shortcuts/persist field-by-field, got:\n$prompt",
        )
    }
}
