package com.mindfulfocus.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.core.DiagLog
import com.mindfulfocus.core.PomodoroPhase
import com.mindfulfocus.core.RandomPhase
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.core.TimerEngine
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
 *
 * Three kinds of redundancy make the bells dependable:
 *  1. the 1s loop (fast path while the process is alive),
 *  2. an exact [android.app.AlarmManager] alarm for the next due trigger, so Doze /
 *     OEM battery managers cannot swallow a bell ([AlarmScheduler] + [AlarmReceiver]),
 *  3. a heads-up notification for every ring, so the bell is still noticed when the
 *     media volume is low or the phone is muted.
 */
class TimerForegroundService : LifecycleService() {

    private val container by lazy { (application as MindFocusApp).container }
    private val controller by lazy { container.controller }
    private val notifier by lazy { container.alertNotifier }

    private var tickJob: kotlinx.coroutines.Job? = null

    /** Last text pushed to the ongoing notification, so we only notify on change. */
    private var lastOngoingText: String? = null

    /** Epoch of the alarm currently armed, so we only re-arm when it actually moves. */
    private var scheduledTriggerEpochMs: Long = -1L

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(AlertNotifier.ID_ONGOING, notifier.ongoingNotification("准备就绪"))
        lastOngoingText = "准备就绪"
        DiagLog.serviceStarted()
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
                    DiagLog.tick()
                    handleEvents(events)
                    refreshOngoing()
                    syncAlarm()
                } catch (e: Exception) {
                    // Never let one bad tick kill the loop — but do record why.
                    DiagLog.error("tick", e)
                }
                delay(1000L)
            }
        }
    }

    private suspend fun handleEvents(events: List<TimerEvent>) {
        if (events.isEmpty()) return
        // Every ring / phase change / session end becomes a visible alert.
        notifier.notifyForEvents(events)
        events.forEach { event ->
            when (event) {
                is TimerEvent.Ring -> DiagLog.ring("${event.type} 铃声")
                is TimerEvent.BlindBell -> DiagLog.ring("正念盲盒铃声")
                is TimerEvent.CloseBell -> DiagLog.ring("盲盒收尾音")
                is TimerEvent.PhaseChanged -> DiagLog.ring("番茄阶段 ${event.from}→${event.to}")
                TimerEvent.Finished -> DiagLog.note("会话结束")
            }
        }
        if (events.any { it == TimerEvent.Finished }) {
            // Record meditation history if the finished session was meditation.
            val all = controller.observeAllSessions().first()
            all[SessionType.MEDITATION]?.let { snap ->
                if (snap.status == RunStatus.IDLE && snap.phaseEndEpochMs == 0L) {
                    controller.recordFinishedMeditation(snap)
                }
            }
        }
    }

    /** Push the live countdown into the ongoing notification, but only when it changes. */
    private suspend fun refreshOngoing() {
        val text = buildOngoingText()
        if (text == lastOngoingText) return
        lastOngoingText = text
        notifier.updateOngoing(text)
    }

    private suspend fun buildOngoingText(): String {
        val all = controller.observeAllSessions().first()
        val now = System.currentTimeMillis()
        val blindMode = controller.randomMindfulSettings.first().blindMode
        val visible = all.values.filter {
            it.status == RunStatus.RUNNING ||
                (it.status == RunStatus.PAUSED && it.elapsedAccumulatedMs > 0L)
        }
        if (visible.isEmpty()) return "准备就绪"
        return visible.joinToString(" · ") { describe(it, now, blindMode) }
    }

    private fun describe(s: SessionSnapshot, now: Long, blindMode: Boolean): String {
        val label = when (s.type) {
            SessionType.INTERVAL_BELL -> "定时提醒"
            SessionType.MEDITATION -> "冥想"
            SessionType.RANDOM_MINDFUL -> when (s.randomPhase) {
                RandomPhase.FOCUS -> "正念盲盒"
                RandomPhase.REST -> "觉察呼吸"
                RandomPhase.DONE -> "正念盲盒"
            }
            SessionType.POMODORO -> when (s.pomodoroPhase) {
                PomodoroPhase.FOCUS -> "专注"
                PomodoroPhase.SHORT_BREAK -> "短休息"
                PomodoroPhase.LONG_BREAK -> "长休息"
            }
        }
        if (s.status == RunStatus.PAUSED) return "$label 已暂停"
        val hideNumbers = blindMode && s.type == SessionType.RANDOM_MINDFUL
        if (hideNumbers) return label
        val remaining = when {
            s.type == SessionType.INTERVAL_BELL -> s.untilNextBellMs(now)
            s.phaseEndEpochMs > 0L -> s.remainingMs(now)
            else -> 0L
        }
        return if (remaining > 0L) "$label ${formatClock(remaining)}" else label
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val sec = total % 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    /** Keep exactly one exact alarm armed for the nearest pending trigger. */
    private suspend fun syncAlarm() {
        val all = controller.observeAllSessions().first()
        val next = all.values
            .map { TimerEngine.nextTriggerEpochMs(it) }
            .filter { it > 0L }
            .minOrNull() ?: 0L
        if (next == scheduledTriggerEpochMs) return
        scheduledTriggerEpochMs = next
        AlarmScheduler.scheduleNext(this, next)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away may kill the process. The system keeps our alarm, so the
        // session still comes back on time; just record it for the self-check page.
        DiagLog.note("任务被划掉（闹钟仍保留）")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        DiagLog.note("前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
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
