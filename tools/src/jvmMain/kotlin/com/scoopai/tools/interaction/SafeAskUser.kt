package com.scoopai.tools.interaction

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A safe replacement for Koog's built-in AskUser tool (`__ask_user__`).
 *
 * Tries to read from the terminal in this order:
 * 1. `System.console()` — works when JVM is launched directly from a terminal
 * 2. `System.in` wrapped in BufferedReader — works when launched via batch/script wrappers
 *    where System.console() is null but stdin is still connected to a terminal
 * 3. Default response — when stdin is not available (piped input, CI, etc.)
 */
@LLMDescription("Ask the user a question and wait for their response. In non-interactive mode, returns a default answer so the agent proceeds autonomously.")
object SafeAskUser : SimpleTool<SafeAskUser.Args>(
    typeToken<Args>(),
    "__ask_user__",
    "Ask the user a question and get their response"
) {

    @Serializable
    @LLMDescription("Arguments for asking the user a question")
    data class Args(
        @LLMDescription("The question or message to present to the user")
        val message: String
    )

    private const val DEFAULT_RESPONSE = "Please make your best judgment and proceed autonomously."

    /**
     * Checks whether stdin is connected to a real terminal (not piped/redirected).
     * On Windows, when running from a batch file, System.console() is null but
     * System.in is still usable if the terminal is interactive.
     */
    private fun isStdinAvailable(): Boolean {
        return try {
            java.lang.System.`in`.available() >= 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun execute(args: Args): String {
        // Non-interactive mode — skip prompts, return default so agent proceeds autonomously
        val nonInteractive = (System.getProperty("SCOOP_AI_NON_INTERACTIVE") ?: System.getenv("SCOOP_AI_NON_INTERACTIVE"))?.let {
            it.isNotBlank() && it != "false" && it != "0"
        } ?: false
        if (nonInteractive) {
            return DEFAULT_RESPONSE
        }

        val console = java.lang.System.console()

        // Approach 1: System.console() (direct terminal launch)
        if (console != null) {
            console.printf("%s%n> ", args.message)
            val line = console.readLine() ?: DEFAULT_RESPONSE
            return line.ifBlank { DEFAULT_RESPONSE }
        }

        // Approach 2: System.in fallback (batch file / script wrapper launch)
        if (isStdinAvailable()) {
            java.lang.System.out.printf("%s%n> ", args.message)
            java.lang.System.out.flush()
            val reader = BufferedReader(InputStreamReader(java.lang.System.`in`))
            val line = reader.readLine() ?: DEFAULT_RESPONSE
            return line.ifBlank { DEFAULT_RESPONSE }
        }

        // Approach 3: Non-interactive — return default so agent can continue
        return DEFAULT_RESPONSE
    }
}
