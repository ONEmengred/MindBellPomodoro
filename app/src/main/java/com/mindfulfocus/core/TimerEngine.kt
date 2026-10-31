package com.mindfulfocus.core

import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * What the foreground service should physically do when an event is due.
 */
sealed interface TimerEvent {
    /** Play a bell sound + optional vibration. */
    data class Ring(
        val sound: BellSound,
        val volumePercent: Int,
        val vibrate: Boolean
    ) : TimerEvent

    /** Play one of the synthesised blind-box sounds (风铃 / 颂钵 / 轻叮咚). */
    data class BlindBell(
        val sound: BlindBoxSound,
        val volumePercent: Int,
        val vibrate: Boolean
    ) : TimerEvent

    /** Play the low closing chime that marks the end of a blind-box session. */
    data class CloseBell(val volumePercent: Int) : TimerEvent

    /** Pomodoro phase transitioned; used to update the persistent notification text. */
    data class PhaseChanged(val from: PomodoroPhase, val to: PomodoroPhase) : TimerEvent

    /** A sitting / pomodoro run finished (always accompanied by a Ring for the end bell). */
    data object Finished : TimerEvent
}

/** Result of a single tick: the (possibly advanced) snapshot plus events to deliver. */
data class TickResult(val snapshot: SessionSnapshot, val events: List<TimerEvent>)

/**
 * The timing brain. All public functions are pure: given the current snapshot,
 * the settings, "now" (wall-clock epoch ms) and a [Random], they return the next
 * snapshot and the events that are due. No state is stored in a ticking variable,
 * so the service can be killed / restarted / rebooted and still be exact.
 */
object TimerEngine {

    private const val MINUTE = 60_000L

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Minute-of-day for a wall-clock epoch, using the device time zone. */
    private fun minuteOfDay(epochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val cal = java.util.Calendar.getInstance(zone)
        cal.timeInMillis = epochMs
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** True if a bell is suppressed right now by active-window / DND rules. */
    private fun suppressed(
        now: Long,
        activeWindow: TimeWindow,
        activeEnabled: Boolean,
        dnd: TimeWindow?,
        dndEnabled: Boolean
    ): Boolean {
        val m = minuteOfDay(now)
        if (dndEnabled && dnd != null && dnd.contains(m)) return true
        if (activeEnabled && !activeWindow.contains(m)) return true
        return false
    }

    /** Advance [candidate] forward to the next moment that is inside the active window (and outside DND). */
    private fun nextAllowedTime(
        candidate: Long,
        intervalMs: Long,
        activeWindow: TimeWindow,
        activeEnabled: Boolean,
        dnd: TimeWindow?,
        dndEnabled: Boolean
    ): Long {
        if (!activeEnabled && !dndEnabled) return candidate
        var t = candidate
        // Guard against pathological configs; cap iterations.
        var guard = 0
        while (suppressed(t, activeWindow, activeEnabled, dnd, dndEnabled) && guard < 5000) {
            // Nudge forward by a minute until allowed; keeps intervals roughly aligned.
            t += MINUTE
            guard++
        }
        return t
    }

    /** Random gap strictly within [minMinutes]..[maxMinutes] (inclusive), expressed in ms. */
    fun randomGapMs(minMinutes: Int, maxMinutes: Int, random: Random): Long {
        val lo = max(1, minOf(minMinutes, maxMinutes))
        val hi = max(lo, maxOf(minMinutes, maxMinutes))
        return random.nextLong(lo.toLong(), hi.toLong() + 1) * MINUTE
    }

    // ------------------------------------------------------------------
    // Bell mode 1 — interval reminder
    // ------------------------------------------------------------------

    fun startIntervalBell(settings: IntervalBellSettings, now: Long): SessionSnapshot {
        val next = now + settings.intervalMinutes * MINUTE
        return SessionSnapshot(
            type = SessionType.INTERVAL_BELL,
            status = RunStatus.RUNNING,
            nextBellEpochMs = nextAllowedTime(
                next, settings.intervalMinutes * MINUTE,
                settings.activeWindow, settings.enabledActiveWindow,
                settings.doNotDisturb, settings.enabledDnd
            ),
            runStartedEpochMs = now,
            elapsedAccumulatedMs = 0L
        )
    }

    // ------------------------------------------------------------------
    // Bell mode 2 — meditation sitting
    // ------------------------------------------------------------------

    fun startMeditation(settings: MeditationSettings, now: Long): SessionSnapshot {
        val prepMs = settings.prepSeconds * 1000L
        val practiceMs = settings.sessionMinutes * MINUTE
        val endEpoch = now + prepMs + practiceMs

        // Schedule interval bells relative to the end of prep, up to the end.
        val bells = mutableListOf<Long>()
        if (settings.intervalBell && settings.intervalBellMinutes > 0) {
            var t = now + prepMs + settings.intervalBellMinutes * MINUTE
            while (t < endEpoch) {
                bells.add(t)
                t += settings.intervalBellMinutes * MINUTE
            }
        }

        return SessionSnapshot(
            type = SessionType.MEDITATION,
            status = RunStatus.RUNNING,
            phaseEndEpochMs = endEpoch,
            phaseTotalMs = prepMs + practiceMs,
            pendingBellEpochs = bells,
            runStartedEpochMs = now
        )
    }

    // ------------------------------------------------------------------
    // Bell mode 3 — mindful blind box
    // ------------------------------------------------------------------

    /**
     * Draw one random focus phase: uniform inside [minMinutes]..[maxMinutes],
     * rounded to whole seconds and never shorter than one second — the same
     * contract as the reference prototype's `drawDuration()`.
     */
    fun randomPhaseMs(minMinutes: Float, maxMinutes: Float, random: Random): Long {
        val loMin = minOf(minMinutes, maxMinutes).coerceAtLeast(RandomMindfulSettings.MIN_MINUTES)
        val hiMin = maxOf(minMinutes, maxMinutes).coerceAtLeast(loMin)
        val loMs = (loMin * MINUTE.toDouble()).roundToLong()
        val hiMs = (hiMin * MINUTE.toDouble()).roundToLong()
        val drawn = if (hiMs <= loMs) loMs else random.nextLong(loMs, hiMs + 1)
        return max(1000L, (drawn / 1000.0).roundToLong() * 1000L)
    }

    /** Start a blind-box session with a freshly drawn focus phase. */
    fun startRandomMindful(settings: RandomMindfulSettings, now: Long, random: Random): SessionSnapshot {
        val duration = randomPhaseMs(settings.minMinutes, settings.maxMinutes, random)
        return SessionSnapshot(
            type = SessionType.RANDOM_MINDFUL,
            status = RunStatus.RUNNING,
            randomPhase = RandomPhase.FOCUS,
            phaseEndEpochMs = now + duration,
            phaseTotalMs = duration,
            runStartedEpochMs = now,
            elapsedAccumulatedMs = 0L,
            focusAccumulatedMs = 0L,
            bellCount = 0
        )
    }

    // ------------------------------------------------------------------
    // Pomodoro
    // ------------------------------------------------------------------

    fun startPomodoro(settings: PomodoroSettings, now: Long): SessionSnapshot {
        val focusMs = settings.focusMinutes * MINUTE
        return SessionSnapshot(
            type = SessionType.POMODORO,
            status = RunStatus.RUNNING,
            phaseEndEpochMs = now + focusMs,
            phaseTotalMs = focusMs,
            pomodoroPhase = PomodoroPhase.FOCUS,
            completedPomodoros = 0,
            runStartedEpochMs = now,
            elapsedAccumulatedMs = 0L
        )
    }

    /** Begin one concrete pomodoro phase at [now]. */
    fun startPomodoroPhase(
        settings: PomodoroSettings,
        phase: PomodoroPhase,
        completedPomodoros: Int,
        now: Long
    ): SessionSnapshot {
        val lenMs = when (phase) {
            PomodoroPhase.FOCUS -> settings.focusMinutes * MINUTE
            PomodoroPhase.SHORT_BREAK -> settings.shortBreakMinutes * MINUTE
            PomodoroPhase.LONG_BREAK -> settings.longBreakMinutes * MINUTE
        }
        return SessionSnapshot(
            type = SessionType.POMODORO,
            status = RunStatus.RUNNING,
            phaseEndEpochMs = now + lenMs,
            phaseTotalMs = lenMs,
            pomodoroPhase = phase,
            completedPomodoros = completedPomodoros,
            runStartedEpochMs = now,
            elapsedAccumulatedMs = 0L
        )
    }

    /** Decide which phase follows [phase] given how many focus rounds are done. */
    fun nextPomodoroPhase(settings: PomodoroSettings, finished: PomodoroPhase, completed: Int): PomodoroPhase =
        when (finished) {
            PomodoroPhase.FOCUS ->
                if (completed > 0 && completed % settings.cyclesBeforeLongBreak == 0)
                    PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
            PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK -> PomodoroPhase.FOCUS
        }

    // ------------------------------------------------------------------
    // Generic pause / resume / stop
    // ------------------------------------------------------------------

    fun pause(snapshot: SessionSnapshot, now: Long): SessionSnapshot = when (snapshot.status) {
        RunStatus.RUNNING -> {
            // Freeze the current remaining values for display while paused.
            val remaining = when {
                snapshot.phaseEndEpochMs > 0L -> (snapshot.phaseEndEpochMs - now).coerceAtLeast(0L)
                snapshot.nextBellEpochMs > 0L -> (snapshot.nextBellEpochMs - now).coerceAtLeast(0L)
                else -> 0L
            }
            // Blind box: bank the partial focus time so the "已专注" stat never jumps.
            val focusBanked = if (snapshot.type == SessionType.RANDOM_MINDFUL &&
                snapshot.randomPhase == RandomPhase.FOCUS && snapshot.phaseTotalMs > 0L
            ) {
                val phaseStart = snapshot.phaseEndEpochMs - snapshot.phaseTotalMs
                (now - phaseStart).coerceIn(0L, snapshot.phaseTotalMs)
            } else 0L
            snapshot.copy(
                status = RunStatus.PAUSED,
                pausedRemainingMs = remaining,
                pausedAtEpochMs = now,
                elapsedAccumulatedMs = snapshot.elapsedMs(now),
                focusAccumulatedMs = snapshot.focusAccumulatedMs + focusBanked,
                runStartedEpochMs = 0L
            )
        }
        else -> snapshot
    }

    fun resume(snapshot: SessionSnapshot, now: Long): SessionSnapshot = when (snapshot.status) {
        RunStatus.PAUSED -> {
            // How long the session sat paused — shift every absolute timestamp by this.
            val pauseDuration = (now - snapshot.pausedAtEpochMs).coerceAtLeast(0L)
            snapshot.copy(
                status = RunStatus.RUNNING,
                phaseEndEpochMs = if (snapshot.phaseEndEpochMs > 0L) snapshot.phaseEndEpochMs + pauseDuration else 0L,
                nextBellEpochMs = if (snapshot.nextBellEpochMs > 0L) snapshot.nextBellEpochMs + pauseDuration else 0L,
                pendingBellEpochs = snapshot.pendingBellEpochs.map { it + pauseDuration },
                pausedRemainingMs = 0L,
                pausedAtEpochMs = 0L,
                runStartedEpochMs = now
            )
        }
        else -> snapshot
    }

    fun stop(snapshot: SessionSnapshot): SessionSnapshot =
        SessionSnapshot(type = snapshot.type)

    // ------------------------------------------------------------------
    // Tick — the only place events are produced
    // ------------------------------------------------------------------

    /**
     * Advance [snapshot] to [now], returning events that became due.
     * Callers run this once per second (and once on resume-from-background), but the
     * outcome depends only on timestamps, not on how many times it was called.
     */
    fun tick(
        snapshot: SessionSnapshot,
        now: Long,
        random: Random = Random.Default,
        intervalSettings: IntervalBellSettings? = null,
        meditationEndBell: Boolean = true,
        randomSettings: RandomMindfulSettings? = null,
        pomodoroSettings: PomodoroSettings? = null,
        medSound: Triple<BellSound, Int, Boolean>? = null,
        pomoSound: Triple<BellSound, Int, Boolean>? = null,
        randomSound: Triple<BlindBoxSound, Int, Boolean>? = null,
        intervalSound: Triple<BellSound, Int, Boolean>? = null
    ): TickResult {
        if (snapshot.status != RunStatus.RUNNING) return TickResult(snapshot, emptyList())

        return when (snapshot.type) {
            SessionType.INTERVAL_BELL -> tickIntervalBell(snapshot, now, intervalSettings, intervalSound)
            SessionType.MEDITATION -> tickMeditation(snapshot, now, meditationEndBell, medSound ?: Triple(BellSound.SINGING_BOWL, 70, true))
            SessionType.RANDOM_MINDFUL -> tickRandom(snapshot, now, random, randomSettings, randomSound)
            SessionType.POMODORO -> tickPomodoro(snapshot, now, pomodoroSettings ?: PomodoroSettings(), pomoSound ?: Triple(BellSound.TEMPLE_BELL, 70, true))
        }
    }

    private fun tickIntervalBell(
        s: SessionSnapshot,
        now: Long,
        settings: IntervalBellSettings?,
        sound: Triple<BellSound, Int, Boolean>?
    ): TickResult {
        if (settings == null) return TickResult(s, emptyList())
        if (s.nextBellEpochMs <= 0L || now < s.nextBellEpochMs) return TickResult(s, emptyList())

        val events = mutableListOf<TimerEvent>()
        val ring = sound ?: Triple(settings.sound, settings.volumePercent, settings.vibrate)
        // Only ring when not suppressed; suppression is checked against actual now.
        val isSuppressed = suppressed(
            s.nextBellEpochMs, settings.activeWindow, settings.enabledActiveWindow,
            settings.doNotDisturb, settings.enabledDnd
        )
        if (!isSuppressed) {
            events.add(TimerEvent.Ring(ring.first, ring.second, ring.third))
        }
        val next = nextAllowedTime(
            now + settings.intervalMinutes * MINUTE, settings.intervalMinutes * MINUTE,
            settings.activeWindow, settings.enabledActiveWindow,
            settings.doNotDisturb, settings.enabledDnd
        )
        return TickResult(s.copy(nextBellEpochMs = next), events)
    }

    private fun tickMeditation(
        s: SessionSnapshot,
        now: Long,
        endBell: Boolean,
        sound: Triple<BellSound, Int, Boolean>
    ): TickResult {
        val events = mutableListOf<TimerEvent>()
        // Fire any interval bells that are due.
        val stillPending = mutableListOf<Long>()
        for (bellEpoch in s.pendingBellEpochs) {
            if (bellEpoch <= now) events.add(TimerEvent.Ring(sound.first, sound.second, sound.third))
            else stillPending.add(bellEpoch)
        }
        var updated = s.copy(pendingBellEpochs = stillPending)
        // End of sitting?
        if (s.phaseEndEpochMs in 1L..now) {
            if (endBell) events.add(TimerEvent.Ring(sound.first, sound.second, sound.third))
            events.add(TimerEvent.Finished)
            updated = updated.copy(
                status = RunStatus.IDLE,
                elapsedAccumulatedMs = s.elapsedMs(s.phaseEndEpochMs),
                runStartedEpochMs = 0L,
                phaseEndEpochMs = 0L,
                pendingBellEpochs = emptyList()
            )
        }
        return TickResult(updated, events)
    }

    /**
     * The blind-box state machine: focus --(bell)--> rest --(breathing done)--> focus ...
     * Every phase is an absolute epoch ([SessionSnapshot.phaseEndEpochMs]), so a single
     * tick can catch up over any number of missed phases after the app was killed.
     *
     * The session cap is the absolute moment the active clock reaches `capMinutes`.
     * Because the active clock only runs while RUNNING (pause shifts the timestamps),
     * that moment is `runStartedEpochMs + (capMs - elapsedAccumulatedMs)` — stable for
     * the whole running streak, which makes the cap exact and pause-proof.
     */
    private fun tickRandom(
        s: SessionSnapshot,
        now: Long,
        random: Random,
        settings: RandomMindfulSettings?,
        sound: Triple<BlindBoxSound, Int, Boolean>?
    ): TickResult {
        if (settings == null) return TickResult(s, emptyList())
        if (s.randomPhase == RandomPhase.DONE || s.phaseEndEpochMs <= 0L) return TickResult(s, emptyList())

        val ring = sound ?: Triple(settings.sound, settings.volumePercent, settings.vibrate)
        val capMs = settings.capMinutes.toLong() * MINUTE
        val capEpoch = if (capMs > 0L && s.runStartedEpochMs > 0L) {
            s.runStartedEpochMs + (capMs - s.elapsedAccumulatedMs).coerceAtLeast(0L)
        } else Long.MAX_VALUE

        val events = mutableListOf<TimerEvent>()
        var snap = s
        var guard = 0

        while (guard++ < 1000) {
            if (snap.randomPhase == RandomPhase.DONE || snap.phaseEndEpochMs <= 0L) break
            val phaseEnd = snap.phaseEndEpochMs

            // Reached the session cap inside (or exactly at the end of) this phase?
            if (capEpoch <= now && capEpoch <= phaseEnd) {
                snap = finishBlindBox(snap, capEpoch, capMs)
                events.add(TimerEvent.CloseBell(ring.second))
                events.add(TimerEvent.Finished)
                break
            }
            if (now < phaseEnd) break

            // Phase boundary reached — advance the machine.
            snap = when (snap.randomPhase) {
                RandomPhase.FOCUS -> {
                    events.add(TimerEvent.BlindBell(ring.first, ring.second, ring.third))
                    val restMs = settings.restSeconds.coerceIn(
                        RandomMindfulSettings.MIN_REST_SECONDS,
                        RandomMindfulSettings.MAX_REST_SECONDS
                    ) * 1000L
                    snap.copy(
                        randomPhase = RandomPhase.REST,
                        focusAccumulatedMs = snap.focusAccumulatedMs + snap.phaseTotalMs,
                        bellCount = snap.bellCount + 1,
                        bellFlashAtEpochMs = phaseEnd,
                        phaseTotalMs = restMs,
                        phaseEndEpochMs = phaseEnd + restMs
                    )
                }
                RandomPhase.REST -> {
                    val duration = randomPhaseMs(settings.minMinutes, settings.maxMinutes, random)
                    snap.copy(
                        randomPhase = RandomPhase.FOCUS,
                        phaseTotalMs = duration,
                        phaseEndEpochMs = phaseEnd + duration
                    )
                }
                RandomPhase.DONE -> break
            }
        }
        return TickResult(snap, events)
    }

    /** Close a blind-box session at [finishEpoch], banking the elapsed / focus totals. */
    private fun finishBlindBox(s: SessionSnapshot, finishEpoch: Long, capMs: Long): SessionSnapshot {
        val elapsed = s.elapsedMs(finishEpoch).coerceAtLeast(0L)
        val capped = if (capMs > 0L) elapsed.coerceAtMost(capMs) else elapsed
        val focusAdd = if (s.randomPhase == RandomPhase.FOCUS && s.phaseTotalMs > 0L) {
            val phaseStart = s.phaseEndEpochMs - s.phaseTotalMs
            (finishEpoch - phaseStart).coerceIn(0L, s.phaseTotalMs)
        } else 0L
        return s.copy(
            status = RunStatus.IDLE,
            randomPhase = RandomPhase.DONE,
            phaseEndEpochMs = 0L,
            phaseTotalMs = 0L,
            runStartedEpochMs = 0L,
            elapsedAccumulatedMs = capped,
            focusAccumulatedMs = s.focusAccumulatedMs + focusAdd
        )
    }

    private fun tickPomodoro(
        s: SessionSnapshot,
        now: Long,
        settings: PomodoroSettings,
        sound: Triple<BellSound, Int, Boolean>
    ): TickResult {
        if (s.phaseEndEpochMs <= 0L || now < s.phaseEndEpochMs) return TickResult(s, emptyList())

        val events = mutableListOf<TimerEvent>()
        events.add(TimerEvent.Ring(sound.first, sound.second, sound.third))

        val finishedPhase = s.pomodoroPhase
        val completed = if (finishedPhase == PomodoroPhase.FOCUS) s.completedPomodoros + 1 else s.completedPomodoros
        val nextPhase = nextPomodoroPhase(settings, finishedPhase, completed)
        events.add(TimerEvent.PhaseChanged(finishedPhase, nextPhase))

        return if (settings.autoStartNextPhase) {
            val lenMs = when (nextPhase) {
                PomodoroPhase.FOCUS -> settings.focusMinutes * MINUTE
                PomodoroPhase.SHORT_BREAK -> settings.shortBreakMinutes * MINUTE
                PomodoroPhase.LONG_BREAK -> settings.longBreakMinutes * MINUTE
            }
            val updated = s.copy(
                status = RunStatus.RUNNING,
                pomodoroPhase = nextPhase,
                completedPomodoros = completed,
                phaseTotalMs = lenMs,
                phaseEndEpochMs = now + lenMs,
                runStartedEpochMs = now,
                elapsedAccumulatedMs = 0L
            )
            TickResult(updated, events)
        } else {
            // Pause at the boundary, waiting for the user to start the next phase.
            val updated = s.copy(
                status = RunStatus.RUNNING,
                pomodoroPhase = nextPhase,
                completedPomodoros = completed,
                phaseTotalMs = when (nextPhase) {
                    PomodoroPhase.FOCUS -> settings.focusMinutes * MINUTE
                    PomodoroPhase.SHORT_BREAK -> settings.shortBreakMinutes * MINUTE
                    PomodoroPhase.LONG_BREAK -> settings.longBreakMinutes * MINUTE
                },
                phaseEndEpochMs = 0L,
                runStartedEpochMs = 0L,
                elapsedAccumulatedMs = 0L
            )
            TickResult(updated, events)
        }
    }
}
