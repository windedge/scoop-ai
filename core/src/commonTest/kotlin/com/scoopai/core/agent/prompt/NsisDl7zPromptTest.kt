package com.scoopai.core.agent.prompt

import com.scoopai.core.RefineContext
import com.scoopai.core.agent.LicenseInfo
import com.scoopai.core.agent.ReferenceManifests
import com.scoopai.core.agent.ResearchOutput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that prompts teach the LLM about Scoop's `#/dl.7z` NSIS extraction
 * mechanism so review/refine do not falsely flag valid bin entries, and that
 * the NSIS GUI-only reference manifest follows the official pattern.
 */
class NsisDl7zPromptTest {

    @Test
    fun `review prompt explains dl-7z extraction mechanism`() {
        assertTrue(
            REVIEW_AGENT_SYSTEM_PROMPT.contains("#/dl.7z"),
            "review prompt must mention '#/dl.7z' fragment suffix",
        )
    }

    @Test
    fun `review prompt accepts gui-only manifests with only shortcuts`() {
        assertTrue(
            REVIEW_AGENT_SYSTEM_PROMPT.contains("GUI-only") &&
                REVIEW_AGENT_SYSTEM_PROMPT.contains("without a top-level"),
            "review prompt must allow GUI-only manifests with shortcuts and no bin",
        )
    }

    @Test
    fun `refine prompt includes dl-7z extraction context`() {
        val ctx = RefineContext(
            manifest = """{"version":"1.0","url":"https://example.com/app.exe#/dl.7z"}""",
            feedback = "bin points directly to 'pot.exe' which will not exist after extracting a standard NSIS installer zip",
            retryCount = 1,
        )
        val prompt = buildRefinePrompt(ctx)
        assertTrue(
            prompt.contains("#/dl.7z"),
            "refine prompt must mention '#/dl.7z'",
        )
        assertTrue(
            prompt.contains("false positive"),
            "refine prompt must explain how to recognize dl-7z false positives",
        )
    }

    @Test
    fun `refine prompt includes conversation history when present`() {
        val ctx = RefineContext(
            manifest = """{"version":"1.0","url":"https://example.com/app.zip"}""",
            feedback = "Remove notes field",
            retryCount = 1,
            history = listOf(
                "User requested: Prefer portable zip",
                "User requested: Rename bin to fd",
            ),
        )
        val prompt = buildRefinePrompt(ctx)
        assertTrue(prompt.contains("Conversation / Refine History"), "Must contain history section header")
        assertTrue(prompt.contains("Prefer portable zip"), "Must contain first history item")
        assertTrue(prompt.contains("Rename bin to fd"), "Must contain second history item")
        assertTrue(prompt.contains("Remove notes field"), "Must contain current feedback/instruction")
    }

    @Test
    fun `analyze prompt allows gui-only shortcuts without bin`() {
        val prompt = buildAnalyzePrompt(
            research = ResearchOutput(
                project = "example/testapp",
                description = "A desktop application.",
                homepage = "https://example.com",
                license = LicenseInfo(value = "MIT", source = "GitHub API"),
            ),
        )
        assertTrue(
            prompt.contains("GUI-only") || prompt.contains("only shortcuts"),
            "analyze prompt must allow pure GUI apps to use only shortcuts",
        )
    }

    @Test
    fun `nsis gui-only reference manifest has no bin and uses dl-7z`() {
        val m = ReferenceManifests.POT_DESKTOP
        assertTrue(m.contains("#/dl.7z"), "reference url must carry #/dl.7z")
        assertFalse(Regex("\"bin\"").containsMatchIn(m), "GUI-only reference must not define bin")
        assertTrue(m.contains("pot.exe"), "reference must contain pot.exe shortcut target")
        assertTrue(m.contains("shortcuts"), "reference must contain shortcuts")
        assertTrue(m.contains("checkver"), "reference must contain checkver")
        assertTrue(m.contains("autoupdate"), "reference must contain autoupdate")
    }

    @Test
    fun `nsis gui-only reference is included in ALL list`() {
        assertTrue(
            ReferenceManifests.ALL.contains(ReferenceManifests.POT_DESKTOP),
            "ALL must embed the POT_DESKTOP manifest content",
        )
    }
}
