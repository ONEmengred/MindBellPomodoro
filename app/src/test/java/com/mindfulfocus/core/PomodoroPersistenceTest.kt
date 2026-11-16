package com.mindfulfocus.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PomodoroPersistenceTest {
    private val base = 1_700_000_000_000L
    private val settings = PomodoroSettings(focusMinutes = 1)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun restore(s: SessionSnapshot): SessionSnapshot =
        json.decodeFromString(SessionSnapshot.serializer(), json.encodeToString(SessionSnapshot.serializer(), s))

    @Test fun oldSnapshotWithoutTaskNameStillLoads() {
        val restored = json.decodeFromString(SessionSnapshot.serializer(), """{"type":"POMODORO","status":"IDLE"}""")
        assertEquals("", restored.taskName)
    }

    @Test fun restoredFocusWritesTaskNameOnCompletion() {
        val snap = restore(TimerEngine.startPomodoro(settings, base, "阅读 Kotlin 📖"))
        val result = TimerEngine.tick(snap, snap.phaseEndEpochMs, pomodoroSettings = settings)
        val draft = SessionAccounting.draftsForTick(snap, result).focuses.single()
        assertEquals("阅读 Kotlin 📖", draft.taskName)
        assertTrue(draft.completed)
        assertEquals(snap.taskName, restore(result.snapshot).taskName)
    }

    @Test fun pausedRestoredFocusWritesTaskNameOnStop() {
        val snap = TimerEngine.startPomodoro(settings, base, "写报告")
        val paused = restore(TimerEngine.pause(snap, base + 20_000L))
        val draft = SessionAccounting.draftsForStop(paused, base + 90_000L).focuses.single()
        assertEquals("写报告", draft.taskName)
        assertEquals(20_000L, draft.focusedMs)
        assertFalse(draft.completed)
        assertEquals("写报告", restore(TimerEngine.stop(paused)).taskName)
    }

    @Test fun automaticAndManualNextPhasesKeepTaskName() {
        for (auto in listOf(true, false)) {
            val config = settings.copy(autoStartNextPhase = auto)
            var snap = TimerEngine.startPomodoro(config, base, "练习")
            snap = restore(TimerEngine.tick(snap, snap.phaseEndEpochMs, pomodoroSettings = config).snapshot)
            if (!auto) snap = TimerEngine.startPomodoroPhase(config, snap.pomodoroPhase, snap.completedPomodoros, base + 61_000L, snap.taskName)
            assertEquals(PomodoroPhase.SHORT_BREAK, snap.pomodoroPhase)
            assertEquals("练习", snap.taskName)
            val result = TimerEngine.tick(snap, snap.phaseEndEpochMs, pomodoroSettings = config)
            assertEquals("练习", result.snapshot.taskName)
            assertTrue(SessionAccounting.draftsForTick(snap, result).isEmpty)
        }
    }

    @Test fun notificationOffStillRingsAndRecordsFocus() {
        val config = settings.copy(notify = false)
        val snap = TimerEngine.startPomodoro(config, base, "专注")
        val result = TimerEngine.tick(snap, snap.phaseEndEpochMs, pomodoroSettings = config)
        assertFalse(result.events.filterIsInstance<TimerEvent.PhaseChanged>().single().notify)
        assertEquals(1, result.events.filterIsInstance<TimerEvent.Ring>().size)
        assertTrue(SessionAccounting.draftsForTick(snap, result).focuses.single().completed)
    }

    @Test fun alertSettingsReachEventIndependently() {
        val config = settings.copy(sound = BellSound.WOOD_BLOCK, volumePercent = 0, vibrate = false, notify = true)
        val snap = TimerEngine.startPomodoro(config, base)
        val result = TimerEngine.tick(snap, snap.phaseEndEpochMs, pomodoroSettings = config,
            pomoSound = Triple(config.sound, config.volumePercent, config.vibrate))
        val ring = result.events.filterIsInstance<TimerEvent.Ring>().single()
        assertEquals(BellSound.WOOD_BLOCK, ring.sound)
        assertEquals(0, ring.volumePercent)
        assertFalse(ring.vibrate)
        assertTrue(result.events.filterIsInstance<TimerEvent.PhaseChanged>().single().notify)
    }

    @Test fun blindBoxClosingHapticFollowsItsOwnSetting() {
        for (vibrate in listOf(true, false)) {
            val config = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 3f, capMinutes = 1, vibrate = vibrate)
            val snap = TimerEngine.startRandomMindful(config, base, kotlin.random.Random(1))
            val result = TimerEngine.tick(snap, base + 60_000L, randomSettings = config)
            assertEquals(vibrate, result.events.filterIsInstance<TimerEvent.CloseBell>().single().vibrate)
        }
    }

    @Test fun stalePhaseIsSilentButStillRecorded() {
        val snap = TimerEngine.startPomodoro(settings, base, "迟到恢复")
        val result = TimerEngine.tick(snap, snap.phaseEndEpochMs + TimerEngine.CATCH_UP_GRACE_MS + 1, pomodoroSettings = settings)
        assertFalse(result.events.filterIsInstance<TimerEvent.PhaseChanged>().single().notify)
        assertTrue(result.events.none { it is TimerEvent.Ring })
        assertEquals("迟到恢复", SessionAccounting.draftsForTick(snap, result).focuses.single().taskName)
    }
}
