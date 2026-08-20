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
    fun `classifyException maps ScoopAiGenerationException to LlmError with exit 65`() {
        // Typed generation failure (e.g. model without function calling support):
        // must be classified as an LLM error, exit 65.
        val e = com.scoopai.core.ScoopAiGenerationException(
            "Manifest generation failed during the 'research' phase: the model 'nv/muse-glimmer-30bl' never issued a tool call."
        )
        val result = classifyException(e)
        assertTrue(result is LlmError)
        assertEquals(ExitCode.LLM_ERROR, result.exit)
        assertSame(e, result.cause)
    }

    @Test
    fun `classifyException keeps multi-line actionable hint of ScoopAiGenerationException`() {
        // The typed exception's message is crafted for end users and may span
        // multiple lines (cause analysis + fix hint). It must NOT be truncated
        // to the first line (unlike raw third-party exceptions).
        val hint = "line 1: model never called a tool" +
            "\nline 2: Fix: switch to a tool-capable model."
        val e = com.scoopai.core.ScoopAiGenerationException(hint)
        val result = classifyException(e)
        val msg = result.message ?: ""
        assertTrue(msg.contains("line 1"), "first line must be preserved: $msg")
        assertTrue(msg.contains("Fix: switch to a tool-capable model"), "actionable hint must be preserved: $msg")
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

    // ── stack trace leak prevention ───────────────────────────────

    @Test
    fun `classifyException output does not contain internal framework class paths`() {
        // When an LLMClientException is classified, the error message shown to users
        // must not leak internal framework paths like AbstractOpenAILLMClient,
        // ai.koog.prompt.executor, or stack trace lines containing "at"
        val e = LLMClientException("401 Unauthorized: invalid API key", "req-123", null)
        // Fill stack trace to simulate a real exception
        val filled = Exception("wrapper", e)
        val result = classifyException(filled.cause!!)
        val msg = result.message ?: ""
        assertTrue(msg.contains("401") || msg.contains("Unauthorized") || msg.contains("API key"),
            "Error message should preserve key info: $msg")
        assertFalse(msg.contains("ai.koog"),
            "Error message must not contain internal framework paths: $msg")
        assertFalse(msg.contains("AbstractOpenAI"),
            "Error message must not contain internal class names: $msg")
    }

    @Test
    fun `classifyException message is single-line without stack trace`() {
        // Simulate an exception with a multi-line message or stack trace embedded
        val e = RuntimeException("top-level error\n\tat ai.koog.prompt.executor.clients.AbstractOpenAILLMClient.doChat(AbstractOpenAILLMClient.kt:123)")
        val result = classifyException(e)
        val msg = result.message ?: ""
        // Message should not contain stack trace lines ("at " prefix)
        assertFalse(msg.contains("\tat "),
            "Error message must not contain stack trace lines: $msg")
        assertFalse(msg.contains("AbstractOpenAILLMClient"),
            "Error message must not contain internal class names: $msg")
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
