package com.scoopai.cli

import java.io.PrintStream

/**
 * CLI progress overlay that prints single-line phase indicators to stderr
 * using carriage-return overprinting.
 *
 * This is independent of [com.scoopai.core.agent.PhaseProgressLogger] — the
 * logger writes permanent INFO lines to the logback appender, while this
 * class writes a transient overlay line (with \r) to stderr for TTY users.
 *
 * ## Activation (decided by the caller, typically [Main]):
 *  - `System.console() != null` (is a real TTY)
 *  - No `--no-progress` flag
 *  - Not `--output-format json`
 *
 * @param output The [PrintStream] to write to (default: [System.err]).
 * @param enabled Whether output is enabled. When `false`, all methods are no-ops.
 */
class ConsoleProgress(
    private val output: PrintStream = System.err,
    private var enabled: Boolean = true,
) {
    /** Print an initial status line. */
    fun start() {
        if (enabled) {
            output.print("\r[phase] initializing...")
        }
    }

    /**
     * Overwrite the current line with `[phase] <name>`.
     * No newline is printed — the next [phase] or [done] call overwrites this line.
     */
    fun phase(name: String) {
        if (enabled) {
            output.print("\r[phase] $name")
        }
    }

    /**
     * Finalize the progress display by advancing to the next line.
     * After this call, further output (e.g. from [Main]) appears on a fresh line.
     */
    fun done() {
        if (enabled) {
            output.println()
        }
    }

    /** Disable all subsequent output at runtime. */
    fun disable() {
        enabled = false
    }
}
