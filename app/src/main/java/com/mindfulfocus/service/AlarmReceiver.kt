package com.mindfulfocus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.core.BlindBoxSound
import com.mindfulfocus.core.DiagLog
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
        val isTest = intent.action == AlarmScheduler.ACTION_TEST
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                val container = app.container
                container.alertNotifier.ensureChannels()
                DiagLog.alarmFired()

                if (isTest) {
                    container.controller.previewBlindSound(BlindBoxSound.CHIME, 90)
                    container.alertNotifier.ring("自检 · 闹钟通路", "这条通知说明精确闹钟能叫醒 App")
                    DiagLog.note("测试闹钟触发（通知 + 铃声）")
                    return@launch
                }

                val events = container.controller.tickAll()
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
                    } catch (e: Exception) {
                        DiagLog.error("restart service", e)
                    }
                }
            } catch (e: Exception) {
                DiagLog.error("alarm receiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
