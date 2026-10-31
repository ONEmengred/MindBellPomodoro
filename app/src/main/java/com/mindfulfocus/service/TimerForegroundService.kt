package com.mindfulfocus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mindfulfocus.MainActivity
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.R
import com.mindfulfocus.core.PomodoroPhase
import com.mindfulfocus.core.RandomPhase
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.core.TimerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps all four timing sessions alive in background and on the lock screen.
 *
 * The loop wakes once per second but does NOT decrement a counter — it calls
 * [com.mindfulfocus.core.SessionController.tickAll], which re-derives everything
 * from wall-clock timestamps. Killing/restarting the service therefore never loses
 * time: on the next tick the sessions are recomputed correctly from persisted
 * snapshots.
 */
class TimerForegroundService : LifecycleService() {

    private val container by lazy { (application as MindFocusApp).container }
    private val controller by lazy { container.controller }

    private var tickJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID, buildNotification("准备就绪"))
        startTicking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // If the user swipes the task away, don't kill the timer: restart sticky.
        return START_STICKY
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val events = controller.tickAll()
                    handleEvents(events)
                    updateOngoingNotification()
                } catch (e: Exception) {
                    // Never let one bad tick kill the loop.
                }
                delay(1000L)
            }
        }
    }

    private suspend fun handleEvents(events: List<TimerEvent>) {
        events.forEach { event ->
            when (event) {
                is TimerEvent.Ring -> {
                    // Audio is already played by the controller; this is a hook
                    // if you want a full-screen heads-up for a finished pomodoro.
                }
                is TimerEvent.PhaseChanged -> {
                    postPhaseChangedNotification(event.from, event.to)
                }
                is TimerEvent.BlindBell, is TimerEvent.CloseBell -> {
                    // Blind-box bells are synthesised by the controller.
                }
                TimerEvent.Finished -> {
                    // Record meditation history if the finished session was meditation.
                    val all = controller.observeAllSessions().first()
                    all[SessionType.MEDITATION]?.let { snap ->
                        if (snap.status == RunStatus.IDLE && snap.phaseEndEpochMs == 0L) {
                            controller.recordFinishedMeditation(snap)
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateOngoingNotification() {
        val all = controller.observeAllSessions().first()
        val running = all.values.filter {
            it.status == RunStatus.RUNNING || (it.status == RunStatus.PAUSED && it.elapsedAccumulatedMs > 0)
        }
        val text = if (running.isEmpty()) "准备就绪"
        else running.joinToString(" · ") { describe(it) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun describe(s: SessionSnapshot): String {
        val label = when (s.type) {
            SessionType.INTERVAL_BELL -> "定时提醒"
            SessionType.MEDITATION -> "冥想"
            SessionType.RANDOM_MINDFUL -> when (s.randomPhase) {
                RandomPhase.FOCUS -> "正念盲盒·专注"
                RandomPhase.REST -> "正念盲盒·觉察"
                RandomPhase.DONE -> "正念盲盒·已完成"
            }
            SessionType.POMODORO -> when (s.pomodoroPhase) {
                PomodoroPhase.FOCUS -> "专注"
                PomodoroPhase.SHORT_BREAK -> "短休息"
                PomodoroPhase.LONG_BREAK -> "长休息"
            }
        }
        val status = if (s.status == RunStatus.PAUSED) "已暂停" else "进行中"
        return "$label($status)"
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正念铃 · 番茄钟")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun postPhaseChangedNotification(from: PomodoroPhase, to: PomodoroPhase) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when (to) {
            PomodoroPhase.FOCUS -> "休息结束，开始新的专注"
            PomodoroPhase.SHORT_BREAK -> "专注完成，短休息一下"
            PomodoroPhase.LONG_BREAK -> "专注完成，长休息一下"
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("番茄钟")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(ALERT_ID, n)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "计时进行中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示正在运行的计时器状态"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "阶段提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "番茄钟阶段切换提醒"
                enableVibration(true)
            }
        )
    }

    override fun onDestroy() {
        tickJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val CHANNEL_ONGOING = "timer_ongoing"
        private const val CHANNEL_ALERT = "timer_alert"
        private const val NOTIF_ID = 1001
        private const val ALERT_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerForegroundService::class.java))
        }
    }
}
