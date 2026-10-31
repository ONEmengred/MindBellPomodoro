package com.mindfulfocus.core

import kotlinx.serialization.Serializable

/** A daily time window in minutes-since-midnight, e.g. 08:00 -> 480. */
@Serializable
data class TimeWindow(
    val startMinuteOfDay: Int = 8 * 60,
    val endMinuteOfDay: Int = 22 * 60
) {
    fun contains(minuteOfDay: Int): Boolean {
        // Handles windows that cross midnight (e.g. 22:00 -> 06:00).
        return if (startMinuteOfDay <= endMinuteOfDay) {
            minuteOfDay in startMinuteOfDay until endMinuteOfDay
        } else {
            minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        }
    }
}

/** Bell mode 1 settings — fixed interval reminder. */
@Serializable
data class IntervalBellSettings(
    val intervalMinutes: Int = 25,
    val activeWindow: TimeWindow = TimeWindow(8 * 60, 22 * 60),
    val doNotDisturb: TimeWindow? = null,
    val sound: BellSound = BellSound.TEMPLE_BELL,
    val volumePercent: Int = 70,
    val vibrate: Boolean = true,
    val enabledActiveWindow: Boolean = true,
    val enabledDnd: Boolean = false
)

/** Bell mode 2 settings — guided meditation sitting. */
@Serializable
data class MeditationSettings(
    val sessionMinutes: Int = 20,
    val prepSeconds: Int = 10,
    val intervalBellMinutes: Int = 0,        // 0 = no interval bells
    val startBell: Boolean = true,
    val intervalBell: Boolean = true,
    val endBell: Boolean = true,
    val sound: BellSound = BellSound.SINGING_BOWL,
    val volumePercent: Int = 70,
    val vibrate: Boolean = true
)

/**
 * Bell mode 3 settings — the "mindful blind box".
 *
 * Defaults match the reference prototype: a random 3–5 minute focus phase, a
 * 10 second breathing rest after each bell, a 90 minute session cap and the
 * 风铃 sound. Durations are stored in minutes as [Float] so 0.5 minute steps work.
 */
@Serializable
data class RandomMindfulSettings(
    val minMinutes: Float = 3f,
    val maxMinutes: Float = 5f,
    val restSeconds: Int = 10,
    /** Total session cap in minutes; 0 means unlimited. */
    val capMinutes: Int = 90,
    /** Blind-box mode: hide the countdown number so you cannot "wait" for the bell. */
    val blindMode: Boolean = false,
    val sound: BlindBoxSound = BlindBoxSound.CHIME,
    val volumePercent: Int = 70,
    val vibrate: Boolean = true
) {
    companion object {
        const val MIN_MINUTES = 0.5f
        const val MAX_MINUTES = 180f
        const val MIN_REST_SECONDS = 1
        const val MAX_REST_SECONDS = 300
        const val MAX_CAP_MINUTES = 600
    }
}

/** Pomodoro settings. */
@Serializable
data class PomodoroSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val cyclesBeforeLongBreak: Int = 4,
    val autoStartNextPhase: Boolean = false,
    val sound: BellSound = BellSound.TEMPLE_BELL,
    val volumePercent: Int = 70,
    val vibrate: Boolean = true,
    val notify: Boolean = true
)
