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
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
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
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /* ---------- views ---------- */
    private lateinit var previewTexture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var recIndicator: TextView
    private lateinit var btnRecord: ImageButton
    private lateinit var btnSpeed: Button
    private lateinit var reviewPanel: View
    private lateinit var reviewVideo: VideoView
    private lateinit var reviewOverlay: OverlayView
    private lateinit var reviewSeek: SeekBar
    private lateinit var fpsBadge: TextView
    private lateinit var btnRevPlay: Button

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

    /* ---------- review state ---------- */
    private var reviewMp: MediaPlayer? = null
    private var reviewUri: Uri? = null
    private var reviewFps = 30
    private var reviewSpeed = 0.25f
    private var reviewPosMs = 0.0
    private val seekPoll = object : Runnable {
        override fun run() {
            val mp = reviewMp
            if (mp != null && reviewPanel.visibility == View.VISIBLE) {
                try {
                    if (mp.isPlaying) {
                        reviewPosMs = mp.currentPosition.toDouble()
                        if (mp.duration > 0) {
                            reviewSeek.progress =
                                (reviewPosMs / mp.duration * 1000).roundToInt().coerceIn(0, 1000)
                        }
                    }
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, 100)
            }
        }
    }

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
            else Toast.makeText(this, "SwingLines needs the camera to work", Toast.LENGTH_LONG).show()
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
        reviewPanel = findViewById(R.id.reviewPanel)
        reviewVideo = findViewById(R.id.reviewVideo)
        reviewOverlay = findViewById(R.id.reviewOverlay)
        reviewSeek = findViewById(R.id.reviewSeek)
        fpsBadge = findViewById(R.id.fpsBadge)
        btnRevPlay = findViewById(R.id.btnRevPlay)

        setupToolbar()
        setupReviewPanel()
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
    }

    override fun onPause() {
        if (recording) stopRecording(openReplay = false)
        closeCamera()
        super.onPause()
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
            // portrait: content is displayed rotated (bufH x bufW) then stretched to the view
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
            // output file via MediaStore (pending until finished)
            val name = "swing-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SwingLines")
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

    /** Recording failed to start - clean up and delete the empty file. */
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
            ok = false // stopped too quickly - nothing written
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
       instant replay
       ================================================================ */

    private fun setupReviewPanel() {
        findViewById<Button>(R.id.btnRevClose).setOnClickListener { closeReview() }
        btnRevPlay.setOnClickListener {
            val mp = reviewMp ?: return@setOnClickListener
            try {
                if (mp.isPlaying) mp.pause() else {
                    applySpeed(mp, playing = true)
                }
                updatePlayLabel()
            } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.btnRevBack).setOnClickListener { stepFrame(-1) }
        findViewById<Button>(R.id.btnRevFwd).setOnClickListener { stepFrame(1) }
        findViewById<Button>(R.id.btnSpeedSlow).setOnClickListener { setSpeed(0.125f) }
        findViewById<Button>(R.id.btnSpeedQuarter).setOnClickListener { setSpeed(0.25f) }
        findViewById<Button>(R.id.btnSpeedFull).setOnClickListener { setSpeed(1.0f) }
        findViewById<Button>(R.id.btnRevUndo).setOnClickListener { reviewOverlay.undo() }
        findViewById<Button>(R.id.btnRevClear).setOnClickListener { reviewOverlay.clearAll() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareCurrent() }

        reviewSeek.max = 1000
        reviewSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val mp = reviewMp ?: return
                try {
                    if (mp.isPlaying) mp.pause()
                    updatePlayLabel()
                    if (mp.duration > 0) {
                        reviewPosMs = mp.duration.toDouble() * progress / 1000.0
                        mp.seekTo(reviewPosMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    }
                } catch (_: Exception) {}
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        reviewVideo.setOnPreparedListener { mp ->
            reviewMp = mp
            mp.isLooping = false
            reviewPosMs = 0.0
            applySpeed(mp, playing = true)
            updatePlayLabel()
            mainHandler.removeCallbacks(seekPoll)
            mainHandler.post(seekPoll)
        }
        reviewVideo.setOnCompletionListener { updatePlayLabel() }
        reviewVideo.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Playback error ($what/$extra)", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun openReview(uri: Uri, fps: Int) {
        reviewUri = uri
        reviewFps = fps
        reviewSpeed = if (fps > 60) 0.25f else 1.0f
        fpsBadge.text = "${fps}fps"
        reviewPanel.visibility = View.VISIBLE
        reviewVideo.setVideoURI(uri)
    }

    private fun closeReview() {
        mainHandler.removeCallbacks(seekPoll)
        try { reviewVideo.stopPlayback() } catch (_: Exception) {}
        reviewMp = null
        reviewUri = null
        reviewPanel.visibility = View.GONE
    }

    private fun setSpeed(s: Float) {
        reviewSpeed = s
        val mp = reviewMp ?: return
        try {
            if (mp.isPlaying) applySpeed(mp, playing = true)
        } catch (_: Exception) {}
    }

    /** Setting playbackParams starts playback, so only apply when we want to play. */
    private fun applySpeed(mp: MediaPlayer, playing: Boolean) {
        try {
            val params = mp.playbackParams
            params.speed = reviewSpeed
            mp.playbackParams = params
            if (!playing) mp.pause()
        } catch (_: Exception) {
            if (playing) try { mp.start() } catch (_: Exception) {}
        }
    }

    private fun stepFrame(dir: Int) {
        val mp = reviewMp ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                reviewPosMs = mp.currentPosition.toDouble()
            }
            updatePlayLabel()
            val frameMs = 1000.0 / reviewFps
            reviewPosMs = (reviewPosMs + dir * frameMs).coerceIn(0.0, mp.duration.toDouble())
            mp.seekTo(reviewPosMs.roundToInt().toLong(), MediaPlayer.SEEK_CLOSEST)
            if (mp.duration > 0) {
                reviewSeek.progress = (reviewPosMs / mp.duration * 1000).roundToInt().coerceIn(0, 1000)
            }
        } catch (_: Exception) {}
    }

    private fun updatePlayLabel() {
        val playing = try { reviewMp?.isPlaying == true } catch (_: Exception) { false }
        btnRevPlay.text = if (playing) "❚❚ Pause" else "▶ Play"
    }

    private fun shareCurrent() {
        val uri = reviewUri ?: return
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
       toolbar / setups (unchanged behaviour from v0.1)
       ================================================================ */

    private fun setupToolbar() {
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
        btnRecord.setOnClickListener { toggleRecording() }
        btnSpeed.setOnClickListener { cycleSpeed() }
        applyFeaturePrefs()
    }

    /* ---------------- feature visibility settings ---------------- */

    private val featureDefs = listOf(
        Triple("feat.draw", "Freehand pen", R.id.btnDraw),
        Triple("feat.circle", "Circle tool", R.id.btnCircle),
        Triple("feat.grid", "Grid button", R.id.btnGrid),
        Triple("feat.flip", "Flip camera button", R.id.btnFlip),
        Triple("feat.speed", "Speed button", R.id.btnSpeed),
        Triple("feat.caps", "Camera info button", R.id.btnCaps)
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
            .setTitle("Features on screen")
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
