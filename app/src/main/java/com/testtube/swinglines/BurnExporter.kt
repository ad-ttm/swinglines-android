package com.testtube.swinglines

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * Renders overlay lines INTO a copy of a clip so shares carry the annotations.
 * Decodes frames with MediaMetadataRetriever, composes frame + shapes onto a
 * bitmap, pushes that to the encoder's input surface through OpenGL ES, and
 * muxes with rewritten timestamps.
 * Output is 30fps (plenty for sharing) and capped at 20 seconds.
 *
 * The GL step is not decoration. A Canvas lock on a MediaCodec input surface is
 * explicitly unsupported by the platform and "may fail or produce unexpected
 * results", which on some devices means a silently black or garbled share.
 */
object BurnExporter {

    interface Listener {
        fun onProgress(pct: Int)
        fun isCancelled(): Boolean
        fun onDone(out: Uri?)
    }

    private const val OUT_FPS = 30
    private const val MAX_MS = 20_000L
    private const val MAX_SIDE = 1280f

    fun export(
        ctx: Context,
        src: Uri,
        shapes: List<OverlayView.Shape>,
        folder: String,
        listener: Listener
    ) {
        Thread {
            val resolver = ctx.contentResolver
            val retriever = MediaMetadataRetriever()
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var surface: Surface? = null
            var gl: GlBitmapRecorder? = null
            var pfd: ParcelFileDescriptor? = null
            var outUri: Uri? = null
            var ok = false
            try {
                retriever.setDataSource(ctx, src)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val sw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1080f
                val sh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1920f
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val w0 = if (rot % 180 != 0) sh else sw
                val h0 = if (rot % 180 != 0) sw else sh
                val sc = min(1f, MAX_SIDE / maxOf(w0, h0))
                val ow = ((w0 * sc).toInt() / 2) * 2
                val oh = ((h0 * sc).toInt() / 2) * 2
                val capMs = minOf(if (durMs > 0) durMs else MAX_MS, MAX_MS)
                val frames = ((capMs / 1000.0) * OUT_FPS).toInt().coerceAtLeast(1)

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh)
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, OUT_FPS)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                encoder.start()

                val name = "swing-lines-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no output uri")
                pfd = resolver.openFileDescriptor(outUri, "w")
                    ?: throw IllegalStateException("no output fd")
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                var track = -1
                var muxStarted = false
                var outCount = 0L
                val info = MediaCodec.BufferInfo()

                fun drain(endStream: Boolean) {
                    var spins = 0
                    while (true) {
                        val idx = encoder!!.dequeueOutputBuffer(info, if (endStream) 10_000 else 0)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (!endStream) return
                                if (++spins > 200) return
                            }
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                track = muxer!!.addTrack(encoder!!.outputFormat)
                                muxer!!.start()
                                muxStarted = true
                            }
                            idx >= 0 -> {
                                val buf = encoder!!.getOutputBuffer(idx)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (buf != null && info.size > 0 && muxStarted) {
                                    // rewrite timestamps so playback speed is exact
                                    info.presentationTimeUs = outCount * 1_000_000L / OUT_FPS
                                    outCount++
                                    muxer!!.writeSampleData(track, buf, info)
                                }
                                encoder!!.releaseOutputBuffer(idx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = ow * 0.006f // matches the on-screen half-thickness lines
                }
                val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                val dst = RectF(0f, 0f, ow.toFloat(), oh.toFloat())

                val g = GlBitmapRecorder(ow, oh)
                gl = g
                val ready = java.util.concurrent.CountDownLatch(1)
                var glOk = false
                g.start(surface!!) { good ->
                    glOk = good
                    ready.countDown()
                }
                ready.await()
                if (!glOk) throw IllegalStateException("GL encoder surface unavailable")

                val composed = android.graphics.Bitmap.createBitmap(
                    ow, oh, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(composed)

                for (i in 0 until frames) {
                    if (listener.isCancelled()) break
                    val tUs = i * 1_000_000L / OUT_FPS
                    val bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    canvas.drawColor(Color.BLACK)
                    if (bmp != null) canvas.drawBitmap(bmp, null, dst, bmpPaint)
                    val w = ow.toFloat()
                    val h = oh.toFloat()
                    for (s in shapes) {
                        stroke.color = s.color
                        if (s.type == "circle" && s.pts.size == 2) {
                            val cx = s.pts[0].x * w
                            val cy = s.pts[0].y * h
                            val r = hypot(
                                ((s.pts[1].x - s.pts[0].x) * w).toDouble(),
                                ((s.pts[1].y - s.pts[0].y) * h).toDouble()
                            ).toFloat()
                            if (r > 2f) canvas.drawCircle(cx, cy, r, stroke)
                        } else {
                            for (j in 1 until s.pts.size) {
                                canvas.drawLine(
                                    s.pts[j - 1].x * w, s.pts[j - 1].y * h,
                                    s.pts[j].x * w, s.pts[j].y * h, stroke
                                )
                            }
                        }
                    }
                    // wait for the upload before the next frame reuses the bitmap
                    val drawn = java.util.concurrent.CountDownLatch(1)
                    g.frame(composed, i * 1_000_000_000L / OUT_FPS) { drawn.countDown() }
                    drawn.await()
                    bmp?.recycle()
                    drain(false)
                    if (i % 5 == 0) listener.onProgress(i * 100 / frames)
                }
                composed.recycle()
                encoder.signalEndOfInputStream()
                drain(true)
                ok = !listener.isCancelled() && outCount > 0
            } catch (_: Exception) {
                ok = false
            } finally {
                // GL context goes first: it holds the encoder's input surface
                try {
                    val stopped = java.util.concurrent.CountDownLatch(1)
                    if (gl == null) stopped.countDown() else gl!!.stop { stopped.countDown() }
                    stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {}
                try { encoder?.stop() } catch (_: Exception) {}
                try { encoder?.release() } catch (_: Exception) {}
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
                try { retriever.release() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
            val finalUri = outUri
            if (finalUri != null) {
                if (ok) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    try { resolver.update(finalUri, v, null, null) } catch (_: Exception) {}
                } else {
                    try { resolver.delete(finalUri, null, null) } catch (_: Exception) {}
                }
            }
            listener.onDone(if (ok) finalUri else null)
        }.start()
    }
}
