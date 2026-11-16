package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [buildNoAssetsExplanation] user_explanation handling and
 * ResearchOutput backward-compatible decoding.
 */
class ResearchOutcomeUtilsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------
    // user_explanation preferred over the deterministic English join
    // ---------------------------------------------------------------

    @Test
    fun `buildNoAssetsExplanation prefers user_explanation when present`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL.",
            user_explanation = "该项目只提供 Linux 版本，可以尝试 WSL 或虚拟机。",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "该项目只提供 Linux 版本，可以尝试 WSL 或虚拟机。",
            explanation,
            "a non-blank user_explanation must replace the deterministic English join",
        )
    }

    @Test
    fun `buildNoAssetsExplanation trims user_explanation`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            user_explanation = "  此项目没有 Windows 构建。  ",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals("此项目没有 Windows 构建。", explanation)
    }

    // ---------------------------------------------------------------
    // Blank or missing user_explanation falls back to the English join
    // ---------------------------------------------------------------

    @Test
    fun `buildNoAssetsExplanation falls back to the English join when user_explanation is blank`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM.",
            user_explanation = "   ",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool). Consider using WSL or a Linux VM.",
            explanation,
            "a blank user_explanation must fall back to the deterministic English join",
        )
    }

    @Test
    fun `buildNoAssetsExplanation falls back when user_explanation is null`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            notes = "Only Makefile build targets are provided.",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool). Only Makefile build targets are provided.",
            explanation,
        )
    }

    // ---------------------------------------------------------------
    // Overlong user_explanation is capped with the shared 220-char limit
    // ---------------------------------------------------------------

    @Test
    fun `buildNoAssetsExplanation caps overlong user_explanation with an ellipsis`() {
        // Kotlin String.length counts UTF-16 code units, so the fixture must
        // exceed the 220-char cap to trigger truncation.
        val longText = "字".repeat(221)

        val explanation = buildNoAssetsExplanation(
            ResearchOutput(status = ResearchStatus.NO_WINDOWS_ASSETS, user_explanation = longText),
        )!!

        assertTrue(explanation.length <= 220, "capped explanation must be at most 220 chars, got ${explanation.length}")
        assertTrue(explanation.endsWith("..."), "capped explanation must end with an ellipsis, got: $explanation")
        assertTrue(explanation.startsWith("\u5b57"), "cap must keep the head of the text, got: $explanation")
    }

    @Test
    fun `buildNoAssetsExplanation keeps a user_explanation at exactly the limit intact`() {
        val head = "没有 Windows 版本，"
        val filler = "字"
        val text = head + filler.repeat(220 - head.length)

        val explanation = buildNoAssetsExplanation(
            ResearchOutput(status = ResearchStatus.NO_WINDOWS_ASSETS, user_explanation = text),
        )

        assertEquals(text, explanation, "text at exactly 220 chars must not be truncated")
    }

    // ---------------------------------------------------------------
    // ResearchOutput decoding stays backward compatible
    // ---------------------------------------------------------------

    @Test
    fun `ResearchOutput user_explanation decodes when present and defaults to null`() {
        val decoded = json.decodeFromString<ResearchOutput>(
            """{"status":"no_windows_assets","project":"a/b","user_explanation":"没有 Windows 产物。"}""",
        )
        assertEquals("没有 Windows 产物。", decoded.user_explanation)

        val legacy = json.decodeFromString<ResearchOutput>(
            """{"status":"no_windows_assets","project":"a/b"}""",
        )
        assertNull(legacy.user_explanation, "old checkpoints without user_explanation must decode to null")
    }
}
