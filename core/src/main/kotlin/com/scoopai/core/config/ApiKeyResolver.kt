package com.scoopai.core.config

/**
 * Resolves API keys for LLM providers from CLI arguments, environment variables,
 * or provider-specific defaults.
 *
 * This is the **single source of truth** for API key resolution.
 * Extracted from [Main.kt][com.scoopai.cli.Main] and
 * [ScoopManifestConfig][com.scoopai.core.ScoopManifestConfig]
 * to centralize provider → key mapping.
 */
object ApiKeyResolver {

    /** Provider-specific environment variable names. */
    private val PROVIDER_ENV_KEYS = mapOf(
        "deepseek" to "DEEPSEEK_API_KEY",
        "openai" to "OPENAI_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "openai-compatible" to "OPENAI_COMPATIBLE_API_KEY",
    )

    /**
     * Resolves the API key for the given [provider].
     *
     * Resolution order:
     * 1. Explicit [cliKey] (from CLI `--api-key` argument)
     * 2. `SCOOP_AI_API_KEY` environment variable
     * 3. Provider-specific env var (e.g. `DEEPSEEK_API_KEY`)
     * 4. Ollama: returns empty string (no key needed)
     *
     * @throws IllegalStateException if no key can be resolved
     */
    fun resolve(provider: String, cliKey: String = ""): String {
        if (cliKey.isNotBlank()) return cliKey

        val scoopKey = env("SCOOP_AI_API_KEY")
        if (!scoopKey.isNullOrBlank()) return scoopKey

        if (provider == "ollama") return ""

        val envKey = PROVIDER_ENV_KEYS[provider]
            ?: error("Unsupported provider: $provider")

        return env(envKey)
            ?: error("Please set $envKey environment variable or pass --api-key")
    }

    private fun env(key: String): String? = System.getenv(key)
}
