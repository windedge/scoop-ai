package com.scoopai.core.llm

import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel

@OptIn(kotlin.time.ExperimentalTime::class)
object LlmProviderFactory {
    fun create(provider: String, apiKey: String): PromptExecutor = when (provider) {
        "deepseek" -> SingleLLMPromptExecutor(DeepSeekLLMClient(apiKey))
        "openai" -> simpleOpenAIExecutor(apiKey)
        "anthropic" -> simpleAnthropicExecutor(apiKey)
        "ollama" -> simpleOllamaAIExecutor()
        "openrouter" -> simpleOpenRouterExecutor(apiKey)
        else -> error("Unsupported provider: $provider")
    }

    fun defaultModel(provider: String): LLModel = when (provider) {
        "deepseek" -> DeepSeekModels.DeepSeekChat
        "openai" -> OpenAIModels.Chat.GPT4o
        "anthropic" -> ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_1
        "ollama" -> ai.koog.prompt.executor.ollama.client.OllamaModels.Meta.LLAMA_3_2
        "openrouter" -> OpenAIModels.Chat.GPT4o
        else -> DeepSeekModels.DeepSeekChat
    }
}
