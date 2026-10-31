package com.mindfulfocus.ui.bell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfulfocus.core.BlindBoxSound
import com.mindfulfocus.core.RandomMindfulSettings
import com.mindfulfocus.core.RandomPhase
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.ui.components.CountdownRing
import com.mindfulfocus.ui.components.NumberStepperRow
import com.mindfulfocus.ui.components.RoundActionButton
import com.mindfulfocus.ui.components.SettingRow
import com.mindfulfocus.ui.formatDuration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mode 3 — 正念盲盒 (mindful blind box), a faithful port of the reference
 * prototype `mindful-blindbox-timer.html`:
 *
 * focus (随机时长，静候铃声) → 铃响 → rest (觉察呼吸若干秒) → focus → …
 * until the session cap is reached, at which point a closing chime plays.
 */
@Composable
fun BlindBoxPane(vm: BellViewModel, now: Long) {
    val settings by vm.randomSettings.collectAsStateWithLifecycle()
    val session by vm.randomSession.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val isRunning = session.status == RunStatus.RUNNING
    val isPaused = session.status == RunStatus.PAUSED
    val isDone = session.randomPhase == RandomPhase.DONE
    val inRest = isRunning && session.randomPhase == RandomPhase.REST

    val remaining = when {
        isRunning -> session.remainingMs(now)
        isPaused -> session.pausedRemainingMs
        else -> 0L
    }

    val subtitle = when {
        isDone -> "本次盲盒已完成"
        isPaused -> "已暂停"
        inRest -> "觉察呼吸"
        isRunning -> "静候下一声铃"
        else -> "开始一段未知的静心"
    }

    // One-shot ring flash for ~1.4 s after a bell, like the prototype's halo pulse.
    val sinceBell = if (session.bellFlashAtEpochMs > 0L) now - session.bellFlashAtEpochMs else Long.MAX_VALUE
    val flash = if (sinceBell in 0L..1400L) 1f - (sinceBell / 1400f) else 0f

    // Slow breathing scale while resting.
    val breathe = rememberInfiniteTransition(label = "breathe")
    val breathScale by breathe.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse),
        label = "breathScale"
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Halo that appears the moment a bell rings.
            if (flash > 0f) {
                Box(
                    Modifier
                        .size((260 + 40 * flash).dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * flash),
                            CircleShape
                        )
                )
            }
            val ringModifier = if (inRest) Modifier.scale(breathScale) else Modifier
            CountdownRing(
                progress = if (isRunning || isPaused) session.phaseProgress(now) else 0f,
                timeText = when {
                    isDone -> "✓"
                    settings.blindMode && (isRunning || isPaused) -> "· · ·"
                    isRunning || isPaused -> formatDuration(remaining)
                    else -> "--:--"
                },
                subtitle = subtitle,
                modifier = ringModifier
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- stats: 响铃次数 / 已专注 / 剩余上限 ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("响铃", "${session.bellCount} 次")
            StatItem("已专注", formatDuration(session.focusMs(now)))
            StatItem(
                "剩余上限",
                if (settings.capMinutes <= 0) "不限"
                else formatDuration(
                    (settings.capMinutes * 60_000L - session.elapsedMs(now)).coerceAtLeast(0L)
                )
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- controls ----
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                isRunning -> {
                    RoundActionButton("暂停", filled = false, onClick = { vm.pause(SessionType.RANDOM_MINDFUL) })
                    RoundActionButton("重置", filled = false, onClick = { vm.stop(SessionType.RANDOM_MINDFUL) })
                }
                isPaused -> {
                    RoundActionButton("继续", filled = true, onClick = { vm.resume(SessionType.RANDOM_MINDFUL) })
                    RoundActionButton("重置", filled = false, onClick = { vm.stop(SessionType.RANDOM_MINDFUL) })
                }
                isDone -> {
                    RoundActionButton("再来", filled = true, onClick = { vm.startRandom() })
                }
                else -> {
                    RoundActionButton("开始", filled = true, onClick = { vm.startRandom() })
                }
            }
        }

        TextButton(onClick = { showSettings = !showSettings }) {
            Text(if (showSettings) "收起设置" else "设置")
        }

        if (showSettings) {
            BlindBoxSettingsCard(
                settings = settings,
                onUpdate = { block -> vm.updateRandom(block) },
                onPreview = { sound -> vm.previewBlindSound(sound) }
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun BlindBoxSettingsCard(
    settings: RandomMindfulSettings,
    onUpdate: ((RandomMindfulSettings) -> RandomMindfulSettings) -> Unit,
    onPreview: (BlindBoxSound) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("盲盒设置", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            FloatStepperRow(
                label = "最短间隔",
                value = settings.minMinutes,
                min = RandomMindfulSettings.MIN_MINUTES,
                max = RandomMindfulSettings.MAX_MINUTES,
                unit = "分钟"
            ) { v ->
                onUpdate { s ->
                    val newMin = v.coerceAtMost(s.maxMinutes)
                    s.copy(minMinutes = newMin)
                }
            }

            FloatStepperRow(
                label = "最长间隔",
                value = settings.maxMinutes,
                min = RandomMindfulSettings.MIN_MINUTES,
                max = RandomMindfulSettings.MAX_MINUTES,
                unit = "分钟"
            ) { v ->
                onUpdate { s ->
                    val newMax = v.coerceAtLeast(s.minMinutes)
                    s.copy(maxMinutes = newMax)
                }
            }

            IntStepperRow(
                label = "觉察呼吸",
                value = settings.restSeconds,
                min = RandomMindfulSettings.MIN_REST_SECONDS,
                max = RandomMindfulSettings.MAX_REST_SECONDS,
                unit = "秒"
            ) { v -> onUpdate { s -> s.copy(restSeconds = v) } }

            IntStepperRow(
                label = "会话上限",
                value = settings.capMinutes,
                min = 0,
                max = RandomMindfulSettings.MAX_CAP_MINUTES,
                unit = "分钟(0=不限)"
            ) { v -> onUpdate { s -> s.copy(capMinutes = v) } }

            Spacer(Modifier.height(8.dp))
            Text("提示音", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlindBoxSound.entries.forEach { sound ->
                    FilterChip(
                        selected = settings.sound == sound,
                        onClick = {
                            onUpdate { s -> s.copy(sound = sound) }
                            onPreview(sound) // 点选即试听
                        },
                        label = { Text(sound.label) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingRow("音量", subtitle = "${settings.volumePercent}%") {
                Slider(
                    value = settings.volumePercent.toFloat(),
                    onValueChange = { v -> onUpdate { s -> s.copy(volumePercent = v.roundToInt()) } },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(0.55f)
                )
            }

            SettingRow("震动", subtitle = "铃响时轻振") {
                Switch(
                    checked = settings.vibrate,
                    onCheckedChange = { v -> onUpdate { s -> s.copy(vibrate = v) } }
                )
            }

            SettingRow("盲盒模式", subtitle = "隐藏倒计时数字") {
                Switch(
                    checked = settings.blindMode,
                    onCheckedChange = { v -> onUpdate { s -> s.copy(blindMode = v) } }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "每段专注时长在最短与最长之间随机抽取，铃响后进入觉察呼吸，然后重新抽取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun FloatStepperRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    onValue: (Float) -> Unit
) {
    NumberStepperRow(
        label = label,
        valueText = formatMinutes(value),
        unit = unit,
        canDecrease = value > min,
        canIncrease = value < max,
        onDecrease = { onValue((value - 0.5f).coerceAtLeast(min)) },
        onIncrease = { onValue((value + 0.5f).coerceAtMost(max)) },
        onCommit = { typed ->
            typed.toFloatOrNull()?.let { onValue(it.coerceIn(min, max)) }
        }
    )
}

@Composable
private fun IntStepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String,
    onValue: (Int) -> Unit
) {
    NumberStepperRow(
        label = label,
        valueText = value.toString(),
        unit = unit,
        canDecrease = value > min,
        canIncrease = value < max,
        onDecrease = { onValue((value - 1).coerceAtLeast(min)) },
        onIncrease = { onValue((value + 1).coerceAtMost(max)) },
        onCommit = { typed -> typed.toIntOrNull()?.let { onValue(it.coerceIn(min, max)) } }
    )
}

private fun formatMinutes(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
