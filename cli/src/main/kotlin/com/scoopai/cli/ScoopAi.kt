package com.scoopai.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.scoopai.cli.command.CompletionCommand
import com.scoopai.cli.command.ConfigCommand
import com.scoopai.cli.command.DoctorCommand
import com.scoopai.cli.command.GenerateCommand
import com.scoopai.cli.command.VersionCommand

/**
 * Top-level clikt entry point for scoop-ai.
 *
 * Registers subcommands:
 * - [GenerateCommand]: `scoop-ai generate <url>` or `scoop-ai <url>` (via Main.kt routing)
 * - [DoctorCommand]: `scoop-ai doctor`
 * - [ConfigCommand]: `scoop-ai config show`
 * - [CompletionCommand]: `scoop-ai completion bash|zsh|fish|powershell`
 *
 * This command has NO positional arguments of its own, so subcommand names
 * like "doctor" are correctly dispatched rather than consumed as a URL.
 */
class ScoopAi : CliktCommand(
    name = "scoop-ai",
    help = "Generate Scoop manifests from GitHub project URLs",
    invokeWithoutSubcommand = true,
) {
    init {
        versionOption(Version.fullVersion(), message = { it })
        // Disable ANSI color codes in --help and --version output.
        // Clikt 4.x uses Mordant (truecolor Terminal) by default; setting
        // AnsiLevel.NONE on the root context propagates to all subcommands.
        context {
            terminal = Terminal(AnsiLevel.NONE)
        }
        subcommands(
            GenerateCommand(),
            DoctorCommand(),
            ConfigCommand(),
            CompletionCommand(),
            VersionCommand(),
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand != null) return

        // No subcommand was dispatched. If we reach here without any args
        // (e.g. `scoop-ai`), show the top-level help.
        // If someone explicitly passes flags like --help before a subcommand,
        // clikt handles that before reaching run().
        throw PrintHelpMessage(currentContext)
    }
}
