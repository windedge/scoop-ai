package com.scoopai.cli

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

    val i = args.iterator()
    while (i.hasNext()) {
        when (val arg = i.next()) {
            "-o", "--output" -> output = i.next()
            "-p", "--provider" -> provider = i.next()
            "--api-key" -> apiKey = i.next()
            "--base-url" -> baseUrl = i.next()
            "-m", "--model" -> modelName = i.next()
            "-V", "--verify" -> verify = true
            "--app-name" -> appName = i.next()
            "--no-cache" -> keepCache = false
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
    val config = ProviderConfigResolver.resolve(
        cliProvider = provider,
        cliApiKey = apiKey,
        cliBaseUrl = baseUrl,
        cliModel = modelName,
    )

    if (config.provider == "openai-compatible" && config.baseUrl == null) {
        System.err.println("--base-url or SCOOP_AI_BASE_URL is required when using openai-compatible provider.")
        return
    }

    // ── Resolve app name ───────────────────────────────────────────
    val resolvedAppName = appName ?: inferAppName(url)

    // ── Assemble object graph & run ────────────────────────────────
    val manifestIo = FileManifestIo(defaultAppName = resolvedAppName.ifBlank { null })
    val resolvedOutput = output.ifBlank { env("SCOOP_AI_OUTPUT") ?: "" }
    val envVerify = env("SCOOP_AI_VERIFY")?.lowercase() == "true"
    val doVerify = verify || envVerify

    val scoopVerifier = if (doVerify) {
        try {
            val check = ProcessBuilder("scoop", "--version")
                .redirectErrorStream(true)
                .start()
            check.inputStream.bufferedReader().readText()
            check.waitFor()
            if (check.exitValue() != 0) {
                System.err.println("--verify requires scoop to be installed. Skipping installation verification.")
                null
            } else {
                ProcessScoopVerifier()
            }
        } catch (_: Exception) {
            System.err.println("--verify requires scoop to be installed. Skipping installation verification.")
            null
        }
    } else null

    val cache = ScoopCacheManager(keepCache = keepCache)

    val toolRegistry = DefaultToolRegistryFactory.create(
        cache = cache,
        manifestIo = manifestIo,
        scoopVerifier = scoopVerifier,
    )

    try {
        val result = runBlocking {
            val generator = ScoopManifestGenerator(
                promptExecutor = config.createExecutor(),
                model = config.model,
                toolRegistry = toolRegistry,
                manifestIo = manifestIo,
                maxIterations = 500,
            )

            val outputPath = resolvedOutput.ifBlank { null }
            generator.generate(url, outputPath)
        }
        println(result)

        // ── Finalize cache entries ─────────────────────────────────
        if (keepCache) {
            // Parse version and URL with regex (handles indented or compact JSON)
            val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(result)?.groupValues?.get(1)
            val mainUrl = Regex(""""url"\s*:\s*"(https?://[^"]+)"""").find(result)?.groupValues?.get(1)

            logger.info { "[cache] Finalizing: app=$resolvedAppName version=$version url=$mainUrl" }
            if (version != null && mainUrl != null) {
                val finalized = cache.finalizeCacheEntry(mainUrl, resolvedAppName, version)
                if (finalized != null) {
                    println("\nCache: ${finalized.toAbsolutePath()}")
                    println("scoop install $resolvedAppName will reuse this file.")
                } else {
                    logger.warn { "[cache] No cached file found for $mainUrl" }
                }
            } else {
                logger.warn { "[cache] Could not parse manifest: version=$version url=$mainUrl" }
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
    } finally {
        DefaultToolRegistryFactory.cleanup()
    }
}

private val HELP_TEXT = """
    Usage: scoop-ai <url> [options]

    Arguments:
      <url>                        GitHub repository URL or project URL

    Options:
      -o, --output <path>          Output file path
      -p, --provider <name>        LLM provider: deepseek|openai|anthropic|ollama|openrouter|openai-compatible
      -m, --model <name>           LLM model name (overrides default for provider)
          --base-url <url>         Custom API base URL (required for openai-compatible)
          --api-key <key>          API key
          --app-name <name>        App name for cache (default: inferred from URL)
          --no-cache               Do not keep downloaded files in Scoop cache
      -V, --verify                 Verify manifest by running scoop install
      -h, --help                   Show this help message

    Environment Variables:
      SCOOP_AI_PROVIDER            LLM provider (default: deepseek)
      SCOOP_AI_API_KEY             API key (provider-specific: DEEPSEEK_API_KEY, OPENAI_API_KEY, etc.)
      SCOOP_AI_BASE_URL            Custom API base URL
      SCOOP_AI_MODEL               LLM model name
      SCOOP_AI_OUTPUT              Default output file path

    Cache:
      Downloaded files are stored in the Scoop cache directory (~/scoop/cache/)
      using the same naming convention as Scoop. After generating a manifest,
      you can run 'scoop install <app>' directly — it will reuse the cached file.

    Examples:
      scoop-ai https://github.com/sharkdp/fd
      scoop-ai https://github.com/jqlang/jq -p openai
      scoop-ai https://github.com/BurntSushi/ripgrep -p openai-compatible --base-url http://localhost:8080/v1 -m qwen3
      scoop-ai https://github.com/Draver93/ff-studio --app-name ff-studio
""".trimIndent()
