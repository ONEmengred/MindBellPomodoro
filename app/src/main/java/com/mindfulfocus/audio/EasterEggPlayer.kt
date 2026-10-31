package com.mindfulfocus.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.mindfulfocus.R

/**
 * 彩蛋音效：一段笑声。
 *
 * 全 App 只允许存在一个播放实例，任何"别处"的操作都应该把它掐掉，所以这里做成
 * 单例，并对外只暴露 play / stop。
 */
object EasterEggPlayer {

    private const val TAG = "EasterEggPlayer"

    /** 点开彩蛋的那一次触摸也会冒泡到"点击别处"的监听里，用它避开这次误停。 */
    private const val GRACE_MS = 400L

    private var player: MediaPlayer? = null
    private var startedAt = 0L

    val isPlaying: Boolean
        get() = player?.let { runCatching { it.isPlaying }.getOrDefault(false) } ?: false

    fun play(context: Context) {
        stop()
        val created = runCatching {
            MediaPlayer.create(context.applicationContext, R.raw.naiva_laugh)
        }.getOrNull()
        if (created == null) {
            Log.w(TAG, "笑声资源无法播放")
            return
        }
        created.setOnCompletionListener { stop() }
        created.setOnErrorListener { _, _, _ ->
            stop()
            true
        }
        runCatching { created.start() }
            .onSuccess {
                player = created
                startedAt = SystemClock.elapsedRealtime()
            }
            .onFailure {
                runCatching { created.release() }
                Log.w(TAG, "笑声播放失败", it)
            }
    }

    /** 点击/离开屏幕时调用：只停真正的播放，不会误伤刚点开的那一下。 */
    fun stopFromElsewhere() {
        if (SystemClock.elapsedRealtime() - startedAt < GRACE_MS) return
        stop()
    }

    fun stop() {
        val p = player ?: return
        player = null
        startedAt = 0L
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }
}
