package com.testtube.swinglines

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Listens for the crack of club on ball and records when each one happened.
 *
 * This is possible without touching the capture path at all because the swing
 * recorder captures video only: no audio source is set on its MediaRecorder, so
 * the microphone is free the whole time the camera is rolling.
 *
 * Detection is a transient test rather than a loudness test. A strike is a very
 * sharp rise over the surrounding noise floor, and it is that suddenness which
 * separates it from wind, a mower, or someone talking nearby. The floor tracks
 * the range slowly, so the detector settles into whatever the place sounds like
 * instead of needing a fixed threshold.
 *
 * It cannot tell Rich's ball from the ball in the next bay. That is what the
 * sensitivity setting is for, and why the long recording is kept rather than
 * thrown away after cutting.
 */
class StrikeDetector(private val sensitivity: Int) {

    /** Times of detected strikes, in ms from the start of listening. */
    val strikes = mutableListOf<Long>()

    private var thread: Thread? = null
    @Volatile private var running = false

    companion object {
        const val OFF = 0
        const val LOW = 1
        const val MEDIUM = 2
        const val HIGH = 3

        private const val RATE = 44100
        private const val WINDOW = 512          // about 11.6ms of audio
        private const val REFRACTORY_MS = 700L  // one strike cannot fire twice
        private const val FLOOR_RISE = 0.02f    // how fast the noise floor tracks

        fun label(sensitivity: Int): String = when (sensitivity) {
            LOW -> "Auto: low"
            MEDIUM -> "Auto: med"
            HIGH -> "Auto: high"
            else -> "Auto: off"
        }

        /** How far above the noise floor a sound has to jump to count. */
        private fun factor(sensitivity: Int): Float = when (sensitivity) {
            LOW -> 7.0f
            MEDIUM -> 4.5f
            else -> 3.0f
        }

        /** Absolute floor, so a quiet room does not trigger on nothing. */
        private const val MIN_LEVEL = 0.015f
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (sensitivity == OFF) return false
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                // CAMCORDER rather than MIC: it prefers the mic pointing the same
                // way as the camera, and it does not apply the noise suppression
                // and gain control that VOICE sources do, which would flatten the
                // very transient being looked for.
                MediaRecorder.AudioSource.CAMCORDER,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, WINDOW * 8)
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Exception) {}
            return false
        }
        try {
            rec.startRecording()
        } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) {}
            return false
        }
        running = true
        val t = Thread {
            val buf = ShortArray(WINDOW)
            val threshold = factor(sensitivity)
            var floor = -1f
            var samples = 0L
            var lastStrikeMs = -REFRACTORY_MS
            while (running) {
                val n = try { rec.read(buf, 0, WINDOW) } catch (_: Exception) { -1 }
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val level = sqrt(sum / n).toFloat()
                // time of the END of this window, measured in samples read, so it
                // does not drift with thread scheduling the way a clock would
                samples += n
                val atMs = samples * 1000L / RATE
                if (floor < 0f) {
                    floor = level // first window sets the starting floor
                } else {
                    val jumped = level > floor * threshold && level > MIN_LEVEL
                    if (jumped && atMs - lastStrikeMs >= REFRACTORY_MS) {
                        lastStrikeMs = atMs
                        synchronized(strikes) { strikes.add(atMs) }
                    }
                    // let the floor follow the room, but never let a strike drag
                    // it up: that would deafen the detector to the next one
                    if (!jumped) floor += (level - floor) * FLOOR_RISE
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        thread = t
        return true
    }

    /** Stop listening and return the strike times found, in order. */
    fun stop(): List<Long> {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        return synchronized(strikes) { strikes.toList() }
    }
}
