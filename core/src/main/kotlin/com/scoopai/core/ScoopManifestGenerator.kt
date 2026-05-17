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

class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val manifestIo: ManifestIo,
    private val maxIterations: Int = 30,
    private val maxFixAttempts: Int = 2,
    private val maxRetries: Int = 3,
) {
    @Volatile
    private var lastToolResults = mutableListOf<Pair<String, String?>>()

    @Volatile
    private var lastLLMTextResponse = ""

    @Volatile
    private var manifestFromToolArgs = ""

    suspend fun generate(url: String, outputPath: String? = null): String {
        val input = if (outputPath != null) "$url\nOutput file: $outputPath" else url

        // Initial agent run
        val rawResult = runAgent(input)
        logger.info { "Agent returned ${rawResult.length} chars" }

        var resultText = extractResult(rawResult)

        // If empty, retry with a follow-up prompt that includes collected info
        if (resultText.isBlank() && lastToolResults.isNotEmpty()) {
            for (attempt in 1..maxRetries) {
                logger.info { "Retry $attempt/${maxRetries}: sending follow-up prompt" }
                val followUp = buildFollowUpPrompt()
                val retryResult = runAgent(followUp)
                resultText = extractResult(retryResult)
                if (resultText.isNotBlank()) break
            }
        }

        if (resultText.isBlank()) {
            logger.warn { "Agent returned empty result after all retries" }
            return ""
        }

        val json = extractJson(resultText) ?: return resultText

        val validation = manifestIo.validate(json)
        val validationResult = kotlinx.serialization.json.Json.parseToJsonElement(validation).jsonObject
        val isValid = validationResult["valid"]?.jsonPrimitive?.content == "true"

        if (isValid) return json

        val errors = validationResult["errors"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        if (errors.isEmpty()) return json

        logger.info { "Validation found ${errors.size} error(s), asking LLM to fix..." }
        errors.forEach { logger.info { "  - $it" } }

        var fixedResult = json
        for (attempt in 1..maxFixAttempts) {
            val fixPrompt = buildFixPrompt(json, errors)
            val fixResponse = runAgent(fixPrompt)
            val fixText = fixResponse.ifBlank {
                manifestFromToolArgs.ifBlank {
                    lastLLMTextResponse.ifBlank { extractFromToolResults() }
                }
            }
            val fixedJson = extractJson(fixText) ?: continue

            val revalidation = manifestIo.validate(fixedJson)
            val revalResult = kotlinx.serialization.json.Json.parseToJsonElement(revalidation).jsonObject
            val revalValid = revalResult["valid"]?.jsonPrimitive?.content == "true"

            if (revalValid) {
                logger.info { "Fix attempt $attempt succeeded." }
                fixedResult = fixedJson
                break
            }

            logger.info { "Fix attempt $attempt did not resolve all errors." }
            fixedResult = fixedJson
        }

        return fixedResult
    }

    private suspend fun runAgent(input: String): String {
        lastToolResults.clear()
        lastLLMTextResponse = ""
        manifestFromToolArgs = ""

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            systemPrompt = SystemPrompts.MANIFEST_GENERATOR,
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
                    lastToolResults.add(ctx.toolName to ctx.toolResult?.toString())
                    // Capture manifest JSON from tool arguments
                    if (ctx.toolName in listOf("write", "validate", "writeManifestToFile", "validateManifestJson")) {
                        val argsStr = ctx.toolArgs.toString()
                        val json = extractJson(argsStr)
                        if (json != null) {
                            manifestFromToolArgs = json
                            logger.info { "[tool] Captured manifest from ${ctx.toolName} args (${json.length} chars)" }
                        }
                    }
                }
                onLLMCallCompleted { ctx ->
                    val response = ctx.responses.firstOrNull() as? Message.Assistant
                    if (response != null) {
                        logger.info { "[llm] finishReason=${response.finishReason}" }
                        val text = response.parts.mapNotNull {
                            (it as? ContentPart.Text)?.text
                        }.joinToString("")
                        if (text.isNotBlank()) {
                            lastLLMTextResponse = text
                            logger.info { "[llm] Captured: ${text.take(100)}..." }
                        }
                    }
                }
                onAgentCompleted {
                    logger.info { "[agent] Completed." }
                }
                onAgentExecutionFailed { ctx ->
                    logger.error { "[agent] Failed: ${ctx.throwable.message}" }
                }
            }
        }
        return agent.run(input)
    }

    private fun extractResult(rawResult: String): String {
        // Tool args (validate/write) take highest priority — contains the actual manifest JSON
        if (manifestFromToolArgs.isNotBlank()) return manifestFromToolArgs
        return rawResult.ifBlank {
            lastLLMTextResponse.ifBlank { extractFromToolResults() }
        }
    }

    private fun buildFollowUpPrompt(): String {
        val toolSummary = lastToolResults.map { (name, result) ->
            "- $name: ${(result ?: "null").take(300)}"
        }.joinToString("\n")
        return """
You previously gathered the following information but failed to output a manifest:

$toolSummary

Now generate the Scoop manifest JSON. Call the `validate` tool with the manifest JSON, then call `write` to save it.
        """.trimIndent()
    }

    private fun extractFromToolResults(): String {
        // Try to find manifest JSON from tool call results
        for ((name, result) in lastToolResults.reversed()) {
            if (name in listOf("validateManifestJson", "writeManifestToFile")) continue
            if (result != null && result.contains("\"version\"") && result.contains("\"url\"")) {
                val json = extractJson(result)
                if (json != null) return json
            }
        }
        return ""
    }

    private fun extractJson(text: String): String? {
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        val codeBlockMatch = codeBlockRegex.find(text)
        if (codeBlockMatch != null) {
            val candidate = codeBlockMatch.groupValues[1].trim()
            if (isValidJson(candidate)) return candidate
        }

        val candidate = text.trim()
        if (isValidJson(candidate)) return candidate

        val braceStart = candidate.indexOf('{')
        val braceEnd = candidate.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            val sub = candidate.substring(braceStart, braceEnd + 1)
            if (isValidJson(sub)) return sub
        }

        return null
    }

    private fun isValidJson(text: String): Boolean {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(text)
            element is kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            false
        }
    }

    private fun buildFixPrompt(originalJson: String, errors: List<String>): String {
        val errorList = errors.joinToString("\n") { "- $it" }
        return """
The manifest you generated has validation errors:

$errorList

Here is the original manifest:
$originalJson

Please fix the errors and output the corrected Scoop manifest JSON.
        """.trimIndent()
    }
}
