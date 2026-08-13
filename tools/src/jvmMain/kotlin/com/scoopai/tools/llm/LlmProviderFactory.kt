package com.scoopai.tools.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.llm.InterceptingHttpClient
import com.scoopai.core.llm.downgradeToolChoice
import com.scoopai.core.llm.fixDoubleEncoding

@OptIn(kotlin.time.ExperimentalTime::class)
object LlmProviderFactory {

    /**
     * Create a PromptExecutor for the given provider.
     *
     * OpenAI-family clients always use the explicit Java HTTP factory so SPI
     * auto-resolution does not break when both Java and Ktor clients are present.
     */
    fun create(provider: String, apiKey: String, baseUrl: String? = null, modelName: String? = null): PromptExecutor = when (provider) {
        "deepseek" -> {
            val settings = DeepSeekClientSettings()
            MultiLLMPromptExecutor(
                DeepSeekLLMClient(settings = settings, httpClient = javaHttpClient(apiKey, settings, "DeepSeekLLMClient"))
            )
        }
        "openai" -> {
            val settings = OpenAIClientSettings()
            MultiLLMPromptExecutor(
                OpenAILLMClient(settings = settings, httpClient = javaHttpClient(apiKey, settings, "OpenAILLMClient"))
            )
        }
        "anthropic" -> MultiLLMPromptExecutor(AnthropicLLMClient(apiKey = apiKey))
        "ollama" -> MultiLLMPromptExecutor(OllamaClient())
        "openrouter" -> {
            val settings = OpenRouterClientSettings()
            MultiLLMPromptExecutor(
                OpenRouterLLMClient(settings = settings, httpClient = javaHttpClient(apiKey, settings, "OpenRouterLLMClient"))
            )
        }
        "openai-compatible" -> {
            val rawBaseUrl = baseUrl ?: error("--base-url is required for openai-compatible provider")
            // Clients append "v1/chat/completions"; strip user-supplied /v1 to avoid double path (404).
            val resolvedBaseUrl = normalizeBaseUrl(rawBaseUrl)
            // Always OpenAILLMClient: proxy responses follow OpenAI schema (no systemFingerprint).
            // Thinking capability for DeepSeek model names is set in openAICompatibleCapabilities().
            val baseHttpClient = javaHttpClient(
                apiKey = apiKey,
                settings = DeepSeekClientSettings(
                    baseUrl = resolvedBaseUrl,
                    chatCompletionsPath = "v1/chat/completions",
                ),
                clientName = "OpenAICompatibleLLMClient",
            )
            val interceptors = buildList {
                add(::fixDoubleEncoding)
                if (modelName.isDeepSeekModelName()) add(::downgradeToolChoice)
                if (isDebugHttpEnabled()) add(::logRequestBody)
            }
            MultiLLMPromptExecutor(
                OpenAILLMClient(
                    settings = OpenAIClientSettings(
                        baseUrl = resolvedBaseUrl,
                        chatCompletionsPath = "v1/chat/completions",
                    ),
                    httpClient = InterceptingHttpClient(baseHttpClient, interceptors),
                )
            )
        }
        else -> error("Unsupported provider: $provider. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible")
    }

    fun defaultModel(provider: String, modelName: String? = null, baseUrl: String? = null): LLModel = when {
        // openai-compatible + DeepSeek model name uses OpenAI provider identity
        // (proxy returns OpenAI-shaped responses) but keeps Thinking capability.
        modelName != null && provider == "openai-compatible" && modelName.isDeepSeekModelName() -> LLModel(
            provider = LLMProvider.OpenAI,
            id = modelName,
            capabilities = openAICompatibleCapabilities(modelName),
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

    /** Explicit Java factory avoids multi-SPI resolution failures on consumer classpaths. */
    private fun javaHttpClient(
        apiKey: String,
        settings: OpenAIBaseSettings,
        clientName: String,
    ): KoogHttpClient = AbstractOpenAILLMClient.createConfiguredHttpClient(
        apiKey = apiKey,
        settings = settings,
        httpClientFactory = JavaKoogHttpClient.Factory(),
        clientName = clientName,
    )

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
        if (modelName.isDeepSeekModelName()) {
            base.add(LLMCapability.Thinking)
        }
        return base.toList()
    }
}

/**
 * Normalize an OpenAI-compatible base URL so clients can safely append
 * `v1/chat/completions` without producing a double `/v1` path (HTTP 404).
 *
 * - trims whitespace
 * - strips trailing `/`
 * - strips trailing `/v1` or `/v1/`
 * - strips trailing `/v1/chat/completions`
 */
internal fun normalizeBaseUrl(baseUrl: String): String {
    var url = baseUrl.trim()
    while (url.endsWith('/')) {
        url = url.dropLast(1)
    }
    val suffixes = listOf("/v1/chat/completions", "/v1")
    for (suffix in suffixes) {
        if (url.endsWith(suffix, ignoreCase = true)) {
            url = url.dropLast(suffix.length)
            break
        }
    }
    while (url.endsWith('/')) {
        url = url.dropLast(1)
    }
    return url
}
