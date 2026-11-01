package com.mindfulfocus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Arms a single exact alarm for the next due trigger of any running session.
 *
 * A foreground service + one-second coroutine loop is the primary mechanism, but
 * Android can still freeze or heavily delay background work (Doze, App Standby,
 * aggressive OEM battery managers). `setExactAndAllowWhileIdle` is the only thing
 * that reliably wakes the process at a wall-clock moment, so every bell is backed
 * by an alarm as well. The alarm is a wake-up hint only — [AlarmReceiver] recomputes
 * everything from timestamps, so a late or extra alarm can never corrupt the state.
 */
object AlarmScheduler {

    const val ACTION_TICK = "com.mindfulfocus.action.ALARM_TICK"
    private const val REQUEST_CODE = 4201

    fun scheduleNext(context: Context, triggerAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context)
        if (triggerAtMs <= 0L) {
            am.cancel(pi)
            return
        }
        // Never schedule in the past (would fire immediately over and over).
        val trigger = triggerAtMs.coerceAtLeast(System.currentTimeMillis() + 500L)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
        } catch (_: Exception) {
        }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
