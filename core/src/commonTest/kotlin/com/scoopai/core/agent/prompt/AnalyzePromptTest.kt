package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.ResearchOutput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyzePromptTest {

    private fun minimalResearch() = ResearchOutput(
        project = "owner/repo",
        version = "1.0.0",
        description = "test",
        homepage = "https://github.com/owner/repo",
    )

    @Test
    fun `buildAnalyzePrompt requires hash and archive listing for large MSI assets`() {
        val prompt = buildAnalyzePrompt(minimalResearch())
        assertTrue(prompt.contains("MSI"), "Prompt should mention MSI large-asset rules")
        assertTrue(prompt.contains("computeSha256FromUrl"), "Prompt should still instruct hash computation")
        assertTrue(prompt.contains("listArchiveContents"), "Prompt should require archive listing for extract_dir")
        assertFalse(
            prompt.contains("call `detectInstallerType` first to check if it's usable before calling `computeSha256FromUrl`"),
            "Old skip-large-asset rule should be removed",
        )
        assertTrue(
            prompt.contains("even when size") && (prompt.contains("stream") || prompt.contains("memory-safe")),
            "Prompt should tell agent large assets still need streaming hash analysis",
        )
        assertTrue(
            prompt.contains("MUST call both") && prompt.contains("listArchiveContents"),
            "Prompt should require MSI hash + listArchiveContents",
        )
        assertTrue(prompt.contains("Never skip MSI analysis"), "Prompt must not allow skipping MSI due to size")
    }
}
