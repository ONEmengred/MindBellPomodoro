package com.mindfulfocus.core

import kotlinx.serialization.Serializable

/**
 * The four independent timing scenarios the app must keep apart.
 * Each [SessionType] owns its own persisted [SessionSnapshot], so starting or
 * stopping one never touches another.
 */
enum class SessionType {
    /** Bell mode 1: fixed / custom interval reminder that repeats all day. */
    INTERVAL_BELL,

    /** Bell mode 2: a meditation sitting with start / interval / end bells. */
    MEDITATION,

    /** Bell mode 3: the "mindful blind box" — random focus phases, a bell, a breathing rest, repeat. */
    RANDOM_MINDFUL,

    /** Pomodoro: focus / short break / long break phases. */
    POMODORO
}

enum class RunStatus { IDLE, RUNNING, PAUSED }

enum class PomodoroPhase { FOCUS, SHORT_BREAK, LONG_BREAK }

/**
 * Blind-box phase machine, mirroring the reference prototype:
 * focus (随机时长，静候铃响) -> rest (铃响后的觉察呼吸) -> focus -> ... -> done (达到会话上限).
 */
enum class RandomPhase { FOCUS, REST, DONE }

/** Volume / notification behaviour shared by every bell and the pomodoro alarm. */
enum class BellSound(val label: String) {
    TEMPLE_BELL("寺院钟"),
    SINGING_BOWL("颂钵"),
    GENTLE_CHIME("柔和铃"),
    WOOD_BLOCK("木鱼")
}

/**
 * The three sounds of the blind box. They are synthesised at runtime (see
 * `audio/ToneSynth.kt`), so the APK needs no audio assets at all.
 */
enum class BlindBoxSound(val label: String) {
    CHIME("风铃"),
    BOWL("颂钵"),
    DING("轻叮咚")
}

/**
 * A complete, serialisable snapshot of one session's runtime.
 *
 * Important: nothing here is a "seconds remaining" counter that a UI decrements.
 * The only moving parts are absolute wall-clock epoch values ([phaseEndEpochMs],
 * [nextBellEpochMs], [pendingBellEpochs]). Everything is derived from the clock,
 * so the timer survives the app being killed, the screen locking, and a reboot.
 */
@Serializable
data class SessionSnapshot(
    val type: SessionType,
    val status: RunStatus = RunStatus.IDLE,

    /** Wall-clock epoch (ms) at which the current countdown phase ends. 0 when n/a. */
    val phaseEndEpochMs: Long = 0L,

    /** Length of the current countdown phase in ms (for progress bars). 0 when n/a. */
    val phaseTotalMs: Long = 0L,

    /** When paused, how much of the current phase was left (ms). */
    val pausedRemainingMs: Long = 0L,

    /** Wall-clock epoch at which the session entered PAUSED (ms). 0 when not paused. */
    val pausedAtEpochMs: Long = 0L,

    /** Wall-clock epoch (ms) of the next recurring bell (bell mode 1). */
    val nextBellEpochMs: Long = 0L,

    /** For meditation: the remaining scheduled interval-bell epochs. */
    val pendingBellEpochs: List<Long> = emptyList(),

    /** Pomodoro-only state. */
    val pomodoroPhase: PomodoroPhase = PomodoroPhase.FOCUS,
    val completedPomodoros: Int = 0,

    /** Blind-box only state. */
    val randomPhase: RandomPhase = RandomPhase.FOCUS,
    val bellCount: Int = 0,
    val focusAccumulatedMs: Long = 0L,

    /** Epoch (ms) of the most recent bell — drives the one-shot ring flash animation. */
    val bellFlashAtEpochMs: Long = 0L,

    /** Milliseconds elapsed in this session so far (accumulated across pauses). */
    val elapsedAccumulatedMs: Long = 0L,

    /** Wall-clock epoch when this session's current RUNNING streak began. 0 when not running. */
    val runStartedEpochMs: Long = 0L
) {
    /** Remaining ms of the current countdown phase at [now], clamped at 0. */
    fun remainingMs(now: Long): Long = when (status) {
        RunStatus.PAUSED -> pausedRemainingMs.coerceAtLeast(0L)
        RunStatus.RUNNING -> (phaseEndEpochMs - now).coerceAtLeast(0L)
        RunStatus.IDLE -> 0L
    }

    /** Remaining ms until the next recurring bell at [now] (bell mode 1). */
    fun untilNextBellMs(now: Long): Long {
        if (nextBellEpochMs <= 0L) return 0L
        val reference = if (status == RunStatus.PAUSED) pausedAtEpochMs else now
        return (nextBellEpochMs - reference).coerceAtLeast(0L)
    }

    /** Total elapsed (ms) at [now], including the currently running streak. */
    fun elapsedMs(now: Long): Long {
        val live = if (status == RunStatus.RUNNING && runStartedEpochMs > 0L)
            (now - runStartedEpochMs).coerceAtLeast(0L) else 0L
        return elapsedAccumulatedMs + live
    }

    /**
     * Blind-box "已专注" time at [now]: every completed focus phase plus the part of
     * the focus phase that is running right now. Only focus time counts, never the
     * breathing rest — exactly like the reference prototype.
     */
    fun focusMs(now: Long): Long {
        val inCurrentFocus = status == RunStatus.RUNNING &&
            randomPhase == RandomPhase.FOCUS &&
            phaseEndEpochMs > 0L && phaseTotalMs > 0L
        val partial = if (inCurrentFocus) {
            val phaseStart = phaseEndEpochMs - phaseTotalMs
            (now - phaseStart).coerceIn(0L, phaseTotalMs)
        } else 0L
        return focusAccumulatedMs + partial
    }

    /** 0..1 progress of the current blind-box phase at [now]. */
    fun phaseProgress(now: Long): Float {
        if (randomPhase == RandomPhase.DONE) return 1f
        if (phaseTotalMs <= 0L) return 0f
        val elapsed = (phaseTotalMs - remainingMs(now)).coerceIn(0L, phaseTotalMs)
        return elapsed.toFloat() / phaseTotalMs.toFloat()
    }
}
