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
 * [snapshot] returns all completed phases. An exception is thrown if
 * [exitPhase] is called with a name that does not match the current phase,
 * or if [exitPhase] is called without a preceding [enterPhase].
 */
class GenerationObserver {

    private class PhaseAccumulator(
        val phaseName: String,
    ) {
        var inputTokens: Long = 0L
        var outputTokens: Long = 0L
        var toolCallCount: Int = 0
    }

    private var current: PhaseAccumulator? = null
    private val completed = mutableListOf<StageInfo>()

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
     * @throws IllegalArgumentException if [name] does not match the current phase
     *   or if no phase is active.
     */
    fun exitPhase(name: String, durationMs: Long) {
        val phase = current ?: throw IllegalArgumentException(
            "exitPhase(\"$name\") called but no phase is active"
        )
        require(phase.phaseName == name) {
            "exitPhase(\"$name\") called but current phase is \"${phase.phaseName}\""
        }
        completed.add(
            StageInfo(
                phaseName = name,
                durationMs = durationMs,
                inputTokens = phase.inputTokens,
                outputTokens = phase.outputTokens,
                toolCallCount = phase.toolCallCount,
            )
        )
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
    }
}
