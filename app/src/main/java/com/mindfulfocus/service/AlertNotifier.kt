package com.mindfulfocus.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mindfulfocus.MainActivity
import com.mindfulfocus.R
import com.mindfulfocus.core.PomodoroPhase
import com.mindfulfocus.core.TimerEvent

/**
 * All notification rendering lives here so the foreground service and the alarm
 * receiver behave identically.
 *
 * Two channels:
 *  * [CH_ONGOING] — the silent, permanent "timer is running" notification that also
 *    carries the live countdown text (so the user can see *when* it will ring).
 *  * [CH_ALERT] — the sound-free heads-up alert fired for every bell. It deliberately
 *    carries no sound of its own: the bell itself is synthesised by
 *    [com.mindfulfocus.audio.ToneSynth], and a channel sound would double-ring.
 */
class AlertNotifier(private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Android 13+ lets the user revoke notifications; without it nothing can be shown. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "计时进行中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示正在运行的计时器与倒计时"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_ALERT, "铃响提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每一次铃响、阶段切换与练习结束的提醒"
                setShowBadge(true)
                setSound(null, null) // 铃声由 ToneSynth 合成播放，避免重复
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** The persistent foreground-service notification. */
    fun ongoingNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_mindbell)
            .setContentTitle("正念铃 · 番茄钟")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .build()

    /** Refresh the ongoing notification text (caller decides when the text changed). */
    fun updateOngoing(text: String) = post(ID_ONGOING, ongoingNotification(text))

    fun cancelOngoing() {
        try {
            manager.cancel(ID_ONGOING)
        } catch (_: SecurityException) {
        }
    }

    /** Fire one visible + vibrating heads-up alert. */
    fun ring(title: String, text: String) {
        wakeBriefly()
        post(
            ID_ALERT,
            NotificationCompat.Builder(context, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_mindbell)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setContentIntent(openApp())
                .build()
        )
    }

    fun cancelAlerts() {
        try {
            manager.cancel(ID_ALERT)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Turn timer events into notifications. Shared by the 1s service loop and the
     * AlarmManager receiver, so an alarm that fires while the service is frozen still
     * produces the same alert the user expects.
     */
    fun notifyForEvents(events: List<TimerEvent>) {
        events.forEach { event ->
            when (event) {
                is TimerEvent.Ring -> when (event.type) {
                    // Pomodoro rings always come with a PhaseChanged; that one carries
                    // the richer "what happens next" copy.
                    com.mindfulfocus.core.SessionType.POMODORO -> Unit
                    com.mindfulfocus.core.SessionType.MEDITATION -> ring("冥想练习", "铃响了，回到呼吸")
                    com.mindfulfocus.core.SessionType.INTERVAL_BELL -> ring("定时提醒", "时间到了，停下来觉察一下")
                    else -> ring("正念铃", "铃响了")
                }
                is TimerEvent.BlindBell -> ring("正念盲盒", "铃响了 —— 停下来，跟随呼吸")
                is TimerEvent.CloseBell -> ring("正念盲盒", "这次练习结束了")
                is TimerEvent.PhaseChanged -> ring("番茄钟", phaseText(event.to))
                // The end bell / closing chime already carries the copy the user sees.
                is TimerEvent.Finished -> Unit
            }
        }
    }

    private fun phaseText(to: PomodoroPhase): String = when (to) {
        PomodoroPhase.FOCUS -> "休息结束，开始新的专注"
        PomodoroPhase.SHORT_BREAK -> "专注完成，短休息一下"
        PomodoroPhase.LONG_BREAK -> "专注完成，长休息一下"
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FROM_ALERT, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun post(id: Int, notification: Notification) {
        // Posting without the runtime permission throws on some OEM builds; skip quietly.
        if (!hasPermission()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    /** Give the CPU a short window to actually play the bell / flash the screen. */
    private fun wakeBriefly() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MindBell:ring").acquire(20_000L)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CH_ONGOING = "timer_ongoing"
        const val CH_ALERT = "timer_alert_v2"
        const val ID_ONGOING = 1001
        const val ID_ALERT = 1002
        const val EXTRA_FROM_ALERT = "from_alert"
    }
}
