package com.scoopai.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError as CliktUsageError
import com.scoopai.cli.error.CliError
import com.scoopai.cli.error.ExitCode
import com.scoopai.cli.error.classifyException

/** Known subcommand names for URL routing. */
private val SUBCOMMANDS = setOf("generate", "doctor", "config", "completion", "version")

/**
 * Minimal CLI runner that wraps clikt [ScoopAi].
 *
 * Responsibilities:
 * 1. Normalize args: prepend "generate" for bare URLs (`scoop-ai <url>`)
 * 2. Parse via clikt [ScoopAi] and return an exit code
 * 3. Catch clikt exceptions ([ProgramResult], [PrintHelpMessage], usage errors)
 *    and convert to [ExitCode] for the JVM
 * 4. Catch application errors ([CliError]) and unexpected exceptions,
 *    classify them, and return the matching exit code
 */
object CliRunner {

    /**
     * Parse [args] and execute the appropriate subcommand.
     * @return exit code (0 for success, non-zero for errors).
     */
    fun run(args: Array<String>): Int {
        val adjusted = normalizeArgs(args)
        return runCli(adjusted)
    }

    /**
     * Route bare URL arguments to `generate` subcommand.
     *
     * - Empty args → pass through (top-level help)
     * - First arg is a known subcommand → pass through
     * - First arg starts with `--` (global option like --version, --help) → pass through
     * - Everything else → prepend "generate"
     */
    internal fun normalizeArgs(args: Array<String>): Array<String> {
        if (args.isEmpty()) return args
        val first = args.first()
        if (first in SUBCOMMANDS) return args
        if (first.startsWith("--")) return args
        return arrayOf("generate", *args)
    }

    /**
     * Execute clikt [ScoopAi] with the given args.
     */
    internal fun runCli(args: Array<String>): Int {
        val cmd = ScoopAi()
        return try {
            cmd.parse(args.toList())
            ExitCode.SUCCESS.code
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: PrintHelpMessage) {
            ExitCode.SUCCESS.code
        } catch (e: CliktUsageError) {
            ExitCode.USAGE.code
        } catch (e: com.github.ajalt.clikt.core.CliktError) {
            e.statusCode
        } catch (e: CliError) {
            System.err.println("ERROR: ${e::class.simpleName}: ${e.message}")
            e.exit.code
        } catch (e: Exception) {
            val cliError = classifyException(e)
            System.err.println("ERROR: ${cliError::class.simpleName}: ${cliError.message}")
            cliError.exit.code
        }
    }
}
