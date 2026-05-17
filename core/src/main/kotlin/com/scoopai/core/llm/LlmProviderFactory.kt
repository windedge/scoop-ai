package com.scoopai.core.llm

import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

@OptIn(kotlin.time.ExperimentalTime::class)
object LlmProviderFactory {
    fun create(provider: String, apiKey: String, baseUrl: String? = null, modelName: String? = null): PromptExecutor = when (provider) {
        "deepseek" -> SingleLLMPromptExecutor(DeepSeekLLMClient(apiKey))
        "openai" -> simpleOpenAIExecutor(apiKey)
        "anthropic" -> simpleAnthropicExecutor(apiKey)
        "ollama" -> simpleOllamaAIExecutor()
        "openrouter" -> simpleOpenRouterExecutor(apiKey)
        "openai-compatible" -> SingleLLMPromptExecutor(
            OpenAILLMClient(
                apiKey = apiKey,
                settings = OpenAIClientSettings(baseUrl = baseUrl ?: error("--base-url is required for openai-compatible provider")),
            )
        )
        else -> error("Unsupported provider: $provider. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible")
    }

    fun defaultModel(provider: String, modelName: String? = null, baseUrl: String? = null): LLModel = when {
        modelName != null && provider == "openai-compatible" -> LLModel(
            provider = LLMProvider.OpenAI,
            id = modelName,
        )
        modelName != null -> LLModel(
            provider = when (provider) {
                "deepseek" -> LLMProvider.DeepSeek
                "openai" -> LLMProvider.OpenAI
                "anthropic" -> LLMProvider.Anthropic
                "ollama" -> LLMProvider.Ollama
                "openrouter" -> LLMProvider.OpenRouter
                else -> LLMProvider.OpenAI
            },
            id = modelName,
        )
        else -> when (provider) {
            "deepseek" -> DeepSeekModels.DeepSeekChat
            "openai" -> OpenAIModels.Chat.GPT4o
            "anthropic" -> ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_1
            "ollama" -> ai.koog.prompt.executor.ollama.client.OllamaModels.Meta.LLAMA_3_2
            "openrouter" -> OpenAIModels.Chat.GPT4o
            "openai-compatible" -> LLModel(provider = LLMProvider.OpenAI, id = "default")
            else -> DeepSeekModels.DeepSeekChat
        }
    }
}
