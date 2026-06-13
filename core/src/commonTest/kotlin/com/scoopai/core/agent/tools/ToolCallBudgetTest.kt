package com.scoopai.core.agent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCallBudgetTest {

    // ---------------------------------------------------------------
    // tryAcquire
    // ---------------------------------------------------------------

    @Test
    fun `tryAcquire returns true within limit`() {
        val budget = ToolCallBudget()
        assertTrue(budget.tryAcquire("testTool", 3), "First call should be within limit")
        assertTrue(budget.tryAcquire("testTool", 3), "Second call should be within limit")
        assertTrue(budget.tryAcquire("testTool", 3), "Third call should be within limit")
    }

    @Test
    fun `tryAcquire returns false when limit exceeded`() {
        val budget = ToolCallBudget()
        assertTrue(budget.tryAcquire("testTool", 2), "First call should succeed")
        assertTrue(budget.tryAcquire("testTool", 2), "Second call should succeed")
        assertFalse(budget.tryAcquire("testTool", 2), "Third call should be rejected")
    }

    @Test
    fun `tryAcquire tracks different tools independently`() {
        val budget = ToolCallBudget()
        assertTrue(budget.tryAcquire("toolA", 2))
        assertTrue(budget.tryAcquire("toolB", 2))
        assertTrue(budget.tryAcquire("toolA", 2))
        assertFalse(budget.tryAcquire("toolA", 2), "toolA should be exhausted")
        assertTrue(budget.tryAcquire("toolB", 2), "toolB should still have capacity")
        assertFalse(budget.tryAcquire("toolB", 2), "toolB should now be exhausted")
    }

    @Test
    fun `tryAcquire with limit 0 always returns false`() {
        val budget = ToolCallBudget()
        assertFalse(budget.tryAcquire("testTool", 0))
        assertFalse(budget.tryAcquire("testTool", 0))
    }

    // ---------------------------------------------------------------
    // Reset
    // ---------------------------------------------------------------

    @Test
    fun `reset clears all counters`() {
        val budget = ToolCallBudget()
        assertTrue(budget.tryAcquire("toolA", 1))
        assertFalse(budget.tryAcquire("toolA", 1), "toolA should be exhausted")
        budget.reset()
        assertTrue(budget.tryAcquire("toolA", 1), "After reset, toolA should be available again")
    }

    @Test
    fun `reset is idempotent`() {
        val budget = ToolCallBudget()
        budget.reset()
        assertTrue(budget.tryAcquire("toolA", 1))
    }

    // ---------------------------------------------------------------
    // usageCount
    // ---------------------------------------------------------------

    @Test
    fun `usageCount returns current count for a tool`() {
        val budget = ToolCallBudget()
        assertEquals(0, budget.usageCount("toolA"))
        budget.tryAcquire("toolA", 10)
        assertEquals(1, budget.usageCount("toolA"))
        budget.tryAcquire("toolA", 10)
        assertEquals(2, budget.usageCount("toolA"))
    }

    @Test
    fun `usageCount returns 0 for unknown tool`() {
        val budget = ToolCallBudget()
        assertEquals(0, budget.usageCount("neverCalled"))
    }
}
