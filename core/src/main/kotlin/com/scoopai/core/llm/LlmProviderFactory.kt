package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

@OptIn(kotlin.time.ExperimentalTime::class)
object LlmProviderFactory {

    /**
     * Create a PromptExecutor for the given provider.
     *
     * For openai-compatible + DeepSeek models, the Koog 1.0.0 DeepSeekLLMClient
     * already uses AdditionalPropertiesFlatteningSerializer internally, so we no
     * longer need the manual InterceptBodyPlugin workaround from Koog 0.8.0.
     */
    fun create(provider: String, apiKey: String, baseUrl: String? = null, modelName: String? = null): PromptExecutor = when (provider) {
        "deepseek" -> SingleLLMPromptExecutor(DeepSeekLLMClient(apiKey))
        "openai" -> SingleLLMPromptExecutor(OpenAILLMClient(apiKey = apiKey))
        "anthropic" -> SingleLLMPromptExecutor(AnthropicLLMClient(apiKey = apiKey))
        "ollama" -> SingleLLMPromptExecutor(OllamaClient())
        "openrouter" -> SingleLLMPromptExecutor(OpenRouterLLMClient(apiKey = apiKey))
        "openai-compatible" -> {
            val resolvedBaseUrl = baseUrl ?: error("--base-url is required for openai-compatible provider")
            val settings = DeepSeekClientSettings(
                baseUrl = resolvedBaseUrl,
                chatCompletionsPath = "v1/chat/completions",
            )
            // Build the HTTP client via the standard factory, then optionally
            // wrap with LoggingKoogHttpClient when SCOOP_AI_DEBUG_HTTP is set.
            val baseHttpClient: KoogHttpClient = AbstractOpenAILLMClient.createConfiguredHttpClient(
                apiKey = apiKey,
                settings = settings,
                httpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
                clientName = "DeepSeekLLMClient",
            )
            val httpClient = DoubleEncodingFixHttpClient(
                LoggingKoogHttpClient.wrapIfDebug(baseHttpClient)
            )
            SingleLLMPromptExecutor(
                DeepSeekLLMClient(
                    settings = settings,
                    httpClient = httpClient,
                )
            )
        }
        else -> error("Unsupported provider: $provider. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible")
    }

    fun defaultModel(provider: String, modelName: String? = null, baseUrl: String? = null): LLModel = when {
        // openai-compatible + DeepSeek model name → use DeepSeek provider identity
        // but keep the user-provided model id (e.g. "opencode/deepseek-v4-flash")
        modelName != null && provider == "openai-compatible" && modelName.isDeepSeekModelName() -> LLModel(
            provider = LLMProvider.DeepSeek,
            id = modelName,
            capabilities = deepSeekCapabilities(),
            contextLength = DeepSeekModels.DeepSeekV4Flash.contextLength,
            maxOutputTokens = DeepSeekModels.DeepSeekV4Flash.maxOutputTokens,
        )
        modelName != null -> LLModel(
            provider = providerToLLMProvider(provider),
            id = modelName,
            capabilities = modelCapabilities(provider, modelName),
        )
        else -> when (provider) {
            "deepseek" -> DeepSeekModels.DeepSeekV4Flash
            "openai" -> OpenAIModels.Chat.GPT4o
            "anthropic" -> ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_1
            "ollama" -> ai.koog.prompt.executor.ollama.client.OllamaModels.Meta.LLAMA_3_2
            "openrouter" -> OpenAIModels.Chat.GPT4o
            "openai-compatible" -> LLModel(
                provider = LLMProvider.OpenAI,
                id = "default",
                capabilities = openAICompatibleCapabilities(),
            )
            else -> DeepSeekModels.DeepSeekV4Flash
        }
    }

    private fun providerToLLMProvider(provider: String): LLMProvider = when (provider) {
        "deepseek" -> LLMProvider.DeepSeek
        "openai" -> LLMProvider.OpenAI
        "anthropic" -> LLMProvider.Anthropic
        "ollama" -> LLMProvider.Ollama
        "openrouter" -> LLMProvider.OpenRouter
        else -> LLMProvider.OpenAI
    }

    private fun String?.isDeepSeekModelName(): Boolean =
        this?.contains("deepseek", ignoreCase = true) == true

    private fun modelCapabilities(provider: String, modelName: String? = null): List<LLMCapability> = when (provider) {
        "openai", "openrouter", "openai-compatible" -> openAICompatibleCapabilities(modelName)
        "deepseek" -> deepSeekCapabilities()
        "anthropic" -> listOf(
            LLMCapability.Temperature,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Completion,
        )
        "ollama" -> listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
            LLMCapability.Completion,
        )
        else -> listOf(LLMCapability.Completion, LLMCapability.Tools)
    }

    /**
     * DeepSeek V4 models support Thinking capability.
     * When using an openai-compatible proxy with a DeepSeek model name,
     * include LLMCapability.Thinking so the agent framework passes
     * thinking parameters correctly.
     */
    private fun deepSeekCapabilities(): List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Tools,
        LLMCapability.Completion,
        LLMCapability.OpenAIEndpoint.Completions,
        LLMCapability.Thinking,
    )

    private fun openAICompatibleCapabilities(modelName: String? = null): List<LLMCapability> {
        val base = mutableListOf(
            LLMCapability.Temperature,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
        )
        // DeepSeek models accessed via openai-compatible proxy also support thinking
        if (modelName.isDeepSeekModelName()) {
            base.add(LLMCapability.Thinking)
        }
        return base.toList()
    }
}