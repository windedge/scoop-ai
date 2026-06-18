package com.scoopai.cli.diagnostic

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HealthCheckTest {

    // ---------------------------------------------------------------
    // JdkVersionCheck
    // ---------------------------------------------------------------

    @Test
    fun `jdk version 17 returns OK`() {
        val check = JdkVersionCheck(versionProvider = { "17.0.9" })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("17"))
    }

    @Test
    fun `jdk version 21 returns OK with recommendation`() {
        val check = JdkVersionCheck(versionProvider = { "21.0.1" })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("21"))
    }

    @Test
    fun `jdk version 11 returns FAIL`() {
        val check = JdkVersionCheck(versionProvider = { "11.0.18" })
        val result = runBlocking { check.run() }
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.message.contains("11"))
    }

    @Test
    fun `jdk version 8 returns FAIL`() {
        val check = JdkVersionCheck(versionProvider = { "1.8.0_352" })
        val result = runBlocking { check.run() }
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.message.contains("8"))
    }

    @Test
    fun `jdk unparseable version returns FAIL`() {
        val check = JdkVersionCheck(versionProvider = { "unknown" })
        val result = runBlocking { check.run() }
        assertEquals(Status.FAIL, result.status)
    }

    @Test
    fun `jdk version with vendor string`() {
        val check = JdkVersionCheck(versionProvider = { "17.0.9+9-LTS" })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
    }

    // ---------------------------------------------------------------
    // OsCheck
    // ---------------------------------------------------------------

    @Test
    fun `os check always returns OK`() {
        val check = OsCheck(
            osNameProvider = { "Windows 11" },
            osVersionProvider = { "10.0" },
        )
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("Windows 11"))
        assertTrue(result.message.contains("10.0"))
    }

    @Test
    fun `os check works on linux mock`() {
        val check = OsCheck(
            osNameProvider = { "Linux" },
            osVersionProvider = { "6.1.0" },
        )
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("Linux"))
    }

    // ---------------------------------------------------------------
    // ScoopInstalledCheck
    // ---------------------------------------------------------------

    @Test
    fun `scoop installed returns OK`() {
        val check = ScoopInstalledCheck(scoopRunner = {
            ScoopResult(true, "v0.5.2")
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("v0.5.2"))
    }

    @Test
    fun `scoop not installed returns WARN`() {
        val check = ScoopInstalledCheck(scoopRunner = {
            ScoopResult(false, "", "scoop: command not found")
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.WARN, result.status)
        assertTrue(result.message.contains("not found"))
    }

    @Test
    fun `scoop fails with error exit code returns WARN`() {
        val check = ScoopInstalledCheck(scoopRunner = {
            ScoopResult(false, "Some error output", "exit code 1")
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.WARN, result.status)
    }

    // ---------------------------------------------------------------
    // ApiKeyCheck
    // ---------------------------------------------------------------

    @Test
    fun `api key found in SCOOP_AI_API_KEY returns OK with masked value`() {
        val check = ApiKeyCheck(envVars = mapOf("SCOOP_AI_API_KEY" to "sk-1234567890abcdef"))
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("sk-"))
        assertTrue(result.message.contains("..."))
        assertFalse(result.message.contains("1234567890abcdef"), "Must not leak full key")
        assertFalse(result.message.contains("abcdef"), "Must not leak key suffix without masking")
    }

    @Test
    fun `api key found in DEEPSEEK_API_KEY`() {
        val check = ApiKeyCheck(envVars = mapOf("DEEPSEEK_API_KEY" to "sk-deepseek-key-12345"))
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("DEEPSEEK_API_KEY"))
    }

    @Test
    fun `api key found in OPENAI_API_KEY`() {
        val check = ApiKeyCheck(envVars = mapOf("OPENAI_API_KEY" to "sk-openai-test-67890"))
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("OPENAI_API_KEY"))
    }

    @Test
    fun `no api key returns WARN`() {
        val check = ApiKeyCheck(envVars = emptyMap())
        val result = runBlocking { check.run() }
        assertEquals(Status.WARN, result.status)
    }

    @Test
    fun `api key uses first found var in priority order`() {
        val check = ApiKeyCheck(envVars = mapOf(
            "SCOOP_AI_API_KEY" to "sk-priority",
            "OPENAI_API_KEY" to "sk-should-not-appear",
        ))
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("SCOOP_AI_API_KEY"))
        assertFalse(result.message.contains("OPENAI_API_KEY"))
    }

    @Test
    fun `blank api key is treated as missing`() {
        val check = ApiKeyCheck(envVars = mapOf("SCOOP_AI_API_KEY" to "  "))
        val result = runBlocking { check.run() }
        assertEquals(Status.WARN, result.status)
    }

    // ---------------------------------------------------------------
    // ApiKeyCheck.maskApiKey
    // ---------------------------------------------------------------

    @Test
    fun `maskApiKey shows prefix and last 4 chars for long key`() {
        val masked = ApiKeyCheck.maskApiKey("sk-abcdefghijklmnopqrstuvwxyz123456")
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.endsWith("3456"))
        assertTrue(masked.contains("..."))
    }

    @Test
    fun `maskApiKey returns set for short key under 8 chars`() {
        assertEquals("set", ApiKeyCheck.maskApiKey("sk-abc"))
    }

    @Test
    fun `maskApiKey returns set for exactly 7 chars`() {
        assertEquals("set", ApiKeyCheck.maskApiKey("1234567"))
    }

    @Test
    fun `maskApiKey handles key without prefix`() {
        val masked = ApiKeyCheck.maskApiKey("abcdefghijklmnop")
        assertTrue(masked.contains("..."))
        assertTrue(masked.endsWith("nop"))
    }

    // ---------------------------------------------------------------
    // CacheDirCheck
    // ---------------------------------------------------------------

    @Test
    fun `cache dir exists and writable returns OK`() {
        val dir = createTempDir()
        try {
            val check = CacheDirCheck(cacheDirProvider = { dir })
            val result = runBlocking { check.run() }
            assertEquals(Status.OK, result.status)
            assertTrue(result.message.contains("writable"))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `cache dir does not exist returns WARN`() {
        val check = CacheDirCheck(cacheDirProvider = {
            File("/nonexistent/scoop/cache")
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.WARN, result.status)
        assertTrue(result.message.contains("does not exist"))
    }

    // ---------------------------------------------------------------
    // WritePermissionCheck
    // ---------------------------------------------------------------

    @Test
    fun `writable current directory returns OK`() {
        val check = WritePermissionCheck(writableDirs = {
            listOf(createTempDir())
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
    }

    @Test
    fun `non-existent directory skips check and returns OK`() {
        val check = WritePermissionCheck(writableDirs = {
            listOf(File("/root/protected-dir"))
        })
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
    }

    // ---------------------------------------------------------------
    // VersionCheck
    // ---------------------------------------------------------------

    @Test
    fun `version check returns OK`() {
        val check = VersionCheck()
        val result = runBlocking { check.run() }
        assertEquals(Status.OK, result.status)
        assertTrue(result.message.contains("scoop-ai"))
    }

    @Test
    fun `version check contains version string`() {
        val check = VersionCheck()
        val result = runBlocking { check.run() }
        assertTrue(result.message.contains("version") || result.message.contains("commit"))
    }

    // ---------------------------------------------------------------
    // defaultHealthChecks
    // ---------------------------------------------------------------

    @Test
    fun `defaultHealthChecks returns 7 checks in fixed order`() {
        val checks = defaultHealthChecks()
        assertEquals(7, checks.size)
        assertEquals("jdk-version", checks[0].name)
        assertEquals("os", checks[1].name)
        assertEquals("scoop-installed", checks[2].name)
        assertEquals("api-key", checks[3].name)
        assertEquals("cache-dir", checks[4].name)
        assertEquals("write-permission", checks[5].name)
        assertEquals("version", checks[6].name)
    }
}
