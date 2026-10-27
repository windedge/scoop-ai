package com.scoopai.core.agent

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Start/duration store for strategy phases, replacing the five per-phase
 * TimeMark variables. Each enter/exit pair brackets one phase execution and
 * mirrors the observer's enterPhase/exitPhase calls; loop phases (verify,
 * assist, repair) simply run more pairs, the latest one winning.
 *
 * [clock] is injectable so tests can produce deterministic durations.
 */
class PhaseTelemetry(private val clock: () -> TimeMark = TimeSource.Monotonic::markNow) {

    private val starts = mutableMapOf<String, TimeMark>()
    private val lastDurations = mutableMapOf<String, Long>()

    /** Marks [phase] as started; a previous unclosed mark is overwritten. */
    fun enter(phase: String) {
        starts[phase] = clock()
    }

    /**
     * Closes the current [phase] bracket and returns its elapsed milliseconds;
     * 0 when the phase has no open mark (mismatched or double exit).
     */
    fun exit(phase: String): Long {
        val start = starts.remove(phase) ?: return 0L
        val elapsed = start.elapsedNow().inWholeMilliseconds
        lastDurations[phase] = elapsed
        return elapsed
    }

    /** Latest exited duration for [phase]; 0 when it never completed a pair. */
    fun durationOf(phase: String): Long = lastDurations[phase] ?: 0L

    /** Snapshot of every exited phase mapped to its latest duration. */
    fun durations(): Map<String, Long> = lastDurations.toMap()
}
