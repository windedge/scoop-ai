package com.scoopai.core.agent

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.asUserMessage
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Maximum consecutive pure-reasoning retries before falling back to a diagnostic text finish.
 */
private const val MAX_PURE_REASONING_RETRIES = 2

/**
 * A variant of Koog's [singleRunStrategy][ai.koog.agents.core.agent.singleRunStrategy]
 * that correctly handles DeepSeek V4 thinking mode quirks:
 *
 * 1. **Pure Reasoning responses** — DeepSeek can return `reasoning_content` without
 *    `content` or `tool_calls`. Koog's `requestLLM()` appends this as an assistant message.
 *    When sent back to DeepSeek, it causes a 400 error
 *    ("content or tool_calls must be set").
 *
 *    This strategy detects pure-reasoning responses, drops the last assistant message
 *    from prompt history (via [AIAgentLLMWriteSession.dropLastNMessages]), then retries
 *    the LLM request. This avoids sending an invalid assistant message back to DeepSeek.
 *
 *    If the model keeps returning pure reasoning after [MAX_PURE_REASONING_RETRIES]
 *    attempts, the strategy falls back to a diagnostic text finish — it does NOT expose
 *    the model's internal reasoning content.
 *
 *    This approach follows the same philosophy as Koog's
 *    `DeepSeekLLMClient.prepareMessagesForDeepSeek()`, which sanitizes assistant messages
 *    before sending them to the DeepSeek API.
 *
 * 2. **Truncated tool call JSON** — DeepSeek V4 thinking mode can produce incomplete
 *    tool call argument JSON (e.g. `{"owner": "sharkdp"`), causing parse errors in Koog's
 *    response decoder. Caught at the LLM request node and converted into a text message
 *    so the agent can retry instead of crashing. Non-JSON-parse errors are re-thrown.
 *
 * @param parallelTools if true, tools will be executed in parallel, otherwise sequentially
 */
@OptIn(kotlin.time.ExperimentalTime::class)
public fun thinkingCompatibleSingleRunStrategy(parallelTools: Boolean = false) = strategy<String, String>("thinking_compatible_single_run") {
    val nodeLLMRequest by node<Message.User, Message.Assistant>("nodeLLMRequest") { message ->
        requestLLMOrRecover(message)
    }

    /**
     * Retry node: invoked when the LLM returned a pure-reasoning response.
     * Drops the invalid assistant message from history, then re-requests.
     */
    val nodeRetryAfterPureReasoning by node<Message.Assistant, Message.Assistant>("nodeRetryAfterPureReasoning") { pureReasoningResponse ->
        llm.writeSession {
            // Remove the pure-reasoning assistant message that requestLLM() just appended.
            // Without this, the next request would include an invalid assistant message
            // (reasoningContent != null, content == null, toolCalls == null),
            // which DeepSeek rejects with 400.
            dropLastNMessages(1, preserveSystemMessages = true)
            appendPrompt {
                user("Continue. Provide either a final answer or a tool call with complete JSON arguments.")
            }
            requestLLM()
        }
    }

    /**
     * Fallback node: when the model returns pure reasoning repeatedly.
     * Outputs a diagnostic text and finishes. Does NOT expose reasoning content.
     */
    val nodeFallbackAfterRepeatedReasoning by node<Message.Assistant, String>("nodeFallbackAfterRepeatedReasoning") { _ ->
        llm.writeSession {
            // Drop the last pure-reasoning assistant message to keep history clean
            dropLastNMessages(1, preserveSystemMessages = true)
        }
        "Model returned reasoning-only responses repeatedly; no final answer or tool call was produced."
    }

    val nodeExecuteTool by nodeExecuteTools(parallel = parallelTools)

    // Standard edges (same as singleRunStrategy)
    edge(nodeStart forwardTo nodeLLMRequest asUserMessage { it })
    edge(nodeLLMRequest forwardTo nodeExecuteTool onToolCalls { true })
    edge(nodeLLMRequest forwardTo nodeFinish onTextMessage { true })
    edge(nodeExecuteTool forwardTo nodeLLMRequest)

    // Pure-reasoning edge: detected when response has parts but none are Text or Tool.Call
    edge(nodeLLMRequest forwardTo nodeRetryAfterPureReasoning onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })

    // Retry → check if the second attempt also returned pure reasoning
    edge(nodeRetryAfterPureReasoning forwardTo nodeFallbackAfterRepeatedReasoning onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })
    // Retry produced text → finish
    edge(nodeRetryAfterPureReasoning forwardTo nodeFinish onTextMessage { true })
    // Retry produced tool calls → execute
    edge(nodeRetryAfterPureReasoning forwardTo nodeExecuteTool onToolCalls { true })

    // Fallback → finish with diagnostic text
    edge(nodeFallbackAfterRepeatedReasoning forwardTo nodeFinish)
}

/**
 * Sends a user message to the LLM and returns the response.
 * Catches JSON parsing errors caused by truncated tool call arguments
 * (a DeepSeek V4 thinking mode issue) and returns a recoverable text message
 * instead of crashing the agent.
 *
 * Non-JSON-parse errors (network, auth, server) are re-thrown.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private suspend fun ai.koog.agents.core.agent.context.AIAgentGraphContextBase.requestLLMOrRecover(
    message: Message.User
): Message.Assistant {
    return try {
        llm.writeSession {
            appendPrompt {
                message(message)
            }
            requestLLM()
        }
    } catch (e: Exception) {
        if (isToolCallJsonParseError(e)) {
            Message.Assistant(
                parts = listOf(
                    MessagePart.Text(
                        "[JSON parsing error: The LLM produced a malformed tool call with " +
                            "truncated or invalid JSON arguments. Please retry your tool call " +
                            "with complete, well-formed JSON arguments. Error: ${e.message?.take(200)}"
                    )
                ),
                metaInfo = ai.koog.prompt.message.ResponseMetaInfo(
                    timestamp = kotlin.time.Clock.System.now()
                )
            )
        } else {
            throw e
        }
    }
}

/**
 * Returns true if the exception is caused by a JSON parsing error in tool call arguments,
 * as opposed to a real API/network/auth error.
 */
internal fun isToolCallJsonParseError(e: Exception): Boolean {
    val msg = e.message ?: return false
    return msg.contains("JSON token") ||
        msg.contains("Expected end of the object") ||
        msg.contains("JsonDecoding") ||
        msg.contains("tool call", ignoreCase = true) && msg.contains("JSON", ignoreCase = true)
}
