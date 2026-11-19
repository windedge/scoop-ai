package com.scoopai.tools

import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.system.exitProcess

/**
 * Real-gateway smoke for the repair phase: drives assist -> verify -> repair
 * instead of a plain assist round. The instruction is a bare URL plus a
 * non-sentence field directive that makes verify fail deterministically
 * (absolute persist path is rejected by validatePersistPaths). Language
 * anchoring therefore rests on rule 2 (configured reply language), not on the
 * instruction's own language.
 */
private const val MAX_INTENTS = 5
private const val CJK_START = 0x4E00
private const val CJK_END = 0x9FFF

private fun hasCjk(text: String): Boolean = text.any { it.code in CJK_START..CJK_END }

fun main() {
    val provider = System.getenv("SCOOP_AI_PROVIDER")
    val apiKey = System.getenv("SCOOP_AI_API_KEY")
    val baseUrl = System.getenv("SCOOP_AI_BASE_URL")
    val model = System.getenv("SCOOP_AI_MODEL")
    val missing = listOf("SCOOP_AI_PROVIDER" to provider, "SCOOP_AI_API_KEY" to apiKey, "SCOOP_AI_BASE_URL" to baseUrl, "SCOOP_AI_MODEL" to model)
        .filter { it.second.isNullOrBlank() }
        .map { it.first }
    if (missing.isNotEmpty()) {
        println("SKIP: missing env vars: $missing")
        exitProcess(0)
    }

    val instruction = """https://github.com/BurntSushi/ripgrep persist=C:\Users\xujl\AppData\Roaming\ripgrep"""
    val config = ScoopAiConfig(
        provider = provider,
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        appName = "reply-language-repair-smoke",
        replyLanguage = "Simplified Chinese",
    )
    println("=".repeat(70))
    println("[repair scenario] instruction: $instruction")
    println("[repair scenario] replyLanguage: Simplified Chinese")
    println("=".repeat(70))

    val json = Json { ignoreUnknownKeys = true }
    val activitiesByPhase = linkedMapOf<String, MutableList<String>>()
    val intents = mutableListOf<String>()
    val generator: ScoopManifestGenerator = DefaultScoopAiFactory.createGenerator(
        config = config,
        usedAppNamesProvider = { emptySet() },
        onPhaseOutput = { phase, output ->
            println("[phase-output] $phase (${output.length} chars)")
            runCatching {
                val meta = json.parseToJsonElement(output).jsonObject["_meta"]?.jsonObject
                meta?.get("activities")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }.getOrNull()?.let { found ->
                activitiesByPhase.getOrPut(phase) { mutableListOf() }.addAll(found)
            }
        },
        onToolActivity = { activity ->
            val intent = when (activity) {
                is ToolActivity.Started -> activity.intent
                is ToolActivity.Completed -> activity.intent
                is ToolActivity.Failed -> activity.intent
            }
            if (!intent.isNullOrBlank() && intent !in intents && intents.size < MAX_INTENTS) intents.add(intent)
        },
    )
    val result = runBlocking {
        generator.assist(
            manifestJson = "{}",
            instruction = instruction,
            suggestedAppName = "reply-language-repair-smoke",
        )
    }
    println("---- result flags ----")
    println("answered=${result.answered} noAssets=${result.noAssets} exhausted=${result.exhausted} manifestLen=${result.manifest.length} issues=${result.issues}")
    println("---- explanation (verbatim) ----")
    println(result.explanation ?: "<null>")
    println("---- activities per phase (verbatim) ----")
    activitiesByPhase.forEach { (phase, items) ->
        println("[phase=$phase] ${items.size} activities")
        items.forEachIndexed { index, activity -> println("  ${index + 1}. $activity [CJK=${hasCjk(activity)}]") }
    }
    println("---- tool-call intents ----")
    intents.forEachIndexed { index, intent -> println("${index + 1}. $intent [CJK=${hasCjk(intent)}]") }
    println("---- verdict ----")
    println("phases=${activitiesByPhase.keys} totalActivities=${activitiesByPhase.values.sumOf { it.size }}")
}
