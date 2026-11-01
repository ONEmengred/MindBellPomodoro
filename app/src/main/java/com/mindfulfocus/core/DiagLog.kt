package com.mindfulfocus.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the in-app self-check screen shows about the background timer machinery. */
data class DiagSnapshot(
    val serviceStartedAt: Long = 0L,
    val lastTickAt: Long = 0L,
    val tickCount: Long = 0L,
    val lastRingAt: Long = 0L,
    val lastRingText: String = "",
    val armedAlarmAt: Long = 0L,
    val lastAlarmAt: Long = 0L,
    val alarmFiredCount: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastError: String = "",
    val log: List<String> = emptyList()
)

/**
 * A tiny ring buffer of "what the timer actually did", persisted to a file so it
 * survives the process being killed.
 *
 * This exists because "铃不响" is invisible from the outside: you cannot tell whether
 * the tick loop died, the alarm never fired, or the bell played into a muted stream.
 * Every relevant step is logged here and shown on the 自检 page.
 */
object DiagLog {

    private const val MAX_LINES = 120
    private const val FILE_NAME = "diag.log"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    private val _state = MutableStateFlow(DiagSnapshot())
    val state: StateFlow<DiagSnapshot> = _state.asStateFlow()

    @Volatile
    private var file: File? = null

    fun attach(context: Context) {
        if (file == null) {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            file = f
            val existing = runCatching {
                if (f.exists()) f.readLines().takeLast(MAX_LINES) else emptyList()
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(log = existing.reversed())
        }
    }

    fun serviceStarted() {
        _state.value = _state.value.copy(serviceStartedAt = System.currentTimeMillis())
        append("前台服务已启动")
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val count = _state.value.tickCount + 1
        _state.value = _state.value.copy(lastTickAt = now, tickCount = count)
    }

    fun ring(text: String) {
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(lastRingAt = now, lastRingText = text)
        append("铃响：$text")
    }

    fun alarmArmed(at: Long) {
        _state.value = _state.value.copy(armedAlarmAt = at)
    }

    fun alarmFired() {
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            lastAlarmAt = now,
            alarmFiredCount = _state.value.alarmFiredCount + 1
        )
        append("精确闹钟触发")
    }

    fun error(where: String, t: Throwable) {
        val now = System.currentTimeMillis()
        val message = "$where: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}"
        _state.value = _state.value.copy(lastErrorAt = now, lastError = message)
        append("错误 $message")
    }

    fun note(text: String) = append(text)

    fun clear() {
        _state.value = DiagSnapshot()
        runCatching { file?.writeText("") }
    }

    private fun append(text: String) {
        val line = "${timeFormat.format(Date())}  $text"
        _state.value = _state.value.copy(log = (listOf(line) + _state.value.log).take(MAX_LINES))
        val f = file ?: return
        runCatching {
            // Rewrite trimmed: the file stays small (max ~120 lines).
            val kept = (if (f.exists()) f.readLines().takeLast(MAX_LINES - 1) else emptyList()) + line
            f.writeText(kept.joinToString("\n"))
        }
    }
}
