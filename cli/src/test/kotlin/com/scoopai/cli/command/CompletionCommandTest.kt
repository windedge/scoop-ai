package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import com.scoopai.cli.ScoopAi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionCommandTest {

        /** Minimal parent command for testing completion generation. */
    private open class ParentCommand : CliktCommand(name = "scoop-ai") {
        override fun run() {}
    }
    private val parent = ParentCommand()

    // ---------------------------------------------------------------
    // Bash
    // ---------------------------------------------------------------

    @Test
    fun `bash completion is non-empty`() {
        val script = CompletionCommand.generateScript("bash", parent)
        assertNotNull(script)
        assertTrue(script.isNotBlank())
    }

    @Test
    fun `bash completion contains scoop-ai reference`() {
        val script = CompletionCommand.generateScript("bash", parent)
        assertTrue(script.contains("scoop-ai"))
    }

    @Test
    fun `bash completion contains generate subcommand`() {
        val script = CompletionCommand.generateScript("bash", parent)
        assertTrue(script.contains("generate"))
    }

    @Test
    fun `bash completion contains doctor subcommand`() {
        val script = CompletionCommand.generateScript("bash", parent)
        assertTrue(script.contains("doctor"))
    }

    @Test
    fun `bash completion has no carriage returns`() {
        val script = CompletionCommand.generateScript("bash", parent)
        assertFalse(script.contains("\r"), "Bash script must not contain CR chars")
    }

    // ---------------------------------------------------------------
    // Zsh
    // ---------------------------------------------------------------

    @Test
    fun `zsh completion is non-empty`() {
        val script = CompletionCommand.generateScript("zsh", parent)
        assertNotNull(script)
        assertTrue(script.isNotBlank())
    }

    @Test
    fun `zsh completion contains compdef directive`() {
        val script = CompletionCommand.generateScript("zsh", parent)
        assertTrue(script.contains("#compdef"))
    }

    @Test
    fun `zsh completion has no carriage returns`() {
        val script = CompletionCommand.generateScript("zsh", parent)
        assertFalse(script.contains("\r"), "Zsh script must not contain CR chars")
    }

    // ---------------------------------------------------------------
    // Fish
    // ---------------------------------------------------------------

    @Test
    fun `fish completion is non-empty`() {
        val script = CompletionCommand.generateScript("fish", parent)
        assertNotNull(script)
        assertTrue(script.isNotBlank())
    }

    @Test
    fun `fish completion contains complete commands`() {
        val script = CompletionCommand.generateScript("fish", parent)
        assertTrue(script.contains("complete -c scoop-ai"))
    }

    @Test
    fun `fish completion has no carriage returns`() {
        val script = CompletionCommand.generateScript("fish", parent)
        assertFalse(script.contains("\r"), "Fish script must not contain CR chars")
    }

    // ---------------------------------------------------------------
    // PowerShell
    // ---------------------------------------------------------------

    @Test
    fun `powershell completion is non-empty`() {
        val script = CompletionCommand.generateScript("powershell", parent)
        assertNotNull(script)
        assertTrue(script.isNotBlank())
    }

    @Test
    fun `powershell completion contains Register-ArgumentCompleter`() {
        val script = CompletionCommand.generateScript("powershell", parent)
        assertTrue(script.contains("Register-ArgumentCompleter"))
    }

    @Test
    fun `powershell completion has no carriage returns`() {
        val script = CompletionCommand.generateScript("powershell", parent)
        assertFalse(script.contains("\r"), "PowerShell script must not contain CR chars")
    }

    // ---------------------------------------------------------------
    // CLI test (runs through clikt)
    // ---------------------------------------------------------------

    @Test
    fun `completion bash through ScoopAi subcommand exits 0 and produces output`() {
        val result = ScoopAi().test("completion bash")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("bash"), "Output should contain bash content")
        assertTrue(result.output.contains("scoop-ai"), "Output should contain scoop-ai")
        assertFalse(result.output.contains("\r"), "Output must not contain CR chars")
    }

    @Test
    fun `completion zsh through ScoopAi subcommand`() {
        val result = ScoopAi().test("completion zsh")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("#compdef"))
        assertFalse(result.output.contains("\r"))
    }

    @Test
    fun `completion fish through ScoopAi subcommand`() {
        val result = ScoopAi().test("completion fish")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("complete -c scoop-ai"))
        assertFalse(result.output.contains("\r"))
    }

    @Test
    fun `completion powershell through ScoopAi subcommand`() {
        val result = ScoopAi().test("completion powershell")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Register-ArgumentCompleter"))
        assertFalse(result.output.contains("\r"))
    }

    @Test
    fun `completion invalid shell shows error`() {
        val cmd = CompletionCommand()
        val result = cmd.test("invalid")
        assertTrue(result.statusCode != 0, "Invalid shell must fail")
    }
}
