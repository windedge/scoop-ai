package com.scoopai.tools.config

import ai.koog.prompt.llm.LLModel
import com.scoopai.tools.llm.LlmProviderFactory
import com.scoopai.tools.llm.ProviderEndpoints

/**
 * Default provider when neither the flag nor `SCOOP_AI_PROVIDER` is set.
 *
 * Note: engine default ("deepseek") differs from the desktop host default
 * ("openai-compatible" in Scooper) on purpose: CLI users are expected to have
 * a key for a hosted provider, while the GUI host defaults to a local gateway.
 */
public const val DEFAULT_PROVIDER: String = "deepseek"

/** Provider id of the gateway provider whose base URL is mandatory. */
public const val OPENAI_COMPATIBLE_PROVIDER: String = "openai-compatible"

/**
 * Single shared message for the mandatory openai-compatible base URL.
 *
 * The literal lives in [ProviderEndpoints] (the engine's provider metadata
 * table, also consumed by the desktop host); this is a re-export so the
 * config surface and the CLI report exactly the same text as the factory.
 */
public const val OPENAI_COMPATIBLE_BASE_URL_REQUIRED: String =
    ProviderEndpoints.OPENAI_COMPATIBLE_BASE_URL_REQUIRED

/**
 * Fully resolved LLM provider configuration - all CLI/env resolution is
 * complete. Pure data: no resolution logic inside.
 */
public data class ResolvedProviderConfig(
    val provider: String,
    val apiKey: String,
    val model: LLModel,
    val baseUrl: String?,
    val githubToken: String? = null,
) {
    fun createExecutor() = LlmProviderFactory.create(provider, apiKey, baseUrl, model.id)
}

/**
 * Resolve GitHub token: flag > `SCOOP_AI_GITHUB_TOKEN` > `GITHUB_TOKEN` > null.
 * Blank values count as unset.
 */
public fun resolveGithubToken(
    flagGithubToken: String? = null,
    env: (String) -> String? = System::getenv,
): String? = flagGithubToken
    ?: env("SCOOP_AI_GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
    ?: env("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }

/**
 * Single source of truth for flag > env > default configuration resolution.
 *
 * Aggregates the former ApiKeyResolver and ProviderConfigResolver logic so
 * the CLI resolves once per run and hands the result to the factory:
 *
 * - provider: [flagProvider] > `SCOOP_AI_PROVIDER` > [defaultProvider]
 * - apiKey: [flagApiKey] > `SCOOP_AI_API_KEY` > provider env key
 *   (e.g. `DEEPSEEK_API_KEY`; ollama needs no key)
 * - baseUrl: [flagBaseUrl] > `SCOOP_AI_BASE_URL` (no normalization here:
 *   [LlmProviderFactory] owns normalization)
 * - model: [flagModel] > `SCOOP_AI_MODEL` > provider default model
 * - githubToken: [flagGithubToken] > `SCOOP_AI_GITHUB_TOKEN` > `GITHUB_TOKEN`
 *
 * The provider is validated against [ProviderEndpoints] first so an unknown
 * provider always fails with the shared message, no matter which key source
 * would have matched. The mandatory openai-compatible base URL is validated
 * here too, so a host that resolves once fails at resolution time with the
 * shared message instead of later inside the factory.
 *
 * @throws IllegalStateException when the provider is unknown, a required
 *   API key is missing, or openai-compatible has no base URL.
 */
public fun resolveProviderConfig(
    flagProvider: String = "",
    flagApiKey: String = "",
    flagBaseUrl: String? = null,
    flagModel: String? = null,
    flagGithubToken: String? = null,
    env: (String) -> String? = System::getenv,
    defaultProvider: String = DEFAULT_PROVIDER,
): ResolvedProviderConfig {
    val provider = flagProvider.ifBlank { env("SCOOP_AI_PROVIDER")?.ifBlank { null } ?: defaultProvider }

    val endpoint = ProviderEndpoints.forProvider(provider)
        ?: error(ProviderEndpoints.unsupportedProviderMessage(provider))

    val apiKey = flagApiKey.ifBlank {
        env("SCOOP_AI_API_KEY")?.ifBlank { null }
            ?: if (endpoint.requiresApiKey) {
                val envKey = endpoint.envKey
                    ?: error(ProviderEndpoints.unsupportedProviderMessage(provider))
                env(envKey)?.ifBlank { null }
                    ?: error("Please set $envKey environment variable or pass --api-key")
            } else {
                ""
            }
    }

    val baseUrl = flagBaseUrl ?: env("SCOOP_AI_BASE_URL")?.ifBlank { null }

    if (provider == OPENAI_COMPATIBLE_PROVIDER && baseUrl == null) {
        error(OPENAI_COMPATIBLE_BASE_URL_REQUIRED)
    }

    val modelName = flagModel ?: env("SCOOP_AI_MODEL")?.ifBlank { null }
    val model = LlmProviderFactory.defaultModel(provider, modelName, baseUrl)

    return ResolvedProviderConfig(
        provider = provider,
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        githubToken = resolveGithubToken(flagGithubToken, env),
    )
}
