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
 * After a reboot, wall-clock based snapshots are still valid. If any session was
 * running, restart the foreground service so it keeps ticking (and rings any bell
 * that came due while the phone was off on its first tick).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val app = context.applicationContext as? MindFocusApp ?: return
        val pending = goAsync()
        val me = app
        me.applicationScope.launch {
            try {
                val all = me.container.controller.observeAllSessions().first()
                // Re-arm the exact alarm first: it is the only thing that survives the
                // service being killed again right after boot.
                val next = all.values
                    .map { TimerEngine.nextTriggerEpochMs(it) }
                    .filter { it > 0L }
                    .minOrNull() ?: 0L
                AlarmScheduler.scheduleNext(context, next)
                if (all.values.any { it.status == RunStatus.RUNNING }) {
                    TimerForegroundService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
