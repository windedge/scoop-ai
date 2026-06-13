package com.scoopai.core.agent.tools

/**
 * Per-agent-run budget tracker for expensive tool calls.
 *
 * The agent runs synchronously (one tool call at a time), so this class
 * does not need synchronization. Call [reset] before each [ai.koog.agents.core.AIAgent.run]
 * invocation to clear counters from the previous run.
 */
class ToolCallBudget {

    private val counters = mutableMapOf<String, Int>()

    /**
     * Try to acquire a budget slot for [toolName].
     * @return `true` if the call is within the [limit], `false` if the budget is exhausted.
     */
    fun tryAcquire(toolName: String, limit: Int): Boolean {
        if (limit <= 0) return false
        val current = counters.getOrDefault(toolName, 0)
        if (current >= limit) return false
        counters[toolName] = current + 1
        return true
    }

    /** Returns the number of times [toolName] has been called in this run. */
    fun usageCount(toolName: String): Int = counters.getOrDefault(toolName, 0)

    /** Reset all counters for a new agent run. */
    fun reset() {
        counters.clear()
    }

    companion object {
        /** Budget limits for expensive tools. */
        const val FETCH_WEB_PAGE_LIMIT = 10
        const val SEARCH_TEXT_IN_ARCHIVE_LIMIT = 8
        const val RESOLVE_REDIRECT_LIMIT = 6

        /** Message returned when a tool budget is exhausted. */
        fun exhaustedMessage(toolName: String, limit: Int): String =
            "Tool budget for '$toolName' exhausted ($limit calls used). Proceed with data already collected and call finalize_task_result."
    }
}
