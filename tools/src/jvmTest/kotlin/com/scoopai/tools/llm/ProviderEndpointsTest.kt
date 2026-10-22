package com.scoopai.tools.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoint table is the contract between the engine and the host GUI:
 * both sides must agree on base URL, paths and auth headers, otherwise the
 * host probes an endpoint the engine cannot use.
 */
class ProviderEndpointsTest {

    // ---------------------------------------------------------------
    // table content
    // ---------------------------------------------------------------

    @Test
    fun `deepseek endpoint matches the Koog client settings`() {
        val endpoint = ProviderEndpoints.forProvider("deepseek")
        assertNotNull(endpoint)
        assertEquals("https://api.deepseek.com", endpoint.defaultBaseUrl)
        assertEquals("models", endpoint.modelsPath)
        assertEquals("chat/completions", endpoint.chatPath)
        assertEquals("Authorization", endpoint.authHeader)
        assertTrue(endpoint.bearerAuth)
        assertNull(endpoint.apiVersionHeader)
        assertNull(endpoint.apiVersion)
        assertTrue(endpoint.requiresApiKey)
        assertFalse(endpoint.requiresBaseUrl)
        assertEquals("DEEPSEEK_API_KEY", endpoint.envKey)
        assertTrue(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    @Test
    fun `openai endpoint matches the Koog client settings`() {
        val endpoint = ProviderEndpoints.forProvider("openai")
        assertNotNull(endpoint)
        assertEquals("https://api.openai.com", endpoint.defaultBaseUrl)
        assertEquals("v1/models", endpoint.modelsPath)
        assertEquals("v1/chat/completions", endpoint.chatPath)
        assertEquals("Authorization", endpoint.authHeader)
        assertTrue(endpoint.bearerAuth)
        assertNull(endpoint.apiVersionHeader)
        assertNull(endpoint.apiVersion)
        assertTrue(endpoint.requiresApiKey)
        assertFalse(endpoint.requiresBaseUrl)
        assertEquals("OPENAI_API_KEY", endpoint.envKey)
        assertTrue(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    @Test
    fun `anthropic endpoint uses the x-api-key protocol headers`() {
        val endpoint = ProviderEndpoints.forProvider("anthropic")
        assertNotNull(endpoint)
        assertEquals("https://api.anthropic.com", endpoint.defaultBaseUrl)
        assertEquals("v1/models", endpoint.modelsPath)
        assertEquals("v1/messages", endpoint.chatPath)
        assertEquals("x-api-key", endpoint.authHeader)
        assertFalse(endpoint.bearerAuth)
        assertEquals("anthropic-version", endpoint.apiVersionHeader)
        assertEquals("2023-06-01", endpoint.apiVersion)
        assertTrue(endpoint.requiresApiKey)
        assertFalse(endpoint.requiresBaseUrl)
        assertEquals("ANTHROPIC_API_KEY", endpoint.envKey)
        assertTrue(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    @Test
    fun `ollama endpoint needs no authentication`() {
        val endpoint = ProviderEndpoints.forProvider("ollama")
        assertNotNull(endpoint)
        assertEquals("http://localhost:11434", endpoint.defaultBaseUrl)
        assertEquals("api/tags", endpoint.modelsPath)
        assertEquals("api/chat", endpoint.chatPath)
        assertNull(endpoint.authHeader)
        assertFalse(endpoint.bearerAuth)
        assertNull(endpoint.apiVersionHeader)
        assertNull(endpoint.apiVersion)
        assertFalse(endpoint.requiresApiKey)
        assertFalse(endpoint.requiresBaseUrl)
        assertNull(endpoint.envKey)
        assertFalse(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    @Test
    fun `openrouter endpoint keeps the api v1 prefix in its paths`() {
        val endpoint = ProviderEndpoints.forProvider("openrouter")
        assertNotNull(endpoint)
        assertEquals("https://openrouter.ai", endpoint.defaultBaseUrl)
        assertEquals("api/v1/models", endpoint.modelsPath)
        assertEquals("api/v1/chat/completions", endpoint.chatPath)
        assertEquals("Authorization", endpoint.authHeader)
        assertTrue(endpoint.bearerAuth)
        assertNull(endpoint.apiVersionHeader)
        assertNull(endpoint.apiVersion)
        assertTrue(endpoint.requiresApiKey)
        assertFalse(endpoint.requiresBaseUrl)
        assertEquals("OPENROUTER_API_KEY", endpoint.envKey)
        assertFalse(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    @Test
    fun `openai-compatible endpoint has no default base url`() {
        val endpoint = ProviderEndpoints.forProvider("openai-compatible")
        assertNotNull(endpoint)
        assertNull(endpoint.defaultBaseUrl)
        assertEquals("v1/models", endpoint.modelsPath)
        assertEquals("v1/chat/completions", endpoint.chatPath)
        assertEquals("Authorization", endpoint.authHeader)
        assertTrue(endpoint.bearerAuth)
        assertNull(endpoint.apiVersionHeader)
        assertNull(endpoint.apiVersion)
        assertTrue(endpoint.requiresApiKey)
        assertTrue(endpoint.requiresBaseUrl)
        assertEquals("OPENAI_COMPATIBLE_API_KEY", endpoint.envKey)
        assertTrue(endpoint.stripV1Suffix)
        assertTrue(endpoint.allowsCustomBaseUrl)
    }

    // ---------------------------------------------------------------
    // lookup and ordering
    // ---------------------------------------------------------------

    @Test
    fun `supported providers keep the ui order`() {
        assertEquals(
            listOf("deepseek", "openai", "anthropic", "ollama", "openrouter", "openai-compatible"),
            ProviderEndpoints.supportedProviders(),
        )
    }

    @Test
    fun `table contains exactly one entry per supported provider`() {
        assertEquals(6, ProviderEndpoints.all.size)
        assertEquals(
            ProviderEndpoints.supportedProviders(),
            ProviderEndpoints.all.map { it.provider },
        )
    }

    @Test
    fun `forProvider returns null for an unknown provider`() {
        assertNull(ProviderEndpoints.forProvider("no-such-provider"))
        assertNull(ProviderEndpoints.forProvider(""))
    }

    // ---------------------------------------------------------------
    // default model
    // ---------------------------------------------------------------

    @Test
    fun `defaultModelId returns the engine default model of every known provider`() {
        for (provider in ProviderEndpoints.supportedProviders()) {
            val modelId = ProviderEndpoints.defaultModelId(provider)
            assertNotNull(modelId, "provider $provider must expose a default model id")
            assertEquals(LlmProviderFactory.defaultModel(provider).id, modelId)
        }
    }

    @Test
    fun `defaultModelId returns null for an unknown provider instead of the deepseek fallback`() {
        assertNull(ProviderEndpoints.defaultModelId("no-such-provider"))
    }

    // ---------------------------------------------------------------
    // shared error message
    // ---------------------------------------------------------------

    @Test
    fun `unsupported provider message lists every supported provider`() {
        val message = ProviderEndpoints.unsupportedProviderMessage("bogus")
        assertEquals(
            "Unsupported provider: bogus. Supported: deepseek, openai, anthropic, ollama, openrouter, openai-compatible",
            message,
        )
    }
}
