package com.mindfulfocus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests for the timing brain. No Android dependency, so they run with
 * `./gradlew test` (or Android Studio's Run Tests) without a device.
 *
 * These lock down the "timestamp, not ticking counter" contract: the same
 * snapshot evaluated at a later wall-clock time produces the same result no matter
 * how many times / how frequently we tick.
 */
class TimerEngineTest {

    private val base = 1_700_000_000_000L // arbitrary fixed epoch
    private val minute = 60_000L

    @Test
    fun intervalBell_schedulesFirstBellAfterInterval() {
        val settings = IntervalBellSettings(
            intervalMinutes = 10,
            enabledActiveWindow = false,
            enabledDnd = false
        )
        val snap = TimerEngine.startIntervalBell(settings, base)
        assertEquals(RunStatus.RUNNING, snap.status)
        assertEquals(base + 10 * minute, snap.nextBellEpochMs)
    }

    @Test
    fun intervalBell_tickRingsExactlyOncePerInterval() {
        val settings = IntervalBellSettings(intervalMinutes = 10, enabledActiveWindow = false, enabledDnd = false)
        var snap = TimerEngine.startIntervalBell(settings, base)

        // Before the bell is due: nothing.
        var r = TimerEngine.tick(snap, base + 9 * minute, intervalSettings = settings)
        assertTrue(r.events.isEmpty())
        snap = r.snapshot

        // At the bell: exactly one Ring.
        r = TimerEngine.tick(snap, base + 10 * minute, intervalSettings = settings)
        assertEquals(1, r.events.count { it is TimerEvent.Ring })
        snap = r.snapshot
        assertEquals(base + 20 * minute, snap.nextBellEpochMs)

        // Ticking again at the same instant must NOT double-fire.
        r = TimerEngine.tick(snap, base + 10 * minute, intervalSettings = settings)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun meditation_schedulesIntervalBellsAndEndsCorrectly() {
        val settings = MeditationSettings(
            sessionMinutes = 20,
            prepSeconds = 0,
            intervalBellMinutes = 5,
            startBell = false,
            intervalBell = true,
            endBell = true
        )
        var snap = TimerEngine.startMeditation(settings, base)
        // 4 interval bells at 5,10,15,20? The one at the very end (20) equals endEpoch and is excluded.
        assertEquals(listOf(base + 5 * minute, base + 10 * minute, base + 15 * minute), snap.pendingBellEpochs)
        assertEquals(base + 20 * minute, snap.phaseEndEpochMs)

        // Tick at end: finished.
        val r = TimerEngine.tick(snap, base + 20 * minute, meditationEndBell = true)
        assertTrue(r.events.any { it == TimerEvent.Finished })
        assertEquals(RunStatus.IDLE, r.snapshot.status)
    }

    @Test
    fun pomodoro_completesFocusAndCountsPomodoro() {
        val settings = PomodoroSettings(focusMinutes = 25, autoStartNextPhase = false)
        var snap = TimerEngine.startPomodoro(settings, base)
        assertEquals(PomodoroPhase.FOCUS, snap.pomodoroPhase)

        val r = TimerEngine.tick(snap, base + 25 * minute, pomodoroSettings = settings)
        assertEquals(1, r.snapshot.completedPomodoros)
        // autoStart=false -> waiting at boundary (no countdown).
        assertEquals(PomodoroPhase.SHORT_BREAK, r.snapshot.pomodoroPhase)
        assertEquals(0L, r.snapshot.phaseEndEpochMs)
    }

    @Test
    fun pomodoro_autoStartMovesIntoBreak() {
        val settings = PomodoroSettings(focusMinutes = 25, shortBreakMinutes = 5, autoStartNextPhase = true)
        val snap = TimerEngine.startPomodoro(settings, base)
        val r = TimerEngine.tick(snap, base + 25 * minute, pomodoroSettings = settings)
        assertEquals(PomodoroPhase.SHORT_BREAK, r.snapshot.pomodoroPhase)
        assertEquals(base + 25 * minute + 5 * minute, r.snapshot.phaseEndEpochMs)
    }

    @Test
    fun pauseResume_preservesRemainingViaTimestampShift() {
        val settings = PomodoroSettings(focusMinutes = 25)
        val snap = TimerEngine.startPomodoro(settings, base)

        // Pause 5 minutes in.
        val paused = TimerEngine.pause(snap, base + 5 * minute)
        assertEquals(RunStatus.PAUSED, paused.status)
        assertEquals(20 * minute, paused.pausedRemainingMs)

        // Stay paused for 10 minutes, then resume.
        val resumed = TimerEngine.resume(paused, base + 15 * minute)
        assertEquals(RunStatus.RUNNING, resumed.status)
        // End shifted by the 10-minute pause: original end (base+25m) + 10m.
        assertEquals(base + 35 * minute, resumed.phaseEndEpochMs)
        // 20 minutes still remain from resume moment.
        assertEquals(20 * minute, resumed.remainingMs(base + 15 * minute))
    }

    @Test
    fun randomGap_isWithinConfiguredBounds() {
        val rnd = Random(42)
        repeat(200) {
            val gap = TimerEngine.randomGapMs(10, 30, rnd)
            assertTrue(gap >= 10 * minute)
            assertTrue(gap <= 30 * minute)
        }
    }

    @Test
    fun blindBox_focusPhaseIsWithinConfiguredRange() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f)
        repeat(50) { seed ->
            val snap = TimerEngine.startRandomMindful(settings, base, Random(seed))
            assertEquals(RandomPhase.FOCUS, snap.randomPhase)
            assertEquals(RunStatus.RUNNING, snap.status)
            val duration = snap.phaseEndEpochMs - base
            assertTrue("duration=$duration", duration >= 3 * minute && duration <= 5 * minute)
            assertEquals(0, duration % 1000L)
        }
    }

    @Test
    fun blindBox_bellEndsFocusAndStartsRest() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, restSeconds = 10)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(7))
        val phaseEnd = snap.phaseEndEpochMs

        // Nothing due one second before the end of the focus phase.
        val before = TimerEngine.tick(snap, phaseEnd - 1000L, Random(7), randomSettings = settings)
        assertTrue(before.events.isEmpty())
        assertEquals(RandomPhase.FOCUS, before.snapshot.randomPhase)

        // At the boundary: a bell rings and the breathing rest begins.
        val at = TimerEngine.tick(snap, phaseEnd, Random(7), randomSettings = settings)
        assertTrue(at.events.any { it is TimerEvent.BlindBell })
        assertEquals(RandomPhase.REST, at.snapshot.randomPhase)
        assertEquals(1, at.snapshot.bellCount)
        assertEquals(10_000L, at.snapshot.phaseTotalMs)
        assertEquals(phaseEnd + 10_000L, at.snapshot.phaseEndEpochMs)
        // The whole focus phase is banked as "已专注".
        assertEquals(snap.phaseTotalMs, at.snapshot.focusAccumulatedMs)

        // When the breathing rest is over, a fresh focus phase is drawn.
        val afterRest = TimerEngine.tick(
            at.snapshot, at.snapshot.phaseEndEpochMs, Random(7), randomSettings = settings
        )
        assertEquals(RandomPhase.FOCUS, afterRest.snapshot.randomPhase)
        val next = afterRest.snapshot.phaseEndEpochMs - at.snapshot.phaseEndEpochMs
        assertTrue("next=$next", next >= 3 * minute && next <= 5 * minute)
    }

    @Test
    fun blindBox_capClosesSessionWithClosingBell() {
        // A one-minute cap that is shorter than the shortest focus phase.
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, capMinutes = 1)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(3))

        val result = TimerEngine.tick(
            snap, base + 2 * minute, Random(3), randomSettings = settings
        )
        assertTrue(result.events.any { it is TimerEvent.CloseBell })
        assertTrue(result.events.any { it == TimerEvent.Finished })
        assertEquals(RunStatus.IDLE, result.snapshot.status)
        assertEquals(RandomPhase.DONE, result.snapshot.randomPhase)
        assertEquals(0L, result.snapshot.phaseEndEpochMs)
        // Focus time is capped at the session cap.
        assertEquals(1 * minute, result.snapshot.focusMs(base + 2 * minute))
    }

    @Test
    fun blindBox_pauseDoesNotConsumeTheSessionCap() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, capMinutes = 90)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(11))
        val originalEnd = snap.phaseEndEpochMs

        // Run for 2 minutes, pause for 10, resume.
        val paused = TimerEngine.pause(snap, base + 2 * minute)
        assertEquals(RunStatus.PAUSED, paused.status)
        val resumed = TimerEngine.resume(paused, base + 12 * minute)
        assertEquals(originalEnd + 10 * minute, resumed.phaseEndEpochMs)
        // Paused time is not counted toward the cap.
        assertEquals(2 * minute, resumed.elapsedMs(base + 12 * minute))
        // No bell fires just because 10 minutes passed while paused.
        val tick = TimerEngine.tick(resumed, base + 12 * minute, Random(11), randomSettings = settings)
        assertTrue(tick.events.isEmpty())
        assertEquals(RandomPhase.FOCUS, tick.snapshot.randomPhase)
    }

    @Test
    fun nextTrigger_isZeroWhenNothingRuns() {
        val snap = TimerEngine.startIntervalBell(
            IntervalBellSettings(intervalMinutes = 10, enabledActiveWindow = false, enabledDnd = false),
            base
        )
        assertEquals(0L, TimerEngine.nextTriggerEpochMs(TimerEngine.stop(snap)))
    }

    @Test
    fun nextTrigger_isTheSoonestBellOrPhaseEnd() {
        // Interval bell: the only trigger is the next bell.
        val settings = IntervalBellSettings(intervalMinutes = 10, enabledActiveWindow = false, enabledDnd = false)
        val interval = TimerEngine.startIntervalBell(settings, base)
        assertEquals(interval.nextBellEpochMs, TimerEngine.nextTriggerEpochMs(interval))

        // Meditation: the earliest pending interval bell wins over the sitting end.
        val med = TimerEngine.startMeditation(
            MeditationSettings(
                sessionMinutes = 20,
                prepSeconds = 0,
                intervalBellMinutes = 5,
                startBell = false,
                intervalBell = true,
                endBell = true
            ),
            base
        )
        val medNext = TimerEngine.nextTriggerEpochMs(med)
        assertEquals(med.pendingBellEpochs.min(), medNext)
        assertTrue(medNext < med.phaseEndEpochMs)

        // Blind box: the current focus phase end.
        val blind = TimerEngine.startRandomMindful(
            RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f), base, Random(5)
        )
        assertEquals(blind.phaseEndEpochMs, TimerEngine.nextTriggerEpochMs(blind))
    }

    @Test
    fun ringEvent_carriesTheSessionType() {
        val settings = IntervalBellSettings(intervalMinutes = 10, enabledActiveWindow = false, enabledDnd = false)
        val snap = TimerEngine.startIntervalBell(settings, base)
        val r = TimerEngine.tick(snap, base + 10 * minute, intervalSettings = settings)
        val ring = r.events.filterIsInstance<TimerEvent.Ring>().single()
        assertEquals(SessionType.INTERVAL_BELL, ring.type)
    }
}
