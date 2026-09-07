package com.testtube.swinglines

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Cuts a single swing out of a long recording.
 *
 * Nothing is re-encoded: the compressed frames are copied straight from one MP4
 * into another. That makes it fast and lossless, and it means a 240fps clip
 * comes out still being a 240fps clip.
 *
 * One consequence of copying rather than re-encoding: a clip can only begin on a
 * keyframe, and MediaRecorder writes roughly one a second. So a cut asking to
 * start two seconds before the strike may actually start nearer three. That is
 * harmless here, and better than the alternative of cutting late.
 */
object SwingCutter {

    /** Rotation the source was recorded at, so the cut plays the right way up. */
    fun rotationOf(ctx: Context, src: Uri): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, src)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Total length of the source in microseconds, or 0 if it cannot be read. */
    fun durationUsOf(ctx: Context, src: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, src)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000L
        } catch (_: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copy everything between startUs and endUs into dst. Returns true only if
     * frames were actually written, so a cut that lands past the end of the
     * recording reports failure rather than leaving an empty file behind.
     */
    fun cut(
        ctx: Context,
        src: Uri,
        dst: FileDescriptor,
        startUs: Long,
        endUs: Long,
        rotation: Int
    ): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, src, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    track = i
                    format = f
                    break
                }
            }
            val vf = format ?: return false
            if (track < 0) return false
            ex.selectTrack(track)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val m = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            if (rotation != 0) m.setOrientationHint(rotation)
            val outTrack = m.addTrack(vf)
            m.start()

            val declared = if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(maxOf(declared, 2 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var base = -1L
            var wrote = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ts = ex.sampleTime
                if (ts < 0) break
                if (ts > endUs) break
                if (base < 0) base = ts
                info.offset = 0
                info.size = size
                // rebase to zero, or the clip opens with a long empty run-in
                info.presentationTimeUs = ts - base
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(outTrack, buf, info)
                wrote++
                if (!ex.advance()) break
            }
            m.stop()
            return wrote > 0
        } catch (_: Exception) {
            return false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }
}
