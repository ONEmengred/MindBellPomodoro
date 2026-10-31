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

    suspend fun stop(type: SessionType) {
        val s = store.session(type)
        // Record partial session history before wiping.
        recordHistoryOnStop(s)
        store.saveSession(TimerEngine.stop(s))
    }

    // ---------- the tick ----------
    /**
     * Advance every RUNNING session by one tick. Returns all events produced so the
     * service can refresh notifications. Safe to call once per second and after
     * returning to foreground.
     */
    suspend fun tickAll(): List<TimerEvent> {
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
            result.events.forEach { deliver(it) }
            out.addAll(result.events)
        }
        return out
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
            is TimerEvent.PhaseChanged, TimerEvent.Finished -> { /* history handled below */ }
        }
    }

    /** Persist a finished meditation sitting (called by service when it sees Finished + was MEDITATION). */
    suspend fun recordFinishedMeditation(snapshot: SessionSnapshot) {
        if (snapshot.type != SessionType.MEDITATION) return
        // Try to reconstruct real durations from the just-finished snapshot.
        val settings = store.observeMeditationSettings().firstValue()
        val planned = settings.sessionMinutes * 60_000L
        history.insertMeditation(
            MeditationRecord(
                startedAtEpochMs = now() - planned,
                practicedMs = planned,
                plannedMs = planned,
                completed = true
            )
        )
    }

    private suspend fun recordHistoryOnStop(snap: SessionSnapshot) {
        val elapsed = snap.elapsedMs(now())
        when (snap.type) {
            SessionType.MEDITATION -> if (elapsed > 0) {
                history.insertMeditation(
                    MeditationRecord(
                        startedAtEpochMs = now() - elapsed,
                        practicedMs = elapsed,
                        plannedMs = snap.phaseTotalMs,
                        completed = false
                    )
                )
            }
            SessionType.POMODORO -> if (elapsed > 0) {
                history.insertPomodoro(
                    PomodoroRecord(
                        startedAtEpochMs = now() - elapsed,
                        phase = snap.pomodoroPhase.name,
                        focusedMs = elapsed,
                        completed = false
                    )
                )
            }
            else -> {}
        }
    }
}

/** Small helper: grab the current value of a Flow without collecting forever. */
private suspend fun <T> Flow<T>.firstValue(): T = this.first()
