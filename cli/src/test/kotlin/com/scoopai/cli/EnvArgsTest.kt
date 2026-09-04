package com.scoopai.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvArgsTest {

    // ---------------------------------------------------------------
    // Data class construction with defaults
    // ---------------------------------------------------------------

    @Test
    fun `construct with URL only sets url and defaults`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        assertEquals("https://github.com/owner/repo", envArgs.url)
        assertEquals("", envArgs.output)
        assertEquals("", envArgs.provider)
        assertEquals("", envArgs.apiKey)
        assertNull(envArgs.baseUrl)
        assertNull(envArgs.modelName)
        assertNull(envArgs.appName)
        assertEquals(true, envArgs.keepCache)
        assertNull(envArgs.userPrompt)
        assertEquals(false, envArgs.nonInteractive)
        assertEquals("auto", envArgs.persistMode)
        assertNull(envArgs.githubToken)
        assertEquals(false, envArgs.debug)
        assertEquals(false, envArgs.noProgress)
    }

    // ---------------------------------------------------------------
    // Individual field construction
    // ---------------------------------------------------------------

    @Test
    fun `construct with output field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", output = "out.json")
        assertEquals("out.json", envArgs.output)
    }

    @Test
    fun `construct with provider field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", provider = "anthropic")
        assertEquals("anthropic", envArgs.provider)
    }

    @Test
    fun `construct with apiKey field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", apiKey = "sk-test123")
        assertEquals("sk-test123", envArgs.apiKey)
    }

    @Test
    fun `construct with baseUrl field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", baseUrl = "http://localhost:8080/v1")
        assertEquals("http://localhost:8080/v1", envArgs.baseUrl)
    }

    @Test
    fun `construct with modelName field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", modelName = "gpt-4")
        assertEquals("gpt-4", envArgs.modelName)
    }

    @Test
    fun `envArgs exposes no verify switch`() {
        val fields = EnvArgs::class.java.declaredFields.map { it.name }
        assertFalse(
            fields.any { it.equals("verify", ignoreCase = true) },
            "EnvArgs must not carry a verify flag: install verification is always on",
        )
    }

    @Test
    fun `construct with debug field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", debug = true)
        assertTrue(envArgs.debug)
    }

    @Test
    fun `construct with noProgress field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", noProgress = true)
        assertTrue(envArgs.noProgress)
    }

    @Test
    fun `construct with nonInteractive field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", nonInteractive = true)
        assertTrue(envArgs.nonInteractive)
    }

    @Test
    fun `construct with keepCache field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", keepCache = false)
        assertEquals(false, envArgs.keepCache)
    }

    @Test
    fun `construct with appName field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", appName = "my-app")
        assertEquals("my-app", envArgs.appName)
    }

    @Test
    fun `construct with userPrompt field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", userPrompt = "use portable exe")
        assertEquals("use portable exe", envArgs.userPrompt)
    }

    @Test
    fun `construct with githubToken field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", githubToken = "ghp_abc123")
        assertEquals("ghp_abc123", envArgs.githubToken)
    }

    @Test
    fun `construct with persistMode field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", persistMode = "force")
        assertEquals("force", envArgs.persistMode)
    }

    // ---------------------------------------------------------------
    // Multiple fields combined
    // ---------------------------------------------------------------

    @Test
    fun `construct with multiple fields`() {
        val envArgs = EnvArgs(
            url = "https://github.com/owner/repo",
            output = "custom.json",
            provider = "openai",
            debug = true,
            keepCache = false,
            persistMode = "force",
        )
        assertEquals("custom.json", envArgs.output)
        assertEquals("openai", envArgs.provider)
        assertTrue(envArgs.debug)
        assertEquals(false, envArgs.keepCache)
        assertEquals("force", envArgs.persistMode)
        assertEquals("https://github.com/owner/repo", envArgs.url)
    }

    // ---------------------------------------------------------------
    // Data class semantics
    // ---------------------------------------------------------------

    @Test
    fun `data class equality`() {
        val a = EnvArgs(url = "https://github.com/x/y")
        val b = EnvArgs(url = "https://github.com/x/y")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class toString contains fields`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", debug = true)
        val str = envArgs.toString()
        assertTrue(str.contains("url="))
        assertTrue(str.contains("debug="))
    }

    @Test
    fun `data class copy preserves defaults`() {
        val a = EnvArgs(url = "https://github.com/x/y", debug = true)
        val b = a.copy(url = "https://github.com/z/w")
        assertEquals("https://github.com/z/w", b.url)
        assertTrue(b.debug) // preserved from original
        assertEquals("auto", b.persistMode) // default from original
    }

    @Test
    fun `construct with outputFormat field`() {
        val envArgs = EnvArgs(url = "https://github.com/x/y", outputFormat = "json")
        assertEquals("json", envArgs.outputFormat)
    }
}
