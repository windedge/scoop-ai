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
 * the observer's enterPhase/exitPhase bracket for one phase execution and the
 * exit return value is the only duration a caller can observe.
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

    // ---------------------------------------------------------------------
    // repeated phase executions (verify / assist / repair run in loops)
    // ---------------------------------------------------------------------

    @Test
    fun `each enter-exit pair reports its own elapsed time`() {
        val clock = FakeClock()
        val telemetry = PhaseTelemetry(clock = clock::mark)

        telemetry.enter("verify")
        clock.now = 10L
        val first = telemetry.exit("verify")
        clock.now = 30L
        telemetry.enter("verify")
        clock.now = 55L
        val second = telemetry.exit("verify")

        assertEquals(10L, first, "the first pair measures only its own span")
        assertEquals(25L, second, "a re-entered phase measures from its new mark")
    }

    @Test
    fun `phases are measured independently of each other`() {
        val clock = FakeClock()
        val telemetry = PhaseTelemetry(clock = clock::mark)

        telemetry.enter("research")
        clock.now = 100L
        val research = telemetry.exit("research")
        clock.now = 140L
        telemetry.enter("analyze")
        clock.now = 200L
        val analyze = telemetry.exit("analyze")

        assertEquals(100L, research)
        assertEquals(60L, analyze)
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

    fun mark(): TimeMark = FakeTimeMark(this, now)
}

/** TimeMark remembering its own create time so later clock moves count as elapsed. */
private class FakeTimeMark(private val clock: FakeClock, private val startedAt: Long) : TimeMark {
    override fun elapsedNow(): Duration = (clock.now - startedAt).milliseconds
}
