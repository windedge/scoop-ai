package com.scoopai.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Integration tests for [CliRunner.run] verifying that all
 * clikt-driven help/version output is captured and printed.
 *
 * These tests redirect System.out/System.err to capture output
 * produced by [com.github.ajalt.clikt.core.CliktCommand.echoFormattedHelp].
 */
class CliRunnerIntegrationTest {

    private val outContent = ByteArrayOutputStream()
    private val errContent = ByteArrayOutputStream()
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @BeforeEach
    fun setUp() {
        originalOut = System.out
        originalErr = System.err
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))
    }

    @AfterEach
    fun tearDown() {
        System.out.flush()
        System.err.flush()
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    // ---------------------------------------------------------------
    // 1. --version
    // ---------------------------------------------------------------

    @Test
    fun `--version exits 0 and outputs version string`() {
        val exitCode = CliRunner.run(arrayOf("--version"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "--version should exit 0")
        assertTrue(output.contains("scoop-ai"), "output should contain 'scoop-ai', got: '$output'")
        assertTrue(output.contains("commit"), "output should contain 'commit', got: '$output'")
    }

    @Test
    fun `--version outputs correct format without extra prefix`() {
        val exitCode = CliRunner.run(arrayOf("--version"))
        val output = outContent.toString().trimEnd()
        assertEquals(0, exitCode, "--version should exit 0")
        // Must match "scoop-ai <semver> (commit <hash>)"
        val pattern = Regex("^scoop-ai \\d+\\.\\d+\\.\\d+ \\(commit [a-f0-9]+\\)$")
        assertTrue(
            pattern.matches(output),
            "Expected pattern 'scoop-ai X.Y.Z (commit XXXXXX)' but got: '$output'",
        )
        // Must NOT have the extra prefix from clikt's versionOption default formatting
        assertFalse(
            "scoop-ai version" in output,
            "--version output should not contain 'scoop-ai version' (indicates extra prefix), got: '$output'",
        )
    }

    // ---------------------------------------------------------------
    // 2. --help (top-level)
    // ---------------------------------------------------------------

    @Test
    fun `--help exits 0 and shows usage with 5 subcommands`() {
        val exitCode = CliRunner.run(arrayOf("--help"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "--help should exit 0")
        assertTrue(output.contains("Usage:"), "output should contain 'Usage:', got: '$output'")
        // Should list all 5 subcommands
        assertTrue(output.contains("generate"), "output should list 'generate'")
        assertTrue(output.contains("doctor"), "output should list 'doctor'")
        assertTrue(output.contains("config"), "output should list 'config'")
        assertTrue(output.contains("completion"), "output should list 'completion'")
        assertTrue(output.contains("version"), "output should list 'version'")
    }

    // ---------------------------------------------------------------
    // 3. generate --help
    // ---------------------------------------------------------------

    @Test
    fun `generate --help exits 0 and shows all 14 flags`() {
        val exitCode = CliRunner.run(arrayOf("generate", "--help"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "generate --help should exit 0")
        assertTrue(output.contains("Usage:"), "output should contain 'Usage:'")
        // Verify key flags are listed
        assertTrue(output.contains("--output"), "should list --output")
        assertTrue(output.contains("--provider"), "should list --provider")
        assertTrue(output.contains("--model"), "should list --model")
        assertTrue(output.contains("--base-url"), "should list --base-url")
        assertTrue(output.contains("--api-key"), "should list --api-key")
        assertTrue(output.contains("--github-token"), "should list --github-token")
        assertTrue(output.contains("--app-name"), "should list --app-name")
        assertTrue(output.contains("--prompt"), "should list --prompt")
        assertTrue(output.contains("--persist"), "should list --persist")
        assertTrue(output.contains("--verify"), "should list --verify")
        assertTrue(output.contains("--non-interactive"), "should list --non-interactive")
        assertTrue(output.contains("--cache"), "should list --cache")
        assertTrue(output.contains("--debug"), "should list --debug")
        assertTrue(output.contains("--no-progress"), "should list --no-progress")
    }

    // ---------------------------------------------------------------
    // 4. doctor --help
    // ---------------------------------------------------------------

    @Test
    fun `doctor --help exits 0 and shows usage`() {
        val exitCode = CliRunner.run(arrayOf("doctor", "--help"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "doctor --help should exit 0")
        assertTrue(output.contains("Usage:"), "output should contain 'Usage:', got: '$output'")
        assertTrue(
            output.contains("doctor") || output.contains("diagnostic"),
            "output should mention 'doctor', got: '$output'",
        )
    }

    // ---------------------------------------------------------------
    // 5. config --help
    // ---------------------------------------------------------------

    @Test
    fun `config --help exits 0 and shows usage`() {
        val exitCode = CliRunner.run(arrayOf("config", "--help"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "config --help should exit 0")
        assertTrue(output.contains("Usage:"), "output should contain 'Usage:', got: '$output'")
        assertTrue(output.contains("config"), "output should mention 'config'")
    }

    // ---------------------------------------------------------------
    // 6. completion --help
    // ---------------------------------------------------------------

    @Test
    fun `completion --help exits 0 and shows usage`() {
        val exitCode = CliRunner.run(arrayOf("completion", "--help"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "completion --help should exit 0")
        assertTrue(output.contains("Usage:"), "output should contain 'Usage:', got: '$output'")
        assertTrue(output.contains("completion"), "output should mention 'completion'")
    }

    // ---------------------------------------------------------------
    // 7. version subcommand
    // ---------------------------------------------------------------

    @Test
    fun `version subcommand exits 0 and outputs version`() {
        val exitCode = CliRunner.run(arrayOf("version"))
        val output = outContent.toString()
        assertEquals(0, exitCode, "version subcommand should exit 0")
        assertTrue(
            output.contains("scoop-ai"),
            "output should contain 'scoop-ai', got: '$output'",
        )
        assertTrue(
            output.contains("commit"),
            "output should contain 'commit', got: '$output'",
        )
    }

    // ---------------------------------------------------------------
    // 8. No args (shows top-level help)
    // ---------------------------------------------------------------

    @Test
    fun `empty args exits 0 and shows help`() {
        val exitCode = CliRunner.run(arrayOf())
        val output = outContent.toString()
        assertEquals(0, exitCode, "scoop-ai with no args should exit 0 (help)")
        assertTrue(
            output.contains("Usage:") || output.contains("scoop-ai"),
            "output should contain help text, got: '$output'",
        )
    }

    // ---------------------------------------------------------------
    // 9. Invalid persist value → usage error (exit 2)
    // ---------------------------------------------------------------

    @Test
    fun `invalid persist exits 2 and shows error message`() {
        val exitCode = CliRunner.run(arrayOf("-s", "invalid", "some-url"))
        val output = outContent.toString()
        val errOutput = errContent.toString()
        assertEquals(2, exitCode, "invalid persist should exit 2 (USAGE)")
        val combined = output + errOutput
        assertTrue(
            combined.contains("Error:") ||
                combined.contains("Invalid") ||
                combined.contains("invalid"),
            "output should contain error message, stdout: '$output', stderr: '$errOutput'",
        )
    }

    // ---------------------------------------------------------------
    // 10. openai-compatible without base-url → config error (exit 3)
    // ---------------------------------------------------------------

    @Test
    fun `openai-compatible without base-url exits 3 and shows error`() {
        val exitCode = CliRunner.run(arrayOf("-P", "openai-compatible", "some-url"))
        val output = outContent.toString()
        val errOutput = errContent.toString()
        assertEquals(3, exitCode, "openai-compatible without base-url should exit 3 (CONFIG)")
        val combined = output + errOutput
        assertTrue(
            combined.contains("ERROR") || combined.contains("Error"),
            "output should contain error, stdout: '$output', stderr: '$errOutput'",
        )
    }
}
