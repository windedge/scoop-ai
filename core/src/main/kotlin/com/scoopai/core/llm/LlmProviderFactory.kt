package com.scoopai.core.llm

import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
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
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@OptIn(kotlin.time.ExperimentalTime::class)
object LlmProviderFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Workaround for Koog 0.8.0 + OpenAI-compatible reasoning models.
     *
     * Koog serializes provider-specific params under `additional_properties`, but some OpenAI-compatible
     * proxies (including the current DeepSeek-backed one) expect fields like `thinking` at the top level.
     *
     * This plugin flattens `additional_properties` into the request JSON body before sending it.
     */
    private val InterceptBodyPlugin = createClientPlugin("InterceptBody") {
        transformRequestBody { _, content, bodyType ->
            if (bodyType?.type == String::class) {
                val jsonContent = content as String
                runCatching {
                    val jsonObj = json.parseToJsonElement(jsonContent).jsonObject
                    val additionalProperties = jsonObj["additional_properties"] as? JsonObject
                    if (additionalProperties != null) {
                        val newJson = buildJsonObject {
                            for ((key, value) in jsonObj) {
                                if (key != "additional_properties") {
                                    put(key, value)
                                }
                            }
                            for ((key, value) in additionalProperties) {
                                if (!jsonObj.containsKey(key)) {
                                    put(key, value)
                                }
                            }
                        }
                        TextContent(
                            text = json.encodeToString(newJson),
                            contentType = ContentType.Application.Json,
                        )
                    } else {
                        null
                    }
                }.getOrElse {
                    null
                }
            } else {
                null
            }
        }
    }

    fun create(provider: String, apiKey: String, baseUrl: String? = null, modelName: String? = null): PromptExecutor = when (provider) {
        "deepseek" -> SingleLLMPromptExecutor(DeepSeekLLMClient(apiKey))
        "openai" -> simpleOpenAIExecutor(apiKey)
        "anthropic" -> simpleAnthropicExecutor(apiKey)
        "ollama" -> simpleOllamaAIExecutor()
        "openrouter" -> simpleOpenRouterExecutor(apiKey)
        "openai-compatible" -> {
            val resolvedBaseUrl = baseUrl ?: error("--base-url is required for openai-compatible provider")
            if (modelName.isDeepSeekModelName()) {
                SingleLLMPromptExecutor(
                    DeepSeekLLMClient(
                        apiKey = apiKey,
                        settings = DeepSeekClientSettings(
                            baseUrl = resolvedBaseUrl,
                            chatCompletionsPath = "v1/chat/completions",
                        ),
                        baseClient = HttpClient {
                            install(InterceptBodyPlugin)
                        },
                    )
                )
            } else {
                SingleLLMPromptExecutor(
                    OpenAILLMClient(
                        apiKey = apiKey,
                        settings = OpenAIClientSettings(baseUrl = resolvedBaseUrl),
                    )
                )
            }
        }
        else -> error("Unsupported provider: $provider. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible")
    }

    fun defaultModel(provider: String, modelName: String? = null, baseUrl: String? = null): LLModel = when {
        modelName != null -> LLModel(
            provider = providerToLLMProvider(provider),
            id = modelName,
            capabilities = modelCapabilities(provider),
        )
        else -> when (provider) {
            "deepseek" -> DeepSeekModels.DeepSeekChat
            "openai" -> OpenAIModels.Chat.GPT4o
            "anthropic" -> ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_1
            "ollama" -> ai.koog.prompt.executor.ollama.client.OllamaModels.Meta.LLAMA_3_2
            "openrouter" -> OpenAIModels.Chat.GPT4o
            "openai-compatible" -> LLModel(
                provider = LLMProvider.OpenAI,
                id = "default",
                capabilities = openAICompatibleCapabilities(),
            )
            else -> DeepSeekModels.DeepSeekChat
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

    private fun modelCapabilities(provider: String): List<LLMCapability> = when (provider) {
        "openai", "openrouter", "openai-compatible" -> openAICompatibleCapabilities()
        "deepseek" -> listOf(
            LLMCapability.Temperature,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
        )
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

    private fun openAICompatibleCapabilities(): List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Tools,
        LLMCapability.Completion,
        LLMCapability.OpenAIEndpoint.Completions,
    )
}
