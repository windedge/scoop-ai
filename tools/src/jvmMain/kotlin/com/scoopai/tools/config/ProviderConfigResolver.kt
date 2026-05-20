package com.scoopai.tools.config

import ai.koog.prompt.llm.LLModel
import com.scoopai.tools.llm.LlmProviderFactory

/**
 * Resolves and assembles the full LLM provider configuration:
 * provider name, API key, model, and base URL.
 *
 * This is the **single source of truth** for assembling [ResolvedProviderConfig].
 * Extracted from [Main.kt][com.scoopai.cli.Main] to centralize
 * CLI → config resolution.
 */
object ProviderConfigResolver {

    /**
     * Resolves a complete [ResolvedProviderConfig] from CLI arguments
     * and environment variable fallbacks.
     */
    fun resolve(
        cliProvider: String = "",
        cliApiKey: String = "",
        cliBaseUrl: String? = null,
        cliModel: String? = null,
    ): ResolvedProviderConfig {
        val provider = resolveProvider(cliProvider)
        val apiKey = ApiKeyResolver.resolve(provider, cliApiKey)
        val baseUrl = cliBaseUrl ?: env("SCOOP_AI_BASE_URL")
        val modelName = cliModel ?: env("SCOOP_AI_MODEL")
        val model = LlmProviderFactory.defaultModel(provider, modelName, baseUrl)

        return ResolvedProviderConfig(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
        )
    }

    private fun resolveProvider(cliValue: String): String =
        cliValue.ifBlank { env("SCOOP_AI_PROVIDER") ?: "deepseek" }

    private fun env(key: String): String? = System.getenv(key)
}

/**
 * Fully resolved LLM provider configuration — all CLI/env resolution is complete.
 * This is a pure data object with no resolution logic.
 */
data class ResolvedProviderConfig(
    val provider: String,
    val apiKey: String,
    val model: LLModel,
    val baseUrl: String?,
) {
    fun createExecutor() = LlmProviderFactory.create(provider, apiKey, baseUrl, model.id)
}
