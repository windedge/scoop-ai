package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.thinkingCompatibleSingleRunStrategy
import com.scoopai.core.agent.PhaseSchemas
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Generates a Scoop manifest in three sequential phases, each with its own focused agent:
 *
 * 1. **Research** — Gather project info (repo, releases, Windows assets)
 * 2. **Analyze** — Compute SHA-256 hashes, inspect ZIP archives
 * 3. **Generate** — Create manifest JSON, validate, write
 *
 * Each phase runs a separate agent instance with a phase-specific system prompt.
 * If a phase produces no usable output it is retried (up to [maxPhaseRetries] times).
 * Finally the manifest is validated locally and fixed in a mini-loop.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int = 80,
    private val maxFixAttempts: Int = 2,
    private val maxPhaseRetries: Int = 2,
    /**
     * Thinking level for models that support reasoning (e.g. DeepSeek V4).
     *
     * Maps to DeepSeek API parameters:
     * - `thinking.type` = `"enabled"` / `"disabled"`
     * - `reasoning_effort` = `"minimal"` / `"low"` / `"medium"` / `"high"` / `"xhigh"`
     *
     * Accepted values:
     * - `null` (default): use the model's default thinking configuration.
     * - `"disabled"`: explicitly disable thinking.
     * - `"enabled"`: enable thinking, let the model decide the effort level.
     * - `"minimal"` / `"low"` / `"medium"` / `"high"` / `"xhigh"`:
     *   enable thinking and set the corresponding `reasoning_effort` level.
     *
     * Only applied when the model declares [LLMCapability.Thinking].
     */
    private val thinkingLevel: String? = null,
) {
    /** Result collected from a single agent execution. */
    data class PhaseResult(
        val llmText: String,
        val toolResults: List<Pair<String, String?>>,
        val manifestJson: String,
    )

    /** Lenient JSON instance for parsing phase outputs. */
    private val phaseJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun generate(url: String, outputPath: String? = null): String {
        // ── Phase 1: Research ──────────────────────────────────────────
        val p1 = runPhaseWithRetry(
            phaseName = "research",
            systemPrompt = SystemPrompts.RESEARCH,
            userPrompt = buildResearchPrompt(url, outputPath),
        )

        // Try structured parse; fallback to legacy free-text on failure
        val researchOutput = parseResearchOutput(p1)
        if (researchOutput != null) {
            if (researchOutput.status == "no_windows_assets") {
                logger.info { "[research] No Windows assets found (structured), skipping analyze/generate" }
                val msg = researchOutput.alternatives.ifBlank {
                    "This project does not provide Windows binaries."
                }
                return msg
            }
            if (researchOutput.windows_assets.isEmpty()) {
                logger.warn { "[research] Structured output has empty windows_assets, aborting" }
                return ""
            }
            logger.info { "[research] Completed (structured): project=${researchOutput.project}, version=${researchOutput.version}, assets=${researchOutput.windows_assets.size}" }
        } else {
            // Legacy fallback
            val legacyMsg = extractNoWindowsAssetsMessage(p1)
            if (legacyMsg != null) {
                logger.info { "[research] No Windows assets found (legacy), skipping analyze/generate" }
                return legacyMsg
            }
            val researchCtx = summarizePhase(p1)
            if (researchCtx.isBlank()) {
                logger.warn { "[research] Phase returned empty, aborting" }
                return ""
            }
            logger.info { "[research] Completed (legacy fallback): ${researchCtx.length} chars" }
        }

        // Build the research context string to pass to phase 2
        val researchJsonString = if (researchOutput != null) {
            phaseJson.encodeToString(ResearchOutput.serializer(), researchOutput)
        } else {
            summarizePhase(p1)
        }

        // ── Phase 2: Analyze ───────────────────────────────────────────
        val p2 = runPhaseWithRetry(
            phaseName = "analyze",
            systemPrompt = SystemPrompts.ANALYZE,
            userPrompt = buildAnalyzePrompt(researchJsonString),
        )

        val analyzeOutput = parseAnalyzeOutput(p2)
        if (analyzeOutput != null) {
            logger.info { "[analyze] Completed (structured): strategy=${analyzeOutput.strategy}, entries=${analyzeOutput.entries.size}" }
        } else {
            logger.info { "[analyze] Completed (legacy fallback): ${summarizePhase(p2).length} chars" }
        }

        // Build the analyze context string to pass to phase 3
        val analyzeJsonString = if (analyzeOutput != null) {
            phaseJson.encodeToString(AnalyzeOutput.serializer(), analyzeOutput)
        } else {
            summarizePhase(p2)
        }

        // ── Phase 3: Generate ──────────────────────────────────────────
        val p3 = runPhaseWithRetry(
            phaseName = "generate",
            systemPrompt = SystemPrompts.GENERATE,
            userPrompt = buildGeneratePrompt(researchJsonString, analyzeJsonString, outputPath),
        )
        var manifestJson = if (p3.manifestJson.isNotBlank()) p3.manifestJson
        else extractJson(p3.llmText) ?: ""

        if (manifestJson.isBlank()) {
            logger.warn { "[generate] Phase returned empty manifest" }
            return ""
        }
        logger.info { "[generate] Completed: ${manifestJson.length} chars" }

        // ── Validate & Fix ─────────────────────────────────────────────
        return validateAndFix(manifestJson)
    }

    // ------------------------------------------------------------------
    // Phase execution
    // ------------------------------------------------------------------

    private suspend fun runPhaseWithRetry(
        phaseName: String,
        systemPrompt: String,
        userPrompt: String,
    ): PhaseResult {
        var result = executePhase(systemPrompt, userPrompt)

        for (attempt in 1..maxPhaseRetries) {
            val hasContent = when (phaseName) {
                "generate" -> result.manifestJson.isNotBlank() || extractJson(result.llmText) != null
                else -> result.llmText.isNotBlank() || result.toolResults.isNotEmpty()
            }
            if (hasContent) break

            logger.info { "[$phaseName] Retry $attempt/$maxPhaseRetries — no usable output" }
            val retryPrompt = buildPhaseRetryPrompt(phaseName, result.toolResults, userPrompt)
            result = executePhase(systemPrompt, retryPrompt)
        }

        return result
    }

    private suspend fun executePhase(systemPrompt: String, userPrompt: String): PhaseResult {
        val capturedToolResults = mutableListOf<Pair<String, String?>>()
        var capturedLlmText = ""
        var capturedManifestJson = ""

        val agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = phasePromptParams(),
            ) {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = maxIterations,
        )

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = thinkingCompatibleSingleRunStrategy(),
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
                    capturedToolResults.add(ctx.toolName to ctx.toolResult?.toString())
                    if (ctx.toolName in listOf("write", "validate", "writeManifestToFile", "validateManifestJson")) {
                        val json = extractManifestJsonFromToolArgs(ctx.toolArgs)
                        if (json != null) {
                            capturedManifestJson = json
                            logger.info { "[tool] Captured manifest from ${ctx.toolName} args (${json.length} chars)" }
                        }
                    }
                }
                onLLMCallCompleted { ctx ->
                    val response = ctx.response as? Message.Assistant
                    if (response != null) {
                        val reasoningParts = response.parts.filterIsInstance<MessagePart.Reasoning>()
                        if (reasoningParts.isNotEmpty()) {
                            logger.debug { "[phase] reasoning: ${reasoningParts.flatMap { it.content }.joinToString(" ").take(200)}..." }
                        }
                        val text = response.parts.mapNotNull {
                            (it as? MessagePart.Text)?.text
                        }.joinToString("")
                        if (text.isNotBlank()) {
                            capturedLlmText = text
                            logger.info { "[phase] finishReason=${response.finishReason}, text=${text.take(100)}..." }
                        }
                    }
                }
                onAgentCompleted {
                    logger.info { "[phase] Agent completed." }
                }
                onAgentExecutionFailed { ctx ->
                    logger.error { "[phase] Failed: ${ctx.error.message}" }
                }
            }
        }
        try {
            agent.run(userPrompt)
        } catch (e: ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException) {
            // Agent exhausted its iterations (common with DeepSeek thinking mode due to
            // empty-arg tool call loops). If we already captured useful results,
            // continue instead of aborting.
            if (capturedManifestJson.isNotBlank() || capturedLlmText.isNotBlank() || capturedToolResults.isNotEmpty()) {
                logger.warn { "[phase] Agent hit max iterations (${maxIterations}) but captured results exist — continuing with partial results" }
            } else {
                logger.error { "[phase] Agent hit max iterations (${maxIterations}) with no captured results" }
                throw e
            }
        }

        return PhaseResult(
            llmText = capturedLlmText,
            toolResults = capturedToolResults.toList(),
            manifestJson = capturedManifestJson,
        )
    }

    private fun phasePromptParams(): ai.koog.prompt.params.LLMParams {
        if (!model.supports(ai.koog.prompt.llm.LLMCapability.Thinking)) {
            return ai.koog.prompt.params.LLMParams()
        }

        val additionalProps = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

        when (thinkingLevel) {
            null -> { /* use model default: thinking enabled, default effort */ }
            "disabled" -> additionalProps["thinking"] =
                kotlinx.serialization.json.buildJsonObject { put("type", "disabled") }
            "enabled" -> additionalProps["thinking"] =
                kotlinx.serialization.json.buildJsonObject { put("type", "enabled") }
            // reasoning_effort levels: enable thinking + set effort
            "minimal", "low", "medium", "high", "xhigh" -> {
                additionalProps["thinking"] =
                    kotlinx.serialization.json.buildJsonObject { put("type", "enabled") }
                additionalProps["reasoning_effort"] =
                    kotlinx.serialization.json.JsonPrimitive(thinkingLevel)
            }
        }

        return if (additionalProps.isEmpty()) {
            ai.koog.prompt.params.LLMParams()
        } else {
            ai.koog.prompt.params.LLMParams(additionalProperties = additionalProps)
        }
    }

    // ------------------------------------------------------------------
    // Prompt builders
    // ------------------------------------------------------------------

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

    private fun buildPhaseRetryPrompt(phaseName: String, toolResults: List<Pair<String, String?>>, originalPrompt: String): String {
        val toolSummary = toolResults.map { (name, result) ->
            "- $name: ${(result ?: "null").take(300)}"
        }.joinToString("\n")

        val phaseGoal = when (phaseName) {
            "research" -> "gather project information and identify Windows assets"
            "analyze" -> "compute SHA-256 hashes and inspect archives"
            "generate" -> "generate the manifest JSON, call validateManifestJson, then call writeManifestToFile"
            else -> "complete your task"
        }

        return """Your previous attempt did not produce complete results. Here is what was collected:

$toolSummary

Please retry to $phaseGoal. Make sure you call the necessary tools and produce a proper result.

Original request: $originalPrompt"""
    }

    // ------------------------------------------------------------------
    // Structured JSON parsing
    // ------------------------------------------------------------------

    private fun parseResearchOutput(result: PhaseResult): ResearchOutput? {
        val jsonText = extractJson(result.llmText) ?: return null
        return try {
            phaseJson.decodeFromString<ResearchOutput>(jsonText)
        } catch (e: Exception) {
            logger.warn { "[research] Failed to parse structured JSON: ${e.message?.take(200)}" }
            null
        }
    }

    private fun parseAnalyzeOutput(result: PhaseResult): AnalyzeOutput? {
        val jsonText = extractJson(result.llmText) ?: return null
        return try {
            phaseJson.decodeFromString<AnalyzeOutput>(jsonText)
        } catch (e: Exception) {
            logger.warn { "[analyze] Failed to parse structured JSON: ${e.message?.take(200)}" }
            null
        }
    }

    // ------------------------------------------------------------------
    // Legacy context formatting (fallback)
    // ------------------------------------------------------------------

    private fun summarizePhase(result: PhaseResult): String {
        val sb = StringBuilder()
        if (result.llmText.isNotBlank()) {
            sb.append(result.llmText)
        }
        val nonManifestResults = result.toolResults.filter { (name, _) ->
            name !in listOf("validateManifestJson", "writeManifestToFile", "readManifestFile")
        }
        if (nonManifestResults.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n---\n")
            sb.append("Tool Results:\n")
            nonManifestResults.forEach { (name, res) ->
                sb.append("- $name: ${(res ?: "null").take(500)}\n")
            }
        }
        return sb.toString()
    }

    private fun extractNoWindowsAssetsMessage(result: PhaseResult): String? {
        val text = result.llmText.trim()
        if (text.isBlank()) return null

        val firstNonBlankLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        return if (firstNonBlankLine.startsWith("NO_WINDOWS_ASSETS:")) {
            text
        } else {
            null
        }
    }

    // ------------------------------------------------------------------
    // JSON extraction & validation
    // ------------------------------------------------------------------

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
            val fixPhase = executePhase(SystemPrompts.GENERATE, fixPrompt)
            val fixText = fixPhase.manifestJson.ifBlank { fixPhase.llmText }
            val fixedJson = extractJson(fixText) ?: continue

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

    private fun extractManifestJsonFromToolArgs(args: JSONObject): String? {
        val fromJsonField = (args.entries["json"] as? JSONPrimitive)?.contentOrNull
        return if (fromJsonField != null && isValidJson(fromJsonField)) {
            fromJsonField
        } else {
            extractJson(args.toString())
        }
    }

    private fun extractJson(text: String): String? {
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

    private fun isValidJson(text: String): Boolean = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text) is kotlinx.serialization.json.JsonObject
    } catch (_: Exception) {
        false
    }

    private fun buildFixPrompt(originalJson: String, errors: List<String>): String {
        val errorList = errors.joinToString("\n") { "- $it" }
        return """The manifest you generated has validation errors:

$errorList

Here is the original manifest:
$originalJson

Please fix the errors and output the corrected Scoop manifest JSON. Call validateManifestJson and writeManifestToFile."""
    }
}
