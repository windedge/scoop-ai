package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.createAgentTool
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.prompt.REVIEW_AGENT_SYSTEM_PROMPT
import com.scoopai.core.agent.prompt.REVIEW_TOOL_DESCRIPTION
import com.scoopai.core.agent.prompt.REVIEW_TOOL_INPUT_DESCRIPTION
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildRefinePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.llm.LlmParamsBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Maximum retries when encountering thinking-model recoverable errors or suspicious outputs. */
private const val MAX_GENERATION_RETRIES = 2

/** Minimum plausible manifest length (shorter = probably garbage like "test"). */
private const val MIN_VALID_MANIFEST_LENGTH = 20

/**
 * Checks whether a thrown exception is a known thinking-model quirk that
 * can be recovered by retrying.
 *
 * Covers:
 * - Pure reasoning without tool calls (thinking model bug)
 * - JSON decoding errors in LLM response (e.g. invalid escape `\q` in tool call arguments)
 * - Insufficient tool messages after tool_calls (Koog/DeepSeek conversation history bug)
 */
internal fun isRetryableError(e: Throwable): Boolean {
    val msg = e.message ?: return false
    // Thinking model: reasoning without tool call
    if (msg.contains("no Call was generated", ignoreCase = true) ||
        msg.contains("Subgraph with task must always call tools", ignoreCase = true)
    ) return true
    // JSON decoding errors from Koog parsing LLM tool call arguments
    if (msg.contains("JsonDecodingException", ignoreCase = true) ||
        msg.contains("Unexpected JSON token", ignoreCase = true) ||
        msg.contains("Invalid escaped char", ignoreCase = true)
    ) return true
    // DeepSeek API: insufficient tool messages following tool_calls message
    // This happens intermittently with long conversations (20+ tool calls)
    if (msg.contains("insufficient tool messages", ignoreCase = true)) return true
    return false
}

/**
 * Sanity-check a generated manifest. Returns true if the output looks like
 * garbage (too short, not JSON, etc.).
 */
internal fun isSuspiciousManifest(manifest: String): Boolean {
    val trimmed = manifest.trim()
    return trimmed.length < MIN_VALID_MANIFEST_LENGTH || !trimmed.startsWith("{")
}

/**
 * Result of a full manifest generation run.
 *
 * @property manifest The final manifest JSON string (after fix loop if applicable).
 *   Empty when no Windows assets were found ([noAssets] = true).
 * @property suggestedAppName The app name extracted from the write tool's `path` parameter,
 *   or `null` if no write tool call was made.
 * @property noAssets True when the project has no Windows assets at all.
 */
data class GenerateResult(
    val manifest: String,
    val suggestedAppName: String? = null,
    val noAssets: Boolean = false,
)

/**
 * Safely truncate a string for logging, replacing non-printable and non-ASCII
 * characters to avoid garbled output on non-UTF-8 consoles.
 * Truncates to [maxLen] code points.
 */
internal fun sanitizeForLog(text: String, maxLen: Int = 120): String {
    val sanitized = text
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .replace(Regex("[^\\x20-\\x7E]"), "?")
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
    private val maxIterations: Int,
    private val thinkingLevel: String? = null,
) {
    suspend fun generate(url: String, outputPath: String? = null, userPrompt: String? = null): GenerateResult {
        var lastException: Throwable? = null

        repeat(MAX_GENERATION_RETRIES + 1) { attempt ->
            try {
                val result = tryGenerate(url, outputPath, userPrompt)

                if (result.noAssets) {
                    // No Windows assets found — not an error, not retryable.
                    return result
                }

                if (isSuspiciousManifest(result.manifest)) {
                    logger.warn {
                        "Generated manifest looks suspicious (length=${result.manifest.length}, " +
                            "preview='${sanitizeForLog(result.manifest, 40)}'). Retrying... (attempt ${attempt + 1})"
                    }
                    lastException = IllegalStateException(
                        "Suspicious manifest output: ${result.manifest.take(40)}"
                    )
                    if (attempt < MAX_GENERATION_RETRIES) {
                        return@repeat
                    }
                } else {
                    return result
                }
            } catch (e: Exception) {
                lastException = e
                if (isRetryableError(e) && attempt < MAX_GENERATION_RETRIES) {
                    logger.warn {
                        "Generation failed with retryable error: ${sanitizeForLog(e.message ?: "unknown")}. " +
                            "Retrying... (attempt ${attempt + 1})"
                    }
                } else {
                    throw e
                }
            }
        }

        throw lastException
            ?: IllegalStateException("Generation failed after $MAX_GENERATION_RETRIES retries")
    }

    /**
     * Creates an independent review agent wrapped as a tool for the refine phase.
     * The review agent is a pure text reasoning agent with NO tools and NO shared context.
     */
    private fun createReviewTool(): ai.koog.agents.core.tools.ToolBase<*, *> {
        val reviewAgent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = ToolRegistry.EMPTY,
            systemPrompt = REVIEW_AGENT_SYSTEM_PROMPT,
            maxIterations = 5,
        )

        @Suppress("UNCHECKED_CAST")
        return AIAgentService
            .fromAgent(reviewAgent as GraphAIAgent<String, String>)
            .createAgentTool<String, String>(
                agentName = PhaseTools.REVIEW_MANIFEST,
                agentDescription = REVIEW_TOOL_DESCRIPTION,
                inputDescription = REVIEW_TOOL_INPUT_DESCRIPTION,
            )
    }

    private suspend fun tryGenerate(
        url: String,
        outputPath: String?,
        userPrompt: String?,
    ): GenerateResult {
        // -- Phase-specific tool subsets -----------------------------------
        val allTools = toolRegistry.tools

        val researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }
        val reviewTool = createReviewTool()
        val generateTools = allTools.filter { it.name in PhaseTools.GENERATE_TOOL_NAMES } + reviewTool

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}, Generate: ${generateTools.map { it.name }}" }

        // -- Build prompt constructors for each phase ----------------------
        val researchPrompt: suspend (String) -> String =
            { buildResearchPrompt(it, outputPath, userPrompt) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it, userPrompt) }
        val refinePrompt: suspend (String, AnalyzeOutput, ResearchOutput?) -> String =
            { skeleton, analyze, research -> buildRefinePrompt(skeleton, analyze, outputPath, userPrompt, research) }

        val llmParams = LlmParamsBuilder.build(model, thinkingLevel)
        val strategy = scoopManifestStrategy(
            researchTools = researchTools,
            analyzeTools = analyzeTools,
            generateTools = generateTools,
            llmParams = llmParams,
            researchPromptBuilder = researchPrompt,
            analyzePromptBuilder = analyzePrompt,
            refinePromptBuilder = refinePrompt,
        )

        // Create an expanded tool registry that includes the review tool.
        // The review tool must be in the parent agent's registry for Koog to
        // find its implementation when the subgraph LLM calls it.
        val expandedToolRegistry = ToolRegistry {
            tools(toolRegistry.tools)
            tool(reviewTool)
        }

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
            toolRegistry = expandedToolRegistry,
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
