package com.scoopai.tools.config

import com.scoopai.tools.llm.ProviderEndpoints

/**
 * Backward-compatible object facade over [resolveProviderConfig].
 *
 * The resolution logic now lives in [resolveProviderConfig]; this facade only
 * exists because `ScoopManifestConfig.resolveApiKey` documents a public
 * delegation target. Kept while hosts still reference it.
 *
 * @see resolveProviderConfig
 */
@Deprecated("Use resolveProviderConfig instead", ReplaceWith("resolveProviderConfig(flagApiKey = cliKey, env = env)"))
object ApiKeyResolver {

    /**
     * Resolves the API key for the given [provider].
     *
     * Resolution order:
     * 1. Explicit [cliKey] (from CLI `--api-key` argument)
     * 2. `SCOOP_AI_API_KEY` environment variable
     * 3. Provider-specific env var (e.g. `DEEPSEEK_API_KEY`)
     * 4. Ollama: returns empty string (no key needed)
     *
     * The provider is validated first so an unknown provider always fails with
     * the shared message, no matter which key source would have matched.
     *
     * @throws IllegalStateException if no key can be resolved
     */
    fun resolve(provider: String, cliKey: String = ""): String {
        val endpoint = ProviderEndpoints.forProvider(provider)
            ?: error(ProviderEndpoints.unsupportedProviderMessage(provider))

        if (cliKey.isNotBlank()) return cliKey

        val scoopKey = env("SCOOP_AI_API_KEY")
        if (!scoopKey.isNullOrBlank()) return scoopKey

        if (!endpoint.requiresApiKey) return ""

        val envKey = endpoint.envKey
            ?: error(ProviderEndpoints.unsupportedProviderMessage(provider))

        return env(envKey)
            ?: error("Please set $envKey environment variable or pass --api-key")
    }

    private fun env(key: String): String? = System.getenv(key)
}
