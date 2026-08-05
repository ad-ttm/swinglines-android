package com.testtube.swinglines

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.InputType
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity(), SensorEventListener {

    /* ---------- views ---------- */
    private lateinit var previewTexture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var recIndicator: TextView
    private lateinit var btnRecord: ImageButton
    private lateinit var btnSpeed: Button
    private lateinit var liveMenu: View
    private lateinit var levelView: LevelView
    private lateinit var reviewPanel: View
    private lateinit var revMenu: View
    private lateinit var playerView: PlayerView
    private lateinit var reviewOverlay: OverlayView
    private lateinit var reviewSeek: SeekBar
    private lateinit var fpsBadge: TextView
    private lateinit var btnRevPlay: Button
    private lateinit var frameCounter: TextView
    private lateinit var jogStrip: JogStrip

    /* ---------- camera state ---------- */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var cameraId: String = "0"
    private var sensorOrientation = 90
    private var facingFront = false
    private var recording = false

    /* ---------- recording state ---------- */
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var pendingUri: Uri? = null
    private var pendingPfd: ParcelFileDescriptor? = null

    /* ---------- speed modes ---------- */
    data class SpeedMode(val label: String, val fps: Int, val size: Size, val highSpeed: Boolean)

    private var modes: List<SpeedMode> = listOf(SpeedMode("Std", 30, Size(1920, 1080), false))
    private var modeIndex = 0

    /* ---------- replay state (ExoPlayer: frame-exact seeking) ---------- */
    private var player: ExoPlayer? = null
    private var reviewUri: Uri? = null
    private var reviewFps = 30
    private var reviewSpeed = 0.25f
    private var reviewPosMs = 0.0
    // dimensions of the recorded frame as displayed (after rotation), used to
    // re-map live-view lines onto the letterboxed replay
    private var recordedFrameW = 1080f
    private var recordedFrameH = 1920f
    private val seekPoll = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && reviewPanel.visibility == View.VISIBLE) {
                if (p.isPlaying) {
                    reviewPosMs = p.currentPosition.toDouble()
                    val dur = p.duration
                    if (dur > 0) {
                        reviewSeek.progress =
                            (reviewPosMs / dur * 1000).roundToInt().coerceIn(0, 1000)
                    }
                }
                updateFrameCounter()
                mainHandler.postDelayed(this, 100)
            }
        }
    }

    /* ---------- throttled, coalesced seeking (spec: never more than one seek
       in flight, at most ~30 per second; always land on the LATEST target) ---------- */
    private var lastSeekAt = 0L
    private var seekQueued = false

    private fun requestSeek() {
        val now = android.os.SystemClock.uptimeMillis()
        val since = now - lastSeekAt
        if (since >= 33) {
            lastSeekAt = now
            player?.seekTo(reviewPosMs.roundToLong())
        } else if (!seekQueued) {
            seekQueued = true
            mainHandler.postDelayed({
                seekQueued = false
                lastSeekAt = android.os.SystemClock.uptimeMillis()
                player?.seekTo(reviewPosMs.roundToLong())
            }, 33 - since)
        }
        // if a seek is already queued, do nothing: it will pick up the latest
        // reviewPosMs when it fires - everything in between is dropped
    }

    private fun updateFrameCounter() {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val total = (dur * reviewFps / 1000).toInt()
        val cur = ((reviewPosMs * reviewFps / 1000).toInt() + 1).coerceIn(1, maxOf(total, 1))
        frameCounter.text = "$cur / $total"
    }

    private fun syncSeekBar() {
        val p = player ?: return
        if (p.duration > 0) {
            reviewSeek.progress = (reviewPosMs / p.duration * 1000).roundToInt().coerceIn(0, 1000)
        }
    }

    /* ---------- spirit level ---------- */
    private var sensorManager: SensorManager? = null
    private var gx = 0f
    private var gy = 9.8f

    private val toolButtons = mutableMapOf<String, Button>()
    private val colors = intArrayOf(
        Color.rgb(255, 59, 48),
        Color.rgb(255, 214, 10),
        Color.rgb(126, 231, 135),
        Color.rgb(77, 201, 255),
        Color.WHITE
    )

    private val prefs by lazy { getSharedPreferences("swinglines", Context.MODE_PRIVATE) }
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) maybeOpenCamera()
            else Toast.makeText(this, "SeePath needs the camera to work", Toast.LENGTH_LONG).show()
        }

    private var pendingPick: ((Uri) -> Unit)? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val cb = pendingPick
            pendingPick = null
            if (uri != null) {
                if (cb != null) cb(uri) else openClip(uri)
            }
        }

    /* ================================================================
       lifecycle
       ================================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewTexture = findViewById(R.id.previewTexture)
        overlay = findViewById(R.id.overlay)
        recIndicator = findViewById(R.id.recIndicator)
        btnRecord = findViewById(R.id.btnRecord)
        btnSpeed = findViewById(R.id.btnSpeed)
        liveMenu = findViewById(R.id.liveMenu)
        levelView = findViewById(R.id.levelView)
        reviewPanel = findViewById(R.id.reviewPanel)
        revMenu = findViewById(R.id.revMenu)
        playerView = findViewById(R.id.playerView)
        reviewOverlay = findViewById(R.id.reviewOverlay)
        reviewSeek = findViewById(R.id.reviewSeek)
        fpsBadge = findViewById(R.id.fpsBadge)
        btnRevPlay = findViewById(R.id.btnRevPlay)
        frameCounter = findViewById(R.id.frameCounter)
        jogStrip = findViewById(R.id.jogStrip)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setupToolbar()
        setupReviewPanel()
        setupCompare()
        setupClipsPanel()
        restoreCurrentLines()
        overlay.onShapesChanged = { persistCurrentLines() }

        previewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                maybeOpenCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                configureTransform(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (previewTexture.isAvailable) maybeOpenCamera()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        if (recording) stopRecording(openReplay = false)
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        paneA?.releaseAll()
        paneB?.releaseAll()
        super.onDestroy()
    }

    private fun maybeOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (cameraDevice == null && previewTexture.isAvailable) openCamera()
    }

    /* ================================================================
       spirit level
       ================================================================ */

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // low-pass filter so the bubble is steady, not jittery
        gx = gx * 0.9f + event.values[0] * 0.1f
        gy = gy * 0.9f + event.values[1] * 0.1f
        var angle = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        // fold to the nearest upright so portrait and landscape both read 0 when level
        angle -= (Math.round(angle / 90f) * 90f)
        if (levelView.visibility == View.VISIBLE) levelView.rollDegrees = angle
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ================================================================
       camera open / preview
       ================================================================ */

    private fun pickCameraId(front: Boolean): String {
        var fallback: String? = null
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val wanted = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
            if (facing == wanted) {
                if (fallback == null) fallback = id
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map?.highSpeedVideoSizes?.isNotEmpty() == true) return id
            }
        }
        return fallback ?: "0"
    }

    private fun buildModes() {
        val ch = cameraManager.getCameraCharacteristics(cameraId)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val list = mutableListOf<SpeedMode>()
        if (map != null) {
            val sizes = map.highSpeedVideoSizes?.sortedByDescending { it.width * it.height } ?: emptyList()
            for (fps in intArrayOf(240, 120)) {
                val size = sizes.firstOrNull { s ->
                    map.getHighSpeedVideoFpsRangesFor(s).any { it.upper == fps }
                }
                if (size != null) list.add(SpeedMode("${fps}fps", fps, size, true))
            }
        }
        list.add(SpeedMode("Std", 30, Size(1920, 1080), false))
        modes = list
        modeIndex = prefs.getInt("modeIndex", 0).coerceIn(0, modes.size - 1)
        btnSpeed.text = modes[modeIndex].label
    }

    private fun openCamera() {
        try {
            cameraId = pickCameraId(facingFront)
            buildModes()
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    Toast.makeText(this@MainActivity, "Camera error $error", Toast.LENGTH_LONG).show()
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Camera permission missing", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun makePreviewSurface(): Surface {
        val mode = modes[modeIndex]
        val st = previewTexture.surfaceTexture!!
        st.setDefaultBufferSize(mode.size.width, mode.size.height)
        previewSurface?.release()
        val s = Surface(st)
        previewSurface = s
        configureTransform(previewTexture.width, previewTexture.height)
        return s
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        if (!previewTexture.isAvailable) return
        try {
            val surface = makePreviewSurface()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                    try { session.setRepeatingRequest(builder.build(), null, mainHandler) } catch (_: Exception) {}
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Preview failed to start", Toast.LENGTH_LONG).show()
                }
            }, mainHandler)
        } catch (e: Exception) {
            Toast.makeText(this, "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Centre-crop ("cover") transform so the preview fills the screen without distortion. */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val mode = modes[modeIndex]
        val bufW = mode.size.width.toFloat()
        val bufH = mode.size.height.toFloat()
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val cx = vw / 2f
        val cy = vh / 2f
        val matrix = Matrix()
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, vw, vh)
            val bufferRect = RectF(0f, 0f, bufH, bufW)
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / bufH, vw / bufW)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else {
            val kx = vw / bufH
            val ky = vh / bufW
            val k = max(kx, ky)
            matrix.postScale(k / kx, k / ky, cx, cy)
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
        }
        previewTexture.setTransform(matrix)
    }

    /* ================================================================
       recording
       ================================================================ */

    private fun toggleRecording() {
        if (recording) stopRecording(openReplay = true) else startRecording()
    }

    private fun startRecording() {
        val device = cameraDevice ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = modes[modeIndex]
        try {
            val name = "swing-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, recordFolder())
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create output file")
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Couldn't open output file")
            pendingUri = uri
            pendingPfd = pfd

            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(
                when {
                    mode.fps >= 240 -> 50_000_000
                    mode.fps >= 120 -> 28_000_000
                    else -> 16_000_000
                }
            )
            rec.setVideoFrameRate(mode.fps)
            rec.setVideoSize(mode.size.width, mode.size.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            val deg = displayRotationDegrees()
            val hint = if (facingFront) (sensorOrientation + deg) % 360
            else (sensorOrientation - deg + 360) % 360
            rec.setOrientationHint(hint)
            val rotated = hint % 180 != 0
            recordedFrameW = if (rotated) mode.size.height.toFloat() else mode.size.width.toFloat()
            recordedFrameH = if (rotated) mode.size.width.toFloat() else mode.size.height.toFloat()
            rec.prepare()
            mediaRecorder = rec
            recorderSurface = rec.surface

            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
            val pSurface = makePreviewSurface()
            val surfaces = listOf(pSurface, rec.surface)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(pSurface)
            builder.addTarget(rec.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        if (mode.highSpeed && session is CameraConstrainedHighSpeedCaptureSession) {
                            builder.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(mode.fps, mode.fps)
                            )
                            val list = session.createHighSpeedRequestList(builder.build())
                            session.setRepeatingBurst(list, null, mainHandler)
                        } else {
                            session.setRepeatingRequest(builder.build(), null, mainHandler)
                        }
                        rec.start()
                        recording = true
                        recIndicator.visibility = View.VISIBLE
                        btnRecord.alpha = 0.5f
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Record start failed: ${e.message}", Toast.LENGTH_LONG).show()
                        abortRecording()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "This speed isn't working on this camera - try another", Toast.LENGTH_LONG).show()
                    abortRecording()
                }
            }

            @Suppress("DEPRECATION")
            if (mode.highSpeed) {
                device.createConstrainedHighSpeedCaptureSession(surfaces, stateCallback, mainHandler)
            } else {
                device.createCaptureSession(surfaces, stateCallback, mainHandler)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_LONG).show()
            abortRecording()
        }
    }

    private fun abortRecording() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        recorderSurface = null
        try { pendingPfd?.close() } catch (_: Exception) {}
        pendingPfd = null
        pendingUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        pendingUri = null
        recording = false
        recIndicator.visibility = View.GONE
        btnRecord.alpha = 1.0f
        startPreview()
    }

    private fun stopRecording(openReplay: Boolean) {
        val rec = mediaRecorder ?: return
        val mode = modes[modeIndex]
        recording = false
        recIndicator.visibility = View.GONE
        btnRecord.alpha = 1.0f
        var ok = true
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try {
            rec.stop()
        } catch (e: Exception) {
            ok = false
        }
        try { rec.release() } catch (_: Exception) {}
        mediaRecorder = null
        recorderSurface = null
        try { pendingPfd?.close() } catch (_: Exception) {}
        pendingPfd = null
        val uri = pendingUri
        pendingUri = null

        if (uri != null) {
            if (ok) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try { contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
            } else {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                Toast.makeText(this, "Too quick - recording discarded", Toast.LENGTH_SHORT).show()
            }
        }

        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        startPreview()

        if (ok && openReplay && uri != null) {
            openReview(uri, mode.fps)
        }
    }

    /* ================================================================
       instant replay (ExoPlayer, frame-exact)
       ================================================================ */

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(this).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.repeatMode = Player.REPEAT_MODE_ONE // replays loop: end rolls back to the start
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayLabel()
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun setupReviewPanel() {
        findViewById<Button>(R.id.btnRevMenu).setOnClickListener {
            revMenu.visibility = if (revMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnRevClose).setOnClickListener { closeReview() }
        findViewById<Button>(R.id.btnBackLive).setOnClickListener { closeReview() }
        findViewById<Button>(R.id.btnLesson).setOnClickListener { toggleLessonRecording() }
        findViewById<Button>(R.id.btnLessonLive).setOnClickListener { toggleLessonRecording() }
        findViewById<Button>(R.id.btnRevCompare).setOnClickListener {
            closeReview()
            openCompare()
        }
        btnRevPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                reviewPosMs = p.currentPosition.toDouble()
            } else {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
        }
        findViewById<Button>(R.id.btnRevStart).setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.pause()
            reviewPosMs = 0.0
            p.seekTo(0)
            syncSeekBar()
            updateFrameCounter()
        }
        findViewById<Button>(R.id.btnRevBack).setOnClickListener { stepFrame(-1) }
        findViewById<Button>(R.id.btnRevFwd).setOnClickListener { stepFrame(1) }
        attachHoldRepeat(findViewById(R.id.btnRevBack)) { stepFrame(-1) }
        attachHoldRepeat(findViewById(R.id.btnRevFwd)) { stepFrame(1) }
        findViewById<Button>(R.id.btnSpeedSlow).setOnClickListener { setSpeed(0.125f) }
        findViewById<Button>(R.id.btnSpeedQuarter).setOnClickListener { setSpeed(0.25f) }
        findViewById<Button>(R.id.btnSpeedFull).setOnClickListener { setSpeed(1.0f) }
        findViewById<Button>(R.id.btnRevUndo).setOnClickListener { reviewOverlay.undo() }
        findViewById<Button>(R.id.btnRevClear).setOnClickListener { reviewOverlay.clearAll() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareCurrent() }
        findViewById<Button>(R.id.btnShareLines).setOnClickListener { shareWithLines() }

        // coarse bar: cheap keyframe seeks while dragging, one exact seek on release
        reviewSeek.max = 1000
        reviewSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = player ?: return
                if (p.isPlaying) p.pause()
                val dur = p.duration
                if (dur > 0) {
                    reviewPosMs = dur.toDouble() * progress / 1000.0
                    requestSeek()
                    updateFrameCounter()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                player?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = player ?: return
                p.setSeekParameters(SeekParameters.EXACT)
                p.seekTo(reviewPosMs.roundToLong())
                updateFrameCounter()
            }
        })

        // jog strip: the frame-accurate control. Relative, geared, always EXACT.
        jogStrip.onGrabbed = {
            val p = player
            if (p != null && p.isPlaying) {
                p.pause()
                reviewPosMs = p.currentPosition.toDouble()
            }
        }
        jogStrip.onFrameStep = { dir ->
            val p = player
            if (p != null) {
                val frameMs = 1000.0 / reviewFps
                val dur = if (p.duration > 0) p.duration.toDouble() else 0.0
                reviewPosMs = wrapPos(reviewPosMs + dir * frameMs, dur)
                requestSeek()
                syncSeekBar()
                updateFrameCounter()
            }
        }
    }

    /**
     * Carry the live-view lines onto the replay. The live view shows a
     * centre-cropped ("cover") camera image while the replay shows the full
     * recorded frame letterboxed ("fit"), so each point is mapped
     * screen -> recorded frame -> replay view, keeping every line pinned to
     * the same ball/body position in both views.
     */
    private fun copyLinesToReview() {
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        val rw = recordedFrameW
        val rh = recordedFrameH
        if (vw <= 0f || vh <= 0f || rw <= 0f || rh <= 0f) return
        // live view: which part of the frame was visible (cover crop)
        val scaleC = max(vw / rw, vh / rh)
        val fw = vw / (scaleC * rw)
        val fh = vh / (scaleC * rh)
        val ox = (1f - fw) / 2f
        val oy = (1f - fh) / 2f
        // replay: where the frame sits on screen (fit / letterbox)
        val scaleF = kotlin.math.min(vw / rw, vh / rh)
        val dw = rw * scaleF / vw
        val dh = rh * scaleF / vh
        val dx = (1f - dw) / 2f
        val dy = (1f - dh) / 2f
        reviewOverlay.shapes.clear()
        for (s in overlay.shapes) {
            val pts = s.pts.map { p ->
                val vx = ox + p.x * fw
                val vy = oy + p.y * fh
                android.graphics.PointF(dx + vx * dw, dy + vy * dh)
            }.toMutableList()
            reviewOverlay.shapes.add(OverlayView.Shape(s.type, s.color, pts))
        }
        reviewOverlay.invalidate()
    }

    private fun openReview(uri: Uri, fps: Int) {
        reviewUri = uri
        reviewFps = fps
        reviewSpeed = if (fps > 60) 0.25f else 1.0f
        reviewPosMs = 0.0
        fpsBadge.text = "${fps}fps"
        clipsPanel.visibility = View.GONE
        reviewPanel.visibility = View.VISIBLE
        revMenu.visibility = View.GONE
        copyLinesToReview()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.setPlaybackSpeed(reviewSpeed)
        p.playWhenReady = true
        mainHandler.removeCallbacks(seekPoll)
        mainHandler.post(seekPoll)
    }

    /**
     * Reverse of copyLinesToReview: whatever is drawn on the replay when it
     * closes becomes the live view's lines, mapped from the letterboxed frame
     * back into the live view's centre-cropped space. This makes the replay
     * the source of truth - lines drawn while analysing survive the trip back.
     */
    private fun copyLinesToLive() {
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        val rw = recordedFrameW
        val rh = recordedFrameH
        if (vw <= 0f || vh <= 0f || rw <= 0f || rh <= 0f) return
        val scaleC = max(vw / rw, vh / rh)
        val fw = vw / (scaleC * rw)
        val fh = vh / (scaleC * rh)
        val ox = (1f - fw) / 2f
        val oy = (1f - fh) / 2f
        val scaleF = kotlin.math.min(vw / rw, vh / rh)
        val dw = rw * scaleF / vw
        val dh = rh * scaleF / vh
        val dx = (1f - dw) / 2f
        val dy = (1f - dh) / 2f
        if (dw <= 0f || dh <= 0f || fw <= 0f || fh <= 0f) return
        overlay.shapes.clear()
        for (s in reviewOverlay.shapes) {
            val pts = s.pts.map { p ->
                val vx = (p.x - dx) / dw
                val vy = (p.y - dy) / dh
                android.graphics.PointF((vx - ox) / fw, (vy - oy) / fh)
            }.toMutableList()
            overlay.shapes.add(OverlayView.Shape(s.type, s.color, pts))
        }
        overlay.invalidate()
        persistCurrentLines()
    }

    private fun closeReview() {
        copyLinesToLive()
        mainHandler.removeCallbacks(seekPoll)
        player?.pause()
        player?.clearMediaItems()
        reviewUri = null
        reviewPanel.visibility = View.GONE
    }

    private fun setSpeed(s: Float) {
        reviewSpeed = s
        player?.setPlaybackSpeed(s)
    }

    /** Advance with wrap-around: past the end lands on the start and vice versa. */
    private fun wrapPos(pos: Double, durMs: Double): Double {
        if (durMs <= 0) return pos.coerceAtLeast(0.0)
        var v = pos
        if (v >= durMs) v -= durMs
        if (v < 0) v += durMs
        return v.coerceIn(0.0, durMs)
    }

    private fun stepFrame(dir: Int) {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            reviewPosMs = p.currentPosition.toDouble()
        }
        val frameMs = 1000.0 / reviewFps
        val dur = if (p.duration > 0) p.duration.toDouble() else 0.0
        reviewPosMs = wrapPos(reviewPosMs + dir * frameMs, dur)
        p.seekTo(reviewPosMs.roundToLong())
        syncSeekBar()
        updateFrameCounter()
    }

    /** Hold a button to repeat its action (~20 steps a second after a short delay). */
    private fun attachHoldRepeat(b: Button, action: () -> Unit) {
        var tick: Runnable? = null
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val r = object : Runnable {
                        override fun run() {
                            action()
                            mainHandler.postDelayed(this, 50)
                        }
                    }
                    tick = r
                    mainHandler.postDelayed(r, 380)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    tick?.let { mainHandler.removeCallbacks(it) }
                    tick = null
                }
            }
            false
        }
    }

    private fun updatePlayLabel() {
        val playing = player?.isPlaying == true
        btnRevPlay.text = if (playing) "❚❚ Pause" else "▶ Play"
    }

    private fun shareCurrent() {
        val uri = reviewUri ?: return
        shareUri(uri)
    }

    private fun shareUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share swing"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open share sheet: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /* ================================================================
       students (tier 1: per-student folders on this phone)
       ================================================================ */

    private var activeStudent: String
        get() = prefs.getString("activeStudent", "") ?: ""
        set(value) { prefs.edit().putString("activeStudent", value).apply() }

    private fun studentList(): MutableList<String> {
        return try {
            val arr = org.json.JSONArray(prefs.getString("students", "[]") ?: "[]")
            MutableList(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveStudentList(list: List<String>) {
        prefs.edit().putString("students", org.json.JSONArray(list).toString()).apply()
    }

    private fun updateStudentButton() {
        val s = activeStudent
        findViewById<Button>(R.id.btnStudent).text = if (s.isEmpty()) "👤 Student" else "👤 $s"
    }

    private fun recordFolder(): String {
        val s = activeStudent
        return if (s.isEmpty()) "Movies/SeePath" else "Movies/SeePath/$s"
    }

    private fun showStudentPicker() {
        val students = studentList().sorted()
        val items = mutableListOf<String>()
        items.add("✕ No student (general)")
        items.addAll(students)
        items.add("➕ Add new student")
        val builder = AlertDialog.Builder(this)
            .setTitle("Whose swings are these?")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> { activeStudent = ""; updateStudentButton(); Toast.makeText(this, "Recording to the general folder", Toast.LENGTH_SHORT).show() }
                    which == items.size - 1 -> promptAddStudent()
                    else -> {
                        activeStudent = items[which]
                        updateStudentButton()
                        Toast.makeText(this, "Recording to ${items[which]}'s folder", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
        if (students.isNotEmpty()) {
            builder.setNeutralButton("Remove…") { _, _ -> promptRemoveStudent(students) }
        }
        builder.show()
    }

    private fun promptAddStudent() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.hint = "Student's name"
        AlertDialog.Builder(this)
            .setTitle("Add student")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString()
                    .replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(30)
                if (name.isNotEmpty()) {
                    val list = studentList()
                    if (!list.contains(name)) { list.add(name); saveStudentList(list) }
                    activeStudent = name
                    updateStudentButton()
                    Toast.makeText(this, "Recording to $name's folder", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRemoveStudent(students: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Remove which student?")
            .setItems(students.toTypedArray()) { _, which ->
                val list = studentList()
                list.remove(students[which])
                saveStudentList(list)
                if (activeStudent == students[which]) { activeStudent = ""; updateStudentButton() }
                Toast.makeText(this, "Removed (their saved clips stay on the phone)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ================================================================
       clips library + import
       ================================================================ */

    /** Open any clip (ours or imported): read fps/size/rotation from the file
     *  so frame stepping and line mapping stay correct. */
    private fun openClip(uri: Uri) {
        var fps = 30
        var w = 1080f
        var h = 1920f
        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val vw = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1080f
            val vh = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1920f
            val rot = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val cap = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            mmr.release()
            if (rot % 180 != 0) { w = vh; h = vw } else { w = vw; h = vh }
            if (cap != null && cap > 12f) {
                fps = cap.roundToInt()
            } else {
                val ex = android.media.MediaExtractor()
                ex.setDataSource(this, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        if (f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                            fps = f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
                        }
                        break
                    }
                }
                ex.release()
            }
        } catch (_: Exception) {
        }
        fps = fps.coerceIn(12, 300)
        recordedFrameW = w
        recordedFrameH = h
        openReview(uri, fps)
    }

    private data class ClipRow(val name: String, val uri: Uri, val student: String)

    /** Query recorded clips as (label, uri), newest first, optionally filtered to one student. */
    private fun queryClips(filterStudent: String?): List<Pair<String, Uri>> =
        queryClipRows(filterStudent).map { r ->
            (if (filterStudent == null && r.student.isNotEmpty()) "${r.student} / ${r.name}" else r.name) to r.uri
        }

    private fun queryClipRows(filterStudent: String?): List<ClipRow> {
        val out = mutableListOf<ClipRow>()
        try {
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            val sel: String
            val selArgs: Array<String>
            if (filterStudent != null) {
                sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                // trailing slash matters: without it "Sam" also matches "Sammy"
                selArgs = arrayOf("Movies/SeePath/$filterStudent/%")
            } else {
                sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                selArgs = arrayOf("Movies/SeePath%", "Movies/SwingLines%")
            }
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, sel, selArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathI = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext() && out.size <= 60) {
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idI)
                    )
                    val name = c.getString(nameI) ?: "swing"
                    val path = c.getString(pathI) ?: ""
                    val sub = path.removePrefix("Movies/SeePath/").removeSuffix("/")
                    val student = if (sub.isNotEmpty() && !sub.startsWith("Movies")) sub else ""
                    out.add(ClipRow(name, uri, student))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /* ---------- clips: thumbnail grid panel ---------- */

    private lateinit var clipsPanel: View
    private lateinit var clipsGrid: android.widget.GridView
    private lateinit var studentChips: LinearLayout
    private var clipsFilter: String? = null // null = everyone
    private var clipsAdapter: ClipsAdapter? = null
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(48)
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    private fun setupClipsPanel() {
        clipsPanel = findViewById(R.id.clipsPanel)
        clipsGrid = findViewById(R.id.clipsGrid)
        studentChips = findViewById(R.id.studentChips)
        findViewById<Button>(R.id.clipsClose).setOnClickListener { clipsPanel.visibility = View.GONE }
        findViewById<Button>(R.id.clipsImport).setOnClickListener {
            importLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.VideoOnly
                )
            )
        }
        clipsGrid.setOnItemClickListener { _, _, pos, _ ->
            clipsAdapter?.rows?.getOrNull(pos)?.let { openClip(it.uri) }
        }
        clipsGrid.setOnItemLongClickListener { _, _, pos, _ ->
            clipsAdapter?.rows?.getOrNull(pos)?.let { clipActions(it.name, it.uri) }
            true
        }
    }

    private fun showClips() {
        clipsFilter = activeStudent.ifEmpty { null }
        refreshStudentChips()
        refreshClipsGrid()
        liveMenu.visibility = View.GONE
        clipsPanel.visibility = View.VISIBLE
    }

    private fun refreshStudentChips() {
        studentChips.removeAllViews()
        val names = mutableListOf<String?>(null)
        names.addAll(studentList().sorted())
        for (n in names) {
            val b = Button(this)
            b.text = n ?: "Everyone"
            b.isAllCaps = false
            b.textSize = 12f
            b.setTextColor(Color.WHITE)
            b.background = ContextCompat.getDrawable(this, R.drawable.pill_bg)
            b.alpha = if (clipsFilter == n) 1f else 0.55f
            val lp = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(6, 0, 6, 0)
            b.layoutParams = lp
            b.setOnClickListener {
                clipsFilter = n
                refreshStudentChips()
                refreshClipsGrid()
            }
            studentChips.addView(b)
        }
    }

    private fun refreshClipsGrid() {
        clipsAdapter = ClipsAdapter(queryClipRows(clipsFilter))
        clipsGrid.adapter = clipsAdapter
    }

    private inner class ClipsAdapter(val rows: List<ClipRow>) : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val cell: android.widget.FrameLayout
            val img: android.widget.ImageView
            val label: TextView
            if (convertView is android.widget.FrameLayout && convertView.childCount == 2) {
                cell = convertView
                img = cell.getChildAt(0) as android.widget.ImageView
                label = cell.getChildAt(1) as TextView
            } else {
                cell = android.widget.FrameLayout(this@MainActivity)
                cell.layoutParams = android.widget.AbsListView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (170 * resources.displayMetrics.density).toInt()
                )
                img = android.widget.ImageView(this@MainActivity)
                img.layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                img.setBackgroundColor(Color.rgb(20, 27, 22))
                cell.addView(img)
                label = TextView(this@MainActivity)
                val lp = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = android.view.Gravity.BOTTOM
                label.layoutParams = lp
                label.setBackgroundColor(Color.argb(170, 0, 0, 0))
                label.setTextColor(Color.WHITE)
                label.textSize = 10f
                label.maxLines = 2
                label.setPadding(10, 5, 10, 5)
                cell.addView(label)
            }
            val row = rows[position]
            label.text = if (row.student.isNotEmpty() && clipsFilter == null) "${row.student}\n${row.name}" else row.name
            val key = row.uri.toString()
            img.tag = key
            val cached = thumbCache.get(key)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                thumbPool.execute {
                    try {
                        val bmp = contentResolver.loadThumbnail(row.uri, android.util.Size(320, 568), null)
                        thumbCache.put(key, bmp)
                        runOnUiThread { if (img.tag == key) img.setImageBitmap(bmp) }
                    } catch (_: Exception) {
                    }
                }
            }
            return cell
        }
    }

    private fun clipActions(name: String, uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("View", "Share", "Delete")) { _, which ->
                when (which) {
                    0 -> openClip(uri)
                    1 -> shareUri(uri)
                    2 -> deleteClip(uri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteClip(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            if (clipsPanel.visibility == View.VISIBLE) refreshClipsGrid()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android protects this one - delete it from your gallery app", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't delete: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /* ================================================================
       lesson recording (internal compositing: video + lines + voice)
       ================================================================ */

    private var lessonRecording = false
    private var lessonRec: MediaRecorder? = null
    private var lessonGl: GlBitmapRecorder? = null
    private var lessonUri: Uri? = null
    private var lessonPfd: ParcelFileDescriptor? = null
    private var lessonFrame: android.graphics.Bitmap? = null
    private val lessonGrabs = HashMap<TextureView, android.graphics.Bitmap>()
    private var lessonStartNs = 0L
    private var lessonFrameBusy = false
    private val lessonPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val lessonPump = object : Runnable {
        override fun run() {
            if (lessonRecording) {
                captureLessonFrame()
                mainHandler.postDelayed(this, 33)
            }
        }
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLesson()
            else Toast.makeText(this, "Lesson recording needs the microphone for your voice", Toast.LENGTH_LONG).show()
        }

    private fun toggleLessonRecording() {
        if (lessonRecording) {
            stopLesson()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startLesson()
    }

    private fun updateLessonButtons() {
        val label = getString(if (lessonRecording) R.string.lesson_stop else R.string.lesson_start)
        findViewById<Button>(R.id.btnLesson).text = label
        findViewById<Button>(R.id.btnLessonLive).text = label
        findViewById<Button>(R.id.btnLessonCmp).text = label
    }

    private fun startLesson() {
        if (lessonRecording) return
        val sw = overlay.width
        val sh = overlay.height
        if (sw <= 0 || sh <= 0) return
        try {
            val outW = 720
            val outH = ((sh.toFloat() / sw * outW).toInt() / 2) * 2
            val name = "lesson-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, recordFolder())
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("couldn't create output file")
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("couldn't open output file")
            lessonUri = uri
            lessonPfd = pfd

            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            // held from here on, so a throw anywhere below still releases the encoder
            lessonRec = rec
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(6_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(outW, outH)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.prepare()

            lessonFrame = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
            lessonGrabs.clear()
            lessonFrameBusy = false
            val gl = GlBitmapRecorder(outW, outH)
            lessonGl = gl
            gl.start(rec.surface) { ok ->
                runOnUiThread {
                    if (!ok) {
                        Toast.makeText(this, "Couldn't start the recorder on this phone", Toast.LENGTH_LONG).show()
                        abortLesson()
                        return@runOnUiThread
                    }
                    try {
                        rec.start()
                        lessonStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                        lessonRecording = true
                        updateLessonButtons()
                        mainHandler.post(lessonPump)
                        Toast.makeText(this, "Recording the lesson - talk away", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Couldn't start lesson recording: ${e.message}", Toast.LENGTH_LONG).show()
                        abortLesson()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start lesson recording: ${e.message}", Toast.LENGTH_LONG).show()
            abortLesson()
        }
    }

    private fun drawTextureView(
        c: android.graphics.Canvas,
        tv: TextureView?,
        scale: Float,
        rootX: Int,
        rootY: Int
    ) {
        if (tv == null || !tv.isAvailable || tv.width <= 0 || tv.height <= 0) return
        // Grab at OUTPUT scale, not at the view's pixel size. getBitmap scales the
        // surface into whatever bitmap it is given, so reading straight to 720-wide
        // moves roughly a third of the pixels a full 1080 grab would and leaves the
        // drawBitmap below at 1:1. This is the biggest cost in the frame and it runs
        // on the main thread, so it matters.
        val gw = (tv.width * scale).toInt().coerceAtLeast(1)
        val gh = (tv.height * scale).toInt().coerceAtLeast(1)
        val cached = lessonGrabs[tv]
        val g = if (cached == null || cached.width != gw || cached.height != gh) {
            android.graphics.Bitmap.createBitmap(gw, gh, android.graphics.Bitmap.Config.ARGB_8888)
                .also { lessonGrabs[tv] = it }
        } else {
            cached
        }
        try {
            tv.getBitmap(g)
            val loc = IntArray(2)
            tv.getLocationInWindow(loc)
            val dx = (loc[0] - rootX) * scale
            val dy = (loc[1] - rootY) * scale
            val dst = android.graphics.RectF(dx, dy, dx + tv.width * scale, dy + tv.height * scale)
            c.drawBitmap(g, null, dst, lessonPaint)
        } catch (_: Exception) {
        }
    }

    /** Compose one frame of whatever screen the coach is on - live camera,
     *  replay, or the split-screen compare - always video + lines, never UI. */
    private fun captureLessonFrame() {
        if (lessonFrameBusy) return
        val frame = lessonFrame ?: return
        val sw = overlay.width
        if (sw <= 0) return
        val scale = frame.width.toFloat() / sw
        val root = IntArray(2)
        overlay.getLocationInWindow(root)
        val c = android.graphics.Canvas(frame)
        c.drawColor(Color.BLACK)
        when {
            comparePanel.visibility == View.VISIBLE -> {
                drawTextureView(c, paneA?.pv?.videoSurfaceView as? TextureView, scale, root[0], root[1])
                drawTextureView(c, paneB?.pv?.videoSurfaceView as? TextureView, scale, root[0], root[1])
            }
            reviewPanel.visibility == View.VISIBLE -> {
                drawTextureView(c, playerView.videoSurfaceView as? TextureView, scale, root[0], root[1])
                c.save()
                c.scale(scale, scale)
                try { reviewOverlay.draw(c) } catch (_: Exception) {}
                c.restore()
            }
            else -> {
                drawTextureView(c, previewTexture, scale, root[0], root[1])
                c.save()
                c.scale(scale, scale)
                try { overlay.draw(c) } catch (_: Exception) {}
                c.restore()
            }
        }
        lessonFrameBusy = true
        lessonGl?.frame(frame, android.os.SystemClock.elapsedRealtimeNanos() - lessonStartNs) {
            lessonFrameBusy = false
        }
    }

    private fun stopLesson() {
        if (!lessonRecording) return
        lessonRecording = false
        updateLessonButtons()
        mainHandler.removeCallbacks(lessonPump)
        val gl = lessonGl
        lessonGl = null
        gl?.stop {
            runOnUiThread {
                var ok = true
                try { lessonRec?.stop() } catch (_: Exception) { ok = false }
                try { lessonRec?.release() } catch (_: Exception) {}
                lessonRec = null
                try { lessonPfd?.close() } catch (_: Exception) {}
                lessonPfd = null
                val uri = lessonUri
                lessonUri = null
                lessonFrame = null
                lessonGrabs.clear()
                if (uri != null) {
                    try {
                        if (ok) {
                            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(uri, v, null, null)
                            Toast.makeText(this, "Lesson saved - it's in Clips", Toast.LENGTH_LONG).show()
                        } else {
                            contentResolver.delete(uri, null, null)
                            Toast.makeText(this, "Lesson recording failed to save", Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Exception) {
                    }
                }
                if (clipsPanel.visibility == View.VISIBLE) refreshClipsGrid()
            }
        }
    }

    private fun abortLesson() {
        lessonRecording = false
        updateLessonButtons()
        mainHandler.removeCallbacks(lessonPump)
        try { lessonGl?.stop {} } catch (_: Exception) {}
        lessonGl = null
        try { lessonRec?.release() } catch (_: Exception) {}
        lessonRec = null
        try { lessonPfd?.close() } catch (_: Exception) {}
        lessonPfd = null
        lessonUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        lessonUri = null
        lessonFrame = null
        lessonGrabs.clear()
    }

    /* ================================================================
       share with lines burned in
       ================================================================ */

    private fun shareWithLines() {
        val uri = reviewUri ?: return
        if (reviewOverlay.shapes.isEmpty()) {
            Toast.makeText(this, "No lines on this replay - draw some first", Toast.LENGTH_SHORT).show()
            return
        }
        // convert panel-normalised shapes to frame-normalised (undo the letterbox)
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        val rw = recordedFrameW
        val rh = recordedFrameH
        if (vw <= 0f || vh <= 0f) return
        val scaleF = kotlin.math.min(vw / rw, vh / rh)
        val dw = rw * scaleF / vw
        val dh = rh * scaleF / vh
        val dx = (1f - dw) / 2f
        val dy = (1f - dh) / 2f
        val frameShapes = reviewOverlay.shapes.map { s ->
            OverlayView.Shape(s.type, s.color, s.pts.map { p ->
                android.graphics.PointF(
                    ((p.x - dx) / dw).coerceIn(0f, 1f),
                    ((p.y - dy) / dh).coerceIn(0f, 1f)
                )
            }.toMutableList())
        }
        player?.pause()
        val msg = TextView(this).apply {
            setPadding(48, 32, 48, 24)
            text = "Rendering your lines into the video…"
        }
        var cancelled = false
        val dlg = AlertDialog.Builder(this)
            .setTitle("Preparing share")
            .setView(msg)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        BurnExporter.export(this, uri, frameShapes, recordFolder(), object : BurnExporter.Listener {
            override fun onProgress(pct: Int) {
                runOnUiThread { msg.text = "Rendering your lines into the video… $pct%" }
            }

            override fun isCancelled(): Boolean = cancelled

            override fun onDone(out: Uri?) {
                runOnUiThread {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    if (out != null) {
                        Toast.makeText(this@MainActivity, "Saved with lines - sharing", Toast.LENGTH_SHORT).show()
                        shareUri(out)
                    } else if (!cancelled) {
                        Toast.makeText(this@MainActivity, "Couldn't render that one - shared clips stay clean for now", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    /* ================================================================
       split-screen compare
       ================================================================ */

    private inner class CmpPane(
        val pv: PlayerView,
        val choose: Button,
        val pp: Button,
        val seek: SeekBar,
        back: Button,
        fwd: Button,
        jog: JogStrip,
        start: Button
    ) {
        var player: ExoPlayer? = null
        var fps = 30
        var posMs = 0.0
        private var lastSeekAt = 0L
        private var seekQueued = false

        init {
            choose.setOnClickListener { pickClip { uri -> load(uri) } }
            pp.setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) { p.pause(); posMs = p.currentPosition.toDouble() }
                else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            }
            back.setOnClickListener { step(-1) }
            fwd.setOnClickListener { step(1) }
            attachHoldRepeat(back) { step(-1) }
            attachHoldRepeat(fwd) { step(1) }
            start.setOnClickListener {
                val p = player ?: return@setOnClickListener
                p.pause()
                posMs = 0.0
                p.seekTo(0)
                seek.progress = 0
            }
            seek.max = 1000
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val p = player ?: return
                    if (p.isPlaying) p.pause()
                    if (p.duration > 0) {
                        posMs = p.duration.toDouble() * progress / 1000.0
                        requestPaneSeek()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    player?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    val p = player ?: return
                    p.setSeekParameters(SeekParameters.EXACT)
                    p.seekTo(posMs.roundToLong())
                }
            })
            // same jog wheel as the replay screen, per pane
            jog.onGrabbed = {
                val p = player
                if (p != null && p.isPlaying) {
                    p.pause()
                    posMs = p.currentPosition.toDouble()
                }
            }
            jog.onFrameStep = { dir ->
                val p = player
                if (p != null) {
                    val frameMs = 1000.0 / fps
                    val dur = if (p.duration > 0) p.duration.toDouble() else 0.0
                    posMs = wrapPos(posMs + dir * frameMs, dur)
                    requestPaneSeek()
                    if (p.duration > 0) {
                        seek.progress = (posMs / p.duration * 1000).roundToInt().coerceIn(0, 1000)
                    }
                }
            }
        }

        /** Throttled, coalesced seek to the latest target - same rules as the replay screen. */
        private fun requestPaneSeek() {
            val now = android.os.SystemClock.uptimeMillis()
            val since = now - lastSeekAt
            if (since >= 33) {
                lastSeekAt = now
                player?.seekTo(posMs.roundToLong())
            } else if (!seekQueued) {
                seekQueued = true
                mainHandler.postDelayed({
                    seekQueued = false
                    lastSeekAt = android.os.SystemClock.uptimeMillis()
                    player?.seekTo(posMs.roundToLong())
                }, 33 - since)
            }
        }

        fun ensure(): ExoPlayer {
            player?.let { return it }
            val p = ExoPlayer.Builder(this@MainActivity).build()
            p.setSeekParameters(SeekParameters.EXACT)
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    pp.text = if (isPlaying) "❚❚" else "▶"
                }
            })
            pv.player = p
            player = p
            return p
        }

        fun load(uri: Uri) {
            fps = clipFps(uri)
            posMs = 0.0
            val p = ensure()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.setPlaybackSpeed(cmpSpeed)
            p.playWhenReady = false
            choose.visibility = View.GONE
        }

        fun step(dir: Int) {
            val p = player ?: return
            if (p.isPlaying) { p.pause(); posMs = p.currentPosition.toDouble() }
            val frameMs = 1000.0 / fps
            val dur = if (p.duration > 0) p.duration.toDouble() else 0.0
            posMs = wrapPos(posMs + dir * frameMs, dur)
            p.seekTo(posMs.roundToLong())
            if (p.duration > 0) {
                seek.progress = (posMs / p.duration * 1000).roundToInt().coerceIn(0, 1000)
            }
        }

        fun poll() {
            val p = player ?: return
            if (p.isPlaying) {
                posMs = p.currentPosition.toDouble()
                if (p.duration > 0) {
                    seek.progress = (posMs / p.duration * 1000).roundToInt().coerceIn(0, 1000)
                }
            }
        }

        fun releaseAll() {
            player?.release()
            player = null
            choose.visibility = View.VISIBLE
        }
    }

    private var paneA: CmpPane? = null
    private var paneB: CmpPane? = null
    private var cmpSpeed = 1.0f
    private lateinit var comparePanel: View
    private val cmpPoll = object : Runnable {
        override fun run() {
            if (comparePanel.visibility == View.VISIBLE) {
                paneA?.poll()
                paneB?.poll()
                mainHandler.postDelayed(this, 100)
            }
        }
    }

    private fun setupCompare() {
        comparePanel = findViewById(R.id.comparePanel)
        paneA = CmpPane(
            findViewById(R.id.playerA), findViewById(R.id.chooseA),
            findViewById(R.id.ppA), findViewById(R.id.seekA),
            findViewById(R.id.backA), findViewById(R.id.fwdA),
            findViewById(R.id.jogA), findViewById(R.id.startA)
        )
        paneB = CmpPane(
            findViewById(R.id.playerB), findViewById(R.id.chooseB),
            findViewById(R.id.ppB), findViewById(R.id.seekB),
            findViewById(R.id.backB), findViewById(R.id.fwdB),
            findViewById(R.id.jogB), findViewById(R.id.startB)
        )
        findViewById<Button>(R.id.cmpPlayBoth).setOnClickListener {
            paneA?.player?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() }
            paneB?.player?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() }
        }
        findViewById<Button>(R.id.cmpPauseBoth).setOnClickListener {
            paneA?.player?.pause()
            paneB?.player?.pause()
        }
        findViewById<Button>(R.id.cmpQuarter).setOnClickListener { setCmpSpeed(0.25f) }
        findViewById<Button>(R.id.cmpFull).setOnClickListener { setCmpSpeed(1.0f) }
        findViewById<Button>(R.id.btnLessonCmp).setOnClickListener { toggleLessonRecording() }
        findViewById<Button>(R.id.cmpClose).setOnClickListener { closeCompare() }
        findViewById<Button>(R.id.btnCompare).setOnClickListener { openCompare() }
        // undo any stale hidden state from when Compare was a hideable feature
        findViewById<Button>(R.id.btnCompare).visibility = View.VISIBLE
    }

    private fun setCmpSpeed(s: Float) {
        cmpSpeed = s
        paneA?.player?.setPlaybackSpeed(s)
        paneB?.player?.setPlaybackSpeed(s)
    }

    private fun openCompare() {
        if (recording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        // defensive: make sure nothing can sit over the compare panel
        clipsPanel.visibility = View.GONE
        if (reviewPanel.visibility == View.VISIBLE) closeReview()
        liveMenu.visibility = View.GONE
        comparePanel.visibility = View.VISIBLE
        mainHandler.removeCallbacks(cmpPoll)
        mainHandler.post(cmpPoll)
    }

    private fun closeCompare() {
        mainHandler.removeCallbacks(cmpPoll)
        paneA?.releaseAll()
        paneB?.releaseAll()
        comparePanel.visibility = View.GONE
    }

    /** Frame rate of an arbitrary clip, for true frame stepping. */
    private fun clipFps(uri: Uri): Int {
        var fps = 30
        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val cap = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            mmr.release()
            if (cap != null && cap > 12f) {
                fps = cap.roundToInt()
            } else {
                val ex = android.media.MediaExtractor()
                ex.setDataSource(this, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        if (f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                            fps = f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
                        }
                        break
                    }
                }
                ex.release()
            }
        } catch (_: Exception) {
        }
        return fps.coerceIn(12, 300)
    }

    /** Clip picker used by compare panes: session clips plus phone import. */
    private fun pickClip(cb: (Uri) -> Unit) {
        val rows = mutableListOf<Pair<String, Uri?>>()
        rows.add("➕ From your phone (camera roll)" to null)
        rows.addAll(queryClips(null))
        val labels = rows.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose a swing")
            .setItems(labels) { _, which ->
                val (_, uri) = rows[which]
                if (uri == null) {
                    pendingPick = cb
                    importLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly
                        )
                    )
                } else {
                    cb(uri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ================================================================
       toolbar / menus / setups
       ================================================================ */

    private fun setupToolbar() {
        findViewById<Button>(R.id.btnMenu).setOnClickListener {
            liveMenu.visibility = if (liveMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val colorRow = findViewById<LinearLayout>(R.id.colorRow)
        for ((i, c) in colors.withIndex()) {
            val b = Button(this)
            val size = (34 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(8, 8, 8, 8)
            b.layoutParams = lp
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(c)
            bg.setStroke(if (i == 0) 8 else 4, Color.argb(160, 255, 255, 255))
            b.background = bg
            b.setOnClickListener {
                overlay.drawColor = c
                reviewOverlay.drawColor = c
                for (j in 0 until colorRow.childCount) {
                    val cb = colorRow.getChildAt(j) as Button
                    val d = cb.background as GradientDrawable
                    d.setStroke(if (colors[j] == c) 8 else 4, Color.argb(160, 255, 255, 255))
                }
            }
            colorRow.addView(b)
        }

        fun bindTool(id: Int, tool: String) {
            val b = findViewById<Button>(id)
            toolButtons[tool] = b
            b.setOnClickListener {
                overlay.tool = tool
                reviewOverlay.tool = tool
                refreshToolHighlight()
            }
        }
        bindTool(R.id.btnLine, "line")
        bindTool(R.id.btnDraw, "draw")
        bindTool(R.id.btnCircle, "circle")
        refreshToolHighlight()

        findViewById<Button>(R.id.btnGrid).setOnClickListener {
            overlay.showGrid = !overlay.showGrid
            prefs.edit().putBoolean("grid", overlay.showGrid).apply()
        }
        overlay.showGrid = prefs.getBoolean("grid", false)

        findViewById<Button>(R.id.btnUndo).setOnClickListener { overlay.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { overlay.clearAll() }
        findViewById<Button>(R.id.btnFlip).setOnClickListener { flipCamera() }
        findViewById<Button>(R.id.btnCaps).setOnClickListener { showCapabilities() }
        findViewById<Button>(R.id.btnSetups).setOnClickListener { showSetups() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showFeatureSettings() }
        findViewById<Button>(R.id.btnClips).setOnClickListener { showClips() }
        findViewById<Button>(R.id.btnStudent).setOnClickListener { showStudentPicker() }
        updateStudentButton()
        btnRecord.setOnClickListener { toggleRecording() }
        btnSpeed.setOnClickListener { cycleSpeed() }
        applyFeaturePrefs()
    }

    /* ---------------- feature visibility settings ---------------- */

    // Speed is deliberately NOT hideable: hiding it while a slo-mo mode is
    // active would lock the user into that frame rate with no way back.
    private val featureDefs = listOf(
        Triple("feat.draw", "Freehand pen", R.id.btnDraw),
        Triple("feat.circle", "Circle tool", R.id.btnCircle),
        Triple("feat.grid", "Grid button", R.id.btnGrid),
        Triple("feat.flip", "Flip camera button", R.id.btnFlip),
        Triple("feat.caps", "Camera info button", R.id.btnCaps),
        Triple("feat.level", "Spirit level", R.id.levelView),
        Triple("feat.clips", "Clips button", R.id.btnClips),
        Triple("feat.student", "Student folders", R.id.btnStudent)
        // Compare is deliberately NOT hideable (like Speed): a stale hidden
        // setting once made it vanish for the coach and nobody could tell why.
    )

    private fun applyFeaturePrefs() {
        for ((key, _, viewId) in featureDefs) {
            findViewById<View>(viewId).visibility =
                if (prefs.getBoolean(key, true)) View.VISIBLE else View.GONE
        }
    }

    private fun showFeatureSettings() {
        val labels = featureDefs.map { it.second }.toTypedArray()
        val checked = featureDefs.map { prefs.getBoolean(it.first, true) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Features on screen - v" + (try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "?"
            }))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                prefs.edit().putBoolean(featureDefs[which].first, isChecked).apply()
                applyFeaturePrefs()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun cycleSpeed() {
        if (recording) {
            Toast.makeText(this, "Stop recording to change speed", Toast.LENGTH_SHORT).show()
            return
        }
        modeIndex = (modeIndex + 1) % modes.size
        prefs.edit().putInt("modeIndex", modeIndex).apply()
        btnSpeed.text = modes[modeIndex].label
        val m = modes[modeIndex]
        Toast.makeText(
            this,
            if (m.highSpeed) "Slo-mo: ${m.size.width}x${m.size.height} @ ${m.fps}fps" else "Standard speed",
            Toast.LENGTH_SHORT
        ).show()
        startPreview()
    }

    private fun flipCamera() {
        if (recording) {
            Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
            return
        }
        facingFront = !facingFront
        closeCamera()
        if (previewTexture.isAvailable) openCamera()
    }

    private fun refreshToolHighlight() {
        for ((tool, b) in toolButtons) {
            b.alpha = if (tool == overlay.tool) 1.0f else 0.55f
        }
    }

    private fun persistCurrentLines() {
        prefs.edit().putString("current", overlay.serialize()).apply()
    }

    private fun restoreCurrentLines() {
        prefs.getString("current", null)?.let { overlay.loadFrom(it) }
    }

    private fun showSetups() {
        val stored = JSONObject(prefs.getString("setups", "{}") ?: "{}")
        val names = stored.keys().asSequence().toList().sorted()
        val items = names.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Saved line setups")
        if (items.isEmpty()) {
            builder.setMessage("Nothing saved yet. Draw some lines, then use \"Save current\".")
        } else {
            builder.setItems(items) { _, which ->
                overlay.loadFrom(stored.getString(items[which]))
                persistCurrentLines()
                Toast.makeText(this, "Loaded \"${items[which]}\"", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setPositiveButton("Save current") { _, _ -> promptSaveSetup(stored) }
        if (items.isNotEmpty()) {
            builder.setNeutralButton("Delete…") { _, _ -> promptDeleteSetup(stored, items) }
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun promptSaveSetup(stored: JSONObject) {
        if (overlay.shapes.isEmpty()) {
            Toast.makeText(this, "Draw some lines first, then save", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "e.g. Down the line"
        AlertDialog.Builder(this)
            .setTitle("Name this setup")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    stored.put(name, overlay.serialize())
                    prefs.edit().putString("setups", stored.toString()).apply()
                    Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDeleteSetup(stored: JSONObject, items: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle("Delete which setup?")
            .setItems(items) { _, which ->
                stored.remove(items[which])
                prefs.edit().putString("setups", stored.toString()).apply()
                Toast.makeText(this, "Deleted \"${items[which]}\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCapabilities() {
        val sb = StringBuilder()
        try {
            for (id in cameraManager.cameraIdList) {
                val ch = cameraManager.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "Back"
                    CameraMetadata.LENS_FACING_FRONT -> "Front"
                    else -> "Other"
                }
                sb.append("- $facing camera (id $id) -\n")
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val hsSizes = map?.highSpeedVideoSizes
                if (hsSizes == null || hsSizes.isEmpty()) {
                    sb.append("High-speed video: not supported\n")
                } else {
                    sb.append("High-speed video:\n")
                    for (size in hsSizes) {
                        val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                            .joinToString(", ") { "${it.lower}-${it.upper}fps" }
                        sb.append("  $size @ $ranges\n")
                    }
                }
                sb.append("\n")
            }
        } catch (e: Exception) {
            sb.append("Couldn't read camera info: ${e.message}")
        }
        AlertDialog.Builder(this)
            .setTitle("This phone's camera modes")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Close", null)
            .show()
    }
}
