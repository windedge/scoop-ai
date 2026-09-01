package com.scoopai.core.agent.prompt

import com.scoopai.core.RefineContext
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompt tests for the finalize explanation protocol (architecture proposal 4.5):
 * - generate phase: one optional-parameter note in the Finalize section;
 * - refine phase: conditional requirement driven by [RefineContext.explanatory].
 */
class RefinePromptTest {

    // ---------------------------------------------------------------
    // buildRefinePrompt: explanatory=true asks for an explanation
    // ---------------------------------------------------------------

    private fun refineContext(explanatory: Boolean) = RefineContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = explanatory,
    )

    @Test
    fun `buildRefinePrompt mentions the explanation parameter when explanatory is true`() {
        val prompt = buildRefinePrompt(refineContext(explanatory = true))

        assertTrue(
            prompt.contains("explanation"),
            "explanatory refine prompt must mention the explanation parameter",
        )
    }

    @Test
    fun `buildRefinePrompt requires answering which fields were added removed or modified when explanatory is true`() {
        val prompt = buildRefinePrompt(refineContext(explanatory = true))

        assertTrue(
            prompt.contains("added") && prompt.contains("removed") && prompt.contains("modified"),
            "explanation guidance must require stating which fields were added/removed/modified, got:\n$prompt",
        )
    }

    @Test
    fun `buildRefinePrompt requires the explanation to follow the user language when explanatory is true`() {
        val prompt = buildRefinePrompt(refineContext(explanatory = true))

        assertTrue(
            prompt.contains("language"),
            "explanation guidance must require following the user's language",
        )
    }

    @Test
    fun `buildRefinePrompt omits the explanation guidance when explanatory is false`() {
        val prompt = buildRefinePrompt(refineContext(explanatory = false))

        assertFalse(
            prompt.contains("explanation"),
            "internal repair rounds must not be asked for an explanation, got:\n$prompt",
        )
    }

    @Test
    fun `buildRefinePrompt caps the explanation at 3 short sentences when explanatory is true`() {
        val prompt = buildRefinePrompt(refineContext(explanatory = true))

        assertTrue(
            prompt.contains("3 short sentences"),
            "explanatory refine prompt must cap the explanation length, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("60 words"),
            "explanatory refine prompt must give a word budget, got:\n$prompt",
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
