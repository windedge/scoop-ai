package com.scoopai.core.observer

import com.scoopai.core.StageInfo

/**
 * Records per-phase execution statistics during a manifest generation run.
 *
 * This observer is [reset] between generation runs. Phases are sequential and
 * non-overlapping. Each call sequence is:
 *
 * ```
 * enterPhase("research")
 *   recordLlmCall(...)
 *   recordToolCall()
 * exitPhase("research", durationMs)
 *
 * enterPhase("analyze")
 *   ...
 * exitPhase("analyze", durationMs)
 * ```
 *
 * [snapshot] returns all completed phases. [exitPhase] is tolerant of
 * skipped or mismatched phases (e.g. during checkpoint resume) and
 * silently returns if no phase is active or the name does not match.
 */
class GenerationObserver {

    private class PhaseAccumulator(
        val phaseName: String,
    ) {
        var inputTokens: Long = 0L
        var outputTokens: Long = 0L
        var toolCallCount: Int = 0
        var llmCallCount: Int = 0
    }

    private var current: PhaseAccumulator? = null
    private val completed = mutableListOf<StageInfo>()
    /** Number of LLM calls recorded in the most recently completed (exited) phase. */
    private var lastLlmCallCount = 0

    /**
     * Start tracking a new phase.
     * Any previous (unclosed) phase is discarded.
     */
    fun enterPhase(name: String) {
        current = PhaseAccumulator(name)
    }

    /**
     * Finalize the current phase and store its [StageInfo].
     *
     * This method is tolerant of skipped or mismatched phases:
     * if no phase is active or the active phase does not match [name],
     * it silently returns. This accommodates checkpoint resume scenarios
     * where a skipped phase's edge transform still fires [exitPhase]
     * without a corresponding [enterPhase].
     */
    fun exitPhase(name: String, durationMs: Long) {
        val phase = current ?: return
        if (phase.phaseName != name) return
        completed.add(
            StageInfo(
                phaseName = name,
                durationMs = durationMs,
                inputTokens = phase.inputTokens,
                outputTokens = phase.outputTokens,
                toolCallCount = phase.toolCallCount,
            )
        )
        lastLlmCallCount = phase.llmCallCount
        current = null
    }

    /**
     * Accumulate an LLM call's token counts to the current phase.
     * No-op if no phase is active.
     */
    fun recordLlmCall(inputTokens: Long, outputTokens: Long) {
        current?.let {
            it.inputTokens += inputTokens
            it.outputTokens += outputTokens
            it.llmCallCount++
        }
    }

    /** Increment the tool call counter for the current phase.
     * No-op if no phase is active.
     */
    fun recordToolCall() {
        current?.let {
            it.toolCallCount++
        }
    }

    /**
     * Number of LLM calls recorded in the most recently completed (exited) phase.
     * Returns 0 before any phase has been exited. Useful for logging the
     * per-phase LLM call count (e.g. proving a Koog retry occurred).
     */
    fun lastCompletedLlmCallCount(): Int = lastLlmCallCount

    /**
     * Return all completed phases in order.
     * Active (non-exited) phases are not included.
     */
    fun snapshot(): List<StageInfo> = completed.toList()

    /**
     * Clear all state so the observer can be reused.
     */
    fun reset() {
        current = null
        completed.clear()
        lastLlmCallCount = 0
    }
}
