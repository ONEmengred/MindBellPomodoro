package com.mindfulfocus.ui.bell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mindfulfocus.core.BellSound
import com.mindfulfocus.core.TimeWindow
import com.mindfulfocus.ui.components.SettingRow
import com.mindfulfocus.ui.formatMinuteOfDay

/** A tap-to-edit HH:mm time window (start..end). */
@Composable
fun TimeWindowField(
    title: String,
    window: TimeWindow,
    onChange: (TimeWindow) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    SettingRow(
        title = title,
        subtitle = "${formatMinuteOfDay(window.startMinuteOfDay)} – ${formatMinuteOfDay(window.endMinuteOfDay)}"
    ) {
        TextButton(onClick = { editing = true }) { Text("编辑") }
    }
    if (editing) {
        TimeWindowDialog(window, onDismiss = { editing = false }, onConfirm = {
            onChange(it); editing = false
        })
    }
}

@Composable
private fun TimeWindowDialog(
    window: TimeWindow,
    onDismiss: () -> Unit,
    onConfirm: (TimeWindow) -> Unit
) {
    var startText by remember { mutableStateOf(formatMinuteOfDay(window.startMinuteOfDay)) }
    var endText by remember { mutableStateOf(formatMinuteOfDay(window.endMinuteOfDay)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置时段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始 (HH:mm)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("结束 (HH:mm)") },
                    singleLine = true
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = parseHm(startText)
                val e = parseHm(endText)
                if (s == null || e == null) error = "请输入 HH:mm 格式，例如 08:00"
                else onConfirm(TimeWindow(s, e))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun parseHm(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Dropdown to pick the bell sound. */
@Composable
fun SoundPicker(
    sound: BellSound,
    onChange: (BellSound) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow("铃声") {
        Column {
            Row(Modifier.clickable { expanded = true }.padding(4.dp)) {
                Text(sound.label, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                BellSound.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        onClick = { onChange(s); expanded = false }
                    )
                }
            }
        }
    }
}

/** Volume slider (0..100). */
@Composable
fun VolumeSlider(volumePercent: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("音量 ${volumePercent}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
