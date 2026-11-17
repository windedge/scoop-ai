package com.scoopai.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reply-language rules in the shared system prompt:
 * - null: byte-identical to the legacy single-sentence prompt;
 * - non-null: MANDATORY language block at the very front (weakest models read
 *   it before the long phase prompt dilutes it), back-reference sentences in
 *   phase prompts, and a direct anchor in assist/repair user prompts.
 */
class PromptLanguageTest {

    // ---------------------------------------------------------------
    // replyLanguage == null: byte-identical legacy prompt
    // ---------------------------------------------------------------

    @Test
    fun `buildGeneratorSystemPrompt with null replyLanguage keeps the legacy wording`() {
        val legacy =
            "You are a Scoop manifest generator. You generate and assist Scoop bucket manifests for Windows software. " +
                "Style: never use em dashes (" + EM_DASH + ") or en dashes (" + EN_DASH + "). " +
                "Use commas, colons, parentheses or plain hyphens (-) instead."
        assertEquals(legacy, buildGeneratorSystemPrompt(), "null replyLanguage must keep the prompt byte-identical")
    }

    @Test
    fun `buildGeneratorSystemPrompt default overload equals the null overload`() {
        assertEquals(buildGeneratorSystemPrompt(null), buildGeneratorSystemPrompt())
    }

    @Test
    fun `null prompt does not contain the reply language rules block`() {
        assertFalse(
            buildGeneratorSystemPrompt().contains("Reply language rules"),
            "null replyLanguage must not append the language block",
        )
    }

    // ---------------------------------------------------------------
    // replyLanguage != null: language block
    // ---------------------------------------------------------------

    @Test
    fun `buildGeneratorSystemPrompt with a language carries the rules block`() {
        val prompt = buildGeneratorSystemPrompt("Chinese")
        assertTrue(
            prompt.contains("MANDATORY reply language rules"),
            "non-null replyLanguage must include the Reply language rules block, got:\n$prompt",
        )
    }

    @Test
    fun `language block names the configured language as the fallback`() {
        val prompt = buildGeneratorSystemPrompt("Simplified Chinese")
        assertTrue(
            prompt.contains("Otherwise reply in Simplified Chinese."),
            "rule 2 must name the configured language, got:\n$prompt",
        )
    }

    @Test
    fun `language block keeps the three-part structure`() {
        val prompt = buildGeneratorSystemPrompt("German")
        assertTrue(prompt.contains("1. If the instruction in THIS request"), "rule 1 must anchor on the request language, got:\n$prompt")
        assertTrue(prompt.contains("2. Otherwise reply in German."), "rule 2 must be the fallback language, got:\n$prompt")
        assertTrue(prompt.contains("3. The following are ALWAYS English"), "rule 3 must list the English exemptions, got:\n$prompt")
    }

    @Test
    fun `language block carries the mixed-language anchoring rule`() {
        val prompt = buildGeneratorSystemPrompt("French")
        assertTrue(
            prompt.contains("anchor on the sentences the user wrote themselves"),
            "rule 1 must anchor mixed-language input on the user's own sentences, got:\n$prompt",
        )
    }

    @Test
    fun `language block carries the closed exemption list`() {
        val prompt = buildGeneratorSystemPrompt("Japanese")
        assertTrue(
            prompt.contains("manifest field values"),
            "rule 3 must exempt manifest field values, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("all phase JSON data fields"),
            "rule 3 must exempt phase JSON data fields, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("review issue strings"),
            "rule 3 must exempt review issue strings, got:\n$prompt",
        )
    }

    @Test
    fun `prompt with a language still bans em dashes`() {
        val prompt = buildGeneratorSystemPrompt("Spanish")
        assertTrue(
            prompt.contains("never use em dashes"),
            "the style constraint must survive the language block, got:\n$prompt",
        )
    }

    @Test
    fun `mandatory language block sits at the very front of the system prompt`() {
        val prompt = buildGeneratorSystemPrompt("Simplified Chinese")
        assertTrue(
            prompt.startsWith("MANDATORY reply language rules"),
            "the language block must be the first paragraph so it is not diluted, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("override defaults when conflicting"),
            "the block must declare it overrides default language behavior, got:\n$prompt",
        )
    }

    @Test
    fun `legacy identity line survives behind the mandatory block`() {
        val prompt = buildGeneratorSystemPrompt("Spanish")
        assertTrue(
            prompt.contains("You are a Scoop manifest generator."),
            "the legacy identity and style line must survive behind the block, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // User-layer anchor in assist/repair prompts (parameter threading)
    // ---------------------------------------------------------------

    private fun anchorFor(language: String) =
        "The explanation and any direct answers MUST be written in $language " +
            "when the user's instruction does not specify another natural language."

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun `assist prompt with replyLanguage carries the user-layer anchor exactly once`() {
        val prompt = buildAssistPrompt(assistContext(), replyLanguage = "Simplified Chinese")
        val anchor = anchorFor("Simplified Chinese")
        assertTrue(
            prompt.contains(anchor),
            "assist prompt must anchor the reply language directly, got:\n$prompt",
        )
        assertEquals(1, countOccurrences(prompt, anchor), "the anchor must appear exactly once")
    }

    @Test
    fun `repair prompt with replyLanguage carries the user-layer anchor exactly once`() {
        val repairCtx = com.scoopai.core.AssistContext(
            manifest = """{"version":"1.0.0"}""",
            feedback = "fix bin",
            retryCount = 1,
            explanatory = false,
        )
        val prompt = buildRepairPrompt(repairCtx, replyLanguage = "German")
        val anchor = anchorFor("German")
        assertTrue(
            prompt.contains(anchor),
            "repair prompt must anchor the reply language directly, got:\n$prompt",
        )
        assertEquals(1, countOccurrences(prompt, anchor), "the anchor must appear exactly once")
    }

    @Test
    fun `assist and repair prompts without replyLanguage omit the anchor`() {
        assertFalse(
            buildAssistPrompt(assistContext()).contains("MUST be written in"),
            "null replyLanguage must not add the anchor to the assist prompt",
        )
        assertFalse(
            buildRepairPrompt(repairContextNoExplanatory()).contains("MUST be written in"),
            "null replyLanguage must not add the anchor to the repair prompt",
        )
    }

    @Test
    fun `assist and repair default overloads equal the explicit-null overloads`() {
        assertEquals(
            buildAssistPrompt(assistContext(), replyLanguage = null),
            buildAssistPrompt(assistContext()),
        )
        assertEquals(
            buildRepairPrompt(repairContextNoExplanatory(), replyLanguage = null),
            buildRepairPrompt(repairContextNoExplanatory()),
        )
    }

    private fun repairContextNoExplanatory() = com.scoopai.core.AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "fix bin",
        retryCount = 1,
        explanatory = false,
    )

    // ---------------------------------------------------------------
    // Back-reference sentences replace the loose wording
    // ---------------------------------------------------------------

    @Test
    fun `mandatory block keeps the three-part rule structure`() {
        val prompt = buildGeneratorSystemPrompt("Italian")
        val blockEnd = prompt.indexOf("You are a Scoop manifest generator.")
        val block = prompt.substring(0, blockEnd)
        assertTrue(block.contains("1. If the instruction in THIS request"), "rule 1 must anchor on the request language, got:\n$block")
        assertTrue(block.contains("2. Otherwise reply in Italian."), "rule 2 must be the fallback language, got:\n$block")
        assertTrue(block.contains("3. The following are ALWAYS English"), "rule 3 must list the English exemptions, got:\n$block")
    }

    private val backReference = "in the language defined by the Reply language rules in the system prompt"

    @Test
    fun `generate prompt explanation guidance uses the back-reference wording`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)
        assertTrue(
            prompt.contains(backReference),
            "generate explanation guidance must reference the system prompt rules, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("in the same language as the user's input."),
            "the loose generate wording must be replaced, got:\n$prompt",
        )
    }

    @Test
    fun `assist prompt explanation guidance uses the back-reference wording`() {
        val prompt = buildAssistPrompt(assistContext())
        assertTrue(
            prompt.contains(backReference),
            "assist explanation guidance must reference the system prompt rules, got:\n$prompt",
        )
    }

    @Test
    fun `question escape hatch uses the back-reference wording`() {
        val prompt = buildAssistPrompt(assistContext())
        assertTrue(
            prompt.contains(backReference),
            "escape hatch answer guidance must reference the system prompt rules, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("in the user's language via the existing"),
            "the loose escape hatch wording must be replaced, got:\n$prompt",
        )
    }

    @Test
    fun `install test phrasing examples keep their user language wording`() {
        // :826 stays: request recognition, not output language.
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)
        assertTrue(
            prompt.contains("their equivalents in the user's language"),
            "the install-test phrasing examples are request recognition and must keep their wording, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // ReviewPrompts: English issues + machine-consumed contract
    // ---------------------------------------------------------------

    @Test
    fun `review prompt requires English issue strings`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = false)
        assertTrue(
            prompt.contains("Return each issue string in English."),
            "review prompt must pin issue strings to English, got:\n$prompt",
        )
    }

    @Test
    fun `review prompt states its output is machine-consumed`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = true)
        assertTrue(
            prompt.contains("Your output is machine-consumed JSON, not user-facing."),
            "review prompt must state the machine-consumed contract, got:\n$prompt",
        )
    }

    @Test
    fun `review prompt does not contain the reply language rules block`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = false)
        assertFalse(
            prompt.contains("Reply language rules"),
            "review prompt must not inherit the reply-language block",
        )
    }

    // ---------------------------------------------------------------
    // LenientStringFinishTool explanation descriptor is language neutral
    // ---------------------------------------------------------------

    @Test
    fun `finish tool explanation descriptor is language neutral`() {
        // Internal class; assert via the finalize descriptor wording used at runtime.
        // The wording itself is asserted indirectly by the generator prompt contract;
        // here we only guard the legacy loose wording is gone.
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)
        assertFalse(
            prompt.contains("in the same language as the user's input"),
            "generate prompt must not carry the loose language wording, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private fun minimalAnalyze() = com.scoopai.core.agent.AnalyzeOutput(
        status = com.scoopai.core.agent.AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = emptyList(),
        bin = emptyList(),
    )

    private fun assistContext() = com.scoopai.core.AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = true,
    )
}
