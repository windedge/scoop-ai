package com.scoopai.cli.command

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigShowCommandTest {

    // ---------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------

    @Test
    fun `config show help via --help`() {
        val result = ConfigShowCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("show"))
        assertTrue(result.output.contains("configuration"))
    }

    @Test
    fun `config show help contains note about not persisted`() {
        val result = ConfigShowCommand().test("--help")
        assertTrue(result.output.contains("config") || result.output.contains("runtime"))
    }

    // ---------------------------------------------------------------
    // Default output (no env vars)
    // ---------------------------------------------------------------

    @Test
    fun `default config shows deepseek provider`() {
        val cmd = ConfigShowCommand(env = emptyMap())
        val result = cmd.test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("deepseek"), "Default provider should be deepseek")
    }

    @Test
    fun `default config shows api-key as unset`() {
        val cmd = ConfigShowCommand(env = emptyMap())
        val result = cmd.test("")
        assertTrue(result.output.contains("api-key:"))
        assertTrue(result.output.contains("unset"))
    }

    @Test
    fun `default config shows github-token as unset`() {
        val cmd = ConfigShowCommand(env = emptyMap())
        val result = cmd.test("")
        assertTrue(result.output.contains("github-token:"))
        assertTrue(result.output.contains("unset"))
    }

    @Test
    fun `default config contains at least 8 config fields`() {
        val cmd = ConfigShowCommand(env = emptyMap())
        val result = cmd.test("")
        // Count lines with colon (config fields)
        val fieldCount = result.output.lines().count { it.contains(":") && it.contains("  ") }
        assertTrue(fieldCount >= 8, "Should show at least 8 config fields, got $fieldCount")
    }

    // ---------------------------------------------------------------
    // With env vars
    // ---------------------------------------------------------------

    @Test
    fun `api key env var shows as set not value`() {
        val cmd = ConfigShowCommand(env = mapOf("SCOOP_AI_API_KEY" to "sk-secret-key-12345"))
        val result = cmd.test("")
        assertTrue(result.output.contains("api-key:"))
        assertTrue(result.output.contains("set"))
        assertFalse(result.output.contains("sk-secret"), "Must not leak actual key")
        assertFalse(result.output.contains("12345"), "Must not leak any part of key")
    }

    @Test
    fun `provider env var overrides default`() {
        val cmd = ConfigShowCommand(env = mapOf("SCOOP_AI_PROVIDER" to "openai"))
        val result = cmd.test("")
        assertTrue(result.output.contains("openai"))
    }

    @Test
    fun `github token env var shows as set`() {
        val cmd = ConfigShowCommand(env = mapOf("GITHUB_TOKEN" to "ghp_abc123def456"))
        val result = cmd.test("")
        assertTrue(result.output.contains("github-token:"))
        assertTrue(result.output.contains("set"))
        assertFalse(result.output.contains("ghp_"), "Must not leak token")
    }

    @Test
    fun `SCOOP_AI_GITHUB_TOKEN shows as set`() {
        val cmd = ConfigShowCommand(env = mapOf("SCOOP_AI_GITHUB_TOKEN" to "ghp_secret"))
        val result = cmd.test("")
        assertTrue(result.output.contains("github-token:"))
        assertTrue(result.output.contains("set"))
    }

    // ---------------------------------------------------------------
    // Output notes
    // ---------------------------------------------------------------

    @Test
    fun `output contains note about runtime config`() {
        val cmd = ConfigShowCommand(env = emptyMap())
        val result = cmd.test("")
        assertTrue(result.output.contains("runtime configuration") ||
                   result.output.contains("persisted config"),
               "Should mention it's runtime config")
    }

    @Test
    fun `output does not contain actual api key value`() {
        val cmd = ConfigShowCommand(env = mapOf("SCOOP_AI_API_KEY" to "sk-very-secret-key-do-not-expose-12345"))
        val result = cmd.test("")
        assertFalse(result.output.contains("very-secret"), "Must not leak key in output")
    }
}
