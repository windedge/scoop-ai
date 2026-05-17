package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import com.scoopai.core.agent.SystemPrompts
import com.scoopai.core.tools.api.ManifestIo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int = 30,
    private val maxFixAttempts: Int = 2,
    private val maxPhaseRetries: Int = 2,
) {
    /** Result collected from a single agent execution. */
    data class PhaseResult(
        val llmText: String,
        val toolResults: List<Pair<String, String?>>,
        val manifestJson: String,
    )

    suspend fun generate(url: String, outputPath: String? = null): String {
        // ── Phase 1: Research ──────────────────────────────────────────
        val p1 = runPhaseWithRetry(
            phaseName = "research",
            systemPrompt = SystemPrompts.RESEARCH,
            userPrompt = buildResearchPrompt(url, outputPath),
        )
        val researchCtx = summarizePhase(p1)
        if (researchCtx.isBlank()) {
            logger.warn { "[research] Phase returned empty, aborting" }
            return ""
        }
        logger.info { "[research] Completed: ${researchCtx.length} chars" }

        // ── Phase 2: Analyze ───────────────────────────────────────────
        val p2 = runPhaseWithRetry(
            phaseName = "analyze",
            systemPrompt = SystemPrompts.ANALYZE,
            userPrompt = buildAnalyzePrompt(researchCtx),
        )
        val analyzeCtx = summarizePhase(p2)
        logger.info { "[analyze] Completed: ${analyzeCtx.length} chars" }

        // ── Phase 3: Generate ──────────────────────────────────────────
        val p3 = runPhaseWithRetry(
            phaseName = "generate",
            systemPrompt = SystemPrompts.GENERATE,
            userPrompt = buildGeneratePrompt(researchCtx, analyzeCtx, outputPath),
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

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry,
            maxIterations = maxIterations,
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
                        val json = extractJson(ctx.toolArgs.toString())
                        if (json != null) {
                            capturedManifestJson = json
                            logger.info { "[tool] Captured manifest from ${ctx.toolName} args (${json.length} chars)" }
                        }
                    }
                }
                onLLMCallCompleted { ctx ->
                    val response = ctx.responses.firstOrNull() as? Message.Assistant
                    if (response != null) {
                        val text = response.parts.mapNotNull {
                            (it as? ContentPart.Text)?.text
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
                    logger.error { "[phase] Failed: ${ctx.throwable.message}" }
                }
            }
        }
        agent.run(userPrompt)

        return PhaseResult(
            llmText = capturedLlmText,
            toolResults = capturedToolResults.toList(),
            manifestJson = capturedManifestJson,
        )
    }

    // ------------------------------------------------------------------
    // Prompt builders
    // ------------------------------------------------------------------

    private fun buildResearchPrompt(url: String, outputPath: String?): String {
        return if (outputPath != null) "Research the project at: $url\nOutput file: $outputPath"
        else "Research the project at: $url"
    }

    private fun buildAnalyzePrompt(researchContext: String): String {
        return """Based on the following research, compute SHA-256 hashes and inspect the Windows assets:

$researchContext

Compute SHA-256 hashes for each Windows asset. For ZIP files, inspect archive contents to determine extract_dir, bin, and installer type."""
    }

    private fun buildGeneratePrompt(researchContext: String, analyzeContext: String, outputPath: String?): String {
        val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
        return """Based on the following research and analysis, generate a complete Scoop manifest.

## Research
$researchContext

## Analysis
$analyzeContext$outputPart

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
    // Context formatting
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
