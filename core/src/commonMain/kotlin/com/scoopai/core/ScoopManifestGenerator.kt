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
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.prompt.REVIEW_AGENT_SYSTEM_PROMPT
import com.scoopai.core.agent.prompt.REVIEW_TOOL_DESCRIPTION
import com.scoopai.core.agent.prompt.REVIEW_TOOL_INPUT_DESCRIPTION
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.agent.scoopManifestStrategy
import ai.koog.agents.core.tools.ToolBase
import com.scoopai.core.llm.LlmParamsBuilder
import com.scoopai.core.utils.sanitizeForLog
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Generates a Scoop manifest using a Koog graph strategy with three phases
 * (research -> analyze -> generate), routed by conditional edges.
 *
 * This class focuses on **orchestration**: connecting the strategy, tools,
 * and prompts. Prompt content is in [prompt][com.scoopai.core.agent.prompt],
 * LLM parameter construction is in [LlmParamsBuilder][com.scoopai.core.llm.LlmParamsBuilder].
 */
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int,
    private val thinkingLevel: String? = null,
) {
    /**
     * Model with [LLMCapability.ToolChoice] stripped so that Koog uses its
     * internal re-prompt path instead of hard-throwing on text-only responses
     * (a common thinking-model quirk).
     */
    private val modelWithoutToolChoice: LLModel by lazy {
        model.copy(capabilities = model.capabilities?.filter { it != LLMCapability.ToolChoice })
    }
    suspend fun generate(
        url: String,
        outputPath: String? = null,
        userPrompt: String? = null,
    ): ManifestResult {
        // -- Track token usage across all LLM calls ------------------------
        val tokenAccumulator = TokenAccumulator()

        // -- Phase-specific tool subsets -----------------------------------
        val allTools = toolRegistry.tools

        val researchTools = allTools.filter { it.name in PhaseTools.RESEARCH_TOOL_NAMES }
        val analyzeTools = allTools.filter { it.name in PhaseTools.ANALYZE_TOOL_NAMES }
        val reviewTool = createReviewTool(promptExecutor, tokenAccumulator)
        val generateTools = allTools.filter { it.name in PhaseTools.GENERATE_TOOL_NAMES } + reviewTool

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}, Generate: ${generateTools.map { it.name }}" }

        // -- Build prompt constructors for each phase ----------------------
        val researchPrompt: suspend (String) -> String =
            { buildResearchPrompt(it, outputPath, userPrompt) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it, userPrompt) }
        val generatePrompt: suspend (String, AnalyzeOutput, ResearchOutput?) -> String =
            { skeleton, analyze, research -> buildGeneratePrompt(skeleton, analyze, outputPath, userPrompt, research) }

        val llmParams = LlmParamsBuilder.build(modelWithoutToolChoice, thinkingLevel)
        val strategy = scoopManifestStrategy(
            researchTools = researchTools,
            analyzeTools = analyzeTools,
            generateTools = generateTools,
            llmParams = llmParams,
            researchPromptBuilder = researchPrompt,
            analyzePromptBuilder = analyzePrompt,
            generatePromptBuilder = generatePrompt,
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
            model = modelWithoutToolChoice,
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
                onLLMCallCompleted { ctx ->
                    ctx.response?.metaInfo?.inputTokensCount?.let { tokenAccumulator.input += it.toLong() }
                    ctx.response?.metaInfo?.outputTokensCount?.let { tokenAccumulator.output += it.toLong() }
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        val result = agent.run(url)
        return result.copy(tokenUsage = TokenUsage(tokenAccumulator.input, tokenAccumulator.output))
    }

    /**
     * Creates an independent review agent wrapped as a tool for the refine phase.
     * The review agent is a pure text reasoning agent with NO tools and NO shared context.
     */
    private fun createReviewTool(
        executor: PromptExecutor,
        accumulator: TokenAccumulator,
    ): ToolBase<*, *> {
        val reviewAgent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            toolRegistry = ToolRegistry.EMPTY,
            systemPrompt = REVIEW_AGENT_SYSTEM_PROMPT,
            maxIterations = 5,
        ) {
            handleEvents {
                onLLMCallCompleted { ctx ->
                    ctx.response?.metaInfo?.inputTokensCount?.let { accumulator.input += it.toLong() }
                    ctx.response?.metaInfo?.outputTokensCount?.let { accumulator.output += it.toLong() }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return AIAgentService
            .fromAgent(reviewAgent as GraphAIAgent<String, String>)
            .createAgentTool<String, String>(
                agentName = PhaseTools.REVIEW_MANIFEST,
                agentDescription = REVIEW_TOOL_DESCRIPTION,
                inputDescription = REVIEW_TOOL_INPUT_DESCRIPTION,
            )
    }
}
