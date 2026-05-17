package com.scoopai.core

import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.llm.LlmProviderFactory

data class ScoopManifestConfig(
    val provider: String = "deepseek",
    val apiKey: String = "",
    val model: LLModel = DeepSeekModels.DeepSeekChat,
    val maxIterations: Int = 30,
    val baseUrl: String? = null,
) {
    fun createExecutor(): PromptExecutor = LlmProviderFactory.create(provider, apiKey, baseUrl)

    companion object {
        fun resolveApiKey(provider: String, explicitKey: String = ""): String {
            if (explicitKey.isNotBlank()) return explicitKey
            return when (provider) {
                "deepseek" -> System.getenv("DEEPSEEK_API_KEY")
                "openai" -> System.getenv("OPENAI_API_KEY")
                "anthropic" -> System.getenv("ANTHROPIC_API_KEY")
                "ollama" -> ""
                "openrouter" -> System.getenv("OPENROUTER_API_KEY")
                "openai-compatible" -> System.getenv("OPENAI_COMPATIBLE_API_KEY") ?: ""
                else -> null
            } ?: error("Please set ${provider.uppercase()}_API_KEY environment variable or pass --api-key")
        }
    }
}
