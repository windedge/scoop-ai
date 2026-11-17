package com.scoopai.tools

import com.scoopai.core.ScoopManifestGenerator
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Real-gateway smoke: replyLanguage end-to-end. NOT part of any test suite
 * (no Test suffix); run manually via gradlew :cli:runReplyLanguageSmoke
 * with SCOOP_AI_* env vars pointing at a live gateway. Prints both rounds'
 * FinalizePayload.explanation verbatim; language judgement is left to the
 * human reading the output. Exits non-zero when an explanation is missing.
 *
 * Scenario 2: replyLanguage="Simplified Chinese" + a pure-URL instruction
 * (no natural language in the request) must answer in Chinese. Control run
 * with replyLanguage=null on the same input is expected to stay English.
 */
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
    val generator: ScoopManifestGenerator = DefaultScoopAiFactory.createGenerator(
        config = config,
        usedAppNamesProvider = { emptySet() },
    )
    // Minimal assist round: no host tools (save/install/read-ref handlers all
    // null), no history, manifest placeholder replaced by the model.
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
    println("---- verdict ----")
    val hasCjk = explanation.any { it.code in 0x4E00..0x9FFF }
    println("contains CJK chars: $hasCjk; answered=${result.answered} noAssets=${result.noAssets} manifestLen=${result.manifest.length} durationMs=${result.durationMs}")
}
