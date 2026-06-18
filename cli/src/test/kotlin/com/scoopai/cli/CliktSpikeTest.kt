package com.scoopai.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliktSpikeTest {

    @Test
    fun `clikt basic command parses options`() {
        class Hello : CliktCommand(name = "hello") {
            val name by option("--name")
            override fun run() {
                echo("Hello, $name!")
            }
        }
        val result = Hello().test("--name world")
        assertEquals("Hello, world!\n", result.output)
    }

    @Test
    fun `clikt subcommand support`() {
        class Top : CliktCommand(name = "top") {
            override fun run() {
                echo("top command")
            }
        }
        class Sub : CliktCommand(name = "sub") {
            override fun run() {
                echo("sub command")
            }
        }
        // In clikt 4.x, parent command runs before dispatching to subcommand
        Top().subcommands(Sub()).test("sub").apply {
            assertTrue(output.contains("sub command"))
        }
    }

    @Test
    fun `clikt version is resolvable`() {
        // Verify clikt classes are on classpath
        assertTrue(CliktCommand::class.qualifiedName?.contains("clikt") == true)
    }
}
