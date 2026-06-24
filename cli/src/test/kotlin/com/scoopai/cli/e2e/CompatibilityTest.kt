package com.scoopai.cli.e2e

import com.github.ajalt.clikt.testing.test
import com.scoopai.cli.CliRunner
import com.scoopai.cli.ScoopAi
import com.scoopai.cli.command.GenerateCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end compatibility tests verifying that Phase 2 refactoring
 * does not break existing CLI usage patterns.
 *
 * These tests use clikt's [test] extension for command-level testing
 * and [CliRunner.run] for entry-point-level testing.
 */
class CompatibilityTest {

    // ---------------------------------------------------------------
    // 1. --version
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai --version exits 0 and shows version`() {
        val result = ScoopAi().test("--version")
        assertEquals(0, result.statusCode, "--version should exit 0")
        assertTrue(result.output.contains("scoop-ai"), "output should contain 'scoop-ai'")
        assertTrue(result.output.contains("commit"), "output should contain 'commit'")
    }

    // ---------------------------------------------------------------
    // 2. --help
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai --help exits 0 and shows Usage`() {
        val result = ScoopAi().test("--help")
        assertEquals(0, result.statusCode, "--help should exit 0")
        assertTrue(result.output.contains("Usage:"), "output should contain 'Usage:'")
        assertTrue(result.output.contains("generate"), "output should list 'generate' subcommand")
        assertTrue(result.output.contains("doctor"), "output should list 'doctor' subcommand")
        assertTrue(result.output.contains("config"), "output should list 'config' subcommand")
        assertTrue(result.output.contains("completion"), "output should list 'completion' subcommand")
        assertTrue(result.output.contains("version"), "output should list 'version' subcommand")
    }

    // ---------------------------------------------------------------
    // 3. No args (shows help)
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai with no args shows help and exits 0`() {
        val code = CliRunner.run(arrayOf())
        assertEquals(0, code, "scoop-ai with no args should exit 0 (help)")
    }

    // ---------------------------------------------------------------
    // 4. Invalid persist value
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai invalid persist exits with USAGE`() {
        // Through CliRunner entry point: CliktUsageError → ExitCode.USAGE (2)
        val code = CliRunner.run(arrayOf("bad-url", "-s", "invalid"))
        assertEquals(2, code, "invalid persist should exit 2 (USAGE)")
    }

    // ---------------------------------------------------------------
    // 5. openai-compatible without base-url
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai openai-compatible no base-url exits with CONFIG`() {
        val code = CliRunner.run(arrayOf("https://github.com/x/y", "-P", "openai-compatible"))
        assertEquals(3, code, "openai-compatible without base-url should exit 3 (CONFIG)")
    }

    // ---------------------------------------------------------------
    // 6. doctor --help
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai doctor --help exits 0`() {
        // Test through CliRunner to verify subcommand dispatch works
        val code = CliRunner.run(arrayOf("doctor", "--help"))
        assertEquals(0, code, "doctor --help should exit 0")
    }

    @Test
    fun `scoop-ai doctor --help shows help`() {
        val result = com.scoopai.cli.command.DoctorCommand(listOf()).test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:") || result.output.contains("doctor"),
            "DoctorCommand --help output should contain Usage or doctor")
    }

    // ---------------------------------------------------------------
    // 7. config show --help
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai config show --help exits 0`() {
        val code = CliRunner.run(arrayOf("config", "show", "--help"))
        assertEquals(0, code, "config show --help should exit 0")
    }

    @Test
    fun `scoop-ai config show --help shows help`() {
        val result = com.scoopai.cli.command.ConfigShowCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:") || result.output.contains("show"),
            "ConfigShowCommand --help output should contain Usage or show")
    }

    // ---------------------------------------------------------------
    // 8. completion bash outputs non-empty
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai completion bash outputs non-empty`() {
        val result = ScoopAi().test("completion", "bash")
        assertTrue(result.output.isNotBlank(), "completion bash output should be non-empty")
        assertTrue(
            result.output.contains("_scoop_ai_completion") || result.output.contains("scoop-ai"),
            "completion bash output should contain shell function",
        )
    }

    // ---------------------------------------------------------------
    // Additional compat: --help via entry point
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai --help via CliRunner exits 0`() {
        val code = CliRunner.run(arrayOf("--help"))
        assertEquals(0, code, "--help via CliRunner should exit 0")
    }

    // ---------------------------------------------------------------
    // Additional compat: generate --help
    // ---------------------------------------------------------------

    @Test
    fun `scoop-ai generate --help shows all flags`() {
        val result = GenerateCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("--output"), "should list --output")
        assertTrue(result.output.contains("--provider"), "should list --provider")
        assertTrue(result.output.contains("--api-key"), "should list --api-key")
        assertTrue(result.output.contains("--base-url"), "should list --base-url")
        assertTrue(result.output.contains("--model"), "should list --model")
        assertTrue(result.output.contains("--verify"), "should list --verify")
        assertTrue(result.output.contains("--app-name"), "should list --app-name")
        assertTrue(result.output.contains("--cache"), "should list --cache")
        assertTrue(result.output.contains("--no-cache") || result.output.contains("--cache"),
            "should list cache-related options")
        assertTrue(result.output.contains("--prompt"), "should list --prompt")
        assertTrue(result.output.contains("--non-interactive"), "should list --non-interactive")
        assertTrue(result.output.contains("--github-token"), "should list --github-token")
        assertTrue(result.output.contains("--persist"), "should list --persist")
        assertTrue(result.output.contains("--debug"), "should list --debug")
        assertTrue(result.output.contains("--no-progress"), "should list --no-progress")
    }
}
