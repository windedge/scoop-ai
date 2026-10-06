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
        /**
         * Model's self-declared intent for this call, captured from the text of
         * the mixed (text + tool calls) LLM response that preceded it. Spans the
         * whole response batch: every tool call of that response shares it. Null
         * when that response carried no text alongside its tool calls.
         */
        val intent: String? = null,
    ) : ToolActivity

    data class Completed(
        override val toolName: String,
        val toolResult: String,
        /**
         * Same batch intent as [Started]: survives until the next LLM response
         * overwrites it, so hosts can keep showing "why" on the persistent
         * completion line.
         */
        val intent: String? = null,
    ) : ToolActivity

    data class Failed(
        override val toolName: String,
        val error: String,
        /** Same batch intent as [Started]/[Completed]; null when the
         * preceding LLM response carried no text alongside its tool calls. */
        val intent: String? = null,
    ) : ToolActivity
}
