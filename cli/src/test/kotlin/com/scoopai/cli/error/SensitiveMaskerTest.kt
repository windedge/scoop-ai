package com.scoopai.cli.error

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SensitiveMaskerTest {

    // ── sk- API key ────────────────────────────────────────────────

    @Test
    fun `masks standalone sk- API key while preserving prefix`() {
        val result = mask("sk-abc1234567890xyz")
        assertTrue(result.contains("sk-"), "should preserve sk- prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("abc1234567890xyz"), "should not leak original value")
    }

    // ── Authorization header ───────────────────────────────────────

    @Test
    fun `masks Authorization Bearer token`() {
        val result = mask("Authorization: Bearer sk-abc123")
        assertTrue(result.contains("Bearer "), "should preserve Bearer prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("sk-abc123"), "should not leak token")
    }

    // ── key= and token= patterns ───────────────────────────────────

    @Test
    fun `masks key equals value pattern`() {
        val result = mask("key=sk-abc123")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("sk-abc123"), "should not leak value")
    }

    @Test
    fun `masks token equals value pattern`() {
        val result = mask("token=ghp_abc123def456")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("ghp_abc123def456"), "should not leak value")
    }

    // ── sensitive env vars ─────────────────────────────────────────

    @Test
    fun `masks SCOOP_AI_API_KEY value`() {
        val result = mask("SCOOP_AI_API_KEY=sk-abc123")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("sk-abc123"), "should not leak API key")
    }

    @Test
    fun `masks DEEPSEEK_API_KEY value`() {
        val result = mask("DEEPSEEK_API_KEY=sk-secret123")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("sk-secret123"), "should not leak API key")
    }

    @Test
    fun `masks GITHUB_TOKEN value`() {
        val result = mask("GITHUB_TOKEN=ghp_abc123def456")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("ghp_abc123def456"), "should not leak token")
    }

    // ── non-sensitive env vars ─────────────────────────────────────

    @Test
    fun `does not mask SCOOP_AI_PROVIDER value`() {
        val result = mask("SCOOP_AI_PROVIDER=deepseek")
        assertEquals("SCOOP_AI_PROVIDER=deepseek", result)
    }

    @Test
    fun `does not mask SCOOP_AI_BASE_URL value`() {
        val result = mask("SCOOP_AI_BASE_URL=https://api.example.com")
        assertEquals("SCOOP_AI_BASE_URL=https://api.example.com", result)
    }

    @Test
    fun `does not mask SCOOP_AI_MODEL value`() {
        val result = mask("SCOOP_AI_MODEL=gpt-4")
        assertEquals("SCOOP_AI_MODEL=gpt-4", result)
    }

    @Test
    fun `does not mask SCOOP_AI_OUTPUT value`() {
        val result = mask("SCOOP_AI_OUTPUT=./output.json")
        assertEquals("SCOOP_AI_OUTPUT=./output.json", result)
    }

    @Test
    fun `does not mask SCOOP_AI_VERIFY value`() {
        val result = mask("SCOOP_AI_VERIFY=true")
        assertEquals("SCOOP_AI_VERIFY=true", result)
    }

    // ── edge cases ─────────────────────────────────────────────────

    @Test
    fun `empty string returns empty`() {
        assertEquals("", mask(""))
    }

    @Test
    fun `URL without sensitive info is unchanged`() {
        val url = "https://api.openai.com/v1"
        assertEquals(url, mask(url))
    }

    @Test
    fun `plain text without sensitive info is unchanged`() {
        val text = "Hello, this is a normal log message."
        assertEquals(text, mask(text))
    }

    @Test
    fun `masks multiple sensitive patterns in one string`() {
        val input = "key=sk-abc and SCOOP_AI_API_KEY=xyz789"
        val result = mask(input)
        assertTrue(result.contains("key=***REDACTED***"))
        assertTrue(result.contains("SCOOP_AI_API_KEY=***REDACTED***"))
        assertFalse(result.contains("sk-abc"))
        assertFalse(result.contains("xyz789"))
    }

    @Test
    fun `masks api-key header`() {
        val result = mask("api-key: sk-abc123")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("sk-abc123"), "should not leak value")
    }

    @Test
    fun `masks GH_TOKEN value`() {
        val result = mask("GH_TOKEN=ghp_abc123")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("ghp_abc123"), "should not leak token")
    }

    // ── GitHub PAT standalone tokens ────────────────────────────────

    @Test
    fun `masks standalone ghp_ token with more than 10 chars after prefix`() {
        val result = mask("my token is ghp_abcdef0123456789abcdef0123456789abcd")
        assertTrue(result.contains("ghp_"), "should preserve ghp_ prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("abcdef0123456789abcdef0123456789abcd"), "should not leak original value")
    }

    @Test
    fun `masks standalone github_pat_ token`() {
        val result = mask("token is github_pat_1234567890abcdef_abcdef0123456789abcdef0123456789")
        assertTrue(result.contains("github_pat_"), "should preserve github_pat_ prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("1234567890abcdef_abcdef0123456789abcdef0123456789"), "should not leak original value")
    }

    @Test
    fun `masks standalone gho_ token`() {
        val result = mask("gho_xxxxxxxxxxxxxxxxxxxx")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("xxxxxxxxxxxxxxxxxxxx"), "should not leak original value")
    }

    // ── Authorization header schemes ────────────────────────────────

    @Test
    fun `masks Authorization Token scheme`() {
        val result = mask("Authorization: Token ghp_xxxxxxxx")
        assertTrue(result.contains("Authorization: Token "), "should preserve Authorization: Token prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("ghp_xxxxxxxx"), "should not leak token")
    }

    @Test
    fun `masks Authorization Basic scheme`() {
        val result = mask("Authorization: Basic dXNlcjpwYXNz")
        assertTrue(result.contains("Authorization: Basic "), "should preserve Authorization: Basic prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("dXNlcjpwYXNz"), "should not leak base64 token")
    }

    @Test
    fun `masks Authorization Bearer token still works`() {
        // Regression: existing Bearer masking must still work with non-sk- tokens
        val result = mask("Authorization: Bearer ghp_xxxxxxxxxxxxxxxxxxxx")
        assertTrue(result.contains("Authorization: Bearer "), "should preserve Authorization: Bearer prefix")
        assertTrue(result.contains("***REDACTED***"), "should contain redaction marker")
        assertFalse(result.contains("ghp_xxxxxxxxxxxxxxxxxxxx"), "should not leak token")
    }

    // ── GitHub PAT short fragment (boundary: ≥10 chars) ────────────

    @Test
    fun `does not mask ghp_ fragments with less than 10 chars after prefix`() {
        val input = "ghp_abc"
        assertEquals(input, mask(input))
    }
}
