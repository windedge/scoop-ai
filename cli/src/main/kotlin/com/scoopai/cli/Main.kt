package com.scoopai.cli

import com.scoopai.tools.DefaultToolRegistryFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Suppress kotlin-logging startup message that prints directly to stdout
    // (bypassing SLF4J/Logback), avoiding corruption of JSON output.
    KotlinLoggingConfiguration.logStartupMessage = false
    val exitCode = CliRunner.run(args)
    DefaultToolRegistryFactory.cleanup()
    exitProcess(exitCode)
}
