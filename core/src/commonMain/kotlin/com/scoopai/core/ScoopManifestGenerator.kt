package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.agent.extractJsonBlock
import com.scoopai.core.agent.isValidJson
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildFixPrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.agent.thinkingCompatibleSingleRunStrategy
import com.scoopai.core.llm.LlmParamsBuilder
import com.scoopai.core.tools.api.ManifestIo
import com.scoopai.core.validation.ManifestFixer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Safely truncate a string for logging, preserving valid Unicode characters.
 * Replaces non-ASCII control characters and truncates to [maxLen] code points.
 */
internal fun sanitizeForLog(text: String, maxLen: Int = 120): String {
    // Replace problematic characters
    val sanitized = text
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .replace(Regex("[\\p{Cntrl}&&[^\n\r\t]]"), "?") // other control chars
    // Truncate by code points to avoid breaking multi-byte chars
    val codePoints = sanitized.codePoints().toArray()
    return if (codePoints.size <= maxLen) {
        sanitized
    } else {
        val truncated = codePoints.take(maxLen)
            .map { String(Character.toChars(it)) }
            .joinToString("")
        "$truncated..."
    }
}

/**
 * Generates a Scoop manifest using a Koog graph strategy with three phases
 * (research -> analyze -> generate), routed by conditional edges.
 *
 * This class focuses on **orchestration**: connecting the strategy, tools,
 * prompts, and fix loop. Prompt content is in [prompt][com.scoopai.core.agent.prompt],
 * validation/fix logic is in [ManifestFixer][com.scoopai.core.validation.ManifestFixer],
 * LLM parameter construction is in [LlmParamsBuilder][com.scoopai.core.llm.LlmParamsBuilder].
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int,
    private val maxFixAttempts: Int = 2,
    private val thinkingLevel: String? = null,
) {
    suspend fun generate(url: String, outputPath: String? = null): String {
        // -- Phase-specific tool subsets -----------------------------------
        val allTools = toolRegistry.tools

        val researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }
        val generateTools = allTools.filter { it.name in PhaseTools.GENERATE_TOOL_NAMES }

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}, Generate: ${generateTools.map { it.name}}" }

        // -- Build prompt constructors for each phase ----------------------
        val researchPrompt: suspend (String) -> String =
            { buildResearchPrompt(it, outputPath) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it) }
        val generatePrompt: suspend (AnalyzeOutput) -> String =
            { buildGeneratePrompt(it, outputPath) }

        val llmParams = LlmParamsBuilder.build(model, thinkingLevel)
        val strategy = scoopManifestStrategy(
            researchTools = researchTools,
            analyzeTools = analyzeTools,
            generateTools = generateTools,
            llmParams = llmParams,
            researchPromptBuilder = researchPrompt,
            analyzePromptBuilder = analyzePrompt,
            generatePromptBuilder = generatePrompt,
        )

        val agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system("You are a Scoop manifest generator. You generate Scoop bucket manifests for Windows software.")
            },
            model = model,
            maxAgentIterations = maxIterations,
        )

        var capturedManifestJson = ""

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = toolRegistry,
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    val args = sanitizeForLog(ctx.toolArgs.toString())
                    logger.info { "[tool] ${ctx.toolName}($args)" }
                }
                onToolCallCompleted { ctx ->
                    val result = sanitizeForLog(ctx.toolResult?.toString() ?: "null", maxLen = 200)
                    logger.info { "[tool] ${ctx.toolName} -> $result" }
                    // Capture manifest from generate-phase tool calls
                    if (ctx.toolName == PhaseTools.WRITE ||
                        ctx.toolName == PhaseTools.VALIDATE ||
                        ctx.toolName == PhaseTools.READ) {
                        val json = extractManifestJsonFromToolArgs(ctx.toolArgs)
                        if (json != null) {
                            capturedManifestJson = json
                            logger.info { "[tool] Captured manifest from ${ctx.toolName} (${json.length} chars)" }
                        }
                    }
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        val result = agent.run(url)
        if (result.isBlank() && capturedManifestJson.isBlank()) {
            logger.warn { "[generate] Graph agent returned empty result and no manifest captured" }
            return ""
        }

        // Prefer captured manifest from tool calls, fall back to text extraction
        val manifestJson = when {
            capturedManifestJson.isNotBlank() -> capturedManifestJson
            else -> extractJson(result) ?: ""
        }
        if (manifestJson.isBlank()) {
            logger.warn { "[generate] No manifest JSON found in result or tool args" }
            return result
        }

        return ManifestFixer(
            promptExecutor = promptExecutor,
            model = model,
            toolRegistry = toolRegistry,
            manifestIo = manifestIo,
            maxIterations = maxIterations,
            maxFixAttempts = maxFixAttempts,
        ).validateAndFix(manifestJson)
    }

    // -- JSON extraction ------------------------------------------------

    private fun extractManifestJsonFromToolArgs(args: JSONObject): String? {
        val fromJsonField = (args.entries["json"] as? JSONPrimitive)?.contentOrNull
        return if (fromJsonField != null && isValidJson(fromJsonField)) fromJsonField
        else extractJson(args.toString())
    }

    internal fun extractJson(text: String): String? = extractJsonBlock(text)

    internal fun isValidJson(text: String): Boolean = com.scoopai.core.agent.isValidJson(text)
}
