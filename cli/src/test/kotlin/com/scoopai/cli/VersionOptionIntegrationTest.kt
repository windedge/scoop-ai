package com.scoopai.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests verifying `--version` flag and `version` subcommand
 * work through the ScoopAi parent command.
 */
class VersionOptionIntegrationTest {

    @Test
    fun `ScoopAi --version flag exits 0 and shows version`() {
        val result = ScoopAi().test("--version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("scoop-ai"),
            "Output should contain 'scoop-ai', got: '${result.output.trim()}'")
        assertTrue(result.output.contains("commit"),
            "Output should contain commit hash")
    }

    @Test
    fun `ScoopAi version subcommand exits 0 and shows version`() {
        val result = ScoopAi().test("version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("scoop-ai"),
            "Output should contain 'scoop-ai', got: '${result.output.trim()}'")
    }
}
