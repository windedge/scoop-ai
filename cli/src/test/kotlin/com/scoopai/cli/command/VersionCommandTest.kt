package com.scoopai.cli.command

import com.github.ajalt.clikt.testing.test
import com.scoopai.cli.ScoopAi
import com.scoopai.cli.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCommandTest {

    // ---------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------

    @Test
    fun `version help shows via --help`() {
        val result = VersionCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("version"))
    }

    @Test
    fun `version help via -h`() {
        val result = VersionCommand().test("-h")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:"))
    }

    // ---------------------------------------------------------------
    // Version output
    // ---------------------------------------------------------------

    @Test
    fun `version command outputs scoop-ai prefix`() {
        val result = VersionCommand().test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("scoop-ai"))
    }

    @Test
    fun `version command outputs version string`() {
        val result = VersionCommand().test("")
        assertTrue(result.output.contains(Version.VERSION),
            "Output should contain version '${Version.VERSION}', got: ${result.output}")
    }

    @Test
    fun `version command outputs commit hash`() {
        val result = VersionCommand().test("")
        assertTrue(result.output.contains("commit"),
            "Output should contain 'commit', got: ${result.output}")
    }

    @Test
    fun `version command output matches fullVersion format`() {
        val result = VersionCommand().test("")
        val expected = Version.fullVersion()
        assertEquals(expected.trim(), result.output.trim(),
            "Version output should match fullVersion()")
    }

    // ---------------------------------------------------------------
    // Exit code
    // ---------------------------------------------------------------

    @Test
    fun `version command exits 0`() {
        val result = VersionCommand().test("")
        assertEquals(0, result.statusCode)
    }

    // ---------------------------------------------------------------
    // Integration through ScoopAi parent
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai version subcommand through ScoopAi exits 0`() {
        val result = ScoopAi().test("version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("scoop-ai"))
    }

    @Test
    fun `scoop-ai --version flag through ScoopAi exits 0`() {
        val result = ScoopAi().test("--version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("scoop-ai"))
        assertTrue(result.output.contains(Version.VERSION))
    }
}
