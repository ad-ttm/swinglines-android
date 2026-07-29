package com.testtube.swinglines

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var recIndicator: TextView
    private lateinit var btnRecord: ImageButton

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val toolButtons = mutableMapOf<String, Button>()
    private val colors = intArrayOf(
        Color.rgb(255, 59, 48),   // red
        Color.rgb(255, 214, 10),  // yellow
        Color.rgb(126, 231, 135), // green
        Color.rgb(77, 201, 255),  // blue
        Color.WHITE
    )

    private val prefs by lazy { getSharedPreferences("swinglines", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "SwingLines needs the camera to work", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        recIndicator = findViewById(R.id.recIndicator)
        btnRecord = findViewById(R.id.btnRecord)

        setupToolbar()
        restoreCurrentLines()
        overlay.onShapesChanged = { persistCurrentLines() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /* ---------------- toolbar ---------------- */

    private fun setupToolbar() {
        val colorRow = findViewById<LinearLayout>(R.id.colorRow)
        for ((i, c) in colors.withIndex()) {
            val b = Button(this)
            val size = (34 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(8, 8, 8, 8)
            b.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(c)
            bg.setStroke(if (i == 0) 8 else 4, Color.argb(160, 255, 255, 255))
            b.background = bg
            b.setOnClickListener {
                overlay.drawColor = c
                for ((j, child) in (0 until colorRow.childCount).withIndex()) {
                    val cb = colorRow.getChildAt(child) as Button
                    val d = cb.background as android.graphics.drawable.GradientDrawable
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
        btnRecord.setOnClickListener { toggleRecording() }
    }

    private fun refreshToolHighlight() {
        for ((tool, b) in toolButtons) {
            b.alpha = if (tool == overlay.tool) 1.0f else 0.55f
        }
    }

    /* ---------------- persistence ---------------- */

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

    /* ---------------- camera ---------------- */

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (recording != null) {
            Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    /* ---------------- recording (standard fps in v0.1) ---------------- */

    private fun toggleRecording() {
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }
        val name = "swing-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SwingLines")
        }
        val options = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        recording = vc.output.prepareRecording(this, options)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recIndicator.visibility = View.VISIBLE
                        btnRecord.alpha = 0.5f
                    }
                    is VideoRecordEvent.Finalize -> {
                        recIndicator.visibility = View.GONE
                        btnRecord.alpha = 1.0f
                        if (event.hasError()) {
                            Toast.makeText(this, "Recording failed (${event.error})", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Saved to Movies/SwingLines", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {}
                }
            }
    }

    /* ---------------- capability report (shapes v0.2's 240fps work) ---------------- */

    private fun showCapabilities() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        try {
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
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
                val ae = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (ae != null) {
                    sb.append("Standard fps ranges: ")
                    sb.append(ae.joinToString(", ") { "${it.lower}-${it.upper}" })
                    sb.append("\n")
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
