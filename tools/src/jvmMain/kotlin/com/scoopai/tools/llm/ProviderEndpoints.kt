package com.scoopai.tools.llm

/**
 * Protocol metadata for every LLM provider supported by the engine.
 *
 * This object is the **single source of truth** for provider endpoints: the
 * engine builds its clients from these values and the host application (GUI)
 * consumes the same table to probe models and test connections. Keeping one
 * table avoids the two sides drifting apart, so "the host probe succeeds"
 * implies "the engine can talk to that endpoint".
 *
 * The endpoint values mirror the Koog 1.1.1 client settings exactly
 * (`DeepSeekClientSettings`, `OpenAIClientSettings`, `AnthropicClientSettings`,
 * `OllamaClient`, `OpenRouterClientSettings`).
 */
public object ProviderEndpoints {

    /**
     * Protocol description of a single provider.
     *
     * @property provider Provider id as used in the configuration and the CLI.
     * @property defaultBaseUrl Base URL used when the user does not supply one.
     *   `null` means the provider has no default endpoint and the user must
     *   provide a base URL (currently only `openai-compatible`).
     * @property modelsPath Relative path of the model listing endpoint.
     * @property chatPath Relative path of the chat completion endpoint.
     * @property authHeader Name of the header carrying the API key, `null` when
     *   the provider needs no authentication (Ollama).
     * @property bearerAuth `true` when the key must be sent as
     *   `Authorization: Bearer <key>`.
     * @property apiVersionHeader Name of the protocol version header, only
     *   Anthropic uses one (`anthropic-version`).
     * @property apiVersion Value of [apiVersionHeader].
     * @property requiresApiKey `false` when the provider works without a key.
     * @property requiresBaseUrl `true` when the user must supply a base URL.
     * @property envKey Environment variable holding the API key, `null` when
     *   the provider needs no key.
     * @property stripV1Suffix `true` when a user supplied base URL must have a
     *   trailing `/v1` removed before the provider path is appended. OpenRouter
     *   keeps it because its official base already carries the `api/v1` prefix.
     * @property allowsCustomBaseUrl `true` when the user may override the
     *   default endpoint.
     */
    public data class Endpoint(
        public val provider: String,
        public val defaultBaseUrl: String?,
        public val modelsPath: String,
        public val chatPath: String,
        public val authHeader: String?,
        public val bearerAuth: Boolean,
        public val apiVersionHeader: String?,
        public val apiVersion: String?,
        public val requiresApiKey: Boolean,
        public val requiresBaseUrl: Boolean,
        public val envKey: String?,
        public val stripV1Suffix: Boolean,
        public val allowsCustomBaseUrl: Boolean,
    )

    /** All supported providers, in the order the UI shows them. */
    public val all: List<Endpoint> = listOf(
        Endpoint(
            provider = "deepseek",
            defaultBaseUrl = "https://api.deepseek.com",
            modelsPath = "models",
            chatPath = "chat/completions",
            authHeader = "Authorization",
            bearerAuth = true,
            apiVersionHeader = null,
            apiVersion = null,
            requiresApiKey = true,
            requiresBaseUrl = false,
            envKey = "DEEPSEEK_API_KEY",
            stripV1Suffix = true,
            allowsCustomBaseUrl = true,
        ),
        Endpoint(
            provider = "openai",
            defaultBaseUrl = "https://api.openai.com",
            modelsPath = "v1/models",
            chatPath = "v1/chat/completions",
            authHeader = "Authorization",
            bearerAuth = true,
            apiVersionHeader = null,
            apiVersion = null,
            requiresApiKey = true,
            requiresBaseUrl = false,
            envKey = "OPENAI_API_KEY",
            stripV1Suffix = true,
            allowsCustomBaseUrl = true,
        ),
        Endpoint(
            provider = "anthropic",
            defaultBaseUrl = "https://api.anthropic.com",
            modelsPath = "v1/models",
            chatPath = "v1/messages",
            authHeader = "x-api-key",
            bearerAuth = false,
            apiVersionHeader = "anthropic-version",
            apiVersion = "2023-06-01",
            requiresApiKey = true,
            requiresBaseUrl = false,
            envKey = "ANTHROPIC_API_KEY",
            stripV1Suffix = true,
            allowsCustomBaseUrl = true,
        ),
        Endpoint(
            provider = "ollama",
            defaultBaseUrl = "http://localhost:11434",
            modelsPath = "api/tags",
            chatPath = "api/chat",
            authHeader = null,
            bearerAuth = false,
            apiVersionHeader = null,
            apiVersion = null,
            requiresApiKey = false,
            requiresBaseUrl = false,
            envKey = null,
            stripV1Suffix = false,
            allowsCustomBaseUrl = true,
        ),
        Endpoint(
            provider = "openrouter",
            defaultBaseUrl = "https://openrouter.ai",
            modelsPath = "api/v1/models",
            chatPath = "api/v1/chat/completions",
            authHeader = "Authorization",
            bearerAuth = true,
            apiVersionHeader = null,
            apiVersion = null,
            requiresApiKey = true,
            requiresBaseUrl = false,
            envKey = "OPENROUTER_API_KEY",
            stripV1Suffix = false,
            allowsCustomBaseUrl = true,
        ),
        Endpoint(
            provider = "openai-compatible",
            defaultBaseUrl = null,
            modelsPath = "v1/models",
            chatPath = "v1/chat/completions",
            authHeader = "Authorization",
            bearerAuth = true,
            apiVersionHeader = null,
            apiVersion = null,
            requiresApiKey = true,
            requiresBaseUrl = true,
            envKey = "OPENAI_COMPATIBLE_API_KEY",
            stripV1Suffix = true,
            allowsCustomBaseUrl = true,
        ),
    )

    private val byProvider: Map<String, Endpoint> = all.associateBy { it.provider }

    /** Protocol description of [provider], or `null` when the provider is unknown. */
    public fun forProvider(provider: String): Endpoint? = byProvider[provider]

    /** Supported provider ids, in UI order. */
    public fun supportedProviders(): List<String> = all.map { it.provider }

    /**
     * Default model id of [provider], or `null` for an unknown provider.
     *
     * The value is taken from [LlmProviderFactory.defaultModel] instead of
     * being hardcoded, but an unknown provider must not fall back to the
     * DeepSeek default: a silent fallback would show a plausible looking model
     * for a provider that does not exist.
     */
    public fun defaultModelId(provider: String): String? {
        if (forProvider(provider) == null) return null
        return LlmProviderFactory.defaultModel(provider).id
    }

    /**
     * Shared error message for an unsupported provider, listing every
     * supported provider id.
     */
    public fun unsupportedProviderMessage(provider: String): String =
        "Unsupported provider: $provider. Supported: ${supportedProviders().joinToString(", ")}"
}
