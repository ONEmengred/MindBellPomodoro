package com.mindfulfocus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mindfulfocus.MainActivity
import com.mindfulfocus.core.DiagLog

/**
 * Arms a single exact alarm for the next due trigger of any running session.
 *
 * A foreground service + one-second coroutine loop is the primary mechanism, but
 * Android can still freeze or heavily delay background work (Doze, App Standby,
 * aggressive OEM battery managers). So every bell is also backed by an alarm:
 *
 *  * [AlarmManager.setAlarmClock] — the strongest form. It is exact, exempt from
 *    Doze, survives process death, and shows a small alarm icon in the status bar
 *    (the price of being reliable).
 *  * if the ROM rejects it, fall back to `setExactAndAllowWhileIdle`, then to
 *    `setAndAllowWhileIdle`.
 *
 * The alarm is only a wake-up hint: [AlarmReceiver] recomputes everything from
 * timestamps, so a late or duplicated alarm can never corrupt the session state.
 */
object AlarmScheduler {

    const val ACTION_TICK = "com.mindfulfocus.action.ALARM_TICK"
    const val ACTION_TEST = "com.mindfulfocus.action.ALARM_TEST"

    private const val REQUEST_CODE = 4201
    private const val REQUEST_CODE_TEST = 4202

    fun scheduleNext(context: Context, triggerAtMs: Long) {
        if (triggerAtMs <= 0L) {
            cancel(context)
            DiagLog.alarmArmed(0L)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, ACTION_TICK, REQUEST_CODE)
        val trigger = triggerAtMs.coerceAtLeast(System.currentTimeMillis() + 500L)

        val exact = try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent(context)), pi)
            true
        } catch (e: Exception) {
            DiagLog.error("setAlarmClock", e)
            false
        }
        if (!exact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (e: Exception) {
                DiagLog.error("setExactAndAllowWhileIdle", e)
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } catch (e2: Exception) {
                    DiagLog.error("setAndAllowWhileIdle", e2)
                }
            }
        }
        DiagLog.alarmArmed(trigger)
    }

    /** Arms a one-off alarm a few seconds out — used by the self-check page. */
    fun scheduleTest(context: Context, afterMs: Long = 10_000L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, ACTION_TEST, REQUEST_CODE_TEST)
        val trigger = System.currentTimeMillis() + afterMs
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent(context)), pi)
        } catch (e: Exception) {
            DiagLog.error("test setAlarmClock", e)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (e2: Exception) {
                DiagLog.error("test setExact", e2)
            }
        }
        DiagLog.note("已安排 ${afterMs / 1000} 秒后的测试闹钟")
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context, ACTION_TICK, REQUEST_CODE))
        } catch (_: Exception) {
        }
    }

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
