package com.scoopai.cli

import com.scoopai.cli.error.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvArgsTest {

    // ---------------------------------------------------------------
    // Basic URL parsing
    // ---------------------------------------------------------------

    @Test
    fun `parse with URL only sets url and defaults`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/owner/repo"))
        assertEquals("https://github.com/owner/repo", envArgs.url)
        assertEquals("", envArgs.output)
        assertEquals("", envArgs.provider)
        assertEquals("", envArgs.apiKey)
        assertNull(envArgs.baseUrl)
        assertNull(envArgs.modelName)
        assertEquals(false, envArgs.verify)
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
    // Output flag
    // ---------------------------------------------------------------

    @Test
    fun `parse with -o flag sets output`() {
        val envArgs = EnvArgs.parse(arrayOf("-o", "out.json", "https://github.com/x/y"))
        assertEquals("out.json", envArgs.output)
        assertEquals("https://github.com/x/y", envArgs.url)
    }

    @Test
    fun `parse with --output flag sets output`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--output", "result.json"))
        assertEquals("result.json", envArgs.output)
    }

    // ---------------------------------------------------------------
    // Provider flag
    // ---------------------------------------------------------------

    @Test
    fun `parse with -P flag sets provider`() {
        val envArgs = EnvArgs.parse(arrayOf("-P", "anthropic", "https://github.com/x/y"))
        assertEquals("anthropic", envArgs.provider)
    }

    @Test
    fun `parse with --provider flag sets provider`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--provider", "ollama"))
        assertEquals("ollama", envArgs.provider)
    }

    // ---------------------------------------------------------------
    // API key and base URL
    // ---------------------------------------------------------------

    @Test
    fun `parse with --api-key flag sets apiKey`() {
        val envArgs = EnvArgs.parse(arrayOf("--api-key", "sk-test123", "https://github.com/x/y"))
        assertEquals("sk-test123", envArgs.apiKey)
    }

    @Test
    fun `parse with --base-url flag sets baseUrl`() {
        val envArgs = EnvArgs.parse(arrayOf("--base-url", "http://localhost:8080/v1", "https://github.com/x/y"))
        assertEquals("http://localhost:8080/v1", envArgs.baseUrl)
    }

    // ---------------------------------------------------------------
    // Model flag
    // ---------------------------------------------------------------

    @Test
    fun `parse with -m flag sets modelName`() {
        val envArgs = EnvArgs.parse(arrayOf("-m", "gpt-4", "https://github.com/x/y"))
        assertEquals("gpt-4", envArgs.modelName)
    }

    @Test
    fun `parse with --model flag sets modelName`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--model", "claude-3"))
        assertEquals("claude-3", envArgs.modelName)
    }

    // ---------------------------------------------------------------
    // Boolean flags
    // ---------------------------------------------------------------

    @Test
    fun `parse with -V flag sets verify to true`() {
        val envArgs = EnvArgs.parse(arrayOf("-V", "https://github.com/x/y"))
        assertTrue(envArgs.verify)
    }

    @Test
    fun `parse with --verify flag sets verify to true`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--verify"))
        assertTrue(envArgs.verify)
    }

    @Test
    fun `parse with --debug flag sets debug to true`() {
        val envArgs = EnvArgs.parse(arrayOf("--debug", "https://github.com/x/y"))
        assertTrue(envArgs.debug)
    }

    @Test
    fun `parse with --no-progress flag sets noProgress to true`() {
        val envArgs = EnvArgs.parse(arrayOf("--no-progress", "https://github.com/x/y"))
        assertTrue(envArgs.noProgress)
    }

    @Test
    fun `parse with -n flag sets nonInteractive to true`() {
        val envArgs = EnvArgs.parse(arrayOf("-n", "https://github.com/x/y"))
        assertTrue(envArgs.nonInteractive)
    }

    @Test
    fun `parse with --non-interactive flag sets nonInteractive to true`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--non-interactive"))
        assertTrue(envArgs.nonInteractive)
    }

    @Test
    fun `parse with --no-cache flag sets keepCache to false`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--no-cache"))
        assertEquals(false, envArgs.keepCache)
    }

    // ---------------------------------------------------------------
    // App name and prompt
    // ---------------------------------------------------------------

    @Test
    fun `parse with --app-name flag sets appName`() {
        val envArgs = EnvArgs.parse(arrayOf("--app-name", "my-app", "https://github.com/x/y"))
        assertEquals("my-app", envArgs.appName)
    }

    @Test
    fun `parse with -p flag sets userPrompt`() {
        val envArgs = EnvArgs.parse(arrayOf("-p", "use portable exe", "https://github.com/x/y"))
        assertEquals("use portable exe", envArgs.userPrompt)
    }

    @Test
    fun `parse with --prompt flag sets userPrompt`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--prompt", "minimal bin"))
        assertEquals("minimal bin", envArgs.userPrompt)
    }

    // ---------------------------------------------------------------
    // GitHub token
    // ---------------------------------------------------------------

    @Test
    fun `parse with --github-token flag sets githubToken`() {
        val envArgs = EnvArgs.parse(arrayOf("--github-token", "ghp_abc123", "https://github.com/x/y"))
        assertEquals("ghp_abc123", envArgs.githubToken)
    }

    // ---------------------------------------------------------------
    // Persist mode
    // ---------------------------------------------------------------

    @Test
    fun `parse with -s force sets persistMode to force`() {
        val envArgs = EnvArgs.parse(arrayOf("-s", "force", "https://github.com/x/y"))
        assertEquals("force", envArgs.persistMode)
    }

    @Test
    fun `parse with -s none sets persistMode to none`() {
        val envArgs = EnvArgs.parse(arrayOf("-s", "none", "https://github.com/x/y"))
        assertEquals("none", envArgs.persistMode)
    }

    @Test
    fun `parse with -s auto sets persistMode to auto`() {
        val envArgs = EnvArgs.parse(arrayOf("-s", "auto", "https://github.com/x/y"))
        assertEquals("auto", envArgs.persistMode)
    }

    @Test
    fun `parse with -s no normalizes to none`() {
        val envArgs = EnvArgs.parse(arrayOf("-s", "no", "https://github.com/x/y"))
        assertEquals("none", envArgs.persistMode)
    }

    @Test
    fun `parse with -s invalid throws UsageError`() {
        val ex = assertThrows(UsageError::class.java) {
            EnvArgs.parse(arrayOf("-s", "invalid", "https://github.com/x/y"))
        }
        assertTrue(ex.message?.contains("--persist") == true)
    }

    @Test
    fun `parse with --persist flag sets persistMode`() {
        val envArgs = EnvArgs.parse(arrayOf("https://github.com/x/y", "--persist", "force"))
        assertEquals("force", envArgs.persistMode)
    }

    // ---------------------------------------------------------------
    // Missing required argument
    // ---------------------------------------------------------------

    @Test
    fun `parse with no URL throws UsageError`() {
        val ex = assertThrows(UsageError::class.java) {
            EnvArgs.parse(arrayOf())
        }
        assertTrue(ex.message?.contains("Missing required argument") == true)
    }

    @Test
    fun `parse with only flags and no URL throws UsageError`() {
        val ex = assertThrows(UsageError::class.java) {
            EnvArgs.parse(arrayOf("--debug", "-V"))
        }
        assertTrue(ex.message?.contains("Missing required argument") == true)
    }

    // ---------------------------------------------------------------
    // Missing value after flag
    // ---------------------------------------------------------------

    @Test
    fun `parse with -o missing value throws`() {
        assertThrows(Exception::class.java) {
            EnvArgs.parse(arrayOf("-o", "https://github.com/x/y"))
        }
    }

    @Test
    fun `parse with -s missing value throws`() {
        assertThrows(Exception::class.java) {
            EnvArgs.parse(arrayOf("-s", "https://github.com/x/y"))
        }
    }

    // ---------------------------------------------------------------
    // Multiple flags combined
    // ---------------------------------------------------------------

    @Test
    fun `parse with multiple flags`() {
        val envArgs = EnvArgs.parse(arrayOf(
            "-o", "custom.json",
            "-P", "openai",
            "--debug",
            "-V",
            "--no-cache",
            "-s", "force",
            "https://github.com/owner/repo"
        ))
        assertEquals("custom.json", envArgs.output)
        assertEquals("openai", envArgs.provider)
        assertTrue(envArgs.debug)
        assertTrue(envArgs.verify)
        assertEquals(false, envArgs.keepCache)
        assertEquals("force", envArgs.persistMode)
        assertEquals("https://github.com/owner/repo", envArgs.url)
    }

    // ---------------------------------------------------------------
    // Data class equality
    // ---------------------------------------------------------------

    @Test
    fun `data class equality`() {
        val a = EnvArgs.parse(arrayOf("https://github.com/x/y"))
        val b = EnvArgs.parse(arrayOf("https://github.com/x/y"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class toString contains fields`() {
        val envArgs = EnvArgs.parse(arrayOf("--debug", "https://github.com/x/y"))
        val str = envArgs.toString()
        assertTrue(str.contains("url="))
        assertTrue(str.contains("debug="))
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    fun `parse with flag at the end with url as next value in iterator`() {
        // If URL looks like a value for the last flag, it's consumed by the flag
        // In this case --app-name consumes "url"
        assertThrows(Exception::class.java) {
            EnvArgs.parse(arrayOf("--app-name", "https://github.com/x/y"))
        }
    }

    @Test
    fun `parse empty args array throws`() {
        assertThrows(UsageError::class.java) {
            EnvArgs.parse(arrayOf<String>())
        }
    }
}
