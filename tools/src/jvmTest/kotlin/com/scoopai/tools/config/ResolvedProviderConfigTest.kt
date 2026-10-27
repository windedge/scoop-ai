package com.scoopai.tools.config

import ai.koog.prompt.llm.LLMProvider
import com.scoopai.tools.llm.ProviderEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvedProviderConfigTest {

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    private val emptyEnv = envOf()

    // ---------------------------------------------------------------
    // Provider resolution: flag > env > default
    // ---------------------------------------------------------------

    @Test
    fun `flag provider wins over env and default`() {
        val config = resolveProviderConfig(
            flagProvider = "openai",
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_PROVIDER" to "anthropic"),
        )
        assertEquals("openai", config.provider)
    }

    @Test
    fun `blank flag provider falls back to SCOOP_AI_PROVIDER env`() {
        val config = resolveProviderConfig(
            flagProvider = "",
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_PROVIDER" to "anthropic"),
        )
        assertEquals("anthropic", config.provider)
    }

    @Test
    fun `missing provider falls back to the default provider constant`() {
        val config = resolveProviderConfig(flagApiKey = "sk-test", env = emptyEnv)
        assertEquals(DEFAULT_PROVIDER, config.provider)
    }

    @Test
    fun `explicit defaultProvider argument overrides the built-in default`() {
        val config = resolveProviderConfig(env = emptyEnv, defaultProvider = "ollama")
        assertEquals("ollama", config.provider)
    }

    @Test
    fun `blank SCOOP_AI_PROVIDER env is treated as unset`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_PROVIDER" to "  "),
        )
        assertEquals(DEFAULT_PROVIDER, config.provider)
    }

    @Test
    fun `DEFAULT_PROVIDER is deepseek`() {
        assertEquals("deepseek", DEFAULT_PROVIDER)
    }

    // ---------------------------------------------------------------
    // Api key resolution: flag > SCOOP_AI_API_KEY > provider env key
    // ---------------------------------------------------------------

    @Test
    fun `flag api key wins over every env fallback`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-flag",
            env = envOf(
                "SCOOP_AI_API_KEY" to "sk-scoop",
                "DEEPSEEK_API_KEY" to "sk-provider",
            ),
        )
        assertEquals("sk-flag", config.apiKey)
    }

    @Test
    fun `SCOOP_AI_API_KEY is used when flag key is blank`() {
        val config = resolveProviderConfig(
            flagApiKey = "",
            env = envOf("SCOOP_AI_API_KEY" to "sk-scoop"),
        )
        assertEquals("sk-scoop", config.apiKey)
    }

    @Test
    fun `SCOOP_AI_API_KEY wins over the provider specific env key`() {
        val config = resolveProviderConfig(
            env = envOf(
                "SCOOP_AI_API_KEY" to "sk-scoop",
                "DEEPSEEK_API_KEY" to "sk-provider",
            ),
        )
        assertEquals("sk-scoop", config.apiKey)
    }

    @Test
    fun `provider specific env key is used when SCOOP_AI_API_KEY is unset`() {
        val config = resolveProviderConfig(
            env = envOf("DEEPSEEK_API_KEY" to "sk-provider"),
        )
        assertEquals("sk-provider", config.apiKey)
    }

    @Test
    fun `provider specific env key follows the selected provider`() {
        val config = resolveProviderConfig(
            flagProvider = "anthropic",
            env = envOf("ANTHROPIC_API_KEY" to "sk-anthropic"),
        )
        assertEquals("sk-anthropic", config.apiKey)
    }

    @Test
    fun `ollama resolves to an empty api key without env vars`() {
        val config = resolveProviderConfig(flagProvider = "ollama", env = emptyEnv)
        assertEquals("", config.apiKey)
    }

    @Test
    fun `missing required api key fails with the set env var message`() {
        val e = assertFailsWith<IllegalStateException> {
            resolveProviderConfig(flagProvider = "deepseek", env = emptyEnv)
        }
        assertEquals(
            "Please set DEEPSEEK_API_KEY environment variable or pass --api-key",
            e.message,
        )
    }

    @Test
    fun `blank api key env values are treated as unset`() {
        val e = assertFailsWith<IllegalStateException> {
            resolveProviderConfig(
                flagProvider = "deepseek",
                env = envOf("SCOOP_AI_API_KEY" to " ", "DEEPSEEK_API_KEY" to ""),
            )
        }
        assertEquals(
            "Please set DEEPSEEK_API_KEY environment variable or pass --api-key",
            e.message,
        )
    }

    @Test
    fun `unsupported provider fails with the shared endpoint message`() {
        val e = assertFailsWith<IllegalStateException> {
            resolveProviderConfig(flagProvider = "bogus-provider", env = emptyEnv)
        }
        assertEquals(ProviderEndpoints.unsupportedProviderMessage("bogus-provider"), e.message)
    }

    // ---------------------------------------------------------------
    // Base URL and model: flag > env
    // ---------------------------------------------------------------

    @Test
    fun `flag base url wins over env`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            flagBaseUrl = "http://flag:8080",
            env = envOf("SCOOP_AI_BASE_URL" to "http://env:8080"),
        )
        assertEquals("http://flag:8080", config.baseUrl)
    }

    @Test
    fun `base url falls back to SCOOP_AI_BASE_URL env`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_BASE_URL" to "http://env:8080"),
        )
        assertEquals("http://env:8080", config.baseUrl)
    }

    @Test
    fun `base url stays null when flag and env are missing`() {
        val config = resolveProviderConfig(flagApiKey = "sk-test", env = emptyEnv)
        assertNull(config.baseUrl)
    }

    @Test
    fun `flag model wins over env`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            flagModel = "flag-model",
            env = envOf("SCOOP_AI_MODEL" to "env-model"),
        )
        assertEquals("flag-model", config.model.id)
    }

    @Test
    fun `model falls back to SCOOP_AI_MODEL env`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_MODEL" to "env-model"),
        )
        assertEquals("env-model", config.model.id)
    }

    @Test
    fun `default model for deepseek keeps the DeepSeek provider identity`() {
        val config = resolveProviderConfig(
            flagProvider = "deepseek",
            flagApiKey = "sk-test",
            env = emptyEnv,
        )
        assertEquals(LLMProvider.DeepSeek, config.model.provider)
        assertTrue(config.model.id.contains("deepseek", ignoreCase = true))
    }

    // ---------------------------------------------------------------
    // GitHub token chain: flag > SCOOP_AI_GITHUB_TOKEN > GITHUB_TOKEN
    // ---------------------------------------------------------------

    @Test
    fun `github token prefers the flag over both env vars`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            flagGithubToken = "ghp_flag",
            env = envOf(
                "SCOOP_AI_GITHUB_TOKEN" to "ghp_scoop",
                "GITHUB_TOKEN" to "ghp_plain",
            ),
        )
        assertEquals("ghp_flag", config.githubToken)
    }

    @Test
    fun `github token falls back to SCOOP_AI_GITHUB_TOKEN`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf(
                "SCOOP_AI_GITHUB_TOKEN" to "ghp_scoop",
                "GITHUB_TOKEN" to "ghp_plain",
            ),
        )
        assertEquals("ghp_scoop", config.githubToken)
    }

    @Test
    fun `github token falls back to GITHUB_TOKEN`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf("GITHUB_TOKEN" to "ghp_plain"),
        )
        assertEquals("ghp_plain", config.githubToken)
    }

    @Test
    fun `github token is null when flag and env are missing`() {
        val config = resolveProviderConfig(flagApiKey = "sk-test", env = emptyEnv)
        assertNull(config.githubToken)
    }

    @Test
    fun `blank github token env values are treated as unset`() {
        val config = resolveProviderConfig(
            flagApiKey = "sk-test",
            env = envOf(
                "SCOOP_AI_GITHUB_TOKEN" to " ",
                "GITHUB_TOKEN" to "",
            ),
        )
        assertNull(config.githubToken)
    }

    @Test
    fun `resolveGithubToken exposes the same chain as resolveProviderConfig`() {
        val env = envOf("GITHUB_TOKEN" to "ghp_plain")
        assertEquals("ghp_plain", resolveGithubToken(env = env))
        assertEquals("ghp_flag", resolveGithubToken(flagGithubToken = "ghp_flag", env = env))
        assertNull(resolveGithubToken(env = emptyEnv))
    }

    // ---------------------------------------------------------------
    // Shared single-point messages
    // ---------------------------------------------------------------

    @Test
    fun `base url required message mentions flag and env var`() {
        assertTrue(OPENAI_COMPATIBLE_BASE_URL_REQUIRED.contains("--base-url"))
        assertTrue(OPENAI_COMPATIBLE_BASE_URL_REQUIRED.contains("SCOOP_AI_BASE_URL"))
        assertTrue(OPENAI_COMPATIBLE_BASE_URL_REQUIRED.contains("openai-compatible"))
    }

    @Test
    fun `openai-compatible provider constant matches the endpoint table id`() {
        val endpoint = ProviderEndpoints.forProvider(OPENAI_COMPATIBLE_PROVIDER)
        assertNotNull(endpoint)
        assertTrue(endpoint.requiresBaseUrl)
    }

    @Test
    fun `openai-compatible without base url fails at resolution with the shared message`() {
        val e = assertFailsWith<IllegalStateException> {
            resolveProviderConfig(
                flagProvider = "openai-compatible",
                flagApiKey = "sk-test",
                env = emptyEnv,
            )
        }
        assertEquals(OPENAI_COMPATIBLE_BASE_URL_REQUIRED, e.message)
    }

    @Test
    fun `openai-compatible with env base url resolves successfully`() {
        val config = resolveProviderConfig(
            flagProvider = "openai-compatible",
            flagApiKey = "sk-test",
            env = envOf("SCOOP_AI_BASE_URL" to "http://env:8080"),
        )
        assertEquals("openai-compatible", config.provider)
        assertEquals("http://env:8080", config.baseUrl)
    }

    // ---------------------------------------------------------------
    // createExecutor
    // ---------------------------------------------------------------

    @Test
    fun `createExecutor builds an executor from the resolved fields without network`() {
        val config = resolveProviderConfig(
            flagProvider = "deepseek",
            flagApiKey = "sk-test",
            env = emptyEnv,
        )
        assertNotNull(config.createExecutor())
    }
}
