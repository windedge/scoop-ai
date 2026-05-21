package com.scoopai.core.agent

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.params.LLMParams
import com.scoopai.core.GenerateResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared Json instance for phase output parsing (lenient). */
internal val phaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Creates a graph strategy for generating Scoop manifests.
 *
 * Each phase runs as a [subgraphWithTask]: the LLM uses tools to gather data,
 * then calls `finalize_task_result` to return a structured result.
 *
 * - research:  String → [ResearchOutput]
 * - analyze:   [ResearchOutput] → [AnalyzeOutput]
 * - generate:  [AnalyzeOutput] → String
 * - wrap:      String → [GenerateResult]
 */
@OptIn(InternalAgentToolsApi::class)
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    generateTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (String) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    generatePromptBuilder: (suspend (AnalyzeOutput) -> String)? = null,
) = strategy<String, GenerateResult>("scoop_manifest_generator") {

    // Capture suggested_app_name from ResearchOutput for the final wrap node
    var suggestedAppName: String? = null

    val research by subgraphWithTask<String, ResearchOutput>(
        tools = researchTools,
        llmParams = llmParams,
    ) { input ->
        researchPromptBuilder?.invoke(input) ?: input.toString()
    }

    val analyze by subgraphWithTask<ResearchOutput, AnalyzeOutput>(
        tools = analyzeTools,
        llmParams = llmParams,
    ) { input ->
        suggestedAppName = input.suggested_app_name
        analyzePromptBuilder?.invoke(input) ?: input.toString()
    }

    val generate by subgraphWithTask<AnalyzeOutput, String>(
        tools = generateTools,
        llmParams = llmParams,
    ) { input ->
        generatePromptBuilder?.invoke(input) ?: input.toString()
    }

    val wrapResult by node<String, GenerateResult>("wrapResult") { manifest ->
        GenerateResult(manifest, suggestedAppName)
    }

    // ── Edges: research → analyze → generate → wrap → finish ──
    edge(nodeStart forwardTo research)
    edge(research forwardTo analyze)
    edge(analyze forwardTo generate)
    edge(generate forwardTo wrapResult)
    edge(wrapResult forwardTo nodeFinish)
}

// ---------------------------------------------------------------------------
// JSON helpers (kept for ScoopManifestGenerator's validateAndFix)
// ---------------------------------------------------------------------------

private val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

internal fun extractJsonBlock(text: String): String? {
    codeBlockRegex.find(text)?.let { match ->
        val candidate = match.groupValues[1].trim()
        if (isValidJson(candidate)) return candidate
    }
    val trimmed = text.trim()
    if (isValidJson(trimmed)) return trimmed
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
