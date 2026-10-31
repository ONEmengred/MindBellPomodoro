package com.mindfulfocus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A clock that ticks once per second and returns the current wall-clock epoch.
 * Screens use it only to *re-render*; the actual remaining time is always derived
 * from the persisted timestamps (see SessionSnapshot.remainingMs). So this is a
 * display concern, never the source of truth.
 */
@Composable
fun rememberNow(tickMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(tickMs)
        }
    }
    return now
}

/** Formats milliseconds as mm:ss (or h:mm:ss when >= 1h). */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/** Formats a minute-of-day value (0..1439) as HH:mm. */
fun formatMinuteOfDay(minute: Int): String =
    String.format("%02d:%02d", (minute / 60) % 24, minute % 60)
