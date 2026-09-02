package com.scoopai.cli.command

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.testing.test
import com.scoopai.cli.error.ExitCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerateCommandTest {

    // ---------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------

    @Test
    fun `--help shows usage with clikt formatting`() {
        val result = GenerateCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:"), "Output should contain 'Usage:'")
        assertTrue(result.output.contains("generate"), "Output should mention 'generate'")
        assertTrue(result.output.contains("--output"), "Output should contain --output")
        assertTrue(result.output.contains("--provider"), "Output should contain --provider")
    }

    @Test
    fun `-h shows usage`() {
        val result = GenerateCommand().test("-h")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:"))
    }

    // ---------------------------------------------------------------
    // Missing URL
    // ---------------------------------------------------------------

    @Test
    fun `missing url throws usage error`() {
        val result = GenerateCommand().test("")
        assertEquals(1, result.statusCode) // clikt's default UsageError exit code
        assertTrue(result.output.contains("Error:"), "Output should contain 'Error:' for missing url")
    }

    @Test
    fun `missing url with flag still fails`() {
        val result = GenerateCommand().test("--debug")
        assertEquals(1, result.statusCode)
    }

    // ---------------------------------------------------------------
    // Missing flag value
    // ---------------------------------------------------------------

    @Test
    fun `flag without value throws usage error`() {
        val result = GenerateCommand().test("-o", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Flag -o without value must fail")
    }

    @Test
    fun `long flag without value throws usage error`() {
        val result = GenerateCommand().test("--output", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Flag --output without value must fail")
    }

    @Test
    fun `persist flag without value throws usage error`() {
        val result = GenerateCommand().test("-s", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Flag -s without value must fail")
    }

    // ---------------------------------------------------------------
    // URL argument
    // ---------------------------------------------------------------

    @Test
    fun `url argument is parsed`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/owner/repo")
        assertEquals("https://github.com/owner/repo", cmd.url)
    }

    @Test
    fun `url argument parsed with options`() {
        val cmd = GenerateCommand()
        cmd.test("-o", "out.json", "https://github.com/x/y")
        assertEquals("https://github.com/x/y", cmd.url)
    }

    // ---------------------------------------------------------------
    // String options
    // ---------------------------------------------------------------

    @Test
    fun `output option with -o`() {
        val cmd = GenerateCommand()
        cmd.test("-o", "result.json", "https://github.com/x/y")
        assertEquals("result.json", cmd.output)
    }

    @Test
    fun `output option with --output`() {
        val cmd = GenerateCommand()
        cmd.test("--output", "result.json", "https://github.com/x/y")
        assertEquals("result.json", cmd.output)
    }

    @Test
    fun `provider option with -P`() {
        val cmd = GenerateCommand()
        cmd.test("-P", "anthropic", "https://github.com/x/y")
        assertEquals("anthropic", cmd.provider)
    }

    @Test
    fun `provider option with --provider`() {
        val cmd = GenerateCommand()
        cmd.test("--provider", "ollama", "https://github.com/x/y")
        assertEquals("ollama", cmd.provider)
    }

    @Test
    fun `api-key option`() {
        val cmd = GenerateCommand()
        cmd.test("--api-key", "sk-test123", "https://github.com/x/y")
        assertEquals("sk-test123", cmd.apiKey)
    }

    @Test
    fun `base-url option`() {
        val cmd = GenerateCommand()
        cmd.test("--base-url", "http://localhost:8080/v1", "https://github.com/x/y")
        assertEquals("http://localhost:8080/v1", cmd.baseUrl)
    }

    @Test
    fun `model option with -m`() {
        val cmd = GenerateCommand()
        cmd.test("-m", "gpt-4", "https://github.com/x/y")
        assertEquals("gpt-4", cmd.model)
    }

    @Test
    fun `model option with --model`() {
        val cmd = GenerateCommand()
        cmd.test("--model", "claude-3", "https://github.com/x/y")
        assertEquals("claude-3", cmd.model)
    }

    @Test
    fun `app-name option`() {
        val cmd = GenerateCommand()
        cmd.test("--app-name", "my-app", "https://github.com/x/y")
        assertEquals("my-app", cmd.appName)
    }

    @Test
    fun `prompt option with -p`() {
        val cmd = GenerateCommand()
        cmd.test("-p", "use portable exe", "https://github.com/x/y")
        assertEquals("use portable exe", cmd.prompt)
    }

    @Test
    fun `prompt option with --prompt`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y", "--prompt", "minimal bin")
        assertEquals("minimal bin", cmd.prompt)
    }

    @Test
    fun `github-token option`() {
        val cmd = GenerateCommand()
        cmd.test("--github-token", "ghp_abc123", "https://github.com/x/y")
        assertEquals("ghp_abc123", cmd.githubToken)
    }

    // ---------------------------------------------------------------
    // Boolean flags
    // ---------------------------------------------------------------

    @Test
    fun `verify flag with -V`() {
        val cmd = GenerateCommand()
        cmd.test("-V", "https://github.com/x/y")
        assertTrue(cmd.verify)
    }

    @Test
    fun `verify flag with --verify`() {
        val cmd = GenerateCommand()
        cmd.test("--verify", "https://github.com/x/y")
        assertTrue(cmd.verify)
    }

    @Test
    fun `verify not set by default`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertFalse(cmd.verify)
    }

    @Test
    fun `debug flag with --debug`() {
        val cmd = GenerateCommand()
        cmd.test("--debug", "https://github.com/x/y")
        assertTrue(cmd.debug)
    }

    @Test
    fun `debug not set by default`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertFalse(cmd.debug)
    }

    @Test
    fun `no-progress flag`() {
        val cmd = GenerateCommand()
        cmd.test("--no-progress", "https://github.com/x/y")
        assertTrue(cmd.noProgress)
    }

    @Test
    fun `non-interactive flag with -n`() {
        val cmd = GenerateCommand()
        cmd.test("-n", "https://github.com/x/y")
        assertTrue(cmd.nonInteractive)
    }

    @Test
    fun `non-interactive flag with --non-interactive`() {
        val cmd = GenerateCommand()
        val result = cmd.test("--non-interactive", "https://github.com/x/y")
        assertTrue(cmd.nonInteractive) { "nonInteractive should be true with --non-interactive" }
    }

    @Test
    fun `non-interactive not set by default`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertFalse(cmd.nonInteractive)
    }

    @Test
    fun `no-cache flag`() {
        val cmd = GenerateCommand()
        cmd.test("--no-cache", "https://github.com/x/y")
        assertTrue(cmd.noCache)
    }

    @Test
    fun `no-cache not set by default`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertFalse(cmd.noCache)
    }

    // ---------------------------------------------------------------
    // Persist mode (choice)
    // ---------------------------------------------------------------

    @Test
    fun `persist with -s force`() {
        val cmd = GenerateCommand()
        cmd.test("-s", "force", "https://github.com/x/y")
        assertEquals("force", cmd.persist)
    }

    @Test
    fun `persist with -s none`() {
        val cmd = GenerateCommand()
        cmd.test("-s", "none", "https://github.com/x/y")
        assertEquals("none", cmd.persist)
    }

    @Test
    fun `persist with -s auto`() {
        val cmd = GenerateCommand()
        cmd.test("-s", "auto", "https://github.com/x/y")
        assertEquals("auto", cmd.persist)
    }

    @Test
    fun `persist with -s no`() {
        val cmd = GenerateCommand()
        cmd.test("-s", "no", "https://github.com/x/y")
        assertEquals("no", cmd.persist)
    }

    @Test
    fun `persist with --persist`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y", "--persist", "force")
        assertEquals("force", cmd.persist)
    }

    @Test
    fun `persist default is auto`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals("auto", cmd.persist)
    }

    @Test
    fun `invalid persist value throws`() {
        val result = GenerateCommand().test("-s", "invalid", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Invalid persist must fail")
        assertTrue(result.output.lowercase().contains("invalid choice") ||
                   result.output.lowercase().contains("error"),
               "Output should contain error about invalid choice")
    }

    // ---------------------------------------------------------------
    // Combined flags
    // ---------------------------------------------------------------

    @Test
    fun `all 16 flags parsed`() {
        val cmd = GenerateCommand()
        cmd.test(
            "-o", "out.json",
            "-P", "openai-compatible",
            "--api-key", "key123",
            "--base-url", "http://x",
            "-m", "model-x",
            "-V",
            "--app-name", "myapp",
            "--cache", "discard",
            "-p", "use portable",
            "-n",
            "--github-token", "ghp_tok",
            "-s", "force",
            "--debug",
            "--no-progress",
            "https://github.com/x/y"
        )
        assertEquals("https://github.com/x/y", cmd.url)
        assertEquals("out.json", cmd.output)
        assertEquals("openai-compatible", cmd.provider)
        assertEquals("key123", cmd.apiKey)
        assertEquals("http://x", cmd.baseUrl)
        assertEquals("model-x", cmd.model)
        assertTrue(cmd.verify)
        assertEquals("myapp", cmd.appName)
        assertEquals("discard", cmd.cache)
        assertEquals("use portable", cmd.prompt)
        assertTrue(cmd.nonInteractive)
        assertEquals("ghp_tok", cmd.githubToken)
        assertEquals("force", cmd.persist)
        assertTrue(cmd.debug)
        assertTrue(cmd.noProgress)
    }

    // ---------------------------------------------------------------
    // Cache options (--cache / --no-cache)
    // ---------------------------------------------------------------

    @Test
    fun `cache option defaults to keep`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals("keep", cmd.cache)
    }

    @Test
    fun `cache option keep`() {
        val cmd = GenerateCommand()
        cmd.test("--cache", "keep", "https://github.com/x/y")
        assertEquals("keep", cmd.cache)
    }

    @Test
    fun `cache option discard`() {
        val cmd = GenerateCommand()
        cmd.test("--cache", "discard", "https://github.com/x/y")
        assertEquals("discard", cmd.cache)
    }

    @Test
    fun `keepCache is false with --cache=discard`() {
        val cmd = GenerateCommand()
        cmd.test("--cache", "discard", "https://github.com/x/y")
        assertEquals(false, cmd.keepCache)
    }

    @Test
    fun `keepCache is true with --cache=keep`() {
        val cmd = GenerateCommand()
        cmd.test("--cache", "keep", "https://github.com/x/y")
        assertEquals(true, cmd.keepCache)
    }

    @Test
    fun `keepCache is false with --no-cache`() {
        val cmd = GenerateCommand()
        cmd.test("--no-cache", "https://github.com/x/y")
        assertEquals(false, cmd.keepCache)
    }

    @Test
    fun `keepCache defaults to true`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals(true, cmd.keepCache)
    }

    @Test
    fun `invalid cache value throws`() {
        val result = GenerateCommand().test("--cache", "invalid", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Invalid cache value must fail")
        assertTrue(result.output.lowercase().contains("invalid choice") ||
                   result.output.lowercase().contains("error"),
               "Output should contain error about invalid choice")
    }

    @Test
    fun `no-cache triggers deprecation warning`() {
        val cmd = GenerateCommand()
        cmd.test("--no-cache", "https://github.com/x/y")
        assertTrue(cmd.noCache, "noCache should be true with --no-cache")
        assertEquals("keep", cmd.cache, "cache should default to keep when only --no-cache")
        assertEquals(false, cmd.keepCache, "keepCache should be false with --no-cache")
        // Note: clikt 4.4.0 auto-creates --no-cache as a pseudo-negation of --cache
        // (choice option), which consumes the URL argument and prevents run() from
        // being called in test() mode. The echo warning text is verified via the
        // NO_CACHE_DEPRECATION_WARNING constant defined in GenerateCommand.
        assertTrue(
            GenerateCommand.NO_CACHE_DEPRECATION_WARNING.contains("deprecated"),
            "deprecation warning should mention 'deprecated'"
        )
        assertTrue(
            GenerateCommand.NO_CACHE_DEPRECATION_WARNING.contains("--cache=discard"),
            "deprecation warning should mention --cache=discard"
        )
    }

    // ---------------------------------------------------------------
    // Output format
    // ---------------------------------------------------------------

    @Test
    fun `output-format default is text`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals("text", cmd.outputFormat)
    }

    @Test
    fun `output-format json`() {
        val cmd = GenerateCommand()
        cmd.test("--output-format", "json", "https://github.com/x/y")
        assertEquals("json", cmd.outputFormat)
    }

    @Test
    fun `output-format text`() {
        val cmd = GenerateCommand()
        cmd.test("--output-format", "text", "https://github.com/x/y")
        assertEquals("text", cmd.outputFormat)
    }

    @Test
    fun `invalid output-format throws`() {
        val result = GenerateCommand().test("--output-format", "xml", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Invalid output-format must fail")
        assertTrue(result.output.lowercase().contains("invalid choice") ||
                   result.output.lowercase().contains("error"),
               "Output should contain error about invalid choice")
    }

    // ---------------------------------------------------------------
    // Default values
    // ---------------------------------------------------------------

    @Test
    fun `default values are empty or false`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals("", cmd.output)
        assertEquals("", cmd.provider)
        assertEquals("", cmd.apiKey)
        assertNull(cmd.baseUrl)
        assertNull(cmd.model)
        assertFalse(cmd.verify)
        assertNull(cmd.appName)
        assertFalse(cmd.noCache)
        assertNull(cmd.prompt)
        assertFalse(cmd.nonInteractive)
        assertNull(cmd.githubToken)
        assertEquals("auto", cmd.persist)
        assertFalse(cmd.debug)
        assertFalse(cmd.noProgress)
        assertEquals("text", cmd.outputFormat)
    }

    // ---------------------------------------------------------------
    // URL can appear after flags
    // ---------------------------------------------------------------

    @Test
    fun `url after all flags`() {
        val cmd = GenerateCommand()
        cmd.test("--debug", "-V", "--no-cache", "https://github.com/x/y")
        assertEquals("https://github.com/x/y", cmd.url)
        assertTrue(cmd.debug)
        assertTrue(cmd.verify)
        assertTrue(cmd.noCache)
    }

    // ---------------------------------------------------------------
    // URL format validation
    // ---------------------------------------------------------------

    @Test
    fun `invalid url like not-a-url reports USAGE error not CONFIG`() {
        val result = GenerateCommand().test("not-a-url")
        assertEquals(2, result.statusCode) // USAGE
        assertTrue(
            result.output.contains("url") || result.output.contains("URL"),
            "Output should mention URL, got: ${result.output}"
        )
    }

    @Test
    fun `short alphanumeric string is rejected as invalid URL`() {
        val result = GenerateCommand().test("foo")
        assertEquals(2, result.statusCode)
        assertTrue(
            result.output.contains("url") || result.output.contains("URL"),
            "Output should mention URL, got: ${result.output}"
        )
    }

    @Test
    fun `valid https url passes validation`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/owner/repo")
        assertEquals("https://github.com/owner/repo", cmd.url)
    }

    @Test
    fun `short form github url passes validation`() {
        val cmd = GenerateCommand()
        cmd.test("github.com/owner/repo")
        assertEquals("github.com/owner/repo", cmd.url)
    }

    @Test
    fun `ssh git url passes validation`() {
        val cmd = GenerateCommand()
        cmd.test("git@github.com:owner/repo.git")
        assertEquals("git@github.com:owner/repo.git", cmd.url)
    }

    // ---------------------------------------------------------------
    // Checkpoint resume: --from / --run-id
    // ---------------------------------------------------------------

    @Test
    fun `--from defaults to empty`() {
        val cmd = GenerateCommand()
        cmd.test("https://github.com/x/y")
        assertEquals("", cmd.from)
    }

    @Test
    fun `--from analyze parsed`() {
        val cmd = GenerateCommand()
        cmd.test("--from", "analyze", "https://github.com/x/y")
        assertEquals("analyze", cmd.from)
    }

    @Test
    fun `--from generate parsed`() {
        val cmd = GenerateCommand()
        cmd.test("--from", "generate", "https://github.com/x/y")
        assertEquals("generate", cmd.from)
    }

    @Test
    fun `invalid --from value throws`() {
        val result = GenerateCommand().test("--from", "invalid", "https://github.com/x/y")
        assertTrue(result.statusCode != 0, "Invalid --from value must fail")
        assertTrue(
            result.output.lowercase().contains("invalid choice") ||
                result.output.lowercase().contains("error"),
            "Output should contain error about invalid choice"
        )
    }

    @Test
    fun `--from without --run-id shows usage error`() {
        // When --from is specified but --run-id is missing, doRun() should throw UsageError
        val cmd = GenerateCommand()
        val result = cmd.test("--from", "analyze", "https://github.com/x/y")
        assertTrue(
            result.statusCode != 0,
            "Should exit with error when --from is specified without --run-id" +
                " (got statusCode=${result.statusCode})"
        )
    }

    @Test
    fun `--from without --run-id reports USAGE before checking provider config`() {
        // Even if no API key is configured, --from without --run-id should report USAGE
        // before reaching resolveProviderConfig()
        val result = GenerateCommand().test("--from", "generate", "https://github.com/x/y")
        assertEquals(2, result.statusCode)
        assertTrue(
            result.output.contains("--run-id") || result.output.contains("run-id"),
            "Output should mention --run-id, got: ${result.output}"
        )
    }

    @Test
    fun `--from with --run-id parsed`() {
        val cmd = GenerateCommand()
        cmd.test("--from", "generate", "--run-id", "abc123", "https://github.com/x/y")
        assertEquals("generate", cmd.from)
        assertEquals("abc123", cmd.runId)
    }

    @Test
    fun `--from analyze with --run-id in all-flags test`() {
        val cmd = GenerateCommand()
        cmd.test(
            "-o", "out.json",
            "--run-id", "run-001",
            "--from", "analyze",
            "https://github.com/x/y"
        )
        assertEquals("analyze", cmd.from)
        assertEquals("run-001", cmd.runId)
        assertEquals("out.json", cmd.output)
    }

    // ---------------------------------------------------------------
    // Manifest rejection guard (manifestRejectionDetail)
    //
    // Regressions for the guard ordering bug: the exhausted/invalid JSON
    // guard was checked first, so the blank-manifest branch was unreachable
    // and an empty manifest got mislabeled "not valid JSON".
    // ---------------------------------------------------------------

    @Test
    fun `blank manifest reports blank message even when exhausted`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            "", exhausted = true, issues = listOf("issue1")
        )
        assertEquals("no manifest generated", detail)
    }

    @Test
    fun `blank manifest reports blank message instead of invalid JSON`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            "   ", exhausted = false, issues = emptyList()
        )
        assertEquals("no manifest generated", detail)
    }

    @Test
    fun `exhausted with valid JSON reports repair loop exhaustion`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            """{"version":"1.0"}""",
            exhausted = true,
            issues = listOf("a", "b"),
        )
        assertEquals(
            "manifest generation failed: repair loop exhausted with 2 unresolved issue(s): a; b",
            detail,
        )
    }

    @Test
    fun `exhausted with invalid JSON still reports exhaustion not JSON error`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            "{not json", exhausted = true, issues = listOf("x")
        )
        assertTrue(
            detail != null && detail.contains("repair loop exhausted"),
            "exhausted must win over invalid JSON, got: $detail",
        )
    }

    @Test
    fun `not exhausted with invalid JSON reports invalid JSON`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            "{not json", exhausted = false, issues = emptyList()
        )
        assertEquals("manifest generation failed: generated manifest is not valid JSON", detail)
    }

    @Test
    fun `valid non-blank manifest passes the guard`() {
        val detail = GenerateCommand.manifestRejectionDetail(
            """{"version":"1.0"}""",
            exhausted = false,
            issues = emptyList(),
        )
        assertNull(detail, "valid manifest must not be rejected")
    }
}
