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
import com.scoopai.core.agent.PhaseSchemas
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.agent.extractJsonBlock
import com.scoopai.core.agent.isValidJson
import com.scoopai.core.agent.phaseJson
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
    private val maxIterations: Int,
    private val maxFixAttempts: Int = 2,
    private val thinkingLevel: String? = null,
) {
    suspend fun generate(url: String, outputPath: String? = null): String {
        // ── Phase-specific tool subsets ────────────────────────────────
        val allTools = toolRegistry.tools
        val researchToolNames = setOf(
            "parseGitHubUrl", "fetchRepoInfo", "fetchLatestRelease", "fetchAllReleases",
            "listReleaseAssets", "fetchFileContent", "fetchWebPage", "resolveRedirect",
            "fetchJson", "__ask_user__"
        )
        val analyzeToolNames = setOf(
            "computeSha256FromUrl", "listZipContents", "findExecutables",
            "detectInstallerType", "__ask_user__"
        )
        val generateToolNames = setOf(
            "validate", "write", "read", "__ask_user__"
        )

        val researchTools = allTools.filter { it.name in researchToolNames }
        val analyzeTools = allTools.filter { it.name in analyzeToolNames }
        val generateTools = allTools.filter { it.name in generateToolNames }

        logger.info { "[tools] Total: ${allTools.size}, Research: ${researchTools.map { it.name }}, Analyze: ${analyzeTools.map { it.name }}, Generate: ${generateTools.map { it.name}}" }

        // ── Build prompt constructors for each phase ─────────────────
        val researchPrompt: suspend (String) -> String =
            { buildResearchPrompt(it, outputPath) }
        val analyzePrompt: suspend (ResearchOutput) -> String =
            { buildAnalyzePrompt(it) }
        val generatePrompt: suspend (AnalyzeOutput) -> String =
            { buildGeneratePrompt(it, outputPath) }

        val llmParams = buildLlmParams()
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
                    val args = ctx.toolArgs.toString().take(120)
                    logger.info { "[tool] ${ctx.toolName}($args)" }
                }
                onToolCallCompleted { ctx ->
                    val result = ctx.toolResult?.toString()?.take(200) ?: "null"
                    logger.info { "[tool] ${ctx.toolName} -> $result" }
                    // Capture manifest from generate-phase tool calls
                    if (ctx.toolName in listOf("write", "validate", "read")) {
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
            // No manifest generated — return the raw result as a user-facing message
            logger.warn { "[generate] No manifest JSON found in result or tool args" }
            return result
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
        return """You are now in the RESEARCH phase. Your task is to gather information about the project using research tools (fetchRepoInfo, fetchLatestRelease, fetchAllReleases, fetchWebPage, etc).

$task

Research stopping rule: as soon as you have enough trustworthy Windows asset URLs for the analyze phase to compute hashes, stop researching and report the findings. If GitHub releases have no assets, prefer the official download page and stop once version variables, filename patterns, or direct download links are sufficient to derive the real Windows asset URLs.

IMPORTANT: Use the available tools to gather information. Do not answer from your training data alone. Call the GitHub API and web fetcher tools to get real, up-to-date data.

${PhaseSchemas.RESEARCH_SCHEMA}"""
    }

    private fun buildAnalyzePrompt(research: ResearchOutput): String {
        val researchJson = kotlinx.serialization.json.Json.encodeToString(ResearchOutput.serializer(), research)
        return """You are now in the ANALYSIS phase. Your task is to compute hashes and inspect archive contents using the analysis tools (computeSha256FromUrl, listZipContents, findExecutables).

Based on the following research results, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

Prefer portable ZIP assets when suitable. Choose one installation strategy first, avoid exhaustive analysis of every candidate, and stop as soon as you have enough data to generate the manifest. Compute SHA-256 hashes only for selected assets using computeSha256FromUrl. For chosen ZIP files, inspect archive contents using listZipContents and findExecutables to determine extract_dir and bin. Only detect installer type if you are actually using an installer-based strategy or ZIP analysis is insufficient.

IMPORTANT: You MUST call the analysis tools (computeSha256FromUrl, listZipContents, findExecutables) to compute real values. Do not guess or estimate hashes.

${PhaseSchemas.ANALYZE_SCHEMA}"""
    }

    private fun buildGeneratePrompt(analyze: AnalyzeOutput, outputPath: String?): String {
        val analyzeJson = kotlinx.serialization.json.Json.encodeToString(AnalyzeOutput.serializer(), analyze)
        val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
        return """You are now in the GENERATION phase. Your task is to generate a Scoop manifest JSON and save it using the tools (validate, write).

Based on the following analysis JSON, generate a complete Scoop manifest.

## Analysis JSON
```json
$analyzeJson
```
$outputPart

You MUST call the tools: first call validate with the manifest JSON to check it, then call write to save it. Do not just output text — you must use the validate and write tools."""
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

    internal fun extractJson(text: String): String? = com.scoopai.core.agent.extractJsonBlock(text)

    internal fun isValidJson(text: String): Boolean = com.scoopai.core.agent.isValidJson(text)

    private fun buildFixPrompt(originalJson: String, errors: List<String>): String {
        val errorList = errors.joinToString("\n") { "- $it" }
        return """The manifest you generated has validation errors:

$errorList

Here is the original manifest:
$originalJson

Please fix the errors and output the corrected Scoop manifest JSON. Call validateManifestJson and writeManifestToFile."""
    }
}
