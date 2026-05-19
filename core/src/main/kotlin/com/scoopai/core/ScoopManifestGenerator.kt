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
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.agent.parseResearchStatus
import com.scoopai.core.agent.scoopManifestStrategy
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Generates a Scoop manifest using a Koog graph strategy with three phases
 * (research → analyze → generate), routed by conditional edges.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int = 80,
    private val maxFixAttempts: Int = 2,
    private val thinkingLevel: String? = null,
) {
    suspend fun generate(url: String, outputPath: String? = null): String {
        // ── Build prompt constructors for each phase ─────────────────
        val researchPrompt: suspend (String) -> String = { input ->
            buildResearchPrompt(input, outputPath)
        }
        val analyzePrompt: suspend (String) -> String = { researchJson ->
            buildAnalyzePrompt(researchJson)
        }
        val generatePrompt: suspend (String) -> String = { analyzeJson ->
            // Research JSON is needed for the generate prompt, but the subgraph
            // input is only the analyze output. We store research output in storage.
            // For now, pass analyzeJson + a note to read research from context.
            buildGeneratePrompt("", analyzeJson, outputPath)
        }

        val llmParams = buildLlmParams()
        val strategy = scoopManifestStrategy(
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
                    val args = ctx.toolArgs.toString().take(120)
                    logger.info { "[tool] ${ctx.toolName}($args)" }
                }
                onToolCallCompleted { ctx ->
                    val result = ctx.toolResult?.toString()?.take(200) ?: "null"
                    logger.info { "[tool] ${ctx.toolName} -> $result" }
                }
                onAgentCompleted {
                    logger.info { "[agent] Agent completed." }
                }
            }
        }

        val result = agent.run(url)
        if (result.isBlank()) {
            logger.warn { "[generate] Graph agent returned empty result" }
            return ""
        }

        val status = parseResearchStatus(result)
        if (status == "no_windows_assets") {
            logger.info { "[generate] No Windows assets found" }
            return result
        }

        val manifestJson = extractJson(result) ?: result
        if (manifestJson.isBlank()) {
            logger.warn { "[generate] No manifest JSON found" }
            return ""
        }

        return validateAndFix(manifestJson)
    }

    // ── LLM params ──────────────────────────────────────────────────

    private fun buildLlmParams(): LLMParams {
        if (!model.supports(ai.koog.prompt.llm.LLMCapability.Thinking)) {
            return LLMParams()
        }
        val additionalProps = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        when (thinkingLevel) {
            null -> {}
            "disabled" -> additionalProps["thinking"] =
                buildJsonObject { put("type", "disabled") }
            "enabled" -> additionalProps["thinking"] =
                buildJsonObject { put("type", "enabled") }
            "minimal", "low", "medium", "high", "xhigh" -> {
                additionalProps["thinking"] = buildJsonObject { put("type", "enabled") }
                additionalProps["reasoning_effort"] = kotlinx.serialization.json.JsonPrimitive(thinkingLevel)
            }
        }
        return if (additionalProps.isEmpty()) LLMParams()
        else LLMParams(additionalProperties = additionalProps)
    }

    // ── Prompt builders ──────────────────────────────────────────────

    private fun buildResearchPrompt(url: String, outputPath: String?): String {
        val task = if (outputPath != null) {
            "Research the project at: $url\nOutput file: $outputPath"
        } else {
            "Research the project at: $url"
        }
        return "$task\n\nResearch stopping rule: as soon as you have enough trustworthy Windows asset URLs for the analyze phase to compute hashes, stop researching and report the findings. If GitHub releases have no assets, prefer the official download page and stop once version variables, filename patterns, or direct download links are sufficient to derive the real Windows asset URLs."
    }

    private fun buildAnalyzePrompt(researchJson: String): String {
        return """Based on the following research JSON, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

Prefer portable ZIP assets when suitable. Choose one installation strategy first, avoid exhaustive analysis of every candidate, and stop as soon as you have enough data to generate the manifest. Compute SHA-256 hashes only for selected assets. For chosen ZIP files, inspect archive contents to determine extract_dir and bin. Only detect installer type if you are actually using an installer-based strategy or ZIP analysis is insufficient."""
    }

    private fun buildGeneratePrompt(researchJson: String, analyzeJson: String, outputPath: String?): String {
        val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
        return """Based on the following structured research and analysis JSON, generate a complete Scoop manifest.

## Research JSON
```json
$researchJson
```

## Analysis JSON
```json
$analyzeJson
```
$outputPart

Generate the manifest JSON, then call validateManifestJson and writeManifestToFile to save it."""
    }

    // ── JSON extraction & validation ─────────────────────────────────

    private suspend fun validateAndFix(manifestJson: String): String {
        val validation = manifestIo.validate(manifestJson)
        val validationResult = kotlinx.serialization.json.Json.parseToJsonElement(validation).jsonObject
        val isValid = validationResult["valid"]?.jsonPrimitive?.content == "true"
        if (isValid) {
            logger.info { "[validate] Valid manifest" }
            return manifestJson
        }

        val errors = validationResult["errors"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()
        if (errors.isEmpty()) {
            logger.info { "[validate] No errors reported" }
            return manifestJson
        }

        logger.info { "[validate] ${errors.size} error(s), attempting fixes..." }
        errors.forEach { logger.info { "  - $it" } }

        var fixed = manifestJson
        for (attempt in 1..maxFixAttempts) {
            val fixPrompt = buildFixPrompt(manifestJson, errors)
            val fixResult = runFixPhase(fixPrompt)
            val fixedJson = extractJson(fixResult) ?: continue
            val reval = kotlinx.serialization.json.Json.parseToJsonElement(manifestIo.validate(fixedJson)).jsonObject
            if (reval["valid"]?.jsonPrimitive?.content == "true") {
                logger.info { "[validate] Fix attempt $attempt succeeded." }
                fixed = fixedJson
                break
            }
            logger.info { "[validate] Fix attempt $attempt did not resolve all errors." }
            fixed = fixedJson
        }
        return fixed
    }

    private suspend fun runFixPhase(fixPrompt: String): String {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = AIAgentConfig(
                prompt = prompt("fix") { system(SystemPrompts.GENERATE) },
                model = model,
                maxAgentIterations = maxIterations,
            ),
            strategy = singleRunStrategy(),
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

    internal fun extractJson(text: String): String? {
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        codeBlockRegex.find(text)?.let { match ->
            val candidate = match.groupValues[1].trim()
            if (isValidJson(candidate)) return candidate
        }
        val candidate = text.trim()
        if (isValidJson(candidate)) return candidate
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val sub = candidate.substring(start, end + 1)
            if (isValidJson(sub)) return sub
        }
        return null
    }

    internal fun isValidJson(text: String): Boolean = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text) is kotlinx.serialization.json.JsonObject
    } catch (_: Exception) { false }

    private fun buildFixPrompt(originalJson: String, errors: List<String>): String {
        val errorList = errors.joinToString("\n") { "- $it" }
        return """The manifest you generated has validation errors:

$errorList

Here is the original manifest:
$originalJson

Please fix the errors and output the corrected Scoop manifest JSON. Call validateManifestJson and writeManifestToFile."""
    }
}
