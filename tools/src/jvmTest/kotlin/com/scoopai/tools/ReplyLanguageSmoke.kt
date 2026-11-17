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
 * Real-gateway smoke: replyLanguage end-to-end. NOT part of any test suite
 * (no Test suffix); run manually via gradlew :cli:runReplyLanguageSmoke
 * with SCOOP_AI_* env vars pointing at a live gateway. Prints the
 * explanation, finalize activities and tool-call intents verbatim with a CJK
 * verdict for each, in both rounds. Exits non-zero when an explanation is
 * missing.
 *
 * Scenario 2: replyLanguage="Simplified Chinese" + a pure-URL instruction
 * (no natural language in the request) must answer in Chinese. Control run
 * with replyLanguage=null on the same input is expected to stay English.
 */
private const val MAX_INTENTS = 5
private const val CJK_START = 0x4E00
private const val CJK_END = 0x9FFF

private fun hasCjk(text: String): Boolean = text.any { it.code in CJK_START..CJK_END }

private fun allCjk(lines: List<String>): Boolean = lines.isNotEmpty() && lines.all(::hasCjk)

private fun anyCjk(lines: List<String>): Boolean = lines.any(::hasCjk)

fun main() {
    val provider = System.getenv("SCOOP_AI_PROVIDER")
    val apiKey = System.getenv("SCOOP_AI_API_KEY")
    val baseUrl = System.getenv("SCOOP_AI_BASE_URL")
    val model = System.getenv("SCOOP_AI_MODEL")
    val missing = listOf("SCOOP_AI_PROVIDER" to provider, "SCOOP_AI_API_KEY" to apiKey, "SCOOP_AI_BASE_URL" to baseUrl, "SCOOP_AI_MODEL" to model)
        .filter { it.second.isNullOrBlank() }
        .map { it.first }
    if (missing.isNotEmpty()) {
        println("SKIP: missing env vars: $missing (set SCOOP_AI_PROVIDER/API_KEY/BASE_URL/MODEL to run)")
        exitProcess(0)
    }

    val instruction = "https://github.com/BurntSushi/ripgrep"
    val baseConfig = { lang: String? ->
        ScoopAiConfig(
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            appName = "reply-language-smoke",
            replyLanguage = lang,
        )
    }

    runCatching { runRound("A: replyLanguage=Simplified Chinese", baseConfig("Simplified Chinese"), instruction) }
        .onFailure { println("Round A FAILED: ${it.message}"); it.printStackTrace() }
    println()
    runCatching { runRound("B: replyLanguage=null (control)", baseConfig(null), instruction) }
        .onFailure { println("Round B FAILED: ${it.message}"); it.printStackTrace() }
}

private fun runRound(label: String, config: ScoopAiConfig, instruction: String) {
    println("=".repeat(70))
    println("[$label] instruction: $instruction")
    println("=".repeat(70))

    val json = Json { ignoreUnknownKeys = true }
    var activities: List<String> = emptyList()
    val intents = mutableListOf<String>()
    // Minimal assist round: no host tools (save/install/read-ref handlers all
    // null), no history, manifest placeholder replaced by the model.
    val generator: ScoopManifestGenerator = DefaultScoopAiFactory.createGenerator(
        config = config,
        usedAppNamesProvider = { emptySet() },
        onPhaseOutput = { phase, output ->
            if (phase == "assist" || phase == "repair") {
                runCatching {
                    val meta = json.parseToJsonElement(output).jsonObject["_meta"]?.jsonObject
                    val nodes = meta?.get("activities")?.jsonArray
                    nodes?.map { element -> element.jsonPrimitive.content } ?: emptyList<String>()
                }.getOrNull()?.let { found -> activities = (activities + found).distinct() }
            }
        },
        onToolActivity = { activity ->
            val intent = when (activity) {
                is ToolActivity.Started -> activity.intent
                is ToolActivity.Completed -> activity.intent
                is ToolActivity.Failed -> activity.intent
            }
            if (!intent.isNullOrBlank() && intent !in intents && intents.size < MAX_INTENTS) {
                intents.add(intent)
            }
        },
    )
    val result = runBlocking {
        generator.assist(
            manifestJson = "{}",
            instruction = instruction,
            suggestedAppName = "reply-language-smoke",
        )
    }
    val explanation = result.explanation
    if (explanation.isNullOrBlank()) {
        println("ERROR: explanation is null or blank (answered=${result.answered} noAssets=${result.noAssets} exhausted=${result.exhausted})")
        exitProcess(2)
    }
    println("---- explanation (verbatim) ----")
    println(explanation)
    println("---- activities (_meta from phase output, verbatim) ----")
    activities.forEachIndexed { index, activity -> println("${index + 1}. $activity [CJK=${hasCjk(activity)}]") }
    println("---- tool-call intents (first $MAX_INTENTS, verbatim) ----")
    intents.forEachIndexed { index, intent -> println("${index + 1}. $intent [CJK=${hasCjk(intent)}]") }
    println("---- verdict ----")
    println(
        "contains CJK: explanation=${hasCjk(explanation)} activities=${allCjk(activities)} " +
            "intent=${anyCjk(intents)}; activities=${activities.size} intents=${intents.size} " +
            "cjkIntents=${intents.count(::hasCjk)} " +
            "answered=${result.answered} noAssets=${result.noAssets} manifestLen=${result.manifest.length} durationMs=${result.durationMs}",
    )
}
