package com.scoopai.cli

import com.scoopai.tools.DefaultToolRegistryFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CliRunner.run(args)
    DefaultToolRegistryFactory.cleanup()
    exitProcess(exitCode)
}
