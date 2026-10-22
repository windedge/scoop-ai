package com.scoopai.tools.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import com.scoopai.core.llm.InterceptingHttpClient
import com.scoopai.tools.config.ApiKeyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmProviderFactoryTest {

    // ---------------------------------------------------------------
    // normalizeBaseUrl tests
    // ---------------------------------------------------------------

    @Test
    fun `normalizeBaseUrl trims whitespace`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("  http://192.168.40.130:8317  "),
        )
    }

    @Test
    fun `normalizeBaseUrl strips trailing slash`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("http://192.168.40.130:8317/"),
        )
    }

    @Test
    fun `normalizeBaseUrl strips trailing v1`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("http://192.168.40.130:8317/v1"),
        )
    }

    @Test
    fun `normalizeBaseUrl strips trailing v1 with slash`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("http://192.168.40.130:8317/v1/"),
        )
    }

    @Test
    fun `normalizeBaseUrl strips trailing v1 chat completions`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("http://192.168.40.130:8317/v1/chat/completions"),
        )
    }

    @Test
    fun `normalizeBaseUrl strips trailing v1 chat completions with slash`() {
        assertEquals(
            "http://192.168.40.130:8317",
            normalizeBaseUrl("http://192.168.40.130:8317/v1/chat/completions/"),
        )
    }

    @Test
    fun `normalizeBaseUrl leaves bare host unchanged`() {
        assertEquals(
            "https://api.example.com",
            normalizeBaseUrl("https://api.example.com"),
        )
    }

    @Test
    fun `normalizeBaseUrl is case insensitive for v1 path`() {
        assertEquals(
            "http://localhost:8080",
            normalizeBaseUrl("http://localhost:8080/V1/Chat/Completions"),
        )
    }

    // ---------------------------------------------------------------
    // defaultModel tests
    // ---------------------------------------------------------------

    @Test
    fun `defaultModel for deepseek provider returns DeepSeekV4Flash`() {
        val model = LlmProviderFactory.defaultModel("deepseek")
        assertEquals(LLMProvider.DeepSeek, model.provider)
        assertTrue(model.id.contains("deepseek", ignoreCase = true))
    }

    @Test
    fun `openai-compatible with DeepSeek model uses uniform OpenAI capabilities with ToolChoice`() {
        // B 批次：openai-compatible 不再对 DeepSeek 模型名特判。DeepSeek 模型
        // 走与其它模型相同的统一能力集（含 ToolChoice、不含 Thinking）。
        val model = LlmProviderFactory.defaultModel("openai-compatible", "opencode/deepseek-v4-flash")
        assertEquals(LLMProvider.OpenAI, model.provider)
        assertEquals("opencode/deepseek-v4-flash", model.id)
        assertTrue(model.capabilities?.contains(LLMCapability.ToolChoice) == true,
            "DeepSeek models via openai-compatible must keep ToolChoice after capability unification")
        assertTrue(model.capabilities?.contains(LLMCapability.Thinking) != true,
            "DeepSeek models must not get Thinking from the DeepSeek name special-case anymore")
        assertTrue(model.capabilities?.contains(LLMCapability.Tools) == true)
    }

    @Test
    fun `openai-compatible with non-DeepSeek model uses OpenAI provider, keeps ToolChoice, no Thinking`() {
        val model = LlmProviderFactory.defaultModel("openai-compatible", "ollama/glm-4.7")
        assertEquals(LLMProvider.OpenAI, model.provider)
        assertEquals("ollama/glm-4.7", model.id)
        assertTrue(model.capabilities?.contains(LLMCapability.Tools) == true)
        assertTrue(model.capabilities?.contains(LLMCapability.ToolChoice) == true,
            "Non-DeepSeek models should keep ToolChoice capability")
        assertTrue(model.capabilities?.contains(LLMCapability.Thinking) != true,
            "Non-DeepSeek models should not have Thinking capability")
    }

    @Test
    fun `openai-compatible capabilities are identical for every model name`() {
        val deepSeek = LlmProviderFactory.defaultModel("openai-compatible", "opencode/deepseek-v4-flash")
        val glm = LlmProviderFactory.defaultModel("openai-compatible", "zai/glm-4.7")
        val default = LlmProviderFactory.defaultModel("openai-compatible")
        assertEquals(glm.capabilities, deepSeek.capabilities,
            "capabilities must not depend on the model name")
        assertEquals(default.capabilities, deepSeek.capabilities,
            "capabilities must be the same with and without a model name")
    }

    @Test
    fun `defaultModel for openai provider returns GPT4o`() {
        val model = LlmProviderFactory.defaultModel("openai")
        assertEquals(LLMProvider.OpenAI, model.provider)
    }

    // ---------------------------------------------------------------
    // create tests
    // ---------------------------------------------------------------

    @Test
    fun `create for unsupported provider throws error`() {
        val e = assertFailsWith<Exception> {
            LlmProviderFactory.create("unsupported-provider", "key")
        }
        assertTrue(e.message?.contains("Unsupported provider") == true)
    }

    @Test
    fun `create for openai-compatible without baseUrl throws error`() {
        val e = assertFailsWith<Exception> {
            LlmProviderFactory.create("openai-compatible", "key", baseUrl = null)
        }
        assertTrue(e.message?.contains("--base-url") == true)
    }

    @Test
    fun `factory retry config tolerates about one minute of gateway outage`() {
        val config = HTTP_RETRY_CONFIG
        assertEquals(5, config.maxRetries)
        assertEquals(1000L, config.initialDelayMs)
        assertEquals(30_000L, config.maxDelayMs)
        assertEquals(2.0, config.backoffFactor)
    }

    @Test
    fun `factory wires the retry config into InterceptingHttpClient`() {
        val noop = object : KoogHttpClient {
            override val clientName = "test"
            override fun close() {}
            override suspend fun <R : Any> get(path: String, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, responseType: kotlin.reflect.KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = throw UnsupportedOperationException()
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<O> = throw UnsupportedOperationException()
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        }

        val client = LlmProviderFactory.interceptingHttpClient(delegate = noop)
        assertTrue(client is InterceptingHttpClient)
        assertEquals(HTTP_RETRY_CONFIG, client.retryConfig)
    }

    @Test
    fun `create for openai-compatible with various model names succeeds`() {
        // DeepSeek reasoner, non-DeepSeek GLM, and Qwen all route to OpenAILLMClient
        // (no systemFingerprint required); the interceptor chain strips tool_choice
        // unconditionally for every model name (no DeepSeek special-case).
        for (modelName in listOf("opencode/deepseek-v4-flash", "zai/glm-4.7", "qwen2.5:72b")) {
            LlmProviderFactory.create(
                provider = "openai-compatible",
                apiKey = "sk-test",
                baseUrl = "http://localhost:8080",
                modelName = modelName,
            )
        }
    }

    // ---------------------------------------------------------------
    // customBaseUrl tests
    // ---------------------------------------------------------------

    @Test
    fun `deepseek custom base url drops the trailing v1 suffix`() {
        assertEquals(
            "https://gateway.example.com",
            resolveBaseUrl("deepseek", "https://gateway.example.com/v1"),
        )
    }

    @Test
    fun `openai custom base url drops the trailing v1 suffix`() {
        assertEquals(
            "https://gateway.example.com",
            resolveBaseUrl("openai", " https://gateway.example.com/v1/ "),
        )
    }

    @Test
    fun `anthropic custom base url drops the trailing v1 suffix`() {
        assertEquals(
            "https://gateway.example.com",
            resolveBaseUrl("anthropic", "https://gateway.example.com/v1"),
        )
    }

    @Test
    fun `openrouter custom base url keeps the v1 suffix`() {
        // Stripping would turn "https://openrouter.ai/api/v1" into
        // "https://openrouter.ai/api", which breaks the official path layout.
        assertEquals(
            "https://openrouter.ai/api/v1",
            resolveBaseUrl("openrouter", "https://openrouter.ai/api/v1"),
        )
        assertEquals(
            "https://gateway.example.com/api/v1",
            resolveBaseUrl("openrouter", "https://gateway.example.com/api/v1/"),
        )
    }

    @Test
    fun `ollama custom base url only drops the trailing slash`() {
        assertEquals(
            "http://127.0.0.1:11434",
            resolveBaseUrl("ollama", "http://127.0.0.1:11434/"),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1",
            resolveBaseUrl("ollama", "http://127.0.0.1:11434/v1"),
        )
    }

    @Test
    fun `provider without custom base url falls back to the endpoint default`() {
        assertEquals("https://api.deepseek.com", resolveBaseUrl("deepseek", null))
        assertEquals("https://api.openai.com", resolveBaseUrl("openai", null))
        assertEquals("https://api.anthropic.com", resolveBaseUrl("anthropic", null))
        assertEquals("http://localhost:11434", resolveBaseUrl("ollama", null))
        assertEquals("https://openrouter.ai", resolveBaseUrl("openrouter", null))
    }

    @Test
    fun `openai-compatible keeps its mandatory base url check`() {
        val e = assertFailsWith<Exception> {
            LlmProviderFactory.create("openai-compatible", "key", baseUrl = null)
        }
        assertTrue(e.message?.contains("--base-url") == true)
    }

    // ---------------------------------------------------------------
    // shared error message
    // ---------------------------------------------------------------

    @Test
    fun `factory and key resolver report an unsupported provider with the same message`() {
        val factoryError = assertFailsWith<Exception> {
            LlmProviderFactory.create("bogus-provider", "key")
        }
        val resolverError = assertFailsWith<Exception> {
            ApiKeyResolver.resolve("bogus-provider")
        }
        assertEquals(factoryError.message, resolverError.message)
        assertEquals(
            "Unsupported provider: bogus-provider. " +
                "Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible",
            factoryError.message,
        )
    }
}
