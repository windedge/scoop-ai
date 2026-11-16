package com.scoopai.tools

import com.scoopai.tools.interaction.QuestionOption
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven tests for [resolveDownloadConfirmAnswer]: the three-layer
 * decision (selected index / legacy keywords / default). true = skip the
 * download.
 */
class DownloadConfirmAnswerTest {

    private val options = listOf(
        QuestionOption(label = "Download and proceed", value = "download"),
        QuestionOption(label = "Skip downloading", value = "skip"),
    )

    // layer 1: "user selected: N" index mapping

    @Test
    fun `selected index 2 maps to the skip option`() {
        assertTrue(resolveDownloadConfirmAnswer("user selected: 2", options))
    }

    @Test
    fun `selected index 1 maps to the download option`() {
        assertFalse(resolveDownloadConfirmAnswer("user selected: 1", options))
    }

    @Test
    fun `selected index out of range falls through to the default`() {
        assertFalse(resolveDownloadConfirmAnswer("user selected: 5", options))
    }

    @Test
    fun `selected index zero is out of range`() {
        assertFalse(resolveDownloadConfirmAnswer("user selected: 0", options))
    }

    @Test
    fun `selected index with extra whitespace still parses`() {
        assertTrue(resolveDownloadConfirmAnswer("  user selected:   2  ", options))
    }

    // layer 2: legacy keyword set (free-typed composer text)

    @Test
    fun `bare n skips`() {
        assertTrue(resolveDownloadConfirmAnswer("n", options))
    }

    @Test
    fun `bare no skips`() {
        assertTrue(resolveDownloadConfirmAnswer("no", options))
    }

    @Test
    fun `cancel skips`() {
        assertTrue(resolveDownloadConfirmAnswer("Cancel", options))
    }

    @Test
    fun `skip skips`() {
        assertTrue(resolveDownloadConfirmAnswer("SKIP", options))
    }

    @Test
    fun `skip downloading phrase skips`() {
        assertTrue(resolveDownloadConfirmAnswer("Please skip downloading this archive", options))
    }

    @Test
    fun `an unrelated sentence does not skip`() {
        assertFalse(resolveDownloadConfirmAnswer("go ahead, download it", options))
    }

    // layer 3: default

    @Test
    fun `empty response defaults to download`() {
        assertFalse(resolveDownloadConfirmAnswer("", options))
    }

    @Test
    fun `blank whitespace response defaults to download`() {
        assertFalse(resolveDownloadConfirmAnswer("   \n  ", options))
    }

    @Test
    fun `unknown text defaults to download`() {
        assertFalse(resolveDownloadConfirmAnswer("whatever, unclear answer", options))
    }

    // layer precedence

    @Test
    fun `index layer wins over keyword layer`() {
        // "user selected: 1" contains no keyword, but a fabricated combined
        // response must resolve through layer 1 regardless of later layers.
        assertFalse(resolveDownloadConfirmAnswer("user selected: 1 (download)", options))
    }
}
