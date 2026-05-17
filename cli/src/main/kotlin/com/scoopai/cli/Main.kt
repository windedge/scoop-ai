package com.scoopai.cli

import com.scoopai.core.ScoopManifestConfig
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.llm.LlmProviderFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.verify.ProcessScoopVerifier
import kotlinx.coroutines.runBlocking

private fun env(key: String): String? = System.getenv(key)

private fun resolveProvider(cliValue: String): String =
    cliValue.ifBlank { env("SCOOP_AI_PROVIDER") ?: "deepseek" }

private fun resolveApiKey(provider: String, cliValue: String): String {
    if (cliValue.isNotBlank()) return cliValue
    val scoopKey = env("SCOOP_AI_API_KEY")
    if (!scoopKey.isNullOrBlank()) return scoopKey
    return ScoopManifestConfig.resolveApiKey(provider)
}

private fun resolveBaseUrl(cliValue: String?): String? =
    cliValue ?: env("SCOOP_AI_BASE_URL")

private fun resolveModel(cliValue: String?): String? =
    cliValue ?: env("SCOOP_AI_MODEL")

private fun resolveOutput(cliValue: String): String =
    cliValue.ifBlank { env("SCOOP_AI_OUTPUT") ?: "" }

fun main(args: Array<String>) {
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
                println("""
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
                """.trimIndent())
                return
            }
            else -> url = arg
        }
    }

    provider = resolveProvider(provider)
    output = resolveOutput(output)
    baseUrl = resolveBaseUrl(baseUrl)
    modelName = resolveModel(modelName)

    if (url.isBlank()) {
        System.err.println("Missing required argument: <url>")
        System.err.println()
        System.err.println("Usage: scoop-ai <url> [options]")
        System.err.println("Run 'scoop-ai --help' for more information.")
        return
    }

    if (provider == "openai-compatible" && baseUrl == null) {
        System.err.println("--base-url or SCOOP_AI_BASE_URL is required when using openai-compatible provider.")
        return
    }

    val resolvedApiKey = resolveApiKey(provider, apiKey)
    val model = LlmProviderFactory.defaultModel(provider, modelName, baseUrl)

    val config = ScoopManifestConfig(
        provider = provider,
        apiKey = resolvedApiKey,
        model = model,
        baseUrl = baseUrl,
    )

    val manifestIo = com.scoopai.tools.manifest.FileManifestIo()
    val envVerify = env("SCOOP_AI_VERIFY")?.lowercase() == "true"
    val doVerify = verify || envVerify

    val scoopVerifier = if (doVerify) {
        try {
            val check = ProcessBuilder("scoop", "--version")
                .redirectErrorStream(true)
                .start()
            val out = check.inputStream.bufferedReader().readText()
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
                maxIterations = config.maxIterations,
            )

            val outputPath = output.ifBlank { null }
            val result = generator.generate(url, outputPath)
            println(result)
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
        return
    }
}
