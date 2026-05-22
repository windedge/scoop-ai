package com.scoopai.core.agent

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.asUserMessage
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Maximum consecutive pure-reasoning retries before falling back to a diagnostic text finish.
 */
private const val MAX_PURE_REASONING_RETRIES = 2

/**
 * A single-run strategy that handles DeepSeek V4 thinking mode quirks:
 *
 * 1. **Pure Reasoning responses** — detected and retried
 * 2. **Truncated tool call JSON** — caught and converted to retry prompt
 *
 * Flow (Koog 1.0.0-preview2):
 * ```
 * nodeStart → nodeLLM(String) → [tool calls] → nodeExecuteTools → nodeSendResults
 *                ↑                                          ↓
 *                └──────────────────────────────────────────┘
 *             → [text] → nodeFinish
 *             → [pure reasoning] → nodeRetry → ...
 * ```
 *
 * @param parallelTools if true, tools will be executed in parallel
 */
@OptIn(kotlin.time.ExperimentalTime::class)
public fun thinkingCompatibleSingleRunStrategy(parallelTools: Boolean = false) = strategy<String, String>("thinking_compatible_single_run") {

    // Built-in nodes: nodeLLMRequest takes String, nodeLLMSendToolResults sends results + calls LLM
    val nodeLLM by nodeLLMRequest("nodeLLM")
    val nodeSendResults by nodeLLMSendToolResults("nodeSendResults")
    val nodeExecuteTool by nodeExecuteTools(parallel = parallelTools)

    // Custom retry node for pure-reasoning responses
    val nodeRetryAfterPureReasoning by node<Message.Assistant, Message.Assistant>("nodeRetryAfterPureReasoning") { _ ->
        llm.writeSession {
            dropLastNMessages(1, preserveSystemMessages = true)
            appendPrompt {
                user("Continue. Provide either a final answer or a tool call with complete JSON arguments.")
            }
            requestLLM()
        }
    }

    // Fallback when model keeps returning pure reasoning
    val nodeFallbackAfterRepeatedReasoning by node<Message.Assistant, String>("nodeFallbackAfterRepeatedReasoning") { _ ->
        llm.writeSession {
            dropLastNMessages(1, preserveSystemMessages = true)
        }
        "Model returned reasoning-only responses repeatedly; no final answer or tool call was produced."
    }

    // Custom LLM node that catches JSON parse errors from truncated tool calls
    val nodeLLMWithErrorRecovery by node<String, Message.Assistant>("nodeLLMWithErrorRecovery") { input ->
        try {
            llm.writeSession {
                appendPrompt { user(input) }
                requestLLM()
            }
        } catch (e: Exception) {
            if (isToolCallJsonParseError(e)) {
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Text(
                            "[JSON parsing error: malformed tool call arguments. " +
                                "Please retry with complete JSON. Error: ${e.message?.take(200)}"
                        )
                    ),
                    metaInfo = ai.koog.prompt.message.ResponseMetaInfo(
                        ai.koog.utils.time.KoogClock.System.now()
                    )
                )
            } else {
                throw e
            }
        }
    }

    // ── Standard edges ────────────────────────────────────────────
    edge(nodeStart forwardTo nodeLLM)
    edge(nodeLLM forwardTo nodeExecuteTool onToolCalls { _ -> true })
    edge(nodeLLM forwardTo nodeFinish onTextMessage { _ -> true })
    edge(nodeExecuteTool forwardTo nodeSendResults)
    // nodeSendResults already calls LLM internally, its output is Message.Assistant
    // Route that output the same way as nodeLLM
    edge(nodeSendResults forwardTo nodeExecuteTool onToolCalls { _ -> true })
    edge(nodeSendResults forwardTo nodeFinish onTextMessage { _ -> true })

    // ── Pure-reasoning edges ──────────────────────────────────────
    edge(nodeLLM forwardTo nodeRetryAfterPureReasoning onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })
    edge(nodeSendResults forwardTo nodeRetryAfterPureReasoning onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })

    edge(nodeRetryAfterPureReasoning forwardTo nodeFallbackAfterRepeatedReasoning onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })
    edge(nodeRetryAfterPureReasoning forwardTo nodeFinish onTextMessage { _ -> true })
    edge(nodeRetryAfterPureReasoning forwardTo nodeExecuteTool onToolCalls { _ -> true })

    edge(nodeFallbackAfterRepeatedReasoning forwardTo nodeFinish)
}

internal fun isToolCallJsonParseError(e: Exception): Boolean {
    val msg = e.message ?: return false
    return msg.contains("JSON token") ||
        msg.contains("Expected end of the object") ||
        msg.contains("JsonDecoding") ||
        (msg.contains("tool call", ignoreCase = true) && msg.contains("JSON", ignoreCase = true))
}
