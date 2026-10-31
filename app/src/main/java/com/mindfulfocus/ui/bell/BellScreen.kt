package com.mindfulfocus.ui.bell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfulfocus.core.IntervalBellSettings
import com.mindfulfocus.core.MeditationSettings
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.core.TimeWindow
import com.mindfulfocus.ui.components.CountdownRing
import com.mindfulfocus.ui.components.NumberStepperRow
import com.mindfulfocus.ui.components.RoundActionButton
import com.mindfulfocus.ui.components.SegmentedTabs
import com.mindfulfocus.ui.components.SettingRow
import com.mindfulfocus.ui.formatDuration
import com.mindfulfocus.ui.rememberNow

private val MODE_TITLES = listOf("定时提醒", "冥想练习", "正念盲盒")

@Composable
fun BellScreen(vm: BellViewModel) {
    var mode by remember { mutableIntStateOf(0) }
    val now = rememberNow()

    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SegmentedTabs(options = MODE_TITLES, selectedIndex = mode, onSelect = { mode = it })
        Spacer(Modifier.height(16.dp))
        when (mode) {
            0 -> IntervalBellPane(vm, now)
            1 -> MeditationPane(vm, now)
            else -> BlindBoxPane(vm, now)
        }
    }
}

// ---------------------------------------------------------------------------
// Mode 1 — interval reminder
// ---------------------------------------------------------------------------

@Composable
private fun IntervalBellPane(vm: BellViewModel, now: Long) {
    val settings by vm.intervalSettings.collectAsStateWithLifecycle()
    val session by vm.intervalSession.collectAsStateWithLifecycle()

    val remaining = if (session.status == RunStatus.RUNNING || session.status == RunStatus.PAUSED)
        session.untilNextBellMs(now) else 0L
    val subtitle = when (session.status) {
        RunStatus.RUNNING -> "距离下一次铃声"
        RunStatus.PAUSED -> "已暂停"
        else -> "未开始"
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            progress = if (session.status == RunStatus.RUNNING && settings.intervalMinutes > 0)
                1f - (remaining.toFloat() / (settings.intervalMinutes * 60_000f)).coerceIn(0f, 1f) else 0f,
            timeText = if (session.status == RunStatus.RUNNING) formatDuration(remaining) else "--:--",
            subtitle = subtitle
        )
        Spacer(Modifier.height(16.dp))
        SessionControls(
            session = session,
            onStart = { vm.startInterval() },
            onPause = { vm.pause(SessionType.INTERVAL_BELL) },
            onResume = { vm.resume(SessionType.INTERVAL_BELL) },
            onStop = { vm.stop(SessionType.INTERVAL_BELL) }
        )
        Spacer(Modifier.height(20.dp))
        IntervalSettingsCard(settings, onUpdate = { block -> vm.updateInterval(block) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalSettingsCard(settings: IntervalBellSettings, onUpdate: ((IntervalBellSettings) -> IntervalBellSettings) -> Unit) {
    val presets = listOf(5, 10, 15, 20, 25, 30, 60)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("固定间隔", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    AssistChip(
                        onClick = { onUpdate { it.copy(intervalMinutes = p) } },
                        label = { Text("${p}分") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            StepperRow("自定义间隔", settings.intervalMinutes, 1, 180, "分钟") {
                onUpdate { s -> s.copy(intervalMinutes = it) }
            }
            SwitchRow("启用时段", settings.enabledActiveWindow) { v ->
                onUpdate { s -> s.copy(enabledActiveWindow = v) }
            }
            if (settings.enabledActiveWindow) {
                TimeWindowField("生效时段", settings.activeWindow) { w ->
                    onUpdate { s -> s.copy(activeWindow = w) }
                }
            }
            SwitchRow("免打扰时段", settings.enabledDnd) { v ->
                onUpdate { s -> s.copy(enabledDnd = v) }
            }
            if (settings.enabledDnd) {
                TimeWindowField("免打扰", settings.doNotDisturb ?: TimeWindow(23 * 60, 7 * 60)) { w ->
                    onUpdate { s -> s.copy(doNotDisturb = w) }
                }
            }
            SoundPicker(settings.sound) { v -> onUpdate { s -> s.copy(sound = v) } }
            VolumeSlider(settings.volumePercent) { v -> onUpdate { s -> s.copy(volumePercent = v) } }
            SwitchRow("震动", settings.vibrate) { v -> onUpdate { s -> s.copy(vibrate = v) } }
        }
    }
}

// ---------------------------------------------------------------------------
// Mode 2 — meditation
// ---------------------------------------------------------------------------

@Composable
private fun MeditationPane(vm: BellViewModel, now: Long) {
    val settings by vm.meditationSettings.collectAsStateWithLifecycle()
    val session by vm.meditationSession.collectAsStateWithLifecycle()

    val remaining = when {
        session.status == RunStatus.IDLE -> settings.sessionMinutes * 60_000L
        session.phaseEndEpochMs > 0L -> session.remainingMs(now)
        else -> session.phaseTotalMs
    }
    val progress = if (session.phaseTotalMs > 0 && session.phaseEndEpochMs > 0L)
        remaining.toFloat() / session.phaseTotalMs else 1f
    val subtitle = when (session.status) {
        RunStatus.PAUSED -> "已暂停"
        RunStatus.RUNNING -> "静坐中"
        else -> "准备开始"
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            progress = progress,
            timeText = formatDuration(remaining),
            subtitle = subtitle
        )
        Spacer(Modifier.height(16.dp))
        SessionControls(
            session = session,
            onStart = { vm.startMeditation() },
            onPause = { vm.pause(SessionType.MEDITATION) },
            onResume = { vm.resume(SessionType.MEDITATION) },
            onStop = { vm.stop(SessionType.MEDITATION) }
        )
        Spacer(Modifier.height(20.dp))
        MeditationSettingsCard(settings, onUpdate = { block -> vm.updateMeditation(block) })
    }
}

@Composable
private fun MeditationSettingsCard(settings: MeditationSettings, onUpdate: ((MeditationSettings) -> MeditationSettings) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("冥想设置", style = MaterialTheme.typography.titleSmall)
            StepperRow("练习时长", settings.sessionMinutes, 1, 180, "分钟") {
                onUpdate { s -> s.copy(sessionMinutes = it) }
            }
            StepperRow("开始前准备", settings.prepSeconds, 0, 120, "秒") {
                onUpdate { s -> s.copy(prepSeconds = it) }
            }
            StepperRow("间隔铃间隔", settings.intervalBellMinutes, 0, 60, "分钟(0=关闭)") {
                onUpdate { s -> s.copy(intervalBellMinutes = it) }
            }
            SwitchRow("开始铃", settings.startBell) { v -> onUpdate { s -> s.copy(startBell = v) } }
            SwitchRow("间隔铃", settings.intervalBell) { v -> onUpdate { s -> s.copy(intervalBell = v) } }
            SwitchRow("结束铃", settings.endBell) { v -> onUpdate { s -> s.copy(endBell = v) } }
            SoundPicker(settings.sound) { v -> onUpdate { s -> s.copy(sound = v) } }
            VolumeSlider(settings.volumePercent) { v -> onUpdate { s -> s.copy(volumePercent = v) } }
            SwitchRow("震动", settings.vibrate) { v -> onUpdate { s -> s.copy(vibrate = v) } }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

@Composable
private fun SessionControls(
    session: SessionSnapshot,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        when (session.status) {
            RunStatus.IDLE -> RoundActionButton("开始", filled = true, onClick = onStart)
            RunStatus.RUNNING -> {
                RoundActionButton("暂停", filled = false, onClick = onPause)
                RoundActionButton("停止", filled = false, onClick = onStop)
            }
            RunStatus.PAUSED -> {
                RoundActionButton("继续", filled = true, onClick = onResume)
                RoundActionButton("停止", filled = false, onClick = onStop)
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, unit: String, onValue: (Int) -> Unit) {
    NumberStepperRow(
        label = label,
        valueText = value.toString(),
        unit = unit,
        canDecrease = value > min,
        canIncrease = value < max,
        onDecrease = { if (value > min) onValue(value - 1) },
        onIncrease = { if (value < max) onValue(value + 1) },
        onCommit = { typed -> typed.toIntOrNull()?.let { onValue(it.coerceIn(min, max)) } }
    )
}
