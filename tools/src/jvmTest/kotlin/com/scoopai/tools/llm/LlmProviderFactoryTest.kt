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
}
