package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.scoopai.tools.config.ProviderConfigResolver

/**
 * Display current runtime configuration.
 *
 * Shows the effective configuration after merging:
 * 1. CLI flags (not applicable here — config show reads env/defaults)
 * 2. Environment variables
 * 3. Built-in defaults
 *
 * API keys and tokens are shown as "set" or "unset" — never the actual value.
 *
 * @param env Environment variable map (default: [System.getenv]), injectable for testing.
 */
class ConfigShowCommand(
    private val env: Map<String, String> = System.getenv(),
) : CliktCommand(
    name = "show",
    help = "Display current runtime configuration (env vars + defaults)",
) {
    override fun run() {
        // Resolve provider config using env vars / defaults (no CLI flags).
        // If resolution fails (e.g. missing API key), fall back to env var reads.
        val (providerStr, modelStr, baseUrlStr) = resolveProviderConfig()

        val apiKeyStatus = resolveApiKeyStatus(providerStr)
        val gitHubTokenStatus = resolveGitHubTokenStatus()
        val persistMode = env["SCOOP_AI_PERSIST"] ?: "auto"
        val nonInteractive = env["SCOOP_AI_NON_INTERACTIVE"]?.lowercase() == "true"
        val cacheEnabled = env["SCOOP_AI_NO_CACHE"]?.lowercase() != "true"
        val debug = env["SCOOP_AI_DEBUG"]?.lowercase() == "true"

        echo("Runtime Configuration (env vars + defaults):")
        echo("")
        echo("  provider:           $providerStr")
        echo("  model:              $modelStr")
        echo("  base-url:           $baseUrlStr")
        echo("  api-key:            $apiKeyStatus")
        echo("  github-token:       $gitHubTokenStatus")
        echo("  persist-mode:       $persistMode")
        echo("  non-interactive:    $nonInteractive")
        echo("  cache-enabled:      $cacheEnabled")
        echo("  debug:              $debug")
        echo("")
        echo("Note: This is the runtime configuration from environment variables and defaults.")
        echo("It is NOT a persisted config file. Use environment variables to configure.")
        echo("Pass --help to the generate subcommand for CLI flag documentation.")
    }

    /**
     * Try to use [ProviderConfigResolver] first; fall back to manual env reads on failure.
     */
    private fun resolveProviderConfig(): Triple<String, String, String> {
        return try {
            val config = ProviderConfigResolver.resolve()
            Triple(
                config.provider,
                config.model.id,
                config.baseUrl ?: "default (not set)",
            )
        } catch (_: Exception) {
            val provider = env["SCOOP_AI_PROVIDER"] ?: "deepseek"
            val model = env["SCOOP_AI_MODEL"] ?: "default"
            val baseUrl = env["SCOOP_AI_BASE_URL"] ?: "default (not set)"
            Triple(provider, model, baseUrl)
        }
    }

    private fun resolveApiKeyStatus(provider: String): String {
        if (env["SCOOP_AI_API_KEY"]?.isNotBlank() == true) return "set"
        val providerKey = when (provider) {
            "deepseek" -> "DEEPSEEK_API_KEY"
            "openai" -> "OPENAI_API_KEY"
            "anthropic" -> "ANTHROPIC_API_KEY"
            "openrouter" -> "OPENROUTER_API_KEY"
            "openai-compatible" -> "OPENAI_COMPATIBLE_API_KEY"
            else -> return "unset"
        }
        return if (env[providerKey]?.isNotBlank() == true) "set" else "unset"
    }

    private fun resolveGitHubTokenStatus(): String {
        if (env["SCOOP_AI_GITHUB_TOKEN"]?.isNotBlank() == true) return "set"
        if (env["GITHUB_TOKEN"]?.isNotBlank() == true) return "set"
        return "unset"
    }
}
