package com.scoopai.core

import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.config.ApiKeyResolver
import com.scoopai.core.llm.LlmProviderFactory

data class ScoopManifestConfig(
    val provider: String = "deepseek",
    val apiKey: String = "",
    val model: LLModel = DeepSeekModels.DeepSeekV4Flash,
    val maxIterations: Int = 500,
    val baseUrl: String? = null,
) {
    fun createExecutor(): PromptExecutor = LlmProviderFactory.create(provider, apiKey, baseUrl, model.id)

    companion object {
        /**
         * Resolves the API key for the given provider.
         * Delegates to [ApiKeyResolver.resolve] for actual resolution.
         */
        fun resolveApiKey(provider: String, explicitKey: String = ""): String =
            ApiKeyResolver.resolve(provider, explicitKey)
    }
}
