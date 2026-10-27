package com.scoopai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionCallbacks] and its builder. The builder keeps the
 * public flat-callback construction surface; the aggregate is what travels
 * through the relay layers (factory -> generator -> strategy).
 */
class SessionCallbacksTest {

    // ---------------------------------------------------------------------
    // SessionCallbacks.empty
    // ---------------------------------------------------------------------

    @Test
    fun `empty has all callbacks null`() {
        val callbacks = SessionCallbacks.empty
        assertNull(callbacks.onPhase)
        assertNull(callbacks.onPhaseOutput)
        assertNull(callbacks.onToolActivity)
    }

    @Test
    fun `empty is usable without crashing when invoked fields are guarded`() {
        val callbacks = SessionCallbacks.empty
        assertNull(callbacks.onPhase?.invoke("research"))
    }

    // ---------------------------------------------------------------------
    // Builder registration
    // ---------------------------------------------------------------------

    @Test
    fun `builder collects the registered callbacks`() {
        val phases = mutableListOf<String>()
        val outputs = mutableListOf<Pair<String, String>>()
        val activities = mutableListOf<ToolActivity>()

        val callbacks = SessionCallbacks.Builder().apply {
            onPhase { phases.add(it) }
            onPhaseOutput { phase, output -> outputs.add(phase to output) }
            onToolActivity { activities.add(it) }
        }.build()

        callbacks.onPhase?.invoke("research")
        callbacks.onPhaseOutput?.invoke("analyze", "{}")
        callbacks.onToolActivity?.invoke(ToolActivity.Started("t", "args"))

        assertEquals(listOf("research"), phases)
        assertEquals(listOf("analyze" to "{}"), outputs)
        assertTrue(activities.isNotEmpty())
    }

    @Test
    fun `unregistered callbacks stay null on the built aggregate`() {
        val callbacks = SessionCallbacks.Builder().apply { onPhase { } }

        assertNull(callbacks.onPhaseOutput)
        assertNull(callbacks.onToolActivity)
    }

    @Test
    fun `builder without any registration yields the empty aggregate`() {
        val callbacks = SessionCallbacks.Builder().apply { }

        assertNull(callbacks.onPhase)
        assertNull(callbacks.onPhaseOutput)
        assertNull(callbacks.onToolActivity)
    }

    // ---------------------------------------------------------------------
    // Builder late registration (dynamic callback sets)
    // ---------------------------------------------------------------------

    @Test
    fun `builder allows registering after creation`() {
        val builder = SessionCallbacks.Builder()
        val phases = mutableListOf<String>()
        builder.onPhase { phases.add(it) }

        val callbacks = builder.build()

        callbacks.onPhase?.invoke("verify")
        assertEquals(listOf("verify"), phases)
    }
}
