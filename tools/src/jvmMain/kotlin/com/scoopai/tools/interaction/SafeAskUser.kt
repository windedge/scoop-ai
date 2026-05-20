package com.scoopai.tools.interaction

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * A safe replacement for Koog's built-in AskUser tool (`__ask_user__`).
 *
 * In interactive mode (System.console() != null), it prompts the user and reads from stdin.
 * In non-interactive mode (piped stdin, CI, etc.), it returns a default response so the
 * agent can proceed autonomously instead of crashing with ReadAfterEOFException.
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

    override suspend fun execute(args: Args): String {
        val console = System.console()
        return if (console != null) {
            // Interactive mode: actually ask the user
            console.printf("%s%n> ", args.message)
            val line = console.readLine() ?: DEFAULT_RESPONSE
            line.ifBlank { DEFAULT_RESPONSE }
        } else {
            // Non-interactive: return default so agent can continue
            DEFAULT_RESPONSE
        }
    }
}