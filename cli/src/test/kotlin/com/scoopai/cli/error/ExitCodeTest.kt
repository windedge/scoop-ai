package com.scoopai.cli.error

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExitCodeTest {

    @Test
    fun `has 8 enum values`() {
        assertEquals(8, ExitCode.entries.size)
    }

    @Test
    fun `all codes are unique`() {
        val codes = ExitCode.entries.map { it.code }
        assertEquals(codes.toSet().size, codes.size)
    }

    @Test
    fun `SUCCESS code is 0`() {
        assertEquals(0, ExitCode.SUCCESS.code)
    }

    @Test
    fun `USAGE code is 2`() {
        assertEquals(2, ExitCode.USAGE.code)
    }

    @Test
    fun `NO_ASSETS code differs from USAGE`() {
        assertNotEquals(ExitCode.USAGE.code, ExitCode.NO_ASSETS.code)
    }

    @Test
    fun `all non-SUCCESS codes are at least 1`() {
        ExitCode.entries.filter { it != ExitCode.SUCCESS }.forEach { code ->
            assertTrue(code.code >= 1, "${code.name}.code should be >= 1, got ${code.code}")
        }
    }
}
