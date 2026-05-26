package com.scoopai.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoopManifestGeneratorRetryTest {

    @Test
    fun `detects no Call was generated as retryable`() {
        val e = IllegalStateException(
            "Unhandled response from LLM. Subgraph with task must always call tools, but no Call was generated"
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `detects pure reasoning crash as retryable`() {
        val e = IllegalStateException(
            "Subgraph with task must always call tools, but no Call was generated, got instead: Assistant(parts=[Reasoning(content=[..."
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `ignores unrelated errors`() {
        val e = IllegalStateException("Something else went wrong")
        assertFalse(isRetryableError(e))
    }

    @Test
    fun `ignores null message errors`() {
        val e = object : Exception() {}
        assertFalse(isRetryableError(e))
    }

    @Test
    fun `ignores network errors`() {
        val e = RuntimeException("Connection timeout")
        assertFalse(isRetryableError(e))
    }

    // ---------------------------------------------------------------
    // JSON decoding errors from Koog parsing LLM tool call arguments
    // ---------------------------------------------------------------

    @Test
    fun `detects JsonDecodingException as retryable`() {
        val e = RuntimeException(
            "JsonDecodingException: Unexpected JSON token at offset 397: Invalid escaped char 'q'"
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `detects Unexpected JSON token as retryable`() {
        val e = RuntimeException(
            "kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 397"
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `detects Invalid escaped char as retryable`() {
        val e = RuntimeException(
            "Invalid escaped char 'q' at path: $"
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `detects JsonDecoding without Invalid escaped is still retryable`() {
        val e = RuntimeException(
            "JsonDecodingException: Unexpected JSON token at offset 100"
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `detects insufficient tool messages as retryable`() {
        val e = RuntimeException(
            "Error from client: DeepSeekLLMClient\n" +
            "Status code: 400\n" +
            "Error body:\n" +
            "\"\"\"\"An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. (insufficient tool messages following tool_calls message)\"\"\"\""
        )
        assertTrue(isRetryableError(e))
    }

    @Test
    fun `suspicious short manifest is detected`() {
        assertTrue(isSuspiciousManifest("test"))
        assertTrue(isSuspiciousManifest("{}"))
        assertTrue(isSuspiciousManifest("not json"))
        assertTrue(isSuspiciousManifest("   \n"))
    }

    @Test
    fun `valid manifest is not suspicious`() {
        val valid = """{"version":"1.0","bin":["app.exe"]}"""
        assertFalse(isSuspiciousManifest(valid))
    }

    @Test
    fun `manifest with leading whitespace is handled`() {
        val valid = "\n\n  {\"version\":\"1.0\",\"bin\":[\"app.exe\"]}  \n"
        assertFalse(isSuspiciousManifest(valid))
    }
}
