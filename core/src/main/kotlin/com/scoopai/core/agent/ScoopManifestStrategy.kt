package com.scoopai.core.agent

import ai.koog.agents.core.dsl.builder.AIAgentSubgraphDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/** Shared Json instance for phase output parsing (lenient). */
internal val phaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** Maximum parse-and-retry attempts inside nodeParse. */
private const val MAX_PARSE_ATTEMPTS = 3 // initial + 2 retries

/**
 * Creates a three-phase graph strategy for generating Scoop manifests.
 *
 * Each phase runs as a subgraph with its own tool set and LLM parameters.
 * The strategy routes linearly: research → analyze → generate → finish.
 *
 * - research:  String → ResearchOutput
 * - analyze:   ResearchOutput → AnalyzeOutput
 * - generate:  AnalyzeOutput → String
 */
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    generateTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (String) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    generatePromptBuilder: (suspend (AnalyzeOutput) -> String)? = null,
) = strategy<String, String>("scoop_manifest_generator") {

    val researchSubgraph by createPhaseSubgraph<String, ResearchOutput>(
        name = "research",
        tools = researchTools,
        llmParams = llmParams,
        promptBuilder = researchPromptBuilder,
        outputParser = { phaseJson.decodeFromString<ResearchOutput>(it) },
    )

    val analyzeSubgraph by createPhaseSubgraph<ResearchOutput, AnalyzeOutput>(
        name = "analyze",
        tools = analyzeTools,
        llmParams = llmParams,
        promptBuilder = analyzePromptBuilder,
        outputParser = { phaseJson.decodeFromString<AnalyzeOutput>(it) },
    )

    val generateSubgraph by createPhaseSubgraph<AnalyzeOutput, String>(
        name = "generate",
        tools = generateTools,
        llmParams = llmParams,
        promptBuilder = generatePromptBuilder,
        outputParser = { it },
    )

    // ── Edges (linear: research → analyze → generate → finish) ───
    edge(nodeStart forwardTo researchSubgraph)
    edge(researchSubgraph forwardTo analyzeSubgraph)
    edge(analyzeSubgraph forwardTo generateSubgraph)
    edge(generateSubgraph forwardTo nodeFinish)
}

/**
 * Creates a subgraph with a complete ReAct loop, pure-reasoning retry
 * for DeepSeek V4 thinking mode compatibility, and an output parse node
 * that converts the LLM text into a structured [Output] object.
 *
 * Internal nodes use `Any` to work around Koog's reified type constraints;
 * external type safety is preserved via the [AIAgentSubgraphDelegate] return type.
 *
 * Internal structure:
 * ```
 * nodeStart → nodeBuildPrompt → nodeLLM
 *   ├→ nodeParse → nodeFinish (text → structured parse path)
 *   ├→ nodeExecuteTool → nodeSendToolResult → nodeLLM (tool loop)
 *   └→ nodeRetry (pure reasoning) → nodeLLM / nodeFallback → nodeFinish
 * ```
 *
 * @param promptBuilder Optional function to transform the subgraph input
 *   into a full prompt before sending to the LLM. If null, the raw input
 *   is used as the user message.
 * @param outputParser Function to convert a JSON string (extracted from the
 *   LLM text response) into the structured [Output] type. Must never return
 *   null; throws on invalid input.
 */
@Suppress("UNCHECKED_CAST")
internal fun <Input, Output> createPhaseSubgraph(
    name: String,
    tools: List<ToolBase<*, *>>,
    llmParams: LLMParams? = null,
    promptBuilder: (suspend (Input) -> String)? = null,
    outputParser: (String) -> Output,
): AIAgentSubgraphDelegate<Input, Output> {
    @Suppress("UNCHECKED_CAST")
    val delegate = subgraph<Any, Any>(
        name = name,
        tools = tools,
        llmParams = llmParams,
    ) {
    // ── Nodes ──────────────────────────────────────────────────────
    // First: optionally build the prompt from input
    val nodeBuildPrompt by node<Any, String>("${name}_buildPrompt") { input ->
        if (promptBuilder == null) {
            input.toString()
        } else {
            runCatching { promptBuilder.invoke(input as Input) }
                .onFailure { logger.warn { "[${name}_buildPrompt] promptBuilder failed, using raw input: ${it.message}" } }
                .getOrDefault(input.toString())
        }
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

    /**
     * Parse node: extracts JSON from LLM text response and converts it to
     * the structured [Output] type. On failure, retries by sending error
     * feedback back to the LLM (up to [MAX_PARSE_ATTEMPTS] - 1 retries).
     */
    val nodeParse by node<String, Any>("${name}_parse") { text ->
        var currentText = text
        var lastError: String? = null

        for (attempt in 1..MAX_PARSE_ATTEMPTS) {
            try {
                val jsonStr = extractJsonBlock(currentText)
                    ?: throw IllegalArgumentException("No JSON code block or object found in LLM response")
                return@node outputParser(jsonStr) as Any
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown parse error"
                logger.warn { "[${name}_parse] Attempt $attempt failed: $lastError | response: ${currentText.take(500)}" }
                if (attempt >= MAX_PARSE_ATTEMPTS) break

                // Ask the LLM to fix its output.
                // The LLM might respond with tool calls instead of text
                // (e.g. it wants to call more tools before producing JSON).
                // In that case, stop retrying — we can't execute tools here.
                val retryResponse = llm.writeSession {
                    dropLastNMessages(1, preserveSystemMessages = true)
                    appendPrompt {
                        user(buildParseRetryPrompt(lastError!!))
                    }
                    requestLLM()
                }
                currentText = retryResponse.parts
                    .filterIsInstance<MessagePart.Text>()
                    .joinToString("") { it.text }
                if (currentText.isBlank()) {
                    logger.warn { "[${name}_parse] LLM retry returned no text (likely tool calls), stopping retries" }
                    break
                }
            }
        }

        logger.warn { "[${name}_parse] Parse failed after $MAX_PARSE_ATTEMPTS attempts, falling back to raw text: $lastError" }
        return@node currentText.takeIf { it.isNotBlank() } ?: text
    }

    // ── Edges ──────────────────────────────────────────────────────
    // Start → build prompt → LLM
    edge(nodeStart forwardTo nodeBuildPrompt)
    edge(nodeBuildPrompt forwardTo nodeLLM asUserMessage { it })

    // LLM → text → parse → finish (normal path with structured output)
    edge(nodeLLM forwardTo nodeParse onTextMessage { true })
    edge(nodeParse forwardTo nodeFinish)

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

    // Retry → text → parse → finish
    edge(nodeRetry forwardTo nodeParse onTextMessage { true })

    // Retry → tool calls → execute
    edge(nodeRetry forwardTo nodeExecuteTool onToolCalls { true })

    // Fallback → finish
    edge(nodeFallback forwardTo nodeFinish)

    // Tool execution → send results → LLM (via sendToolResult)
    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    // After tool results: text → parse → finish, tool calls → execute again
    edge(nodeSendToolResult forwardTo nodeParse onTextMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
    }
    @Suppress("UNCHECKED_CAST")
    return delegate as AIAgentSubgraphDelegate<Input, Output>
}

// ---------------------------------------------------------------------------
// JSON extraction helpers
// ---------------------------------------------------------------------------

/**
 * Extracts a JSON object string from arbitrary text.
 *
 * Priority:
 * 1. A ```json ... ``` (or ``` ... ```) code block whose content is valid JSON.
 * 2. The entire trimmed text if it is valid JSON.
 * 3. The first `{…}` substring that is valid JSON.
 *
 * Returns `null` if no valid JSON object is found.
 */
private val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

internal fun extractJsonBlock(text: String): String? {
    codeBlockRegex.find(text)?.let { match ->
        val candidate = match.groupValues[1].trim()
        if (isValidJson(candidate)) return candidate
    }
    // Try the whole text
    val trimmed = text.trim()
    if (isValidJson(trimmed)) return trimmed
    // Try first {…} span
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) {
        val sub = trimmed.substring(start, end + 1)
        if (isValidJson(sub)) return sub
    }
    return null
}

internal fun isValidJson(text: String): Boolean = try {
    kotlinx.serialization.json.Json.parseToJsonElement(text) is kotlinx.serialization.json.JsonObject
} catch (_: Exception) {
    false
}

internal fun buildParseRetryPrompt(error: String): String = """
Your previous response could not be parsed as valid JSON.

Error: $error

Please output ONLY the JSON object conforming to the required schema, with no additional text before or after it.
""".trimIndent()

/**
 * Extracts the `status` field from a JSON string.
 * Returns "unknown" if the JSON cannot be parsed.
 * Kept for backward compatibility with tests.
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
