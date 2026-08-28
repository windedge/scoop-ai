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
import com.scoopai.core.llm.malformedFunctionCallRetryHint
import com.scoopai.core.llm.normalizeMissingCreated
import com.scoopai.core.llm.reasoningFallbackToContent
import com.scoopai.core.llm.stripToolChoice

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
            val baseClient = javaHttpClient(apiKey, settings, "DeepSeekLLMClient")
            MultiLLMPromptExecutor(
                DeepSeekLLMClient(settings = settings, httpClient = InterceptingHttpClient(delegate = baseClient))
            )
        }
        "openai" -> {
            val settings = OpenAIClientSettings()
            val baseClient = javaHttpClient(apiKey, settings, "OpenAILLMClient")
            MultiLLMPromptExecutor(
                OpenAILLMClient(settings = settings, httpClient = InterceptingHttpClient(delegate = baseClient))
            )
        }
        "anthropic" -> MultiLLMPromptExecutor(AnthropicLLMClient(apiKey = apiKey))
        "ollama" -> MultiLLMPromptExecutor(OllamaClient())
        "openrouter" -> {
            val settings = OpenRouterClientSettings()
            val baseClient = javaHttpClient(apiKey, settings, "OpenRouterLLMClient")
            MultiLLMPromptExecutor(
                OpenRouterLLMClient(settings = settings, httpClient = InterceptingHttpClient(delegate = baseClient))
            )
        }
        "openai-compatible" -> {
            val rawBaseUrl = baseUrl ?: error("--base-url is required for openai-compatible provider")
            // Clients append "v1/chat/completions"; strip user-supplied /v1 to avoid double path (404).
            val resolvedBaseUrl = normalizeBaseUrl(rawBaseUrl)
            // Always OpenAILLMClient: proxy responses follow OpenAI schema (no systemFingerprint).
            // Capabilities are uniform for every model name (no DeepSeek special-case).
            val baseHttpClient = javaHttpClient(
                apiKey = apiKey,
                settings = DeepSeekClientSettings(
                    baseUrl = resolvedBaseUrl,
                    chatCompletionsPath = "v1/chat/completions",
                ),
                clientName = "OpenAICompatibleLLMClient",
            )
            val debugHttp = isDebugHttpEnabled()
            val interceptors = buildList {
                // fixDoubleEncoding removed: Koog 1.1.1 (#2096) fixed the
                // double-encoding of tool call arguments at the source.
                // Koog's setToolChoiceRequired() has no gating, so strip
                // tool_choice unconditionally for every model name.
                add(::stripToolChoice)
                if (debugHttp) add(::logRequestBody)
            }
            val responseLogger: ((String) -> Unit)? = if (debugHttp) {
                { body -> System.err.println("[SCOOP_AI_DEBUG_HTTP] response: ${body.take(2000)}") }
            } else {
                null
            }
            // Some OpenAI-compatible gateways omit the required `created` field.
            // Reasoning-only responses (content blank, no tool_calls, reasoning in an
            // auxiliary key) are mapped to content first, then `malformed_function_call`
            // empty turns get a text retry hint injected (so Koog can re-prompt), then
            // `created` is filled.
            val responseRewriter = { raw: String ->
                normalizeMissingCreated(
                    malformedFunctionCallRetryHint(reasoningFallbackToContent(raw)),
                    0L,
                )
            }
            MultiLLMPromptExecutor(
                OpenAILLMClient(
                    settings = OpenAIClientSettings(
                        baseUrl = resolvedBaseUrl,
                        chatCompletionsPath = "v1/chat/completions",
                    ),
                    httpClient = InterceptingHttpClient(
                        delegate = baseHttpClient,
                        interceptors = interceptors,
                        responseRewriter = responseRewriter,
                        onResponse = responseLogger,
                    ),
                )
            )
        }
        else -> error("Unsupported provider: $provider. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible")
    }

    fun defaultModel(provider: String, modelName: String? = null, baseUrl: String? = null): LLModel = when {
        // openai-compatible + model name goes through the generic branch:
        // provider identity OpenAI, capabilties uniform (no DeepSeek special-case).
        modelName != null -> LLModel(
            provider = providerToLLMProvider(provider),
            id = modelName,
            capabilities = modelCapabilities(provider),
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

    private fun modelCapabilities(provider: String): List<LLMCapability> = when (provider) {
        "openai", "openrouter", "openai-compatible" -> openAICompatibleCapabilities()
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

    /** Uniform capability set for every openai-compatible model name. */
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
    // Longest suffix first so "/v1/chat/completions" is not mis-stripped by "/v1".
    url = when {
        url.endsWith("/v1/chat/completions", ignoreCase = true) ->
            url.dropLast("/v1/chat/completions".length)
        url.endsWith("/v1", ignoreCase = true) ->
            url.dropLast("/v1".length)
        else -> url
    }
    while (url.endsWith('/')) {
        url = url.dropLast(1)
    }
    return url
}
