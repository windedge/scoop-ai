package com.scoopai.tools.interaction

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Safe replacement for Koog's built-in AskUser tool (`__ask_user__`).
 *
 * Read order: `System.console()` -> `System.in` -> default response.
 * Non-interactive mode prefers [nonInteractiveOverride], then
 * `SCOOP_AI_NON_INTERACTIVE` system property / env var.
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

    internal const val DEFAULT_RESPONSE = "Please make your best judgment and proceed autonomously."

    /**
     * When non-null, overrides property / env for non-interactive mode.
     * Set by [com.scoopai.tools.DefaultToolRegistryFactory] (or hosts directly).
     */
    @Volatile
    var nonInteractiveOverride: Boolean? = null

    fun isNonInteractive(): Boolean {
        nonInteractiveOverride?.let { return it }
        val flag = System.getProperty("SCOOP_AI_NON_INTERACTIVE")
            ?: System.getenv("SCOOP_AI_NON_INTERACTIVE")
            ?: return false
        return flag.isNotBlank() && flag != "false" && flag != "0"
    }

    private fun isStdinAvailable(): Boolean = try {
        java.lang.System.`in`.available() >= 0
    } catch (_: Exception) {
        false
    }

    override suspend fun execute(args: Args): String {
        if (isNonInteractive()) return DEFAULT_RESPONSE

        val console = java.lang.System.console()
        if (console != null) {
            console.printf("%s%n> ", args.message)
            return console.readLine()?.ifBlank { DEFAULT_RESPONSE } ?: DEFAULT_RESPONSE
        }

        if (isStdinAvailable()) {
            java.lang.System.out.printf("%s%n> ", args.message)
            java.lang.System.out.flush()
            val line = BufferedReader(InputStreamReader(java.lang.System.`in`)).readLine()
            return line?.ifBlank { DEFAULT_RESPONSE } ?: DEFAULT_RESPONSE
        }

        return DEFAULT_RESPONSE
    }
}
