package com.scoopai.core.agent

import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.asUserMessage
import ai.koog.agents.core.dsl.extension.nodeExecuteToolsAndGetResults
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolBase
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Creates a three-phase graph strategy for generating Scoop manifests.
 *
 * Each phase runs as a subgraph with its own tool set and LLM parameters.
 * The strategy routes via conditional edges based on research phase output.
 */
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    generateTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (String) -> String)? = null,
    analyzePromptBuilder: (suspend (String) -> String)? = null,
    generatePromptBuilder: (suspend (String) -> String)? = null,
) = strategy<String, String>("scoop_manifest_generator") {

    val researchSubgraph by createPhaseSubgraph(
        name = "research",
        tools = researchTools,
        llmParams = llmParams,
        promptBuilder = researchPromptBuilder,
    )

    val analyzeSubgraph by createPhaseSubgraph(
        name = "analyze",
        tools = analyzeTools,
        llmParams = llmParams,
        promptBuilder = analyzePromptBuilder,
    )

    val generateSubgraph by createPhaseSubgraph(
        name = "generate",
        tools = generateTools,
        llmParams = llmParams,
        promptBuilder = generatePromptBuilder,
    )

    // ── Edges ───────────────────────────────────────────────────────
    edge(nodeStart forwardTo researchSubgraph)
    edge(researchSubgraph forwardTo analyzeSubgraph onCondition { parseResearchStatus(it) == "ok" })
    edge(researchSubgraph forwardTo nodeFinish onCondition { parseResearchStatus(it) != "ok" })
    edge(analyzeSubgraph forwardTo generateSubgraph)
    edge(generateSubgraph forwardTo nodeFinish)
}

/**
 * Creates a subgraph with a complete ReAct loop and pure-reasoning retry
 * for DeepSeek V4 thinking mode compatibility.
 *
 * Internal structure:
 * ```
 * nodeStart → nodeBuildPrompt → nodeLLM
 *   ├→ nodeFinish (text response)
 *   ├→ nodeExecuteTool → nodeSendToolResult → nodeLLM (tool loop)
 *   └→ nodeRetry (pure reasoning) → nodeLLM / nodeFallback → nodeFinish
 * ```
 *
 * @param promptBuilder Optional function to transform the subgraph input
 *   into a full prompt before sending to the LLM. If null, the raw input
 *   is used as the user message.
 */
internal fun createPhaseSubgraph(
    name: String,
    tools: List<ToolBase<*, *>>,
    llmParams: LLMParams? = null,
    promptBuilder: (suspend (String) -> String)? = null,
): AIAgentSubgraphDelegate<String, String> = subgraph<String, String>(
    name = name,
    tools = tools,
    llmParams = llmParams,
) {
    // ── Nodes ──────────────────────────────────────────────────────
    // First: optionally build the prompt from input
    val nodeBuildPrompt by node<String, String>("${name}_buildPrompt") { input ->
        promptBuilder?.invoke(input) ?: input
    }

    val nodeLLM by nodeLLMRequest()

    val nodeExecuteTool by nodeExecuteToolsAndGetResults()

    val nodeSendToolResult by nodeLLMSendToolResults()

    val nodeRetry by node<Message.Assistant, Message.Assistant>("${name}_retry") { response ->
        llm.writeSession {
            dropLastNMessages(1, preserveSystemMessages = true)
            appendPrompt {
                user("Continue. Provide either a final answer or a tool call with complete JSON arguments.")
            }
            requestLLM()
        }
    }

    val nodeFallback by node<Message.Assistant, String>("${name}_fallback") { _ ->
        llm.writeSession {
            dropLastNMessages(1, preserveSystemMessages = true)
        }
        "Model returned reasoning-only responses repeatedly; no final answer or tool call was produced."
    }

    // ── Edges ──────────────────────────────────────────────────────
    // Start → build prompt → LLM
    edge(nodeStart forwardTo nodeBuildPrompt)
    edge(nodeBuildPrompt forwardTo nodeLLM asUserMessage { it })

    // LLM → text → finish (normal path)
    edge(nodeLLM forwardTo nodeFinish onTextMessage { true })

    // LLM → tool calls → execute
    edge(nodeLLM forwardTo nodeExecuteTool onToolCalls { true })

    // LLM → pure reasoning → retry
    edge(nodeLLM forwardTo nodeRetry onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })

    // Retry → still reasoning → fallback
    edge(nodeRetry forwardTo nodeFallback onCondition { response ->
        response.parts.isNotEmpty() &&
            response.parts.none { it is MessagePart.Text || it is MessagePart.Tool.Call }
    })

    // Retry → text → finish
    edge(nodeRetry forwardTo nodeFinish onTextMessage { true })

    // Retry → tool calls → execute
    edge(nodeRetry forwardTo nodeExecuteTool onToolCalls { true })

    // Fallback → finish
    edge(nodeFallback forwardTo nodeFinish)

    // Tool execution → send results → LLM (via sendToolResult)
    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    // After tool results: text → finish, tool calls → execute again
    edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
}

/**
 * Extracts the `status` field from a JSON string.
 * Returns "unknown" if the JSON cannot be parsed.
 */
internal fun parseResearchStatus(output: String): String {
    return try {
        val json = kotlinx.serialization.json.Json
            .parseToJsonElement(output)
            .jsonObject
        json["status"]?.jsonPrimitive?.content ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
