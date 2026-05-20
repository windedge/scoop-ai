package com.scoopai.core.validation

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.agent.extractJsonBlock
import com.scoopai.core.agent.isValidJson
import com.scoopai.core.agent.prompt.buildFixPrompt
import com.scoopai.core.agent.thinkingCompatibleSingleRunStrategy
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Validates and automatically fixes Scoop manifest JSON.
 *
 * Extracted from [ScoopManifestGenerator][com.scoopai.core.ScoopManifestGenerator]
 * to isolate the validation/fix loop and make it independently testable.
 *
 * The fix phase reuses [thinkingCompatibleSingleRunStrategy] to ensure
 * consistent behavior with the main flow when using thinking-capable providers
 * (e.g. DeepSeek V4).
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ManifestFixer(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int,
    private val maxFixAttempts: Int = 2,
) {
    /**
     * Validates the manifest and, if errors are found, runs an LLM fix loop.
     * Returns the fixed manifest JSON (or the original if already valid).
     */
    suspend fun validateAndFix(manifestJson: String): String {
        val errors = validate(manifestJson)
        if (errors.isEmpty()) {
            logger.info { "[validate] Valid manifest" }
            return manifestJson
        }

        logger.info { "[validate] ${errors.size} error(s), attempting fixes..." }
        errors.forEach { logger.info { "  - $it" } }

        var fixed = manifestJson
        for (attempt in 1..maxFixAttempts) {
            val fixPrompt = buildFixPrompt(manifestJson, errors)
            val fixResult = runFixPhase(fixPrompt)
            val fixedJson = extractJson(fixResult) ?: continue
            val revalErrors = validate(fixedJson)
            if (revalErrors.isEmpty()) {
                logger.info { "[validate] Fix attempt $attempt succeeded." }
                fixed = fixedJson
                break
            }
            logger.info { "[validate] Fix attempt $attempt did not resolve all errors." }
            fixed = fixedJson
        }
        return fixed
    }

    /**
     * Runs the manifest through [ManifestIo.validate] and returns
     * the list of error messages (empty if valid).
     */
    private fun validate(manifestJson: String): List<String> {
        val validation = manifestIo.validate(manifestJson)
        val validationResult = kotlinx.serialization.json.Json
            .parseToJsonElement(validation).jsonObject
        val isValid = validationResult["valid"]?.jsonPrimitive?.content == "true"
        if (isValid) return emptyList()

        return validationResult["errors"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()
    }

    /**
     * Runs a fix agent using [thinkingCompatibleSingleRunStrategy]
     * for consistent provider behavior.
     */
    private suspend fun runFixPhase(fixPrompt: String): String {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = AIAgentConfig(
                prompt = prompt("fix") { system(SystemPrompts.GENERATE) },
                model = model,
                maxAgentIterations = maxIterations,
            ),
            strategy = thinkingCompatibleSingleRunStrategy(),
            toolRegistry = toolRegistry,
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    logger.info { "[fix] ${ctx.toolName}(${ctx.toolArgs.toString().take(120)})" }
                }
                onToolCallCompleted { ctx ->
                    val json = extractManifestJsonFromToolArgs(ctx.toolArgs)
                    if (json != null) logger.info { "[fix] Captured manifest from ${ctx.toolName}" }
                }
            }
        }
        return try {
            agent.run(fixPrompt)
        } catch (e: Exception) {
            logger.error { "[fix] Fix phase failed: ${e.message?.take(200)}" }
            fixPrompt
        }
    }

    private fun extractManifestJsonFromToolArgs(args: JSONObject): String? {
        val fromJsonField = (args.entries["json"] as? JSONPrimitive)?.contentOrNull
        return if (fromJsonField != null && isValidJson(fromJsonField)) fromJsonField
        else extractJson(args.toString())
    }

    private fun extractJson(text: String): String? = extractJsonBlock(text)
}
