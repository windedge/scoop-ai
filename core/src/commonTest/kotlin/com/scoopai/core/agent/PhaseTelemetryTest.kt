package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Unit tests for [PhaseTelemetry]: the start/duration store that replaces the
 * strategy's five per-phase TimeMark variables. Each enter/exit pair mirrors
 * the observer's enterPhase/exitPhase bracket for one phase execution.
 */
class PhaseTelemetryTest {

    // ---------------------------------------------------------------------
    // enter/exit pairing
    // ---------------------------------------------------------------------

    @Test
    fun `exit returns the elapsed duration of the matching enter`() {
        val telemetry = PhaseTelemetry()

        telemetry.enter("research")
        val elapsed = telemetry.exit("research")

        assertTrue(elapsed >= 0L, "elapsed must be non-negative, got $elapsed")
    }

    @Test
    fun `exit for a phase without enter returns zero`() {
        val telemetry = PhaseTelemetry()

        assertEquals(0L, telemetry.exit("analyze"), "no enter means no duration")
    }

    @Test
    fun `durationOf reports the last exited duration`() {
        val telemetry = PhaseTelemetry()

        telemetry.enter("generate")
        val elapsed = telemetry.exit("generate")

        assertEquals(elapsed, telemetry.durationOf("generate"))
    }

    @Test
    fun `durationOf returns zero for a phase that never ran`() {
        val telemetry = PhaseTelemetry()

        assertEquals(0L, telemetry.durationOf("verify"))
    }

    // ---------------------------------------------------------------------
    // repeated phase executions (verify / assist / repair run in loops)
    // ---------------------------------------------------------------------

    @Test
    fun `a second enter-exit pair overwrites the previous duration`() {
        val telemetry = PhaseTelemetry()

        telemetry.enter("verify")
        val first = telemetry.exit("verify")
        telemetry.enter("verify")
        val second = telemetry.exit("verify")

        assertTrue(first >= 0L && second >= 0L)
        assertEquals(second, telemetry.durationOf("verify"), "the latest pair wins")
    }

    @Test
    fun `durations maps every exited phase to its latest duration`() {
        val telemetry = PhaseTelemetry()

        telemetry.enter("research")
        val research = telemetry.exit("research")
        telemetry.enter("analyze")
        val analyze = telemetry.exit("analyze")

        assertEquals(mapOf("research" to research, "analyze" to analyze), telemetry.durations())
    }

    // ---------------------------------------------------------------------
    // durable clock injection (deterministic tests)
    // ---------------------------------------------------------------------

    @Test
    fun `injected clock supplies the marks so durations are deterministic`() {
        val clock = FakeClock()
        val telemetry = PhaseTelemetry(clock = clock::mark)

        telemetry.enter("research")
        clock.now = 1234L
        val elapsed = telemetry.exit("research")

        assertEquals(1234L, elapsed)
        assertEquals(1234L, telemetry.durationOf("research"))
    }

    @Test
    fun `exit leaves the start mark absent so a stray second exit returns zero`() {
        val telemetry = PhaseTelemetry(clock = FakeClock()::mark)

        telemetry.enter("assist")
        telemetry.exit("assist")

        assertEquals(0L, telemetry.exit("assist"), "double exit must not report a duration")
    }
}

/** Fake monotonic clock: one mutable offset shared by every mark it creates. */
private class FakeClock {
    var now: Long = 0L

    fun mark(): TimeMark = FakeTimeMark(this)
}

/** TimeMark reading the shared clock at elapsedNow, so later clock moves count. */
private class FakeTimeMark(private val clock: FakeClock) : TimeMark {
    override fun elapsedNow(): Duration = clock.now.milliseconds
}
