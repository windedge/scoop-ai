package com.scoopai.core

import kotlinx.serialization.Serializable

/**
 * Request to the unified Scoop AI execution session.
 */
@Serializable
sealed interface SessionRequest {
    /** Fresh manifest generation from a GitHub repository or release URL. */
    @Serializable
    data class Generate(
        val url: String,
        val outputPath: String? = null,
        val userPrompt: String? = null,
    ) : SessionRequest

    /** Interactive manifest assist based on feedback/instructions (a user follow-up). */
    @Serializable
    data class Assist(
        val currentManifest: String,
        val instruction: String,
        val history: List<String> = emptyList(),
        val evidence: String? = null,
        val suggestedAppName: String? = null,
    ) : SessionRequest
}

/**
 * Result of a full manifest generation run.
 *
 * @property manifest The final manifest JSON string (after fix loop if applicable).
 *   Empty when no Windows assets were found ([noAssets] = true).
 * @property suggestedAppName The app name extracted from the write tool's `path` parameter,
 *   or `null` if no write tool call was made.
 * @property noAssets True when the project has no Windows assets at all.
 * @property exhausted True when the assist/repair loop gave up after [maxRetries] attempts
 *   (the returned manifest still contains unresolved validation/review issues).
 *   Frontends must treat an exhausted result as NOT ready (e.g. show red, not green).
 * @property issues The list of unresolved validation/review errors captured when [exhausted]
 *   is true. Empty for a successful run.
 * @property answered True when the user follow-up was a pure question that the model
 *   answered without modifying the manifest. Constructively mutually exclusive with
 *   [exhausted]/[noAssets]; produced only by the answer-escape node. When true the
 *   [manifest] is the original input manifest echoed back verbatim.
 * @property tokenUsage Token usage statistics, or null if not tracked.
 * @property stages Per-phase execution statistics.
 * @property explanation AI's natural-language reply for the user's instruction/generation
 *   intent. Source: the optional `explanation` parameter of `finalize_task_result` in
 *   the generate/assist/repair subgraphs, taken from the round that produced the final
 *   manifest; intermediate repair rounds do not produce one. When null the application
 *   layer synthesizes a deterministic reply. The noAssets branch is synthesized by the
 *   engine from [com.scoopai.core.agent.ResearchOutput].
 */
@Serializable
data class ManifestResult(
    val manifest: String,
    val suggestedAppName: String? = null,
    val noAssets: Boolean = false,
    val exhausted: Boolean = false,
    val answered: Boolean = false,
    val issues: List<String> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    /** Total elapsed time in milliseconds */
    val durationMs: Long = 0L,
    /** Per-phase execution statistics (research/analyze/generate/assist|repair/verify). */
    val stages: List<StageInfo> = emptyList(),
    /**
     * AI 针对用户指令/生成意图所作出的自然语言说明与会话回复。
     * 来源: generate/assist/repair 子图 finalize_task_result 的可选 explanation 参数,
     * 取"产出最终 manifest 的那一轮"; 中间修复轮次不产生。
     * 为 null 时由应用层确定性合成。
     * noAssets 分支由引擎从 ResearchOutput 确定性合成。
     */
    val explanation: String? = null,
)

/**
 * Context passed to the assist subgraph. Serves two callers of the same subgraph entity:
 * - **assist**: a user follow-up ([explanatory] = true), where the finalize call is asked
 *   to produce an `explanation` and may optionally answer a pure question via the escape
 *   hatch (see [com.scoopai.core.agent.LenientStringFinishTool]).
 * - **repair**: the internal fix loop after verify fails ([explanatory] = false), where no
 *   explanation is required (saving tokens).
 *
 * @property manifest The current manifest JSON (may contain issues).
 * @property feedback Description of issues to fix.
 * @property retryCount Current refinement attempt (1-based).
 * @property explanatory True when this is a user-initiated assist (asks for an
 *   `explanation`); false for the internal repair loop (no explanation required).
 */
@kotlinx.serialization.Serializable
data class AssistContext(
    val manifest: String,
    val feedback: String,
    val retryCount: Int = 1,
    val history: List<String> = emptyList(),
    val evidence: String? = null,
    /** true = 用户指令发起的 assist (要求 explanation); false = 内部修复循环 (不要求)。 */
    val explanatory: Boolean = false,
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
 * @property phaseName Phase name ("research", "analyze", "generate", "assist", "repair", "verify").
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
