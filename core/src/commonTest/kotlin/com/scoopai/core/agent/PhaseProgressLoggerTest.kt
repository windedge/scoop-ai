package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseProgressLoggerTest {

    // ---------------------------------------------------------------
    // starting() message format
    // ---------------------------------------------------------------

    @Test
    fun `starting returns formatted message for research`() {
        val msg = PhaseProgressLogger.starting("research")
        assertEquals("[phase:research] starting", msg)
    }

    @Test
    fun `starting returns formatted message for analyze`() {
        val msg = PhaseProgressLogger.starting("analyze")
        assertEquals("[phase:analyze] starting", msg)
    }

    @Test
    fun `starting returns formatted message for generate`() {
        val msg = PhaseProgressLogger.starting("generate")
        assertEquals("[phase:generate] starting", msg)
    }

    @Test
    fun `starting returns formatted message for repair`() {
        val msg = PhaseProgressLogger.starting("repair")
        assertEquals("[phase:repair] starting", msg)
    }

    // ---------------------------------------------------------------
    // completed() message format
    // ---------------------------------------------------------------

    @Test
    fun `completed without tokens returns N_A token`() {
        val msg = PhaseProgressLogger.completed("research", 5000)
        assertEquals("[phase:research] completed in 5s, tokens=N/A", msg)
    }

    @Test
    fun `completed with tokens includes token count`() {
        val msg = PhaseProgressLogger.completed("analyze", 3000, "1234")
        assertEquals("[phase:analyze] completed in 3s, tokens=1234", msg)
    }

    @Test
    fun `completed with zero duration`() {
        val msg = PhaseProgressLogger.completed("generate", 0, "42")
        assertEquals("[phase:generate] completed in 0s, tokens=42", msg)
    }

    @Test
    fun `completed rounds down seconds`() {
        val msgWithoutTokens = PhaseProgressLogger.completed("research", 1999)
        assertEquals("[phase:research] completed in 1s, tokens=N/A", msgWithoutTokens)
    }

    @Test
    fun `completed with large duration`() {
        val msgLargeDuration = PhaseProgressLogger.completed("assist", 123456)
        assertEquals("[phase:assist] completed in 123s, tokens=N/A", msgLargeDuration)
    }

    // ---------------------------------------------------------------
    // All four phases produce expected output via completed()
    // ---------------------------------------------------------------

    @Test
    fun `completed for four phases`() {
        val names = listOf("research", "analyze", "generate", "assist")
        for (phase in names) {
            val msg = PhaseProgressLogger.completed(phase, 1000)
            assertEquals("[phase:$phase] completed in 1s, tokens=N/A", msg)
        }
    }

    // ---------------------------------------------------------------
    // completed() with llmCalls (retry observability)
    // ---------------------------------------------------------------

    @Test
    fun `completed without llmCalls omits the count`() {
        val msg = PhaseProgressLogger.completed("research", 5000, "100")
        assertEquals("[phase:research] completed in 5s, tokens=100", msg)
    }

    @Test
    fun `completed with llmCalls appends count at the end`() {
        val msg = PhaseProgressLogger.completed("research", 5000, "100", 2)
        assertEquals("[phase:research] completed in 5s, tokens=100, 2 LLM calls", msg)
    }

    @Test
    fun `completed with llmCalls and no tokens uses NA fallback`() {
        val msg = PhaseProgressLogger.completed("research", 5000, null, 2)
        assertEquals("[phase:research] completed in 5s, tokens=N/A, 2 LLM calls", msg)
    }
}
