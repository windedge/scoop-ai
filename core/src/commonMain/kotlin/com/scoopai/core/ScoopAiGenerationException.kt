package com.scoopai.core

/**
 * Typed failure for a manifest generation run that aborted because of an
 * LLM/model capability problem (a hard error), as opposed to
 * [ManifestResult.exhausted] which means the refine loop gave up but still
 * produced a manifest.
 *
 * Currently produced when Koog's `subgraphWithTask` gives up on a model that
 * never emits tool calls. Two live failure edges of `subgraphWithTask` are
 * matched deliberately by [isSubgraphTaskToolCallExhaustion]:
 *
 * 1. re-prompt exhaustion (AIAgentSubgraphExt.kt:739):
 *    "Unable to finish subgraph with task... does not support tool choice...
 *    was not able to call `<finishTool>` tool after <3> attempts."
 * 2. the unhandled fallback edge (AIAgentSubgraphExt.kt:770):
 *    "Unhandled response from LLM. Subgraph with task must always call
 *    tools..." thrown by the final routing edge when neither `onToolCalls`
 *    nor `onTextMessage` matched (e.g. a reasoning-only response). scoop-ai
 *    strips [ai.koog.prompt.llm.LLMCapability.ToolChoice] from every model
 *    (see `ScoopManifestGenerator.modelWithoutToolChoice`), so this fallback
 *    edge is a live path, not an over-broad match.
 *
 * ANY model/gateway that cannot emit OpenAI-style `tool_calls` ends here.
 *
 * [message] is crafted for end users and already contains an actionable hint
 * (likely causes + how to diagnose + suggested fix). Frontends should surface
 * it directly; the original Koog exception is preserved as [cause].
 */
class ScoopAiGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Marker of Koog's `subgraphWithTask` re-prompt exhaustion error
 * (AIAgentSubgraphExt.kt, handleAssistantMessage node).
 */
internal const val SUBGRAPH_TASK_EXHAUSTION_MARKER = "Unable to finish subgraph with task"

/**
 * Marker of the sibling Koog errors from `subgraphWithTask` that share the
 * "Subgraph with task must always call tools" wording:
 * - AIAgentSubgraphExt.kt:730 (model HAS ToolChoice capability but returned a
 *   text-only response; scoop-ai strips ToolChoice so this variant is
 *   defensive, e.g. if capabilities are ever restored);
 * - AIAgentSubgraphExt.kt:770 ("Unhandled response from LLM...", the
 *   fallback routing edge reached when neither the tool_calls nor the text
 *   edge matched; with ToolChoice stripped this is a LIVE path for
 *   reasoning-only/empty responses, not an over-broad match).
 * One marker covers both: it is deliberately not narrowed to the exact
 * ToolChoice-capable wording so the unhandled fallback edge stays detected.
 */
internal const val SUBGRAPH_TASK_MUST_CALL_TOOLS_MARKER = "Subgraph with task must always call tools"

/** Max cause-chain depth inspected by [isSubgraphTaskToolCallExhaustion]. */
private const val CAUSE_CHAIN_MAX_DEPTH = 5

/**
 * Detects whether [e] (or a wrapped cause) is Koog's
 * "model never called a tool / subgraphWithTask exhausted" failure.
 *
 * Matches on message content because Koog throws a bare
 * IllegalStateException via `error(...)`; message matching also survives
 * minor wording changes between Koog versions and wrappers.
 */
internal fun isSubgraphTaskToolCallExhaustion(e: Throwable): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < CAUSE_CHAIN_MAX_DEPTH) {
        val msg = current.message
        if (msg != null &&
            (
                SUBGRAPH_TASK_EXHAUSTION_MARKER in msg ||
                    SUBGRAPH_TASK_MUST_CALL_TOOLS_MARKER in msg
                )
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * Builds the user-facing error for a "model never called a tool" failure,
 * embedding the model id, the failing phase, the likely causes, and a
 * diagnosis recipe (SCOOP_AI_DEBUG_HTTP observation points).
 */
internal fun buildToolCallExhaustionMessage(
    modelId: String,
    phase: String?,
    originalMessage: String,
): String {
    val where = phase?.let { " during the '$it' phase" } ?: ""
    return "Manifest generation failed$where: the model '$modelId' never issued a tool call after repeated reminders. " +
        "The workflow requires an LLM with function calling (OpenAI-style \"tool_calls\"): the model must call tools " +
        "and finish each phase via the 'finalize_task_result' tool. " +
        "Likely causes: the model or gateway has no function-calling support, the model writes tool calls as plain " +
        "text inside its content, or it only emits reasoning. " +
        "Fix: switch to a tool-capable model on this gateway (e.g. deepseek-chat / gpt-4o / claude) or a route with " +
        "function calling enabled. " +
        "To confirm, re-run with SCOOP_AI_DEBUG_HTTP=true and inspect the responses: " +
        "a missing \"tool_calls\" field means the model/gateway lacks function calling; " +
        "tool-call-like text inside \"content\" means the model cannot emit structured calls (unsupported); " +
        "null \"content\" with \"reasoning_content\" set means a pure reasoning model. " +
        "Original error: $originalMessage"
}
