package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksumsNoteTest {

    // ---------------------------------------------------------------
    // buildChecksumsNote: deterministic cross-check of the research
    // checksums_filename against the actual release asset names
    // ---------------------------------------------------------------

    @Test
    fun `matching filename keeps the strong MUST instruction`() {
        val note = buildChecksumsNote("checksums.txt", listOf("app.zip", "checksums.txt"))

        assertTrue(note.contains("You MUST add an `autoupdate.hash` block"))
        assertTrue(note.contains("`checksums.txt`"))
    }

    @Test
    fun `filename match is case-insensitive`() {
        val note = buildChecksumsNote("SHA256SUMS.txt", listOf("sha256sums.txt"))

        assertTrue(note.contains("You MUST add an `autoupdate.hash` block"))
    }

    @Test
    fun `hallucinated filename drops the note entirely`() {
        val note = buildChecksumsNote("checksums.txt", listOf("app.zip", "app.exe"))

        assertFalse(note.contains("You MUST add"))
        assertTrue(note.isEmpty(), "a hallucinated checksums file must not reach generate")
    }

    @Test
    fun `missing asset list downgrades the note to advisory wording`() {
        val note = buildChecksumsNote("checksums.txt", emptyList())

        assertFalse(note.contains("You MUST add"))
        assertTrue(note.contains("If the release really publishes this file"))
        assertTrue(note.contains("otherwise omit the hash block"))
    }

    @Test
    fun `blank checksums filename produces no note`() {
        assertTrue(buildChecksumsNote("", listOf("checksums.txt")).isEmpty())
        assertTrue(buildChecksumsNote("   ", listOf("checksums.txt")).isEmpty())
    }

    // ---------------------------------------------------------------
    // analyze.notes checksums backdoor filter
    // (same hallucinated-filename source as buildChecksumsNote guards)
    // ---------------------------------------------------------------

    @Test
    fun `analyze notes lines mentioning checksums are filtered from generate prompt`() {
        val analyze = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            notes = "Needs vcrun2019.\nFound checksums.txt in the release assets, add hash block.\nUse system curl.",
        )
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = analyze, outputPath = null)

        assertFalse(prompt.contains("Found checksums.txt in the release assets"), "hallucinated checksums line must not reach generate via notes")
        assertTrue(prompt.contains("Needs vcrun2019."))
        assertTrue(prompt.contains("Use system curl."))
    }

    @Test
    fun `checksums filter is case-insensitive and drops SHA256SUMS style lines`() {
        val analyze = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            notes = "gui app\nChecksums available at SHA256SUMS.txt",
        )
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = analyze, outputPath = null)

        assertFalse(prompt.contains("SHA256SUMS.txt"))
        assertTrue(prompt.contains("gui app"))
    }

    @Test
    fun `notes made empty by the filter produce no Analysis Notes section`() {
        val analyze = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            notes = "checksums.txt exists",
        )
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = analyze, outputPath = null)

        assertFalse(prompt.contains("## Analysis Notes"))
    }
}
