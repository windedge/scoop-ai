package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.scoopai.cli.Version

/**
 * Display version information.
 *
 * Outputs the application name, version, and commit hash:
 *   scoop-ai 0.1.0 (commit xxxxxxx)
 */
class VersionCommand : CliktCommand(
    name = "version",
    help = "Show version information",
) {
    override fun run() {
        echo(Version.fullVersion())
    }
}
