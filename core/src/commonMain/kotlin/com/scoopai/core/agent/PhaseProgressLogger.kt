package com.scoopai.core.agent

/**
 * Pure utility for formatting phase progress log messages.
 *
 * This object can be tested directly (no mocks required) because it has no
 * side effects: it just generates formatted strings for [PhaseLogger] or
 * any logging frontend to emit.
 */
object PhaseProgressLogger {

    /**
     * Formats a "[phase:<name>] starting" message.
     * @param name Phase name (e.g. "research", "analyze").
     */
    fun starting(name: String): String = "[phase:$name] starting"

    /**
     * Formats a "[phase:<name>] completed in <seconds>s, tokens=<n>" message.
     *
     * @param name Phase name (e.g. "research", "analyze").
     * @param durationMs Elapsed wall-clock time in milliseconds.
     * @param tokens Token count string (e.g. "1234"), or null for "N/A".
     * @param llmCalls Optional number of LLM calls made during the phase.
     *                 When non-null, appends ", N LLM calls" at the end of the message
     *                 (useful for proving a Koog retry occurred, e.g. 2 calls in research).
     */
    fun completed(name: String, durationMs: Long, tokens: String? = null, llmCalls: Int? = null): String {
        val seconds = durationMs / 1000
        val tokenStr = tokens ?: "N/A"
        val base = "[phase:$name] completed in ${seconds}s, tokens=$tokenStr"
        return if (llmCalls != null) "$base, $llmCalls LLM calls" else base
    }
}
