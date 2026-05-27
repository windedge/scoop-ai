package com.scoopai.core.agent

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.params.LLMParams
import com.scoopai.core.GenerateResult
import com.scoopai.core.agent.nodes.assembleManifestSkeleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Flow:
 * ```
 * nodeStart → research(LLM) →
 *   [no_windows] → finishNoAssets
 *   [ok] → analyze(LLM) → filterBin(deterministic) → generate(LLM) → wrapResult → nodeFinish
 * ```
 *
 * Each LLM phase runs as a [subgraphWithTask]: the LLM uses tools to gather data,
 * then calls `finalize_task_result` to return a structured result.
 *
 * Deterministic nodes:
 * - filterBin: Removes helper/proxy/updater exe from bin list
 */
@OptIn(InternalAgentToolsApi::class)
fun scoopManifestStrategy(
    researchTools: List<ToolBase<*, *>> = emptyList(),
    analyzeTools: List<ToolBase<*, *>> = emptyList(),
    generateTools: List<ToolBase<*, *>> = emptyList(),
    llmParams: LLMParams? = null,
    researchPromptBuilder: (suspend (String) -> String)? = null,
    analyzePromptBuilder: (suspend (ResearchOutput) -> String)? = null,
    /** Replaces generatePromptBuilder. Receives (skeleton, analyzeOutput, researchOutput) and returns refine prompt. */
    refinePromptBuilder: (suspend (String, AnalyzeOutput, ResearchOutput?) -> String)? = null,
) = strategy<String, GenerateResult>("scoop_manifest_generator") {

    // Capture research data and suggested_app_name across phases
    var researchData: ResearchOutput? = null
    var suggestedAppName: String? = null
    var lastAnalyzeOutput: AnalyzeOutput? = null

    // ── Research phase (LLM) ──
    val research by subgraphWithTask<String, ResearchOutput>(
        tools = researchTools,
        llmParams = llmParams,
    ) { input ->
        researchPromptBuilder?.invoke(input) ?: input.toString()
    }

    // ── Finish when no Windows assets exist ──
    val finishNoAssets by node<ResearchOutput, GenerateResult>("finishNoAssets") { research ->
        GenerateResult(
            manifest = "",
            suggestedAppName = research.suggested_app_name,
            noAssets = true,
        )
    }

    // ── Analyze phase (LLM) ──
    val analyze by subgraphWithTask<ResearchOutput, AnalyzeOutput>(
        tools = analyzeTools,
        llmParams = llmParams,
    ) { input ->
        researchData = input
        suggestedAppName = input.suggested_app_name
        analyzePromptBuilder?.invoke(input) ?: input.toString()
    }

    // ── Deterministic: assemble JSON skeleton from research + analyze data ──
    val prepareManifest by node<AnalyzeOutput, String>("prepareManifest") { analyzeOutput ->
        lastAnalyzeOutput = analyzeOutput
        assembleManifestSkeleton(researchData!!, analyzeOutput)
    }

    // ── Refine phase (LLM): add checkver/autoupdate and validate ──
    val refine by subgraphWithTask<String, String>(
        tools = generateTools,
        llmParams = llmParams,
    ) { manifestSkeleton ->
        refinePromptBuilder?.invoke(manifestSkeleton, lastAnalyzeOutput!!, researchData) ?: manifestSkeleton
    }

    // ── Deterministic: extract JSON and wrap result ──
    val wrapResult by node<String, GenerateResult>("wrapResult") { refineOutput ->
        val manifest = extractJsonBlock(refineOutput) ?: refineOutput
        GenerateResult(manifest, suggestedAppName)
    }

    // ── Edges ──
    edge(nodeStart forwardTo research)

    // Conditional: no Windows assets → early finish
    edge(research forwardTo finishNoAssets onCondition { output ->
        output.status == ResearchStatus.NO_WINDOWS_ASSETS
    })

    // Conditional: has Windows assets → analyze
    edge(research forwardTo analyze onCondition { output ->
        output.status == ResearchStatus.OK
    })

    edge(finishNoAssets forwardTo nodeFinish)

    // Main path: analyze → prepareManifest → refine → wrapResult → finish
    edge(analyze forwardTo prepareManifest)
    edge(prepareManifest forwardTo refine)
    edge(refine forwardTo wrapResult)
    edge(wrapResult forwardTo nodeFinish)
}

// ---------------------------------------------------------------------------
// JSON helpers
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
    Json.parseToJsonElement(text) is JsonObject
} catch (_: Exception) {
    false
}

@kotlinx.serialization.Serializable
private data class StatusWrapper(val status: ResearchStatus = ResearchStatus.OK)

/**
 * Extracts the `status` field from a JSON string.
 * Returns [ResearchStatus.OK] if the JSON cannot be parsed or the field is absent.
 */
internal fun parseResearchStatus(output: String): ResearchStatus {
    return try {
        phaseJson.decodeFromString(StatusWrapper.serializer(), output).status
    } catch (_: Exception) {
        ResearchStatus.OK
    }
}
