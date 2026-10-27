package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [usableExplanation] and [keepExplanation]: the pure functions
 * that own the "non-blank explanations only" rule shared by the generate and
 * assist/repair exit edges of the strategy.
 */
class UsableExplanationTest {

    // ---------------------------------------------------------------------
    // usableExplanation: null/blank guard
    // ---------------------------------------------------------------------

    @Test
    fun `null explanation is not usable`() {
        assertNull(usableExplanation(null))
    }

    @Test
    fun `blank explanation is not usable`() {
        assertNull(usableExplanation("   "))
    }

    @Test
    fun `usable explanation is trimmed`() {
        assertEquals("ok", usableExplanation("  ok  "))
    }

    // ---------------------------------------------------------------------
    // keepExplanation: non-blank overwrite rule
    // ---------------------------------------------------------------------

    @Test
    fun `usable explanation overwrites the previous value`() {
        var stored: String? = "old"
        stored = keepExplanation(stored, usableExplanation("new"))

        assertEquals("new", stored)
    }

    @Test
    fun `blank explanation keeps the previous value`() {
        val stored = "previous"
        val next = keepExplanation(stored, usableExplanation("  "))

        assertEquals("previous", next)
    }

    @Test
    fun `null explanation keeps the previous value`() {
        val stored = "previous"
        val next = keepExplanation(stored, usableExplanation(null))

        assertEquals("previous", next)
    }
}
