package com.scoopai.core

import kotlinx.serialization.Serializable

/**
 * Result of a full manifest generation run.
 *
 * @property manifest The final manifest JSON string (after fix loop if applicable).
 *   Empty when no Windows assets were found ([noAssets] = true).
 * @property suggestedAppName The app name extracted from the write tool's `path` parameter,
 *   or `null` if no write tool call was made.
 * @property noAssets True when the project has no Windows assets at all.
 * @property exhausted True when the refine loop gave up after [maxRetries] attempts
 *   (the returned manifest still contains unresolved validation/review issues).
 *   Frontends must treat an exhausted result as NOT ready (e.g. show red, not green).
 * @property issues The list of unresolved validation/review errors captured when [exhausted]
 *   is true. Empty for a successful run.
 * @property tokenUsage Token usage statistics, or null if not tracked.
 * @property stages Per-phase execution statistics.
 */
@Serializable
data class ManifestResult(
    val manifest: String,
    val suggestedAppName: String? = null,
    val noAssets: Boolean = false,
    val exhausted: Boolean = false,
    val issues: List<String> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    /** Total elapsed time in milliseconds */
    val durationMs: Long = 0L,
    /** Per-phase execution statistics (4 stages: research/analyze/generate/refine). */
    val stages: List<StageInfo> = emptyList(),
)

/**
 * Context passed to the refine subgraph when manifest validation/review fails.
 *
 * @property manifest The current manifest JSON (may contain issues).
 * @property feedback Description of issues to fix.
 * @property retryCount Current refinement attempt (1-based).
 */
@kotlinx.serialization.Serializable
data class RefineContext(
    val manifest: String,
    val feedback: String,
    val retryCount: Int,
)

/**
 * Cumulative token usage across all LLM calls during a generation run.
 */
@Serializable
data class TokenUsage(
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
) {
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens
}

/**
 * Data for one execution phase in a manifest generation run.
 *
 * @property phaseName Phase name ("research", "analyze", "generate", "refine").
 * @property durationMs Wall-clock duration of the phase in milliseconds.
 * @property inputTokens Cumulative input tokens consumed by LLM calls in this phase.
 * @property outputTokens Cumulative output tokens produced by LLM calls in this phase.
 * @property toolCallCount Number of tool calls made during this phase.
 */
@Serializable
data class StageInfo(
    val phaseName: String,
    val durationMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCallCount: Int,
)

/**
 * Mutable accumulator used by `onLLMCallCompleted` event handlers to collect
 * token counts from both the main agent and the review agent.
 */
internal class TokenAccumulator {
    var input: Long = 0L
    var output: Long = 0L
}
