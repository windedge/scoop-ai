package com.scoopai.tools.web

import com.scoopai.core.agent.tools.ToolCallBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdkHttpWebFetcherBudgetTest {

    // ---------------------------------------------------------------
    // Budget for fetchWebPage
    // ---------------------------------------------------------------

    @Test
    fun `fetchWebPage returns budget exhausted message when budget exceeded`() = kotlinx.coroutines.runBlocking {
        val budget = ToolCallBudget()
        val fetcher = JdkHttpWebFetcher(budget = budget)
        // Exhaust the fetchWebPage budget (limit 10)
        repeat(10) {
            assertTrue(budget.tryAcquire("fetchWebPage", 10), "Call ${it + 1} should succeed")
        }
        // Now the budget is exhausted - fetchWebPage should return immediate message without HTTP call
        val result = fetcher.fetchWebPage("http://127.0.0.1:1/test")
        assertTrue(result.contains("budget"), "Should mention budget: $result")
        assertTrue(result.contains("fetchWebPage"), "Should mention tool name: $result")
    }

    @Test
    fun `fetchWebPage works normally when budget not set`() = kotlinx.coroutines.runBlocking {
        val fetcher = JdkHttpWebFetcher() // no budget
        // This will try to actually fetch, but we just want to verify the method doesn't crash
        // with NullPointerException on the budget check
        val result = fetcher.fetchWebPage("http://127.0.0.1:1/test")
        // Should return an error JSON (not a crash)
        assertTrue(result.contains("error") || result.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // Budget for resolveRedirect
    // ---------------------------------------------------------------

    @Test
    fun `resolveRedirect returns budget exhausted message when budget exceeded`() = kotlinx.coroutines.runBlocking {
        val budget = ToolCallBudget()
        val fetcher = JdkHttpWebFetcher(budget = budget)
        // Exhaust the resolveRedirect budget (limit 6)
        repeat(6) {
            assertTrue(budget.tryAcquire("resolveRedirect", 6), "Call ${it + 1} should succeed")
        }
        val result = fetcher.resolveRedirect("http://127.0.0.1:1/test")
        assertTrue(result.contains("budget"), "Should mention budget: $result")
        assertTrue(result.contains("resolveRedirect"), "Should mention tool name: $result")
    }
}
