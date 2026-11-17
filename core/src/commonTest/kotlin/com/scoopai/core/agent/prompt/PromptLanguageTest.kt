package com.scoopai.core.agent.prompt

import com.scoopai.core.AssistContext
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import com.scoopai.core.agent.PrefetchResult
import com.scoopai.core.agent.ResearchOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mandatory rules block in the shared system prompt is the language
 * contract; phase prompts only keep the concrete fallback language anchor.
 */
class PromptLanguageTest {

    // ---------------------------------------------------------------
    // shared system prompt: golden wording and the nullable fallback
    // ---------------------------------------------------------------

    private val base =
        "You are a Scoop manifest generator. You generate and assist Scoop bucket manifests for Windows software. " +
            "Style: never use em dashes (" + EM_DASH + ") or en dashes (" + EN_DASH + "). " +
            "Use commas, colons, parentheses or plain hyphens (-) instead."

    private val ruleOne =
        "1. If the instruction in THIS request is written in a natural language, reply in that language. " +
            "URLs, paths, manifest field names, code, @bucket/app references and quoted log text do not count. " +
            "When several natural languages are mixed, anchor on the sentences the user wrote themselves; " +
            "quoted or pasted passages do not decide."

    private val ruleThree =
        "3. The following are ALWAYS English regardless of the rules above: manifest field values " +
            "(description, notes, ...), all phase JSON data fields (notes, alternatives, persist_targets, ...), " +
            "and review issue strings. This list is data, not user-visible prose: " +
            "the finalize explanation/activities parameters and your tool-call intent lines are prose, " +
            "and they follow rules 1 and 2."

    private fun expectedSystemPrompt(ruleTwo: String): String =
        "MANDATORY reply language rules (override defaults when conflicting). " +
            "They apply to the prose the user sees: the finalize explanation and activities parameters, " +
            "the one-line intent before each tool call, ask_user question text, and direct answers:\n" +
            ruleOne + "\n" + ruleTwo + "\n" + ruleThree + "\n\n" + base

    @Test
    fun `null replyLanguage gets the rules block with a default-language fallback`() {
        assertEquals(
            expectedSystemPrompt("2. Otherwise keep your default reply language."),
            buildGeneratorSystemPrompt(),
        )
    }

    @Test
    fun `configured replyLanguage replaces only the fallback rule`() {
        assertEquals(
            expectedSystemPrompt("2. Otherwise reply in Simplified Chinese."),
            buildGeneratorSystemPrompt("Simplified Chinese"),
        )
    }

    @Test
    fun `rules block stays at the front of the system prompt`() {
        val prompt = buildGeneratorSystemPrompt("German")
        assertTrue(prompt.startsWith("MANDATORY reply language rules"), "got:\n$prompt")
        assertTrue(prompt.indexOf(base) > 0, "the legacy identity line must follow the rules block, got:\n$prompt")
    }

    // ---------------------------------------------------------------
    // user layer: concrete language name anchor, exactly once
    // ---------------------------------------------------------------

    private fun anchorFor(language: String) =
        "The explanation, the activities and any direct answers MUST be written in $language " +
            "when the user's instruction does not specify another natural language."

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun `assist prompt carries the user-layer anchor exactly once`() {
        val prompt = buildAssistPrompt(assistContext(), replyLanguage = "Simplified Chinese")
        val anchor = anchorFor("Simplified Chinese")
        assertTrue(prompt.contains(anchor), "got:\n$prompt")
        assertEquals(1, countOccurrences(prompt, anchor))
    }

    @Test
    fun `repair prompt carries the user-layer anchor exactly once`() {
        val prompt = buildRepairPrompt(repairContext(), replyLanguage = "German")
        val anchor = anchorFor("German")
        assertTrue(prompt.contains(anchor), "got:\n$prompt")
        assertEquals(1, countOccurrences(prompt, anchor))
    }

    @Test
    fun `assist and repair prompts without replyLanguage omit the anchor`() {
        assertFalse(buildAssistPrompt(assistContext()).contains("MUST be written in"))
        assertFalse(buildRepairPrompt(repairContext()).contains("MUST be written in"))
    }

    // ---------------------------------------------------------------
    // phase prompts: no duplicate language clauses
    // ---------------------------------------------------------------

    @Test
    fun `phase prompts do not repeat the system-prompt language clause`() {
        val clause = "in the language defined by the Reply language rules in the system prompt"
        val prefetch = PrefetchResult(
            isGitHub = true,
            owner = "sharkdp",
            repo = "fd",
            originalUrl = "https://github.com/sharkdp/fd",
        )
        val prompts = mapOf(
            "research" to buildResearchPrompt(prefetch),
            "analyze" to buildAnalyzePrompt(ResearchOutput()),
            "generate" to buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null),
            "assist" to buildAssistPrompt(assistContext()),
            "repair" to buildRepairPrompt(repairContext()),
        )
        prompts.forEach { (name, prompt) ->
            assertFalse(prompt.contains(clause), "$name must not repeat the rules clause")
        }
    }

    @Test
    fun `install test phrasing examples keep their user language wording`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)
        assertTrue(prompt.contains("their equivalents in the user's language"), "got:\n$prompt")
    }

    // ---------------------------------------------------------------
    // review prompt: English issues, machine-consumed JSON
    // ---------------------------------------------------------------

    @Test
    fun `review prompt pins issue strings to English for machine consumers`() {
        val prompt = buildReviewSystemPrompt(hasInstallTest = true)
        assertTrue(prompt.contains("Return each issue string in English."), "got:\n$prompt")
        assertTrue(prompt.contains("Your output is machine-consumed JSON, not user-facing."), "got:\n$prompt")
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private fun minimalAnalyze() = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = emptyList(),
        bin = emptyList(),
    )

    private fun assistContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = true,
    )

    private fun repairContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "fix bin",
        retryCount = 1,
        explanatory = false,
    )
}
