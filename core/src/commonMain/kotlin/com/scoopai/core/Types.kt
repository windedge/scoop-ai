package com.scoopai.core

/**
 * Result of a full manifest generation run.
 *
 * @property manifest The final manifest JSON string (after fix loop if applicable).
 *   Empty when no Windows assets were found ([noAssets] = true).
 * @property suggestedAppName The app name extracted from the write tool's `path` parameter,
 *   or `null` if no write tool call was made.
 * @property noAssets True when the project has no Windows assets at all.
 * @property tokenUsage Token usage statistics, or null if not tracked.
 */
data class ManifestResult(
    val manifest: String,
    val suggestedAppName: String? = null,
    val noAssets: Boolean = false,
    val tokenUsage: TokenUsage? = null,
)

/**
 * Cumulative token usage across all LLM calls during a generation run.
 */
data class TokenUsage(
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
) {
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens
}

/**
 * Mutable accumulator used by `onLLMCallCompleted` event handlers to collect
 * token counts from both the main agent and the review agent.
 */
internal class TokenAccumulator {
    var input: Long = 0L
    var output: Long = 0L
}
