package com.scoopai.cli

import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.config.ProviderConfigResolver
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.verify.ProcessScoopVerifier
import kotlinx.coroutines.runBlocking

private fun env(key: String): String? = System.getenv(key)

fun main(args: Array<String>) {
    // ── Parse CLI arguments ────────────────────────────────────────
    var url = ""
    var output = ""
    var provider = ""
    var apiKey = ""
    var baseUrl: String? = null
    var modelName: String? = null
    var verify = false

    val i = args.iterator()
    while (i.hasNext()) {
        when (val arg = i.next()) {
            "-o", "--output" -> output = i.next()
            "-p", "--provider" -> provider = i.next()
            "--api-key" -> apiKey = i.next()
            "--base-url" -> baseUrl = i.next()
            "-m", "--model" -> modelName = i.next()
            "-V", "--verify" -> verify = true
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

    // ── Assemble object graph & run ────────────────────────────────
    val manifestIo = FileManifestIo()
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

    val toolRegistry = DefaultToolRegistryFactory.create(
        manifestIo = manifestIo,
        scoopVerifier = scoopVerifier,
    )

    try {
        runBlocking {
            val generator = ScoopManifestGenerator(
                promptExecutor = config.createExecutor(),
                model = config.model,
                toolRegistry = toolRegistry,
                manifestIo = manifestIo,
                maxIterations = 500,
            )

            val outputPath = resolvedOutput.ifBlank { null }
            val result = generator.generate(url, outputPath)
            println(result)
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
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
      -V, --verify                 Verify manifest by running scoop install
      -h, --help                   Show this help message

    Environment Variables:
      SCOOP_AI_PROVIDER            LLM provider (default: deepseek)
      SCOOP_AI_API_KEY             API key (provider-specific: DEEPSEEK_API_KEY, OPENAI_API_KEY, etc.)
      SCOOP_AI_BASE_URL            Custom API base URL
      SCOOP_AI_MODEL               LLM model name
      SCOOP_AI_OUTPUT              Default output file path

    CLI arguments take precedence over environment variables.

    Examples:
      scoop-ai https://github.com/sharkdp/fd
      scoop-ai https://github.com/jqlang/jq -p openai
      scoop-ai https://github.com/BurntSushi/ripgrep -p openai-compatible --base-url http://localhost:8080/v1 -m qwen3
""".trimIndent()
