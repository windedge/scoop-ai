package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.PrefetchResult
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ResearchPrefetchPromptTest {

    @Test
    fun `buildResearchPrompt with prefetch includes prefetched data JSON`() {
        val prefetch = PrefetchResult(
            isGitHub = true, owner = "owner", repo = "repo",
            originalUrl = "https://github.com/owner/repo",
            version = "1.0.0",
        )
        val prompt = buildResearchPrompt(prefetch, null)
        assertTrue(prompt.contains("PRE-COLLECTED DATA"), "Should contain pre-collected data header")
        assertTrue(prompt.contains("\"owner\""), "Should contain owner in JSON")
        assertTrue(prompt.contains("1.0.0"), "Should contain version")
    }

    @Test
    fun `buildResearchPrompt with prefetch includes simplified task list`() {
        val prefetch = PrefetchResult(isGitHub = true, owner = "o", repo = "r", originalUrl = "url")
        val prompt = buildResearchPrompt(prefetch, null)
        assertTrue(prompt.contains("YOUR TASKS"), "Should contain task list")
        assertTrue(prompt.contains("License"), "Should mention license task")
    }

    @Test
    fun `buildGeneratorSystemPrompt bans em and en dashes`() {
        val prompt = buildGeneratorSystemPrompt()
        assertTrue(
            prompt.contains("never use em dashes"),
            "generator system prompt must carry the em/en dash style constraint, got:\n$prompt",
        )
    }

    @Test
    fun `buildResearchPrompt with non-GitHub prefetch uses original prompt`() {
        val prefetch = PrefetchResult(isGitHub = false, originalUrl = "https://example.com/tool")
        val prompt = buildResearchPrompt(prefetch, null)
        assertFalse(prompt.contains("PRE-COLLECTED DATA"), "Non-GitHub should not have prefetch section")
    }

    @Test
    fun `buildResearchPrompt without prefetch returns original prompt`() {
        val prefetch = PrefetchResult(isGitHub = false, originalUrl = "https://github.com/owner/repo")
        val prompt = buildResearchPrompt(prefetch, null)
        assertFalse(prompt.contains("PRE-COLLECTED DATA"), "Should not contain prefetch section")
    }
}
