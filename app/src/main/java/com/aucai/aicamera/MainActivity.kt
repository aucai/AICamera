package com.aucai.aicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aucai.aicamera.camera.FrameAnalyzer
import com.aucai.aicamera.camera.LevelSensor
import com.aucai.aicamera.core.GridMode
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.Mode
import com.aucai.aicamera.core.Severity
import com.aucai.aicamera.core.ShotScore
import com.aucai.aicamera.core.Tip
import com.aucai.aicamera.core.TipAction
import com.aucai.aicamera.core.TipCategory
import com.aucai.aicamera.core.TipStabilizer
import com.aucai.aicamera.databinding.ActivityMainBinding
import com.aucai.aicamera.databinding.ViewTipBinding
import com.aucai.aicamera.ui.Speaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var levelSensor: LevelSensor
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stabilizer = TipStabilizer()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var speaker: Speaker? = null

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var mode = Mode.SMART
    private var grid = GridMode.THIRDS
    private var timerSeconds = 0
    private var voiceOn = true

    private var lastFrame: GuidanceFrame? = null
    private var wasAligned = false
    private var lastPhotoUri: Uri? = null
    private var countdownLeft = 0
    private var smoothedScore = -1f

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else binding.permissionPanel.visibility = View.VISIBLE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadSettings()
        applyInsets()

        analyzer = FrameAnalyzer(this) { frame, w, h -> runOnUiThread { onFrame(frame, w, h) } }
        analyzer.grid = grid
        levelSensor = LevelSensor(this) { level ->
            analyzer.level = level
            binding.overlay.level = level
        }
        levelSensor.displayRotationDeg = displayRotationDegrees()

        setupControls()
        renderSettings()
        loadLatestPhoto()

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        levelSensor.start()
    }

    override fun onPause() {
        super.onPause()
        levelSensor.stop()
        cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute { analyzer.close() }
        analysisExecutor.shutdown()
        speaker?.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = displayRotation()
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        levelSensor.displayRotationDeg = displayRotationDegrees()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- camera

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        binding.permissionPanel.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bindUseCases(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val rotation = displayRotation()
        val fourByThree = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val analysisSize = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(fourByThree)
            .setTargetRotation(rotation)
            .build()
        preview.setSurfaceProvider(binding.previewView.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(fourByThree)
            .setTargetRotation(rotation)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)
        analyzer.frontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, capture, analysis)
            this.preview = preview
            imageCapture = capture
            imageAnalysis = analysis
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            toast("相机启动失败：${e.message}")
            return
        }
        stabilizer.reset()
        lastFrame = null
        binding.overlay.clear()
        setupExposure()
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        analyzer.resetRequested = true
        smoothedScore = -1f
        if (hasCameraPermission()) startCamera()
    }

    private fun meterAt(viewX: Float, viewY: Float) {
        val cam = camera ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(viewX, viewY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun setupExposure() {
        val state = camera?.cameraInfo?.exposureState
        val supported = state != null && state.isExposureCompensationSupported
        binding.exposureSeek.isEnabled = supported
        if (state == null || !supported) {
            binding.exposureRow.visibility = View.GONE
            return
        }
        val range = state.exposureCompensationRange
        binding.exposureSeek.max = range.upper - range.lower
        binding.exposureSeek.progress = state.exposureCompensationIndex - range.lower
        updateExposureLabel(state.exposureCompensationIndex)
        binding.exposureRow.visibility = if (mode == Mode.LIGHT) View.VISIBLE else View.GONE
    }

    private fun setExposureIndex(index: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(clamped)
        binding.exposureSeek.progress = clamped - range.lower
        updateExposureLabel(clamped)
    }

    private fun updateExposureLabel(index: Int) {
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        binding.exposureLabel.text = "曝光 %+.1f".format(index * step)
    }

    // ----------------------------------------------------------------- frames

    private fun onFrame(frame: GuidanceFrame, width: Int, height: Int) {
        if (isFinishing) return
        lastFrame = frame
        binding.overlay.update(frame, width, height)

        var tips = frame.tips.filter { it.category in mode.categories }
        if (mode == Mode.POSE && frame.pose == null) {
            tips = tips + Tip("pose.none", TipCategory.POSE, Severity.INFO, "没有检测到人物，把人框进画面")
        }
        val visible = stabilizer.update(SystemClock.elapsedRealtime(), tips)
        renderTips(visible)

        // Smooth the total a little so the number is readable while things move.
        val total = frame.score.total.toFloat()
        smoothedScore = if (smoothedScore < 0f) total else smoothedScore + 0.25f * (total - smoothedScore)
        binding.shutter.score = smoothedScore.roundToInt()
        renderScoreItems(frame.score)

        val aligned = (mode == Mode.SMART || mode == Mode.COMPOSITION) && frame.composition.aligned
        if (aligned && !wasAligned) binding.overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        wasAligned = aligned

        if (voiceOn && countdownLeft == 0) {
            visible.firstOrNull { it.severity != Severity.INFO }?.let { speaker?.say(it.text) }
        }
    }

    private fun renderScoreItems(score: ShotScore) {
        val views = listOf(binding.scoreComposition, binding.scoreLight, binding.scorePose, binding.scoreLevel)
        val byCategory = score.items.associateBy { it.category }
        val order = listOf(TipCategory.COMPOSITION, TipCategory.LIGHT, TipCategory.POSE, TipCategory.LEVEL)
        for ((view, category) in views.zip(order)) {
            val item = byCategory[category] ?: continue
            val head = if (item.applicable) "${category.label} ${item.points}/${item.max}" else "${category.label} —"
            val color = when {
                !item.applicable -> ContextCompat.getColor(this, R.color.text_secondary)
                item.points >= item.max * 0.8f -> ContextCompat.getColor(this, R.color.good)
                item.points >= item.max * 0.5f -> ContextCompat.getColor(this, R.color.accent)
                else -> ContextCompat.getColor(this, R.color.bad)
            }
            val text = SpannableStringBuilder()
                .append(head, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append("\n")
                .append(if (item.applicable) item.note else "无法判断", RelativeSizeSpan(0.85f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.text = text
        }
    }

    private fun renderTips(tips: List<Tip>) {
        bindTip(binding.tip1, tips.getOrNull(0))
        bindTip(binding.tip2, tips.getOrNull(1))
    }

    private fun bindTip(view: ViewTipBinding, tip: Tip?) {
        if (tip == null) {
            view.root.visibility = View.GONE
            return
        }
        view.root.visibility = View.VISIBLE
        view.badge.text = tip.category.label
        view.badge.background.mutate().setTint(
            when (tip.severity) {
                Severity.WARNING -> ContextCompat.getColor(this, R.color.bad)
                Severity.SUGGEST -> ContextCompat.getColor(this, R.color.accent)
                Severity.INFO -> Color.WHITE
            }
        )
        view.text.text = tip.text
        val action = tip.action
        if (action == null) {
            view.action.visibility = View.GONE
            view.action.setOnClickListener(null)
        } else {
            view.action.visibility = View.VISIBLE
            view.action.text = action.label
            view.action.setOnClickListener { performAction(action) }
        }
    }

    private fun performAction(action: TipAction) {
        val state = camera?.cameraInfo?.exposureState ?: return
        when (action) {
            TipAction.METER_SUBJECT -> {
                val p = lastFrame?.lighting?.meterPoint
                if (p == null) {
                    setExposureIndex(state.exposureCompensationIndex + 2)
                } else {
                    val (vx, vy) = binding.overlay.toView(p.x, p.y)
                    meterAt(vx, vy)
                }
                toast("已对人物测光")
            }
            TipAction.EXPOSURE_UP -> setExposureIndex(state.exposureCompensationIndex + 2)
            TipAction.EXPOSURE_DOWN -> setExposureIndex(state.exposureCompensationIndex - 2)
        }
    }

    // ---------------------------------------------------------------- capture

    private fun onShutter() {
        if (countdownLeft > 0) {
            cancelCountdown()
            return
        }
        if (timerSeconds > 0) startCountdown(timerSeconds) else takePhoto()
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (countdownLeft <= 0) return
            countdownLeft--
            if (countdownLeft == 0) {
                binding.countdown.visibility = View.GONE
                takePhoto()
            } else {
                showCountdown()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownLeft = seconds
        showCountdown()
        mainHandler.postDelayed(countdownTick, 1000)
    }

    private fun showCountdown() {
        binding.countdown.visibility = View.VISIBLE
        binding.countdown.text = countdownLeft.toString()
        if (voiceOn && countdownLeft <= 3) speaker?.say(countdownLeft.toString(), force = true)
    }

    private fun cancelCountdown() {
        countdownLeft = 0
        mainHandler.removeCallbacks(countdownTick)
        binding.countdown.visibility = View.GONE
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "AICamera_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
        }
        val metadata = ImageCapture.Metadata().apply {
            // Save selfies mirrored, exactly as they looked in the preview.
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(metadata)
            .build()

        flash()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri ?: return
                lastPhotoUri = uri
                showThumbnail(uri)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture failed", exception)
                toast("拍照失败：${exception.message}")
            }
        })
    }

    private fun flash() {
        binding.flash.animate().cancel()
        binding.flash.alpha = 0.85f
        binding.flash.animate().alpha(0f).setDuration(220).start()
    }

    private fun showThumbnail(uri: Uri) {
        Thread {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (bmp != null && !isDestroyed) binding.thumbnail.setImageBitmap(bmp) }
        }.start()
    }

    /** Shows the most recent photo this app saved, if it can still see one. */
    private fun loadLatestPhoto() {
        Thread {
            val uri = try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("${Environment.DIRECTORY_PICTURES}/AICamera%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
                }
            } catch (e: Exception) {
                null
            }
            if (uri != null) runOnUiThread {
                if (lastPhotoUri == null) {
                    lastPhotoUri = uri
                    showThumbnail(uri)
                }
            }
        }.start()
    }

    private fun openLastPhoto() {
        val uri = lastPhotoUri ?: run {
            toast("还没有拍照")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("没有可以打开图片的应用")
        }
    }

    // --------------------------------------------------------------- controls

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.shutter.setOnClickListener { onShutter() }
        binding.btnSwitch.setOnClickListener { switchCamera() }
        binding.thumbnail.setOnClickListener { openLastPhoto() }
        binding.btnPermission.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        binding.btnGrid.setOnClickListener {
            grid = GridMode.entries[(grid.ordinal + 1) % GridMode.entries.size]
            analyzer.grid = grid
            saveSettings()
            renderSettings()
        }
        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            saveSettings()
            renderSettings()
        }
        binding.btnVoice.setOnClickListener {
            voiceOn = !voiceOn
            saveSettings()
            renderSettings()
            if (voiceOn) {
                ensureSpeaker()
                mainHandler.postDelayed({
                    if (speaker?.available != true) toast("手机没有可用的中文语音引擎")
                }, 1500)
            }
        }

        val tabs = mapOf(
            binding.tabSmart to Mode.SMART,
            binding.tabComposition to Mode.COMPOSITION,
            binding.tabPose to Mode.POSE,
            binding.tabLight to Mode.LIGHT,
        )
        for ((view, m) in tabs) {
            view.text = m.label
            view.setOnClickListener {
                mode = m
                stabilizer.reset()
                saveSettings()
                renderSettings()
            }
        }

        binding.exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val lower = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: return
                setExposureIndex(progress + lower)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        // Tap to focus/meter, pinch to zoom.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoom = cam.cameraInfo.zoomState.value ?: return false
                val next = (zoom.zoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                cam.cameraControl.setZoomRatio(next)
                return true
            }
        })
        binding.previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !scale.isInProgress && e.eventTime - e.downTime < 250) {
                meterAt(e.x, e.y)
                v.performClick()
            }
            true
        }
    }

    private fun renderSettings() {
        binding.overlay.mode = mode
        binding.overlay.grid = grid
        binding.btnGrid.text = grid.label
        binding.btnTimer.text = if (timerSeconds == 0) "定时关" else "${timerSeconds}秒"
        binding.btnVoice.text = if (voiceOn) "语音开" else "语音关"
        binding.btnVoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, if (voiceOn) R.drawable.ic_voice_on else R.drawable.ic_voice_off, 0, 0
        )
        val accent = ContextCompat.getColor(this, R.color.accent)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        binding.btnGrid.setTextColor(if (grid == GridMode.OFF) Color.WHITE else accent)
        binding.btnTimer.setTextColor(if (timerSeconds == 0) Color.WHITE else accent)
        binding.btnVoice.setTextColor(if (voiceOn) accent else Color.WHITE)
        val tabs = listOf(
            binding.tabSmart to Mode.SMART,
            binding.tabComposition to Mode.COMPOSITION,
            binding.tabPose to Mode.POSE,
            binding.tabLight to Mode.LIGHT,
        )
        for ((view, m) in tabs) styleTab(view, m == mode, accent, secondary)
        val exposureSupported = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
        binding.exposureRow.visibility = if (mode == Mode.LIGHT && exposureSupported) View.VISIBLE else View.GONE
        if (voiceOn) ensureSpeaker()
    }

    private fun styleTab(view: TextView, selected: Boolean, accent: Int, secondary: Int) {
        view.setTextColor(if (selected) accent else secondary)
        view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun ensureSpeaker() {
        if (speaker == null) speaker = Speaker(this)
    }

    private fun loadSettings() {
        mode = Mode.entries.getOrNull(prefs.getInt("mode", 0)) ?: Mode.SMART
        grid = GridMode.entries.getOrNull(prefs.getInt("grid", 0)) ?: GridMode.THIRDS
        timerSeconds = prefs.getInt("timer", 0)
        voiceOn = prefs.getBoolean("voice", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("mode", mode.ordinal)
            .putInt("grid", grid.ordinal)
            .putInt("timer", timerSeconds)
            .putBoolean("voice", voiceOn)
            .apply()
    }

    // ------------------------------------------------------------------ misc

    private fun applyInsets() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topPanel.setPadding(pad + bars.left, bars.top, pad + bars.right, 0)
            binding.bottomPanel.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = (binding.root.display ?: windowManager.defaultDisplay).rotation

    private fun displayRotationDegrees(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "AICamera"
    }
}
