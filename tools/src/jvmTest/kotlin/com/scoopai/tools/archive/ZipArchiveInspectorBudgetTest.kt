package com.scoopai.tools.archive

import com.scoopai.core.agent.tools.ToolCallBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipArchiveInspectorBudgetTest {

    // ---------------------------------------------------------------
    // Budget for searchTextInArchive
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive returns budget exhausted message when budget exceeded`() = kotlinx.coroutines.runBlocking {
        val budget = ToolCallBudget()
        val inspector = ZipArchiveInspector(
            cache = com.scoopai.tools.cache.ScoopCacheManager(keepCache = false),
            budget = budget,
        )
        // Exhaust the searchTextInArchive budget (limit 8)
        repeat(8) {
            assertTrue(budget.tryAcquire("searchTextInArchive", 8), "Call ${it + 1} should succeed")
        }
        val result = inspector.searchTextInArchive(
            url = "http://127.0.0.1:1/test.zip",
            patterns = "test",
        )
        assertTrue(result.contains("budget"), "Should mention budget: $result")
        assertTrue(result.contains("searchTextInArchive"), "Should mention tool name: $result")
    }

    @Test
    fun `searchTextInArchive works normally when budget not set`() = kotlinx.coroutines.runBlocking {
        val cache = com.scoopai.tools.cache.ScoopCacheManager(keepCache = false)
        val inspector = ZipArchiveInspector(cache) // no budget
        // Should not crash with NullPointerException
        val result = inspector.searchTextInArchive(
            url = "http://127.0.0.1:1/test.zip",
            patterns = "test",
        )
        // Should return an error JSON (not a crash)
        assertTrue(result.contains("error") || result.isNotEmpty())
    }
}
