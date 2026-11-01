package com.mindfulfocus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.TimerEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired by [AlarmScheduler] at the exact moment a bell is due.
 *
 * It re-runs the same idempotent tick the service uses, so the bell rings, the
 * notification appears and the history is written even if the service's 1s loop was
 * frozen by Doze / an OEM battery manager. Because [TimerEngine] works purely from
 * absolute timestamps, catching up here is exact — and harmless if the loop already
 * handled it.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MindFocusApp ?: return
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                val container = app.container
                val events = container.controller.tickAll()
                container.alertNotifier.ensureChannels()
                container.alertNotifier.notifyForEvents(events)

                val all = container.controller.observeAllSessions().first()
                val next = all.values
                    .map { TimerEngine.nextTriggerEpochMs(it) }
                    .filter { it > 0L }
                    .minOrNull() ?: 0L
                AlarmScheduler.scheduleNext(app, next)

                // Bring the foreground service (and its live countdown) back if it died.
                if (all.values.any { it.status == RunStatus.RUNNING }) {
                    try {
                        TimerForegroundService.start(app)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                // Never let a receiver crash the process.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
