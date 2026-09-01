package com.scoopai.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoopAiConfigTest {

    @Test
    fun `defaults match library-friendly GUI settings`() {
        val config = ScoopAiConfig(
            provider = "deepseek",
            apiKey = "sk-test",
        )
        assertNull(config.baseUrl)
        assertNull(config.model)
        assertNull(config.githubToken)
        assertNull(config.appName)
        assertEquals("auto", config.persistMode)
        assertTrue(config.keepCache)
        assertFalse(config.verify)
        assertTrue(config.nonInteractive)
        assertEquals(500, config.maxIterations)
        assertFalse(config.skipDownloadConfirm)
    }

    @Test
    fun `all fields can be set explicitly`() {
        val config = ScoopAiConfig(
            provider = "openai-compatible",
            apiKey = "key-1",
            baseUrl = "http://localhost:8080",
            model = "opencode/deepseek-v4-flash",
            githubToken = "ghp_x",
            appName = "myapp",
            persistMode = "force",
            keepCache = false,
            verify = true,
            nonInteractive = false,
            maxIterations = 100,
            skipDownloadConfirm = true,
        )
        assertEquals("openai-compatible", config.provider)
        assertEquals("key-1", config.apiKey)
        assertEquals("http://localhost:8080", config.baseUrl)
        assertEquals("opencode/deepseek-v4-flash", config.model)
        assertEquals("ghp_x", config.githubToken)
        assertEquals("myapp", config.appName)
        assertEquals("force", config.persistMode)
        assertFalse(config.keepCache)
        assertTrue(config.verify)
        assertFalse(config.nonInteractive)
        assertEquals(100, config.maxIterations)
        assertTrue(config.skipDownloadConfirm)
    }
}
