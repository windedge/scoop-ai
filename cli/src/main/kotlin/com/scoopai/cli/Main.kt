package com.scoopai.cli

import com.scoopai.core.GenerateResult
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ProviderConfigResolver
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.verify.ProcessScoopVerifier
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

private fun env(key: String): String? = System.getenv(key)

/** Extract app name from a GitHub URL (e.g. "https://github.com/owner/repo" → "repo"). */
private fun inferAppName(url: String): String {
    val trimmed = url.trimEnd('/')
    val githubMatch = Regex("""github\.com/[^/]+/([^/?#]+)""").find(trimmed)
    if (githubMatch != null) return githubMatch.groupValues[1].lowercase()
    // Fallback: last path segment
    return trimmed.substringAfterLast("/").substringBefore("?").substringBefore("#").lowercase()
}

fun main(args: Array<String>) {
    // ── Parse CLI arguments ────────────────────────────────────────
    var url = ""
    var output = ""
    var provider = ""
    var apiKey = ""
    var baseUrl: String? = null
    var modelName: String? = null
    var verify = false
    var appName: String? = null
    var keepCache = true
    var userPrompt: String? = null
    var nonInteractive = false
    var persistMode = "auto"
    var githubToken: String? = null

    val i = args.iterator()
    while (i.hasNext()) {
        when (val arg = i.next()) {
            "-o", "--output" -> output = i.next()
            "-P", "--provider" -> provider = i.next()
            "--api-key" -> apiKey = i.next()
            "--base-url" -> baseUrl = i.next()
            "-m", "--model" -> modelName = i.next()
            "-V", "--verify" -> verify = true
            "--app-name" -> appName = i.next()
            "--no-cache" -> keepCache = false
            "-p", "--prompt" -> userPrompt = i.next()
            "-n", "--non-interactive" -> nonInteractive = true
            "--github-token" -> githubToken = i.next()
            "-s", "--persist" -> {
                val mode = i.next().lowercase()
                persistMode = when (mode) {
                    "auto", "force", "none", "no" -> if (mode == "no") "none" else mode
                    else -> {
                        System.err.println("Error: --persist must be auto, force, none, or no (got: $mode)")
                        return
                    }
                }
            }
            "-h", "--help" -> {
                println(HELP_TEXT)
                return
            }
            else -> url = arg
        }
    }

    if (url.isBlank()) {
        System.err.println("Missing required argument: <url>")
        System.err.println()
        System.err.println("Usage: scoop-ai <url> [options]")
        System.err.println("Run 'scoop-ai --help' for more information.")
        return
    }

    // ── Resolve provider config ────────────────────────────────────
    val config = try {
        ProviderConfigResolver.resolve(
            cliProvider = provider,
            cliApiKey = apiKey,
            cliBaseUrl = baseUrl,
            cliModel = modelName,
        )
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
        return
    }

    if (config.provider == "openai-compatible" && config.baseUrl == null) {
        System.err.println("Error: --base-url or SCOOP_AI_BASE_URL is required when using openai-compatible provider.")
        return
    }

    // ── Inject persist mode into userPrompt ──────────────────────
    val persistInstruction = when (persistMode) {
        "force" -> "PERSIST REQUIREMENT (MANDATORY): You MUST include a 'persist' field in the Scoop manifest. " +
            "Identify config/data directories or files (from persist_targets in the research output, or from source code analysis) " +
            "and add them to 'persist'. Do NOT generate a manifest without the 'persist' field. " +
            "If no persist targets were found in the research phase, persist the most likely config directory based on the project type."
        "none" -> "PERSIST REQUIREMENT (MANDATORY): Do NOT include any 'persist' field in the Scoop manifest. " +
            "The manifest must NOT have a 'persist' key. " +
            "This overrides any other instructions about persist discovery. " +
            "In the Research phase: skip step 6 (persist discovery) entirely, do NOT read source code for the purpose of finding persist targets. " +
            "Set persist_targets to an empty list and env_targets to an empty map. " +
            "In the Analyze phase: set persist to an empty list, do NOT add pre_install scripts for persist directories. " +
            "In the Generate phase: the final manifest must NOT contain a 'persist' key."
        else -> null // auto: no instruction
    }
    val effectiveUserPrompt = listOfNotNull(userPrompt, persistInstruction)
        .joinToString("\n\n")
        .ifBlank { null }

    // ── Set non-interactive mode ────────────────────────────────
    if (nonInteractive) {
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
    }

    // ── Resolve GitHub token ───────────────────────────────────────
    val effectiveGhToken = githubToken
        ?: env("SCOOP_AI_GITHUB_TOKEN")
        ?: env("GITHUB_TOKEN")

    // ── Resolve app name ───────────────────────────────────────────
    val resolvedAppName = appName ?: inferAppName(url)

    // ── Assemble object graph & run ────────────────────────────────
    val manifestIo = FileManifestIo(defaultAppName = resolvedAppName.ifBlank { null }, persistMode = persistMode)
    val resolvedOutput = output.ifBlank { env("SCOOP_AI_OUTPUT") ?: "" }
    val envVerify = env("SCOOP_AI_VERIFY")?.lowercase() == "true"
    val doVerify = verify || envVerify

    val scoopVerifier = createScoopVerifier(doVerify)

    val cache = ScoopCacheManager(keepCache = keepCache)

    val toolRegistry = DefaultToolRegistryFactory.create(
        cache = cache,
        githubToken = effectiveGhToken,
        manifestIo = manifestIo,
        scoopVerifier = scoopVerifier,
    )

    try {
        val result: GenerateResult = runBlocking {
            val generator = ScoopManifestGenerator(
                promptExecutor = config.createExecutor(),
                model = config.model,
                toolRegistry = toolRegistry,
                maxIterations = 500,
            )

            val outputPath = resolvedOutput.ifBlank { null }
            generator.generate(url, outputPath, userPrompt = effectiveUserPrompt)
        }

        val manifest = result.manifest
        if (result.noAssets) {
            System.err.println("No Windows assets found for the given project.")
            return
        }
        if (manifest.isBlank()) {
            System.err.println("Error: no manifest generated")
            return
        }
        
        // Print suggested app name when available
        if (result.suggestedAppName != null) {
            println("Suggested app name: ${result.suggestedAppName}")
        }
        
        // Write manifest to file
        val appNameForOutput = result.suggestedAppName?.ifBlank { null } ?: resolvedAppName
        val writePath = output.ifBlank { "bucket/$appNameForOutput.json" }
        manifestIo.write(manifest, writePath)
        println("Written to $writePath")
        
        // ── Finalize cache entries ─────────────────────────────────
        if (keepCache) {
            val parsed = parseManifestMeta(manifest)
            logger.info { "[cache] Finalizing: app=$appNameForOutput version=${parsed.version} url=${parsed.url}" }
            if (parsed.version != null && parsed.url != null) {
                val finalized = cache.finalizeCacheEntry(parsed.url, appNameForOutput, parsed.version)
                if (finalized != null) {
                    println("Cache: ${finalized.toAbsolutePath()}")
                    println("scoop install $appNameForOutput will reuse this file.")
                } else {
                    logger.warn { "[cache] No cached file found for ${parsed.url}" }
                }
            } else {
                logger.warn { "[cache] Could not parse manifest: version=${parsed.version} url=${parsed.url}" }
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
    } finally {
        DefaultToolRegistryFactory.cleanup()
    }

}

private fun createScoopVerifier(doVerify: Boolean): ProcessScoopVerifier? {
    if (!doVerify) return null
    return try {
        val check = ProcessBuilder("scoop", "--version")
            .redirectErrorStream(true)
            .start()
        check.inputStream.bufferedReader().readText()
        check.waitFor()
        if (check.exitValue() == 0) ProcessScoopVerifier() else null
    } catch (_: Exception) {
        null
    }
}

private data class ManifestMeta(val version: String?, val url: String?)

private fun parseManifestMeta(manifest: String): ManifestMeta {
    val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
    val url = Regex(""""url"\s*:\s*"(https?://[^"]+)"""").find(manifest)?.groupValues?.get(1)
    return ManifestMeta(version, url)
}

private val HELP_TEXT = """
    Usage: scoop-ai <url> [options]

    Arguments:
      <url>                        GitHub repository URL or project URL

    Options:
      -o, --output <path>          Output file path
      -P, --provider <name>        LLM provider: deepseek|openai|anthropic|ollama|openrouter|openai-compatible
      -m, --model <name>           LLM model name (overrides default for provider)
          --base-url <url>         Custom API base URL (required for openai-compatible)
          --api-key <key>          API key
          --github-token <token>   GitHub personal access token (avoids rate limits)
      -p, --prompt <text>          Additional requirements for manifest generation
      -s, --persist <mode>        Persist field policy: auto|force|none (default: auto)
          --app-name <name>        App name for cache (default: inferred from URL)
      -n, --non-interactive        Skip __ask_user__ prompts and use defaults (for batch/CI mode)
          --no-cache               Do not keep downloaded files in Scoop cache
      -V, --verify                 Verify manifest by running scoop install
      -h, --help                   Show this help message

    Environment Variables:
      SCOOP_AI_PROVIDER            LLM provider (default: deepseek)
      SCOOP_AI_API_KEY             API key (provider-specific: DEEPSEEK_API_KEY, OPENAI_API_KEY, etc.)
      SCOOP_AI_BASE_URL            Custom API base URL
      SCOOP_AI_MODEL               LLM model name
      SCOOP_AI_OUTPUT              Default output file path
      SCOOP_AI_GITHUB_TOKEN        GitHub personal access token (fallback for --github-token)

    Cache:
      Downloaded files are stored in the Scoop cache directory (~/scoop/cache/)
      using the same naming convention as Scoop. After generating a manifest,
      you can run 'scoop install <app>' directly — it will reuse the cached file.

    Examples:
      scoop-ai https://github.com/sharkdp/fd
      scoop-ai https://github.com/jqlang/jq -P openai
      scoop-ai https://github.com/BurntSushi/ripgrep -P openai-compatible --base-url http://localhost:8080/v1 -m qwen3
      scoop-ai https://github.com/Draver93/ff-studio --app-name ff-studio
      scoop-ai https://github.com/ggml-org/llama.cpp -p "Use SYCL-enabled binary"
""".trimIndent()