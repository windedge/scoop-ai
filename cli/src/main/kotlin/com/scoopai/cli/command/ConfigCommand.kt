package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.subcommands

/**
 * Configuration management commands.
 *
 * Subcommands:
 * - [ConfigShowCommand]: Display current runtime configuration
 */
class ConfigCommand : CliktCommand(
    name = "config",
    help = "Manage runtime configuration",
) {
    init {
        subcommands(ConfigShowCommand())
    }

    override fun run() {
        // If no subcommand, show help for the config command
        if (currentContext.invokedSubcommand != null) return
        throw PrintHelpMessage(currentContext)
    }
}
