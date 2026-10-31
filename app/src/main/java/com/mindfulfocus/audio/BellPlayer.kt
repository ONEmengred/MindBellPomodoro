package com.mindfulfocus.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mindfulfocus.core.BellSound

/**
 * Plays the bell sounds for 定时提醒 / 冥想练习 / 番茄钟.
 *
 * If a raw resource named after the [BellSound] exists (res/raw/temple_bell.ogg …)
 * it is used. Otherwise the sound is synthesised by [ToneSynth], so the app always
 * has a warm bell instead of falling back to the phone's (often shrill) system
 * notification tone.
 */
class BellPlayer(
    private val context: Context,
    private val synth: ToneSynth
) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loaded = HashMap<BellSound, Int>()

    init {
        BellSound.entries.forEach { bell ->
            val resId = context.resources.getIdentifier(
                bell.rawName(), "raw", context.packageName
            )
            if (resId != 0) {
                loaded[bell] = soundPool.load(context, resId, 1)
            }
        }
    }

    /** Play [sound] at [volumePercent] (0..100) on the media stream. */
    fun play(sound: BellSound, volumePercent: Int) {
        val id = loaded[sound]
        if (id != null) {
            val vol = (volumePercent.coerceIn(0, 100) / 100f)
            soundPool.play(id, vol, vol, 1, 0, 1f)
        } else {
            synth.playBell(sound, volumePercent)
        }
    }

    fun release() {
        soundPool.release()
    }
}

private fun BellSound.rawName(): String = when (this) {
    BellSound.TEMPLE_BELL -> "temple_bell"
    BellSound.SINGING_BOWL -> "singing_bowl"
    BellSound.GENTLE_CHIME -> "gentle_chime"
    BellSound.WOOD_BLOCK -> "wood_block"
}

/** Handles the short haptic pattern used for every alert. */
class VibrationManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun vibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // A single soft pulse: meditation bells shouldn't buzz like an alarm clock.
        val pattern = longArrayOf(0, 180, 120, 180)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }
}
