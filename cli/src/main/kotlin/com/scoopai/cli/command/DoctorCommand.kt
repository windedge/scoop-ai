package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.scoopai.cli.diagnostic.HealthCheck
import com.scoopai.cli.diagnostic.Status
import com.scoopai.cli.diagnostic.defaultHealthChecks
import kotlinx.coroutines.runBlocking

/**
 * Run environment diagnostics.
 *
 * Performs 7 health checks (JDK version, OS, Scoop install, API key,
 * cache directory, write permissions, scoop-ai version) and reports
 * results in a consistent table format.
 *
 * @param healthChecks List of checks to run (default: [defaultHealthChecks]).
 */
class DoctorCommand(
    private val healthChecks: List<HealthCheck> = defaultHealthChecks(),
) : CliktCommand(
    name = "doctor",
    help = "Run environment diagnostics to identify configuration issues",
) {
    override fun run() {
        var failCount = 0
        var warnCount = 0

        for (check in healthChecks) {
            val result = runBlocking { check.run() }
            val statusStr = result.status.name.padEnd(5)
            echo("[$statusStr] ${check.name}: ${result.message}")
            when (result.status) {
                Status.FAIL -> failCount++
                Status.WARN -> warnCount++
                Status.OK -> {}
            }
        }

        val total = healthChecks.size
        val okCount = total - failCount - warnCount
        echo("")

        if (failCount > 0) {
            val noun = if (failCount == 1) "failure" else "failures"
            echo("$failCount $noun, $warnCount warning(s), $okCount/$total checks passed")
            throw ProgramResult(1)
        } else if (warnCount > 0) {
            val noun2 = if (warnCount == 1) "warning" else "warnings"
            echo("$failCount failure(s), $warnCount $noun2, $okCount/$total checks passed")
        } else {
            echo("All $total checks passed")
        }
    }
}
