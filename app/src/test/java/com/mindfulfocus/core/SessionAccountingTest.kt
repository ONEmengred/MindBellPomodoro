package com.mindfulfocus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 记账规则的单测。
 *
 * 这一层以前是「猜」出来的（service 看快照状态反推刚发生了什么），也正是
 * 「完成番茄数恒为 0」「冥想时长用计划值冒充」「盲盒专注不入库」三个 bug 的来源。
 * 现在规则是纯函数，可以用普通 JVM 单测钉死，不需要设备也不需要数据库。
 */
class SessionAccountingTest {

    private val base = 1_700_000_000_000L
    private val minute = 60_000L

    private fun meditationSettings(sessionMinutes: Int, prepSeconds: Int) = MeditationSettings(
        sessionMinutes = sessionMinutes,
        prepSeconds = prepSeconds,
        intervalBellMinutes = 0,
        startBell = false,
        intervalBell = false,
        endBell = true
    )

    // ------------------------------------------------------------------
    // tick：自然结束 / 阶段完成
    // ------------------------------------------------------------------

    @Test
    fun tick_meditationFinished_recordsCompletedSitting() {
        val settings = meditationSettings(sessionMinutes = 10, prepSeconds = 10)
        val snap = TimerEngine.startMeditation(settings, base)
        val result = TimerEngine.tick(snap, snap.phaseEndEpochMs)

        val drafts = SessionAccounting.draftsForTick(snap, result)
        assertEquals(1, drafts.meditations.size)
        val d = drafts.meditations.single()
        assertEquals(10 * minute, d.practicedMs)
        assertEquals(10 * minute, d.plannedMs)
        assertEquals(base, d.startedAtEpochMs)
        assertTrue(d.completed)
        assertTrue(drafts.focuses.isEmpty())
    }

    @Test
    fun tick_meditationStillRunning_recordsNothing() {
        val settings = meditationSettings(sessionMinutes = 10, prepSeconds = 0)
        val snap = TimerEngine.startMeditation(settings, base)
        val result = TimerEngine.tick(snap, base + 3 * minute)
        assertTrue(SessionAccounting.draftsForTick(snap, result).isEmpty)
    }

    @Test
    fun tick_pomodoroFocusCompleted_recordsOneFocusRound() {
        // 这就是「完成番茄数恒为 0」的修复点：以前没有任何一条 completed 的写入路径。
        val settings = PomodoroSettings(focusMinutes = 25, autoStartNextPhase = false)
        val snap = TimerEngine.startPomodoro(settings, base)
        val result = TimerEngine.tick(snap, base + 25 * minute, pomodoroSettings = settings)

        val drafts = SessionAccounting.draftsForTick(snap, result)
        assertEquals(1, drafts.focuses.size)
        val f = drafts.focuses.single()
        assertEquals(FocusOrigin.POMODORO, f.origin)
        assertEquals(25 * minute, f.focusedMs)
        assertEquals(base, f.startedAtEpochMs)
        assertTrue(f.completed)
        assertTrue(drafts.meditations.isEmpty())
    }

    @Test
    fun tick_pomodoroBreakCompleted_recordsNothing() {
        // autoStart = true 会立刻进入休息；休息跑完不该产生番茄记录。
        val settings = PomodoroSettings(
            focusMinutes = 25, shortBreakMinutes = 5, autoStartNextPhase = true
        )
        val afterFocus = TimerEngine.tick(
            TimerEngine.startPomodoro(settings, base), base + 25 * minute, pomodoroSettings = settings
        ).snapshot
        assertEquals(PomodoroPhase.SHORT_BREAK, afterFocus.pomodoroPhase)

        val result = TimerEngine.tick(
            afterFocus, afterFocus.phaseEndEpochMs, pomodoroSettings = settings
        )
        assertTrue(result.events.any { it is TimerEvent.PhaseChanged })
        assertTrue(SessionAccounting.draftsForTick(afterFocus, result).focuses.isEmpty())
    }

    @Test
    fun tick_blindBoxAtCap_recordsMindfulFocus() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, capMinutes = 1)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(3))
        val result = TimerEngine.tick(snap, base + 1 * minute, Random(3), randomSettings = settings)

        val drafts = SessionAccounting.draftsForTick(snap, result)
        assertEquals(1, drafts.focuses.size)
        val f = drafts.focuses.single()
        assertEquals(FocusOrigin.MINDFUL, f.origin)
        assertEquals(1 * minute, f.focusedMs)
        assertEquals(base, f.startedAtEpochMs)
        assertTrue(f.completed)
    }

    @Test
    fun tick_intervalBell_recordsNothing() {
        val settings = IntervalBellSettings(
            intervalMinutes = 10, enabledActiveWindow = false, enabledDnd = false
        )
        val snap = TimerEngine.startIntervalBell(settings, base)
        val result = TimerEngine.tick(snap, base + 30 * minute, intervalSettings = settings)
        assertTrue(result.events.isNotEmpty())
        assertTrue(SessionAccounting.draftsForTick(snap, result).isEmpty)
    }

    // ------------------------------------------------------------------
    // stop：中途结束
    // ------------------------------------------------------------------

    @Test
    fun stop_meditationDuringPrep_recordsNothing() {
        val settings = meditationSettings(sessionMinutes = 10, prepSeconds = 10)
        val snap = TimerEngine.startMeditation(settings, base)
        // 还在调姿势的准备阶段就退出 —— 不算一次静坐。
        val drafts = SessionAccounting.draftsForStop(snap, base + 5_000L)
        assertTrue(drafts.isEmpty)
    }

    @Test
    fun stop_meditationMidSitting_recordsOnlyRealSittingTime() {
        val settings = meditationSettings(sessionMinutes = 10, prepSeconds = 10)
        val snap = TimerEngine.startMeditation(settings, base)
        val drafts = SessionAccounting.draftsForStop(snap, base + 10_000L + 2 * minute)

        val d = drafts.meditations.single()
        assertEquals(2 * minute, d.practicedMs)
        assertEquals(10 * minute, d.plannedMs)
        // 记录的是会话开始时刻（含准备阶段），方便用户对上墙上的时间。
        assertEquals(base, d.startedAtEpochMs)
        assertTrue(!d.completed)
    }

    @Test
    fun stop_pomodoroMidFocus_recordsPartialFocus() {
        val settings = PomodoroSettings(focusMinutes = 25)
        val snap = TimerEngine.startPomodoro(settings, base)
        val drafts = SessionAccounting.draftsForStop(snap, base + 10 * minute)

        val f = drafts.focuses.single()
        assertEquals(FocusOrigin.POMODORO, f.origin)
        assertEquals(10 * minute, f.focusedMs)
        assertEquals(base, f.startedAtEpochMs)
        assertTrue(!f.completed)
    }

    @Test
    fun stop_pomodoroWaitingAtBoundary_recordsNothing() {
        // 焦点轮已经自然跑完（PhaseChanged 已记过账），此刻处于边界等待态。
        val settings = PomodoroSettings(focusMinutes = 25, autoStartNextPhase = false)
        val boundary = TimerEngine.tick(
            TimerEngine.startPomodoro(settings, base), base + 25 * minute, pomodoroSettings = settings
        ).snapshot
        assertEquals(0L, boundary.phaseEndEpochMs)

        assertTrue(SessionAccounting.draftsForStop(boundary, base + 26 * minute).isEmpty)
        // 即便旧快照里还留着累计时长，也不该重复记一轮。
        val legacy = boundary.copy(elapsedAccumulatedMs = 25 * minute, status = RunStatus.RUNNING)
        assertTrue(SessionAccounting.draftsForStop(legacy, base + 26 * minute).isEmpty)
    }

    @Test
    fun stop_pomodoroDuringBreak_recordsNothing() {
        val settings = PomodoroSettings(
            focusMinutes = 25, shortBreakMinutes = 5, autoStartNextPhase = true
        )
        val inBreak = TimerEngine.tick(
            TimerEngine.startPomodoro(settings, base), base + 25 * minute, pomodoroSettings = settings
        ).snapshot
        assertTrue(SessionAccounting.draftsForStop(inBreak, base + 27 * minute).isEmpty)
    }

    @Test
    fun stop_blindBoxMidFocus_recordsPartialFocus() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, capMinutes = 90)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(11))
        val drafts = SessionAccounting.draftsForStop(snap, base + 1 * minute)

        val f = drafts.focuses.single()
        assertEquals(FocusOrigin.MINDFUL, f.origin)
        assertEquals(1 * minute, f.focusedMs)
        assertTrue(!f.completed)
    }

    @Test
    fun stop_blindBoxDuringRest_recordsFocusSoFar() {
        val settings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, restSeconds = 10, capMinutes = 90)
        val snap = TimerEngine.startRandomMindful(settings, base, Random(7))
        val rest = TimerEngine.tick(
            snap, snap.phaseEndEpochMs, Random(7), randomSettings = settings
        ).snapshot
        assertEquals(RandomPhase.REST, rest.randomPhase)

        val f = SessionAccounting.draftsForStop(rest, rest.phaseEndEpochMs).focuses.single()
        assertEquals(snap.phaseTotalMs, f.focusedMs)
    }

    @Test
    fun stop_afterNaturalFinish_recordsNothing() {
        // 自然结束时会话已被引擎重置为 IDLE，再按停止不能写第二行。
        val settings = meditationSettings(sessionMinutes = 10, prepSeconds = 0)
        val snap = TimerEngine.startMeditation(settings, base)
        val finished = TimerEngine.tick(snap, snap.phaseEndEpochMs).snapshot
        assertEquals(RunStatus.IDLE, finished.status)
        assertTrue(SessionAccounting.draftsForStop(finished, base + 10 * minute).isEmpty)

        val blindSettings = RandomMindfulSettings(minMinutes = 3f, maxMinutes = 5f, capMinutes = 1)
        val blind = TimerEngine.startRandomMindful(blindSettings, base, Random(3))
        val done = TimerEngine.tick(blind, base + 1 * minute, Random(3), randomSettings = blindSettings).snapshot
        assertEquals(RandomPhase.DONE, done.randomPhase)
        assertTrue(SessionAccounting.draftsForStop(done, base + 2 * minute).isEmpty)
    }

    @Test
    fun stop_neverStarted_recordsNothing() {
        assertTrue(SessionAccounting.draftsForStop(SessionSnapshot(SessionType.POMODORO), base).isEmpty)
        assertTrue(SessionAccounting.draftsForStop(SessionSnapshot(SessionType.MEDITATION), base).isEmpty)
    }
}
