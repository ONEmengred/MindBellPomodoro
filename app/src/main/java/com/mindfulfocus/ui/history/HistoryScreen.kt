package com.mindfulfocus.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfulfocus.ui.components.NaiWaEasterEgg
import com.mindfulfocus.ui.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val meditations by vm.meditations.collectAsStateWithLifecycle()
    val pomodoros by vm.pomodoros.collectAsStateWithLifecycle()
    val totalMeditation by vm.totalMeditationMs.collectAsStateWithLifecycle()
    val completedPomodoros by vm.completedPomodoroCount.collectAsStateWithLifecycle()
    val totalFocus by vm.totalFocusMs.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("累计统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    StatLine("冥想总时长", formatDuration(totalMeditation))
                    StatLine("完成番茄数", "$completedPomodoros 个")
                    StatLine("专注总时长", formatDuration(totalFocus))
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("冥想记录", style = MaterialTheme.typography.titleSmall)
                Text("${meditations.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (meditations.isEmpty()) {
            item { Text("还没有冥想记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(meditations) { r ->
            HistoryLine(
                title = if (r.completed) "完成冥想" else "提前结束",
                right = formatDuration(r.practicedMs),
                time = formatTime(r.startedAtEpochMs)
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("番茄记录", style = MaterialTheme.typography.titleSmall)
                Text("${pomodoros.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pomodoros.isEmpty()) {
            item { Text("还没有番茄记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(pomodoros) { r ->
            HistoryLine(
                title = if (r.completed) "完成专注" else "提前结束",
                right = formatDuration(r.focusedMs),
                time = formatTime(r.startedAtEpochMs)
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.clearAll() }) { Text("清空全部记录") }
            Spacer(Modifier.height(28.dp))
            // 彩蛋藏在这里：页面滚到底才看得到，小、淡、还想劝你别点。
            NaiWaEasterEgg(Modifier.padding(start = 2.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryLine(title: String, right: String, time: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(right, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
