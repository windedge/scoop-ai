package com.scoopai.core.observer

import com.scoopai.core.StageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationObserverTest {

    // ---------------------------------------------------------------
    // Single phase
    // ---------------------------------------------------------------

    @Test
    fun `single phase records correct StageInfo`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        observer.recordLlmCall(100, 50)
        observer.recordToolCall()
        observer.recordToolCall()
        observer.exitPhase("research", 5000)

        val stages = observer.snapshot()
        assertEquals(1, stages.size)
        val stage = stages[0]
        assertEquals("research", stage.phaseName)
        assertEquals(5000, stage.durationMs)
        assertEquals(100, stage.inputTokens)
        assertEquals(50, stage.outputTokens)
        assertEquals(2, stage.toolCallCount)
    }

    // ---------------------------------------------------------------
    // Multiple phases
    // ---------------------------------------------------------------

    @Test
    fun `multiple phases produce correct StageInfo list`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        observer.recordLlmCall(200, 80)
        observer.recordToolCall()
        observer.exitPhase("research", 10000)

        observer.enterPhase("analyze")
        observer.recordLlmCall(300, 120)
        observer.recordToolCall()
        observer.recordToolCall()
        observer.exitPhase("analyze", 15000)

        observer.enterPhase("generate")
        observer.recordLlmCall(100, 60)
        observer.exitPhase("generate", 12000)

        observer.enterPhase("refine")
        observer.recordLlmCall(50, 30)
        observer.recordToolCall()
        observer.exitPhase("refine", 8000)

        val stages = observer.snapshot()
        assertEquals(4, stages.size)

        assertEquals("research", stages[0].phaseName)
        assertEquals(10000, stages[0].durationMs)
        assertEquals(200, stages[0].inputTokens)
        assertEquals(80, stages[0].outputTokens)
        assertEquals(1, stages[0].toolCallCount)

        assertEquals("analyze", stages[1].phaseName)
        assertEquals(15000, stages[1].durationMs)
        assertEquals(300, stages[1].inputTokens)
        assertEquals(120, stages[1].outputTokens)
        assertEquals(2, stages[1].toolCallCount)

        assertEquals("generate", stages[2].phaseName)
        assertEquals(12000, stages[2].durationMs)
        assertEquals(100, stages[2].inputTokens)
        assertEquals(60, stages[2].outputTokens)
        assertEquals(0, stages[2].toolCallCount)

        assertEquals("refine", stages[3].phaseName)
        assertEquals(8000, stages[3].durationMs)
        assertEquals(50, stages[3].inputTokens)
        assertEquals(30, stages[3].outputTokens)
        assertEquals(1, stages[3].toolCallCount)
    }

    // ---------------------------------------------------------------
    // Empty snapshot
    // ---------------------------------------------------------------

    @Test
    fun `snapshot returns empty list before any enterPhase`() {
        val observer = GenerationObserver()
        assertTrue(observer.snapshot().isEmpty())
    }

    @Test
    fun `snapshot returns empty list after enterPhase without exitPhase`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        // Phase is still active, not captured in snapshot yet
        assertTrue(observer.snapshot().isEmpty())
    }

    // ---------------------------------------------------------------
    // recordLlmCall and recordToolCall without active phase
    // ---------------------------------------------------------------

    @Test
    fun `recordLlmCall without active phase does not crash`() {
        val observer = GenerationObserver()
        // No enterPhase called
        observer.recordLlmCall(100, 50) // Should not throw
        assertTrue(observer.snapshot().isEmpty())
    }

    @Test
    fun `recordToolCall without active phase does not crash`() {
        val observer = GenerationObserver()
        observer.recordToolCall() // Should not throw
        assertTrue(observer.snapshot().isEmpty())
    }

    // ---------------------------------------------------------------
    // Accumulation within a phase
    // ---------------------------------------------------------------

    @Test
    fun `multiple LLM calls in one phase accumulate tokens`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        observer.recordLlmCall(100, 50)
        observer.recordLlmCall(200, 100)
        observer.recordLlmCall(50, 25)
        observer.exitPhase("research", 10000)

        val stages = observer.snapshot()
        assertEquals(1, stages.size)
        assertEquals(350, stages[0].inputTokens)
        assertEquals(175, stages[0].outputTokens)
    }

    @Test
    fun `multiple tool calls in one phase accumulate count`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        observer.recordToolCall()
        observer.recordToolCall()
        observer.recordToolCall()
        observer.exitPhase("research", 5000)

        val stages = observer.snapshot()
        assertEquals(1, stages.size)
        assertEquals(3, stages[0].toolCallCount)
    }

    // ---------------------------------------------------------------
    // exitPhase tolerates skipped/mismatched phases (resume scenario)
    // ---------------------------------------------------------------

    @Test
    fun `exitPhase tolerates mismatched name`() {
        val observer = GenerationObserver()
        observer.enterPhase("research")
        // Resume scenario: exitPhase called with a different name
        // (skipped phase's edge transform still fires)
        observer.exitPhase("analyze", 1000) // Should NOT throw
        // research is still active; mismatched exit is silently ignored
    }

    @Test
    fun `exitPhase tolerates missing enterPhase`() {
        val observer = GenerationObserver()
        // Resume scenario: exitPhase called without any enterPhase
        observer.exitPhase("research", 1000) // Should NOT throw
        assertTrue(observer.snapshot().isEmpty())
    }

    @Test
    fun `exitPhase tolerates skipped phase in resume scenario`() {
        // Simulate resume: exitPhase("analyze") without enterPhase("analyze")
        // e.g. --from generate skips analyze
        val observer = GenerationObserver()
        observer.exitPhase("analyze", 1000) // Should not throw
        // Snapshot still empty (no enter/exit pair completed)
        assertTrue(observer.snapshot().isEmpty())
    }

    @Test
    fun `exitPhase tolerates mismatched phase in resume scenario`() {
        // Resume: entered generate, but exit receives research (leftover from skipped phase)
        val observer = GenerationObserver()
        observer.enterPhase("generate")
        observer.exitPhase("research", 1000) // Should not throw (research not entered)
        // generate is still current, not corrupted
        observer.exitPhase("generate", 2000)
        assertEquals(1, observer.snapshot().size)
        assertEquals("generate", observer.snapshot()[0].phaseName)
    }
}
