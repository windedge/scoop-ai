package com.scoopai.tools.interaction

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeAskUserTest {

    @AfterEach
    fun tearDown() {
        SafeAskUser.nonInteractiveOverride = null
        System.clearProperty("SCOOP_AI_NON_INTERACTIVE")
    }

    @Test
    fun `isNonInteractive false by default when no override or property`() {
        // Force false via override: env may be set in CI.
        SafeAskUser.nonInteractiveOverride = false
        assertFalse(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `nonInteractiveOverride true takes precedence over property false`() {
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "false")
        SafeAskUser.nonInteractiveOverride = true
        assertTrue(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `nonInteractiveOverride false takes precedence over property true`() {
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
        SafeAskUser.nonInteractiveOverride = false
        assertFalse(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `property true enables nonInteractive when override is null`() {
        SafeAskUser.nonInteractiveOverride = null
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
        assertTrue(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `execute returns default response in nonInteractive mode without blocking`() {
        SafeAskUser.nonInteractiveOverride = true
        val result = runBlocking {
            SafeAskUser.execute(SafeAskUser.Args("Pick a license?"))
        }
        assertEquals(SafeAskUser.DEFAULT_RESPONSE, result)
    }
}
