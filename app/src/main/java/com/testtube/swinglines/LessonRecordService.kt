package com.testtube.swinglines

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast

/**
 * Records the screen plus the microphone into a lesson video, using Android's
 * MediaProjection. Runs as a foreground service because the platform requires
 * one for screen capture. The activity creates the pending MediaStore entry,
 * asks the user for capture permission, and hands both to this service.
 */
class LessonRecordService : Service() {

    companion object {
        const val ACTION_START = "com.testtube.swinglines.LESSON_START"
        const val ACTION_STOP = "com.testtube.swinglines.LESSON_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_OUTPUT_URI = "outputUri"
        private const val CHANNEL_ID = "seepath_lesson"
        private const val NOTIF_ID = 41
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lesson recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("SeePath is recording the lesson")
            .setContentText("Screen and voice are being recorded")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun start(intent: Intent) {
        try {
            // foreground first: the platform requires it before capture begins
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, notification())
            }

            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                ?: throw IllegalStateException("no capture permission data")
            outputUri = Uri.parse(intent.getStringExtra(EXTRA_OUTPUT_URI))

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj

            val dm = resources.displayMetrics
            // even dimensions, capped so every phone's encoder copes
            var w = dm.widthPixels
            var h = dm.heightPixels
            val cap = 1080
            if (w > cap) {
                h = (h.toLong() * cap / w).toInt()
                w = cap
            }
            w -= w % 2
            h -= h % 2

            pfd = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: throw IllegalStateException("no output fd")

            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd!!.fileDescriptor)
            rec.setVideoEncodingBitRate(8_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(w, h)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.prepare()
            recorder = rec

            virtualDisplay = proj.createVirtualDisplay(
                "SeePathLesson", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start lesson recording: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(success = false)
            stopSelf()
        }
    }

    private fun stopRecording() {
        var ok = true
        try {
            recorder?.stop()
        } catch (_: Exception) {
            ok = false
        }
        cleanup(success = ok)
        if (ok) {
            Toast.makeText(this, "Lesson saved - it's in Clips", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    private fun cleanup(success: Boolean) {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        val uri = outputUri
        outputUri = null
        if (uri != null) {
            try {
                if (success) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, v, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }
}
