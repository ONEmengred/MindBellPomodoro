package com.mindfulfocus.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.mindfulfocus.core.BellSound
import com.mindfulfocus.core.BlindBoxSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesises every bell sound at runtime — no audio assets in the APK.
 *
 * Design notes (this is what keeps the bells from sounding shrill):
 *  - soft raised-cosine attack instead of an instant onset,
 *  - a low-pass filter per voice, so high partials never dominate,
 *  - low-octave fundamentals (a real bell's body sits well below 1 kHz),
 *  - peak-normalised with real headroom instead of slamming 0 dBFS,
 *  - a short fade-out at the buffer end so releasing the track never clicks.
 *
 * Playback uses the media stream, so the phone's media volume applies on top of
 * the in-app volume slider.
 */
class ToneSynth {

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random.Default

    // ------------------------------------------------------------------
    // Public sounds
    // ------------------------------------------------------------------

    /** 风铃 / 颂钵 / 轻叮咚 — the blind-box alert. */
    fun playBlind(sound: BlindBoxSound, volumePercent: Int) {
        val recipe = when (sound) {
            BlindBoxSound.CHIME -> chimeRecipe(notes = 3 + random.nextInt(3))
            BlindBoxSound.BOWL -> bowlRecipe(base = if (random.nextBoolean()) 196.0 else 196.0 * 1.0293)
            BlindBoxSound.DING -> dingRecipe()
        }
        renderAndPlay(recipe, volumePercent)
    }

    /** 寺院钟 / 颂钵 / 柔和铃 / 木鱼 — used by the other three modes. */
    fun playBell(sound: BellSound, volumePercent: Int) {
        val recipe = when (sound) {
            BellSound.TEMPLE_BELL -> templeRecipe()
            BellSound.SINGING_BOWL -> bowlRecipe(base = 196.0)
            BellSound.GENTLE_CHIME -> chimeRecipe(notes = 2 + random.nextInt(2))
            BellSound.WOOD_BLOCK -> woodRecipe()
        }
        renderAndPlay(recipe, volumePercent)
    }

    /** Low closing chime that marks the end of a blind-box session. */
    fun playClose(volumePercent: Int) {
        renderAndPlay(
            Recipe(
                tones = listOf(
                    Tone(392.00, 0.26, 0.0, 3.2, 0.05),
                    Tone(523.25, 0.17, 0.0, 3.6, 0.05),
                    Tone(261.63, 0.14, 0.18, 4.0, 0.08)
                ),
                lowPassHz = 1900.0,
                peak = 0.55
            ),
            volumePercent
        )
    }

    // ------------------------------------------------------------------
    // Voice recipes
    // ------------------------------------------------------------------

    /**
     * 风铃: soft struck tones from a low pentatonic scale (C4–A4), so the whole
     * voice sits below ~900 Hz. Only one gentle octave partial, heavily low-passed
     * — it reads as a warm glass chime, not a pierce.
     */
    private fun chimeRecipe(notes: Int): Recipe {
        val scale = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 440.00)
        val tones = mutableListOf<Tone>()
        var at = 0.0
        repeat(notes) { i ->
            val f = scale[random.nextInt(scale.size)]
            val gain = 0.28 * (1.0 - i * 0.08)
            val dur = 3.4 - i * 0.2
            tones.add(Tone(f, gain, at, dur, attackSec = 0.075))
            // A single soft octave partial — the shimmer, not the sting.
            tones.add(Tone(f * 2.00, gain * 0.10, at, dur * 0.6, attackSec = 0.075))
            at += 0.26 + (random.nextDouble() - 0.5) * 0.08
        }
        return Recipe(tones, lowPassHz = 1500.0, peak = 0.5)
    }

    /** 颂钵: warm 196 Hz body with inharmonic partials and slow beating. */
    private fun bowlRecipe(base: Double): Recipe {
        val partials = arrayOf(
            doubleArrayOf(1.00, 0.30, 8.0),
            doubleArrayOf(2.00, 0.12, 6.0),
            doubleArrayOf(2.74, 0.075, 5.0),
            doubleArrayOf(4.07, 0.030, 3.4),
            doubleArrayOf(5.43, 0.017, 2.6)
        )
        val tones = mutableListOf<Tone>()
        for (p in partials) {
            val f = base * p[0]
            tones.add(Tone(f, p[1], 0.0, p[2], attackSec = 0.07))
            // Slightly detuned twin creates the slow beat of a real bowl.
            tones.add(Tone(f * 1.0035, p[1] * 0.32, 0.0, p[2], attackSec = 0.07))
        }
        return Recipe(tones, lowPassHz = 2300.0, peak = 0.55)
    }

    /** 寺院钟: deep bronze body, long decay, almost no high content. */
    private fun templeRecipe(): Recipe {
        val base = 220.0
        val partials = arrayOf(
            doubleArrayOf(1.00, 0.30, 7.0),
            doubleArrayOf(2.00, 0.14, 5.5),
            doubleArrayOf(2.76, 0.085, 4.5),
            doubleArrayOf(4.10, 0.035, 3.0),
            doubleArrayOf(5.43, 0.018, 2.2)
        )
        val tones = mutableListOf<Tone>()
        for (p in partials) {
            tones.add(Tone(base * p[0], p[1], 0.0, p[2], attackSec = 0.06))
            tones.add(Tone(base * p[0] * 1.004, p[1] * 0.28, 0.0, p[2], attackSec = 0.06))
        }
        return Recipe(tones, lowPassHz = 2100.0, peak = 0.55)
    }

    /** 木鱼: short and dry, but rounded off so it doesn't click. */
    private fun woodRecipe(): Recipe {
        val tones = listOf(
            Tone(440.0, 0.30, 0.0, 0.22, attackSec = 0.006),
            Tone(660.0, 0.10, 0.0, 0.15, attackSec = 0.006),
            Tone(1100.0, 0.04, 0.0, 0.09, attackSec = 0.006)
        )
        return Recipe(tones, lowPassHz = 2600.0, peak = 0.45)
    }

    /** 轻叮咚: two soft struck notes a fourth apart, both in the mid range. */
    private fun dingRecipe(): Recipe {
        val tones = listOf(
            Tone(392.00, 0.30, 0.0, 3.0, attackSec = 0.07),
            Tone(392.00 * 2.0, 0.055, 0.0, 1.6, attackSec = 0.07),
            Tone(523.25, 0.24, 0.20, 2.8, attackSec = 0.07),
            Tone(523.25 * 2.0, 0.045, 0.20, 1.4, attackSec = 0.07)
        )
        return Recipe(tones, lowPassHz = 1400.0, peak = 0.45)
    }

    // ------------------------------------------------------------------
    // Rendering / playback
    // ------------------------------------------------------------------

    private data class Tone(
        val freq: Double,
        val gain: Double,
        val startSec: Double,
        val durationSec: Double,
        val attackSec: Double = 0.04
    )

    private data class Recipe(
        val tones: List<Tone>,
        val lowPassHz: Double,
        val peak: Double
    )

    private fun renderAndPlay(recipe: Recipe, volumePercent: Int) {
        val level = (volumePercent.coerceIn(0, 100) / 100.0)
        if (level <= 0.0 || recipe.tones.isEmpty()) return
        val pcm = render(recipe, level)
        if (pcm.isEmpty()) return
        playPcm(pcm)
    }

    private fun render(recipe: Recipe, level: Double): ShortArray {
        val tailSec = 0.25
        val totalSec = recipe.tones.maxOf { it.startSec + it.durationSec } + tailSec
        val count = (totalSec * SAMPLE_RATE).toInt() + 1
        if (count <= 0) return ShortArray(0)

        val buffer = DoubleArray(count)
        for (tone in recipe.tones) {
            val start = (tone.startSec * SAMPLE_RATE).toInt()
            val len = (tone.durationSec * SAMPLE_RATE).toInt()
            if (len <= 0 || start >= count) continue
            val end = min(count, start + len)
            val attackSamples = max(2, (tone.attackSec * SAMPLE_RATE).toInt())
            // Exponential decay: down to ~2% of peak at the nominal end of the tone.
            val decay = 3.9 / len.toDouble()
            val w = 2.0 * PI * tone.freq / SAMPLE_RATE
            var i = start
            while (i < end) {
                val n = i - start
                val env = if (n < attackSamples) {
                    // Raised-cosine fade-in: no click, no harsh transient.
                    0.5 * (1.0 - cos(PI * n / attackSamples)) * exp(-decay * n)
                } else {
                    exp(-decay * n)
                }
                buffer[i] += tone.gain * env * sin(w * n)
                i++
            }
        }

        lowPass(buffer, recipe.lowPassHz)

        // Fade the very end out so releasing the AudioTrack can never click.
        val fadeSamples = min(count, (0.18 * SAMPLE_RATE).toInt())
        for (k in 0 until fadeSamples) {
            buffer[count - fadeSamples + k] *= (fadeSamples - k).toDouble() / fadeSamples
        }

        // Normalise to the recipe's headroom target, then apply the user's level.
        var peak = 0.0
        for (v in buffer) peak = max(peak, kotlin.math.abs(v))
        val scale = if (peak > 0.0) (recipe.peak / peak) * level else 0.0

        val out = ShortArray(count)
        for (i in 0 until count) {
            val s = (buffer[i] * scale).coerceIn(-1.0, 1.0)
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** 12 dB/octave biquad low-pass — removes the "ice-pick" top end. */
    private fun lowPass(buffer: DoubleArray, cutoffHz: Double) {
        if (buffer.isEmpty()) return
        val fc = min(cutoffHz, SAMPLE_RATE / 2.5)
        val w0 = 2.0 * PI * fc / SAMPLE_RATE
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.707)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 - cosW) / 2.0) / a0
        val b1 = (1.0 - cosW) / a0
        val b2 = b0
        val a1 = (-2.0 * cosW) / a0
        val a2 = (1.0 - alpha) / a0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in buffer.indices) {
            val x = buffer[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            buffer[i] = y
        }
    }

    private fun playPcm(pcm: ShortArray) {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Media stream: the phone's media volume applies, which is
                        // far gentler than the alarm stream at full blast.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (t: Throwable) {
            return
        }

        try {
            track.write(pcm, 0, pcm.size)
            track.play()
        } catch (t: Throwable) {
            runCatching { track.release() }
            return
        }

        val durationMs = (pcm.size.toLong() * 1000L / SAMPLE_RATE) + 250L
        handler.postDelayed({ runCatching { track.release() } }, durationMs)
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
    }
}
