package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.llm.LlmParamsBuilder
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result of a full manifest generation run.
 *
 * @property manifest The final manifest JSON string (after fix loop if applicable).
 * @property suggestedAppName The app name extracted from the write tool's `path` parameter,
 *   or `null` if no write tool call was made.
 */
data class GenerateResult(val manifest: String, val suggestedAppName: String?)

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
 * and prompts. Prompt content is in [prompt][com.scoopai.core.agent.prompt],
 * LLM parameter construction is in [LlmParamsBuilder][com.scoopai.core.llm.LlmParamsBuilder].
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int,
    private val thinkingLevel: String? = null,
) {
    suspend fun generate(url: String, outputPath: String? = null, userPrompt: String? = null): GenerateResult {
        // -- Phase-specific tool subsets -----------------------------------
        val allTools = toolRegistry.tools

        val researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }
        val generateTools = allTools.filter { it.name in PhaseTools.GENERATE_TOOL_NAMES }

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}, Generate: ${generateTools.map { it.name}}" }

        // -- Build prompt constructors for each phase ----------------------
        val researchPrompt: suspend (String) -> String =
            { buildResearchPrompt(it, outputPath, userPrompt) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it, userPrompt) }
        val generatePrompt: suspend (AnalyzeOutput) -> String =
            { buildGeneratePrompt(it, outputPath, userPrompt) }

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
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        return agent.run(url)
    }
}
