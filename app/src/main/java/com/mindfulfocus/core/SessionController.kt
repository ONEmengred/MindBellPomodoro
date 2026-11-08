package com.mindfulfocus.core

import android.util.Log
import com.mindfulfocus.audio.BellPlayer
import com.mindfulfocus.audio.ToneSynth
import com.mindfulfocus.audio.VibrationManager
import com.mindfulfocus.data.AppDataStore
import com.mindfulfocus.data.HistoryDao
import com.mindfulfocus.data.MeditationRecord
import com.mindfulfocus.data.PomodoroRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The single command surface every UI screen and the foreground service share.
 *
 * It applies [TimerEngine] transitions, persists the affected snapshot only, and
 * delivers the physical side effects (sound, vibration, history). Because it always
 * targets exactly one [SessionType] per call, the four sessions stay isolated.
 *
 * [now] is injected everywhere so it can be faked in unit tests.
 */
class SessionController(
    private val store: AppDataStore,
    private val history: HistoryDao,
    private val bellPlayer: BellPlayer,
    private val vibration: VibrationManager,
    private val synth: ToneSynth,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default
) {

    // ---------- observed state ----------
    fun observeSession(type: SessionType): Flow<SessionSnapshot> = store.observeSession(type)
    fun observeAllSessions(): Flow<Map<SessionType, SessionSnapshot>> = store.observeAllSessions()

    /**
     * Serialises everything that both reads and writes snapshots.
     *
     * The 1s service loop and [com.mindfulfocus.service.AlarmReceiver] can call
     * [tickAll] at the same instant; without this lock both would see the same
     * RUNNING snapshot and bank the same phase twice (double bell + double history row).
     */
    private val mutex = Mutex()

    val intervalBellSettings get() = store.observeIntervalBellSettings()
    val meditationSettings get() = store.observeMeditationSettings()
    val randomMindfulSettings get() = store.observeRandomMindfulSettings()
    val pomodoroSettings get() = store.observePomodoroSettings()

    // ---------- settings writers ----------
    suspend fun saveIntervalBell(s: IntervalBellSettings) = store.saveIntervalBellSettings(s)
    suspend fun saveMeditation(s: MeditationSettings) = store.saveMeditationSettings(s)
    suspend fun saveRandomMindful(s: RandomMindfulSettings) = store.saveRandomMindfulSettings(s)
    suspend fun savePomodoro(s: PomodoroSettings) = store.savePomodoroSettings(s)

    // ---------- start commands ----------
    suspend fun startIntervalBell() {
        val s = (store.observeIntervalBellSettings().firstValue())
        store.saveSession(TimerEngine.startIntervalBell(s, now()))
    }

    suspend fun startMeditation() {
        val s = store.observeMeditationSettings().firstValue()
        store.saveSession(TimerEngine.startMeditation(s, now()))
        // Ring the opening bell immediately when the sitting begins.
        if (s.startBell) {
            try { bellPlayer.play(s.sound, s.volumePercent) } catch (_: Exception) {}
            if (s.vibrate) try { vibration.vibrate() } catch (_: Exception) {}
        }
    }

    suspend fun startRandomMindful() {
        val s = store.observeRandomMindfulSettings().firstValue()
        store.saveSession(TimerEngine.startRandomMindful(s, now(), random))
    }

    suspend fun startPomodoro() {
        val s = store.observePomodoroSettings().firstValue()
        store.saveSession(TimerEngine.startPomodoro(s, now()))
    }

    /** Start the next pomodoro phase after a boundary pause. */
    suspend fun startNextPomodoroPhase(type: SessionType = SessionType.POMODORO) {
        val snap = store.session(type)
        val settings = store.observePomodoroSettings().firstValue()
        store.saveSession(
            TimerEngine.startPomodoroPhase(settings, snap.pomodoroPhase, snap.completedPomodoros, now())
        )
    }

    // ---------- pause / resume / stop ----------
    suspend fun pause(type: SessionType) {
        val s = store.session(type)
        store.saveSession(TimerEngine.pause(s, now()))
    }

    suspend fun resume(type: SessionType) {
        val s = store.session(type)
        store.saveSession(TimerEngine.resume(s, now()))
    }

    suspend fun stop(type: SessionType) = mutex.withLock {
        val s = store.session(type)
        // Bank whatever was really practised before the snapshot is wiped.
        recordDrafts(SessionAccounting.draftsForStop(s, now()))
        store.saveSession(TimerEngine.stop(s))
    }

    // ---------- the tick ----------
    /**
     * Advance every RUNNING session by one tick. Returns all events produced so the
     * service can refresh notifications. Safe to call once per second and after
     * returning to foreground.
     */
    suspend fun tickAll(): List<TimerEvent> = mutex.withLock {
        val t = now()
        val all = store.observeAllSessions().firstValue()
        val out = mutableListOf<TimerEvent>()

        for ((type, snap) in all) {
            if (snap.status != RunStatus.RUNNING) continue
            // Skip pomodoro phases that are waiting at a boundary (no countdown).
            if (type == SessionType.POMODORO && snap.phaseEndEpochMs <= 0L) continue

            val result = when (type) {
                SessionType.INTERVAL_BELL -> {
                    val s = store.observeIntervalBellSettings().firstValue()
                    TimerEngine.tick(
                        snap, t, random,
                        intervalSettings = s,
                        intervalSound = Triple(s.sound, s.volumePercent, s.vibrate)
                    )
                }
                SessionType.MEDITATION -> {
                    val s = store.observeMeditationSettings().firstValue()
                    TimerEngine.tick(
                        snap, t, random,
                        meditationEndBell = s.endBell,
                        medSound = Triple(s.sound, s.volumePercent, s.vibrate)
                    )
                }
                SessionType.RANDOM_MINDFUL -> {
                    val s = store.observeRandomMindfulSettings().firstValue()
                    TimerEngine.tick(
                        snap, t, random,
                        randomSettings = s,
                        randomSound = Triple(s.sound, s.volumePercent, s.vibrate)
                    )
                }
                SessionType.POMODORO -> {
                    val s = store.observePomodoroSettings().firstValue()
                    TimerEngine.tick(
                        snap, t, random,
                        pomodoroSettings = s,
                        pomoSound = Triple(s.sound, s.volumePercent, s.vibrate)
                    )
                }
            }

            if (result.snapshot != snap) store.saveSession(result.snapshot)
            // 记账必须用 tick 之前的快照：引擎重置会话后，真实时长就只剩事件里那一份了。
            recordDrafts(SessionAccounting.draftsForTick(snap, result))
            result.events.forEach { deliver(it) }
            out.addAll(result.events)
        }
        out
    }

    /** Preview a blind-box sound from the settings screen (点选即试听). */
    fun previewBlindSound(sound: BlindBoxSound, volumePercent: Int) {
        try { synth.playBlind(sound, volumePercent) } catch (e: Exception) { Log.w("ToneSynth", "play failed", e) }
    }

    private fun deliver(event: TimerEvent) {
        when (event) {
            is TimerEvent.Ring -> {
                try { bellPlayer.play(event.sound, event.volumePercent) } catch (e: Exception) { Log.w("BellPlayer", "play failed", e) }
                if (event.vibrate) try { vibration.vibrate() } catch (_: Exception) {}
            }
            is TimerEvent.BlindBell -> {
                try { synth.playBlind(event.sound, event.volumePercent) } catch (e: Exception) { Log.w("ToneSynth", "play failed", e) }
                if (event.vibrate) try { vibration.vibrate() } catch (_: Exception) {}
            }
            is TimerEvent.CloseBell -> {
                try { synth.playClose(event.volumePercent) } catch (e: Exception) { Log.w("ToneSynth", "close failed", e) }
            }
            is TimerEvent.PhaseChanged, is TimerEvent.Finished -> { /* history is written by recordDrafts */ }
        }
    }

    /** Write exactly what [SessionAccounting] decided, nothing inferred from settings. */
    private suspend fun recordDrafts(drafts: HistoryDrafts) {
        if (drafts.isEmpty) return
        for (d in drafts.meditations) {
            history.insertMeditation(
                MeditationRecord(
                    startedAtEpochMs = d.startedAtEpochMs,
                    practicedMs = d.practicedMs,
                    plannedMs = d.plannedMs,
                    completed = d.completed
                )
            )
        }
        for (f in drafts.focuses) {
            history.insertPomodoro(
                PomodoroRecord(
                    startedAtEpochMs = f.startedAtEpochMs,
                    phase = if (f.origin == FocusOrigin.POMODORO) {
                        PomodoroRecord.PHASE_FOCUS
                    } else {
                        PomodoroRecord.PHASE_MINDFUL
                    },
                    focusedMs = f.focusedMs,
                    completed = f.completed
                )
            )
        }
    }
}

/** Small helper: grab the current value of a Flow without collecting forever. */
private suspend fun <T> Flow<T>.firstValue(): T = this.first()
