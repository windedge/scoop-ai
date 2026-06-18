package com.scoopai.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class ConsoleProgressTest {

    // ---------------------------------------------------------------
    // Enabled state
    // ---------------------------------------------------------------

    @Test
    fun `phase prints carriage return and name`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = true)
        progress.phase("research")
        assertEquals("\r[phase] research", baos.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `done prints system line separator`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = true)
        progress.done()
        assertEquals(System.lineSeparator(), baos.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `start prints initializing line`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = true)
        progress.start()
        assertEquals("\r[phase] initializing...", baos.toString(StandardCharsets.UTF_8.name()))
    }

    // ---------------------------------------------------------------
    // Disabled state
    // ---------------------------------------------------------------

    @Test
    fun `disabled phase prints nothing`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = false)
        progress.phase("research")
        assertEquals("", baos.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `disabled done prints nothing`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = false)
        progress.done()
        assertEquals("", baos.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `disabled start prints nothing`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = false)
        progress.start()
        assertEquals("", baos.toString(StandardCharsets.UTF_8.name()))
    }

    // ---------------------------------------------------------------
    // disable() at runtime
    // ---------------------------------------------------------------

    @Test
    fun `disable stops subsequent output`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = true)
        progress.phase("research")
        progress.disable()
        progress.phase("analyze")
        assertEquals("\r[phase] research", baos.toString(StandardCharsets.UTF_8.name()))
    }

    // ---------------------------------------------------------------
    // Multiple phases in sequence
    // ---------------------------------------------------------------

    @Test
    fun `multiple phase calls emit all writes with carriage returns`() {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
        val progress = ConsoleProgress(output = ps, enabled = true)
        progress.phase("research")
        progress.phase("analyze")
        progress.phase("generate")
        progress.phase("refine")
        progress.done()
        val output = baos.toString(StandardCharsets.UTF_8.name())
        // ByteArrayOutputStream captures all writes (\r is just a character).
        // The \r before each phase means a TTY will overwrite the line,
        // but the buffer still contains all writes chronologically.
        val expected = "\r[phase] research\r[phase] analyze\r[phase] generate\r[phase] refine${System.lineSeparator()}"
        assertEquals(expected, output)
    }
}
