package com.scoopai.cli.error

import ai.koog.agents.core.tools.ToolException
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliErrorTest {

    // ── exit code correctness ──────────────────────────────────────

    @Test
    fun `UsageError has USAGE exit code`() {
        assertEquals(ExitCode.USAGE, UsageError("test").exit)
    }

    @Test
    fun `ConfigError has CONFIG exit code`() {
        assertEquals(ExitCode.CONFIG, ConfigError("test").exit)
    }

    @Test
    fun `NoAssetsError has NO_ASSETS exit code`() {
        assertEquals(ExitCode.NO_ASSETS, NoAssetsError("test").exit)
    }

    @Test
    fun `LlmError has LLM_ERROR exit code`() {
        assertEquals(ExitCode.LLM_ERROR, LlmError("test").exit)
    }

    @Test
    fun `VerifyFailedError has VERIFY_FAILED exit code`() {
        assertEquals(ExitCode.VERIFY_FAILED, VerifyFailedError("test").exit)
    }

    @Test
    fun `IoError has IO_ERROR exit code`() {
        assertEquals(ExitCode.IO_ERROR, IoError("test").exit)
    }

    @Test
    fun `GeneralError has GENERAL exit code`() {
        assertEquals(ExitCode.GENERAL, GeneralError("test").exit)
    }

    // ── message round-trip ─────────────────────────────────────────

    @Test
    fun `message round-trips correctly`() {
        val msg = "something went wrong"
        assertEquals(msg, GeneralError(msg).message)
    }

    @Test
    fun `message with special characters round-trips`() {
        val msg = "error: key=sk-abc123 at https://example.com"
        assertEquals(msg, UsageError(msg).message)
    }

    // ── cause round-trip ───────────────────────────────────────────

    @Test
    fun `cause round-trips correctly`() {
        val cause = RuntimeException("root cause")
        val err = GeneralError("test", cause)
        assertSame(cause, err.cause)
    }

    @Test
    fun `cause defaults to null`() {
        assertNull(GeneralError("test").cause)
    }

    // =========================================================================
    // classifyException
    // =========================================================================

    @Test
    fun `classifyException maps LLMClientException to LlmError with exit 65`() {
        val e = LLMClientException("API key invalid", "req-123", null)
        val result = classifyException(e)
        assertTrue(result is LlmError)
        assertEquals(ExitCode.LLM_ERROR, result.exit)
        assertTrue(result.message?.contains("API key invalid") == true)
    }

    @Test
    fun `classifyException maps KoogHttpClientException to LlmError with exit 65`() {
        val e = KoogHttpClientException()
        val result = classifyException(e)
        assertTrue(result is LlmError)
        assertEquals(ExitCode.LLM_ERROR, result.exit)
    }

    @Test
    fun `classifyException maps IOException to IoError with exit 70`() {
        val e = java.io.IOException("File not found: bucket/app.json")
        val result = classifyException(e)
        assertTrue(result is IoError)
        assertEquals(ExitCode.IO_ERROR, result.exit)
        assertTrue(result.message?.contains("File not found") == true)
    }

    @Test
    fun `classifyException maps ToolException ValidationFailure to VerifyFailedError with exit 66`() {
        val e = ToolException.ValidationFailure("Manifest has no license field")
        val result = classifyException(e)
        assertTrue(result is VerifyFailedError)
        assertEquals(ExitCode.VERIFY_FAILED, result.exit)
        assertTrue(result.message?.contains("Manifest has no license") == true)
    }

    @Test
    fun `classifyException maps generic Exception to GeneralError with exit 1`() {
        val e = RuntimeException("unexpected error")
        val result = classifyException(e)
        assertTrue(result is GeneralError)
        assertEquals(ExitCode.GENERAL, result.exit)
        assertTrue(result.message?.contains("unexpected error") == true)
    }

    @Test
    fun `classifyException maps exception with null message to GeneralError with class name`() {
        val e = NullPointerException()
        val result = classifyException(e)
        assertTrue(result is GeneralError)
        assertEquals(ExitCode.GENERAL, result.exit)
        assertTrue(result.message?.contains("NullPointerException") == true)
    }

    @Test
    fun `classifyException preserves original exception as cause`() {
        val original = RuntimeException("original error")
        val result = classifyException(original)
        assertTrue(result is GeneralError)
        assertSame(original, result.cause)
    }

    // ── sealed exhaustive check ────────────────────────────────────

    @Test
    fun `sealed exhaustive when expression covers all 7 subclasses`() {
        val errors: List<CliError> = listOf(
            UsageError(""),
            ConfigError(""),
            NoAssetsError(""),
            LlmError(""),
            VerifyFailedError(""),
            IoError(""),
            GeneralError(""),
        )
        for (e in errors) {
            val exitCode = when (e) {
                is UsageError -> e.exit
                is ConfigError -> e.exit
                is NoAssetsError -> e.exit
                is LlmError -> e.exit
                is VerifyFailedError -> e.exit
                is IoError -> e.exit
                is GeneralError -> e.exit
            }
            assertEquals(e.exit, exitCode)
        }
    }
}
