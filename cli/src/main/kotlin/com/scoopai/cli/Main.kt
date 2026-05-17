package com.scoopai.cli

import com.scoopai.core.ScoopManifestConfig
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.llm.LlmProviderFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    var url = ""
    var output = ""
    var provider = "deepseek"
    var apiKey = ""

    val i = args.iterator()
    while (i.hasNext()) {
        when (val arg = i.next()) {
            "-o", "--output" -> output = i.next()
            "-p", "--provider" -> provider = i.next()
            "--api-key" -> apiKey = i.next()
            "-h", "--help" -> {
                println("""
                    Usage: scoop-ai <url> [options]

                    Arguments:
                      <url>                    GitHub repository URL or project URL

                    Options:
                      -o, --output <path>      Output file path
                      -p, --provider <name>    LLM provider: deepseek|openai|anthropic|ollama|openrouter (default: deepseek)
                          --api-key <key>      API key (default: from environment variable)
                      -h, --help               Show this help message
                """.trimIndent())
                return
            }
            else -> url = arg
        }
    }

    require(url.isNotBlank()) { "Error: URL argument is required. Usage: scoop-ai <url>" }

    val resolvedApiKey = ScoopManifestConfig.resolveApiKey(provider, apiKey)
    val model = LlmProviderFactory.defaultModel(provider)

    val config = ScoopManifestConfig(
        provider = provider,
        apiKey = resolvedApiKey,
        model = model,
    )

    val toolRegistry = DefaultToolRegistryFactory.create()

    runBlocking {
        val generator = ScoopManifestGenerator(
            promptExecutor = config.createExecutor(),
            model = config.model,
            toolRegistry = toolRegistry,
            maxIterations = config.maxIterations,
        )

        val outputPath = output.ifBlank { null }
        val result = generator.generate(url, outputPath)
        println(result)
    }
}
