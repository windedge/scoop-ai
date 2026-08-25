package com.scoopai.core

/**
 * Represents real-time tool activity events emitted during manifest generation.
 *
 * Consumed by UI hosts (such as Scooper) to display live tool progress
 * (e.g. fetching releases, downloading assets, calculating hashes).
 */
sealed interface ToolActivity {
    val toolName: String

    data class Started(
        override val toolName: String,
        val toolArgs: String,
    ) : ToolActivity

    data class Completed(
        override val toolName: String,
        val toolResult: String,
    ) : ToolActivity

    data class Failed(
        override val toolName: String,
        val error: String,
    ) : ToolActivity
}
