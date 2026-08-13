package com.scoopai.tools.llm

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
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
    fun `defaultModel for openai-compatible with DeepSeek model uses OpenAI provider and thinking capability`() {
        val model = LlmProviderFactory.defaultModel("openai-compatible", "opencode/deepseek-v4-flash")
        assertEquals(LLMProvider.OpenAI, model.provider)
        assertEquals("opencode/deepseek-v4-flash", model.id)
        assertTrue(model.capabilities?.contains(LLMCapability.Thinking) == true,
            "DeepSeek models via openai-compatible should have Thinking capability")
    }

    @Test
    fun `defaultModel for openai-compatible with non-DeepSeek model uses OpenAI provider`() {
        val model = LlmProviderFactory.defaultModel("openai-compatible", "ollama/glm-4.7")
        assertEquals(LLMProvider.OpenAI, model.provider)
        assertEquals("ollama/glm-4.7", model.id)
        assertTrue(model.capabilities?.contains(LLMCapability.Tools) == true)
        // Non-DeepSeek should NOT have Thinking capability
        assertTrue(model.capabilities?.contains(LLMCapability.Thinking) != true,
            "Non-DeepSeek models should not have Thinking capability")
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
    fun `create for openai-compatible with DeepSeek model name succeeds`() {
        LlmProviderFactory.create(
            provider = "openai-compatible",
            apiKey = "sk-test",
            baseUrl = "http://localhost:8080",
            modelName = "opencode/deepseek-v4-flash",
        )
    }

    @Test
    fun `create for openai-compatible with non-DeepSeek model name succeeds`() {
        // Non-DeepSeek model (GLM, Qwen etc.) → OpenAILLMClient (no systemFingerprint required)
        LlmProviderFactory.create(
            provider = "openai-compatible",
            apiKey = "sk-test",
            baseUrl = "http://localhost:8080",
            modelName = "zai/glm-4.7",
        )
    }

    @Test
    fun `create for openai-compatible with Qwen model name succeeds`() {
        LlmProviderFactory.create(
            provider = "openai-compatible",
            apiKey = "sk-test",
            baseUrl = "http://localhost:8080",
            modelName = "qwen2.5:72b",
        )
    }
}
