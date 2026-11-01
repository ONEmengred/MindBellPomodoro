package com.mindfulfocus.ui.diagnostics

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mindfulfocus.core.DiagLog
import com.mindfulfocus.service.AlertNotifier
import com.mindfulfocus.service.AlarmScheduler
import com.mindfulfocus.service.TimerForegroundService
import com.mindfulfocus.ui.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检页：把「铃不响」拆成可以逐项确认的事实。
 *
 * 后台不响铃的可能原因有五六种（通知权限被关、渠道被关、精确闹钟不许、被电池优化
 * 冻结、媒体音量为 0、服务被杀），只看现象分不清是哪一种。这里把每一项都读出来，
 * 并给出对应的修复入口，另外可以手动触发一次测试闹钟 —— 10 秒后不论 App 是否在
 * 前台，都应该响铃并弹出通知。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsCard(now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val diag by DiagLog.state.collectAsState()
    val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    val notifier = AlertNotifier(context)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val notificationsOn = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31
    val channelOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.getNotificationChannel(AlertNotifier.CH_ALERT)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    } else true
    val exactAlarmOn = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
    val mediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val dndOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } else false
    val serviceAlive = diag.lastTickAt > 0L && now - diag.lastTickAt < 5000L

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "自检 · 为什么没响",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "先看下面几项是不是都正常，再点「10 秒后测试闹钟」：把 App 退到后台，10 秒后应该响铃并弹出通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            CheckRow("通知权限", if (notificationsOn) "已允许" else "未允许 —— 通知全部无法显示", notificationsOn)
            CheckRow("铃响提醒渠道", if (channelOn) "已开启" else "已被关闭 —— 请到系统通知设置里打开", channelOn)
            CheckRow(
                "精确闹钟",
                if (exactAlarmOn) "允许" else "不允许 —— 后台会被系统压到不准确",
                exactAlarmOn
            )
            CheckRow(
                "电池优化",
                if (ignoringBattery) "已忽略（不会被冻结）" else "未忽略 —— 部分机型会冻结后台",
                ignoringBattery
            )
            CheckRow(
                "媒体音量",
                "$mediaVolume / $mediaMax" + if (mediaVolume == 0) " —— 铃声会被静音" else "",
                mediaVolume > 0
            )
            CheckRow("后台服务", if (serviceAlive) "在跑" else "没在跑（未开始计时时正常）", serviceAlive)
            if (dndOn) {
                CheckRow("勿扰模式", "开着 —— 通知可能被静音，但铃声走媒体音量", true)
            }

            Text(
                "已排定闹钟：" + if (diag.armedAlarmAt > 0L) {
                    timeFormat.format(Date(diag.armedAlarmAt)) +
                        "（还有 " + formatDuration((diag.armedAlarmAt - now).coerceAtLeast(0L)) + "）"
                } else "无",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "最近一次铃响：" + if (diag.lastRingAt > 0L) {
                    timeFormat.format(Date(diag.lastRingAt)) + " " + diag.lastRingText
                } else "还没有",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "闹钟触发次数：${diag.alarmFiredCount}",
                style = MaterialTheme.typography.bodySmall
            )
            if (diag.lastError.isNotBlank()) {
                Text(
                    "最近错误：" + timeFormat.format(Date(diag.lastErrorAt)) + " " + diag.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            FlowRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { AlarmScheduler.scheduleTest(context, 10_000L) }) {
                    Text("10 秒后测试闹钟")
                }
                OutlinedButton(onClick = { notifier.ring("自检 · 测试通知", "看到这条说明通知通路是通的") }) {
                    Text("发测试通知")
                }
                OutlinedButton(onClick = { TimerForegroundService.start(context) }) {
                    Text("启动后台服务")
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text("通知设置")
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text("精确闹钟设置")
                    }
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text("关闭电池优化")
                }
                OutlinedButton(onClick = { DiagLog.clear() }) {
                    Text("清空日志")
                }
            }

            if (diag.log.isNotEmpty()) {
                Text(
                    "最近日志",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
                diag.log.take(15).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error
        )
    }
}
