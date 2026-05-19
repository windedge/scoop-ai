package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingCompatibleStrategyTest {

    // ---------------------------------------------------------------
    // isToolCallJsonParseError — matching cases
    // ---------------------------------------------------------------

    @Test
    fun `detects JSON token errors`() {
        val e = Exception("JSON token ':' was not followed by a value")
        assertTrue(isToolCallJsonParseError(e))
    }

    @Test
    fun `detects expected end of object errors`() {
        val e = Exception("Expected end of the object at line 1 column 42")
        assertTrue(isToolCallJsonParseError(e))
    }

    @Test
    fun `detects JsonDecoding prefix errors`() {
        val e = Exception("JsonDecodingException: Unexpected JSON token")
        assertTrue(isToolCallJsonParseError(e))
    }

    @Test
    fun `detects combined tool call and JSON keywords`() {
        val e = Exception("tool call arguments contain invalid JSON")
        assertTrue(isToolCallJsonParseError(e))
    }

    @Test
    fun `detects tool call JSON capitalize insensitive`() {
        val e = Exception("Tool Call produced bad json content")
        assertTrue(isToolCallJsonParseError(e))
    }

    // ---------------------------------------------------------------
    // isToolCallJsonParseError — non-matching cases
    // ---------------------------------------------------------------

    @Test
    fun `ignores network timeout errors`() {
        val e = Exception("Connect timeout: 30s elapsed")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `ignores HTTP errors`() {
        val e = Exception("HTTP 400 Bad Request")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `ignores auth errors`() {
        val e = Exception("401 Unauthorized: invalid API key")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `returns false when message is null`() {
        val e = Exception("")  // Kotlin Exception always has a message, use anonymous subclass
        val nullMsg = object : Exception() {}
        assertFalse(isToolCallJsonParseError(nullMsg))
    }

    @Test
    fun `returns false for empty message`() {
        val e = Exception("")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `returns false when only tool call appears without JSON`() {
        val e = Exception("tool call execution failed")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `returns false when only JSON appears without tool call`() {
        val e = Exception("JSON parse error in config file")
        assertFalse(isToolCallJsonParseError(e))
    }

    @Test
    fun `detects truncation via expected end of object`() {
        // DeepSeek V4 thinking mode may truncate: {"owner":"sharkdp"
        val e = Exception("Expected end of the object at character 24 of {\"owner\":\"sharkdp\"")
        assertTrue(isToolCallJsonParseError(e))
    }
}
