package com.scoopai.cli.command

import com.github.ajalt.clikt.testing.test
import com.scoopai.cli.diagnostic.ApiKeyCheck
import com.scoopai.cli.diagnostic.HealthCheck
import com.scoopai.cli.diagnostic.HealthResult
import com.scoopai.cli.diagnostic.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoctorCommandTest {

    // ---------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------

    @Test
    fun `doctor help shows description`() {
        val result = DoctorCommand(listOf()).test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("doctor"))
        assertTrue(result.output.contains("diagnostic"))
    }

    @Test
    fun `doctor help via -h`() {
        val result = DoctorCommand(listOf()).test("-h")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:"))
    }

    // ---------------------------------------------------------------
    // All OK
    // ---------------------------------------------------------------

    @Test
    fun `all checks ok exits 0 and shows all passed`() {
        val checks = listOf(
            FakeCheck("check-a", Status.OK, "all good"),
            FakeCheck("check-b", Status.OK, "fine"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("All 2 checks passed"))
    }

    @Test
    fun `all checks ok shows table rows`() {
        val checks = listOf(
            FakeCheck("check-a", Status.OK, "good"),
            FakeCheck("check-b", Status.OK, "fine"),
        )
        val result = DoctorCommand(checks).test("")
        assertTrue(result.output.contains("[OK"))
        assertTrue(result.output.contains("check-a"))
        assertTrue(result.output.contains("check-b"))
    }

    // ---------------------------------------------------------------
    // Contains FAIL
    // ---------------------------------------------------------------

    @Test
    fun `one failure exits 1`() {
        val checks = listOf(
            FakeCheck("check-a", Status.OK, "good"),
            FakeCheck("check-b", Status.FAIL, "broken"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `one failure shows count`() {
        val checks = listOf(
            FakeCheck("check-a", Status.OK, "good"),
            FakeCheck("check-b", Status.FAIL, "broken"),
        )
        val result = DoctorCommand(checks).test("")
        assertTrue(result.output.contains("1 failure"))
        assertTrue(result.output.contains("1/2 checks passed"))
    }

    @Test
    fun `multiple failures show plural count`() {
        val checks = listOf(
            FakeCheck("a", Status.FAIL, "fail1"),
            FakeCheck("b", Status.FAIL, "fail2"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("2 failures"))
    }

    // ---------------------------------------------------------------
    // Contains WARN
    // ---------------------------------------------------------------

    @Test
    fun `one warning exits 0`() {
        val checks = listOf(
            FakeCheck("check-a", Status.OK, "good"),
            FakeCheck("check-b", Status.WARN, "caution"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("1 warning"))
    }

    @Test
    fun `multiple warnings show plural`() {
        val checks = listOf(
            FakeCheck("a", Status.WARN, "w1"),
            FakeCheck("b", Status.WARN, "w2"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("2 warnings"))
    }

    // ---------------------------------------------------------------
    // Mixed status
    // ---------------------------------------------------------------

    @Test
    fun `mixed fail and warn exits 1 and shows both counts`() {
        val checks = listOf(
            FakeCheck("a", Status.OK, "ok"),
            FakeCheck("b", Status.WARN, "warn"),
            FakeCheck("c", Status.FAIL, "fail"),
        )
        val result = DoctorCommand(checks).test("")
        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("1 failure"))
        assertTrue(result.output.contains("1 warning"))
        assertTrue(result.output.contains("1/3 checks passed"))
    }

    @Test
    fun `output shows FAIL and WARN markers`() {
        val checks = listOf(
            FakeCheck("a", Status.FAIL, "broken"),
            FakeCheck("b", Status.WARN, "caution"),
        )
        val result = DoctorCommand(checks).test("")
        assertTrue(result.output.contains("[FAIL"))
        assertTrue(result.output.contains("[WARN"))
    }

    // ---------------------------------------------------------------
    // Api-key masking
    // ---------------------------------------------------------------

    @Test
    fun `doctor with api-key check does not leak actual key`() {
        val checks = listOf(
            ApiKeyCheck(envVars = mapOf("SCOOP_AI_API_KEY" to "sk-very-secret-key-1234567890")),
        )
        val result = DoctorCommand(checks).test("")
        assertTrue(result.output.contains("[OK"), "Output should show OK status")
        assertTrue(result.output.contains("api-key"), "Output should mention api-key")
        assertFalse(result.output.contains("very-secret-key"), "Must not leak actual key")
        assertFalse(result.output.contains("1234567890"), "Must not leak any part of key")
    }

    @Test
    fun `doctor with api-key not set shows WARN and no leak`() {
        val checks = listOf(
            ApiKeyCheck(envVars = emptyMap()),
        )
        val result = DoctorCommand(checks).test("")
        assertTrue(result.output.contains("[WARN"), "Output should show WARN status")
        assertTrue(result.output.contains("No API key found"), "Should mention missing key")
    }
}

// ---------------------------------------------------------------
// Fake health check for testing
// ---------------------------------------------------------------

private class FakeCheck(
    override val name: String,
    private val status: Status,
    private val msg: String,
) : HealthCheck {
    override suspend fun run(): HealthResult = HealthResult(status, msg)
}
