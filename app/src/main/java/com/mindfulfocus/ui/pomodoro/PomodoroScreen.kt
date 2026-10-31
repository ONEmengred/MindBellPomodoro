package com.mindfulfocus.ui.pomodoro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfulfocus.core.PomodoroPhase
import com.mindfulfocus.ui.components.NumberStepperRow
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.ui.components.CountdownRing
import com.mindfulfocus.ui.components.RoundActionButton
import com.mindfulfocus.ui.components.SettingRow
import com.mindfulfocus.ui.formatDuration
import com.mindfulfocus.ui.rememberNow

@Composable
fun PomodoroScreen(vm: PomodoroViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val taskName by vm.taskName.collectAsStateWithLifecycle()
    val now = rememberNow()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(phaseLabel(session), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "已完成 ${session.completedPomodoros} 个番茄",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        val remaining = when {
            session.status == RunStatus.IDLE -> settings.focusMinutes * 60_000L
            session.phaseEndEpochMs > 0L -> session.remainingMs(now)
            else -> session.phaseTotalMs
        }
        val progress = if (session.phaseTotalMs > 0 && session.phaseEndEpochMs > 0L)
            (remaining.toFloat() / session.phaseTotalMs) else 1f

        CountdownRing(
            progress = progress,
            timeText = formatDuration(remaining),
            subtitle = if (session.status == RunStatus.PAUSED) "已暂停" else null
        )
        Spacer(Modifier.height(20.dp))

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                session.status == RunStatus.IDLE -> {
                    RoundActionButton("开始", filled = true, onClick = { vm.start() })
                }
                vm.isAtBoundary(session) -> {
                    RoundActionButton("开始下一段", filled = true, onClick = { vm.startNextPhase() })
                    RoundActionButton("停止", filled = false, onClick = { vm.stop() })
                }
                session.status == RunStatus.RUNNING -> {
                    RoundActionButton("暂停", filled = false, onClick = { vm.pause() })
                    RoundActionButton("停止", filled = false, onClick = { vm.stop() })
                }
                session.status == RunStatus.PAUSED -> {
                    RoundActionButton("继续", filled = true, onClick = { vm.resume() })
                    RoundActionButton("停止", filled = false, onClick = { vm.stop() })
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("当前任务", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = taskName,
                    onValueChange = vm::setTaskName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("这个番茄钟做什么？") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("时长设置", style = MaterialTheme.typography.titleSmall)
                DurationStepper("专注", settings.focusMinutes, 1, 120) {
                    vm.updateSettings { s -> s.copy(focusMinutes = it) }
                }
                DurationStepper("短休息", settings.shortBreakMinutes, 1, 60) {
                    vm.updateSettings { s -> s.copy(shortBreakMinutes = it) }
                }
                DurationStepper("长休息", settings.longBreakMinutes, 1, 90) {
                    vm.updateSettings { s -> s.copy(longBreakMinutes = it) }
                }
                DurationStepper("长休息前番茄数", settings.cyclesBeforeLongBreak, 2, 10) {
                    vm.updateSettings { s -> s.copy(cyclesBeforeLongBreak = it) }
                }
                SettingRow("自动进入下一阶段") {
                    Switch(checked = settings.autoStartNextPhase, onCheckedChange = { v ->
                        vm.updateSettings { s -> s.copy(autoStartNextPhase = v) }
                    })
                }
                SettingRow("震动") {
                    Switch(checked = settings.vibrate, onCheckedChange = { v ->
                        vm.updateSettings { s -> s.copy(vibrate = v) }
                    })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun phaseLabel(s: SessionSnapshot): String = when (s.pomodoroPhase) {
    PomodoroPhase.FOCUS -> "专注"
    PomodoroPhase.SHORT_BREAK -> "短休息"
    PomodoroPhase.LONG_BREAK -> "长休息"
}

@Composable
private fun DurationStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValue: (Int) -> Unit
) {
    NumberStepperRow(
        label = label,
        valueText = value.toString(),
        unit = "分钟",
        canDecrease = value > min,
        canIncrease = value < max,
        onDecrease = { if (value > min) onValue(value - 1) },
        onIncrease = { if (value < max) onValue(value + 1) },
        onCommit = { typed -> typed.toIntOrNull()?.let { onValue(it.coerceIn(min, max)) } }
    )
}
