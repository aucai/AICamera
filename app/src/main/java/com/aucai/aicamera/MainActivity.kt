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
import com.aucai.aicamera.core.Check
import com.aucai.aicamera.core.CropChoice
import com.aucai.aicamera.core.RectN
import com.aucai.aicamera.camera.PhotoWriter
import com.aucai.aicamera.ui.CheckText
import androidx.camera.core.ImageProxy
import com.aucai.aicamera.core.Tip
import com.aucai.aicamera.core.TipAction
import com.aucai.aicamera.core.TipCategory
import com.aucai.aicamera.core.TipStabilizer
import com.aucai.aicamera.databinding.ActivityMainBinding
import com.aucai.aicamera.databinding.ViewTipBinding
import com.aucai.aicamera.ui.PhotoReview
import com.aucai.aicamera.ui.ReviewStore
import com.aucai.aicamera.ui.Speaker
import com.aucai.aicamera.core.Sharpness
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var wasAllGood = false
    private var lastPhotoUri: Uri? = null
    private var countdownLeft = 0
    private var cropChoice = CropChoice.SAME
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var extensionsManager: ExtensionsManager? = null
    private var extensionActive = false
    private var capturing = false
    private val reviews by lazy { ReviewStore(this) }

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
        analyzer.crop = cropChoice
        levelSensor = LevelSensor(this) { level ->
            analyzer.level = level
            binding.overlay.level = level
        }
        levelSensor.displayRotationDeg = displayRotationDegrees()

        setupControls()
        renderSettings()
        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        levelSensor.start()
        loadLatestPhoto()
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
        photoExecutor.shutdown()
        speaker?.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = displayRotation()
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        levelSensor.displayRotationDeg = displayRotationDegrees()
        renderSettings()
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
        future.addListener({
            val provider = future.get()
            if (extensionsManager != null) {
                bindUseCases(provider)
                return@addListener
            }
            // The phone maker's own processing (HDR, night mode, noise reduction) when it is exposed.
            val ext = ExtensionsManager.getInstanceAsync(this, provider)
            ext.addListener({
                extensionsManager = runCatching { ext.get() }.getOrNull()
                bindUseCases(provider)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
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
        val fullResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            // ~12 MP: plenty after cropping, and a decoded frame still fits comfortably in memory.
            .setResolutionStrategy(
                ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(fullResolution)
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

        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val em = extensionsManager
        val extensionSelector = try {
            if (em != null &&
                em.isExtensionAvailable(base, ExtensionMode.AUTO) &&
                em.isImageAnalysisSupported(base, ExtensionMode.AUTO)
            ) em.getExtensionEnabledCameraSelector(base, ExtensionMode.AUTO) else null
        } catch (e: Exception) {
            null
        }
        provider.unbindAll()
        val bound = extensionSelector?.let { sel ->
            try {
                provider.bindToLifecycle(this, sel, preview, capture, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "extension bind failed, falling back", e)
                provider.unbindAll()
                null
            }
        }
        extensionActive = bound != null
        try {
            camera = bound ?: provider.bindToLifecycle(this, base, preview, capture, analysis)
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

        val checks = checksFor(frame)
        val passed = CheckText.passed(checks)
        binding.shutter.checks = passed
        binding.checkList.visibility = View.VISIBLE
        binding.checkList.text = CheckText.format(this, checks)
        binding.sceneChip.visibility = View.VISIBLE
        binding.sceneChip.text = "AI 识别：${frame.scene}"
        val reason = frame.composition.plan.reason
        val showReason = reason.isNotEmpty() && (mode == Mode.SMART || mode == Mode.COMPOSITION)
        binding.reasonChip.visibility = if (showReason) View.VISIBLE else View.GONE
        binding.reasonChip.text = "AI 取景：$reason"

        val allGood = passed.first == passed.second
        if (allGood && !wasAllGood) binding.overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        wasAllGood = allGood

        if (voiceOn && countdownLeft == 0) {
            visible.firstOrNull { it.severity != Severity.INFO }?.let { speaker?.say(it.text) }
        }
    }

    /** The frame's checks plus one the analysis thread cannot know: is the phone being held still. */
    private fun checksFor(frame: GuidanceFrame): List<Check> {
        val steady = levelSensor.isSteady
        return frame.checks + Check(steady, if (steady) "手机稳定" else "手在抖")
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

    /** Focus on the subject, wait for the phone to be still, then capture. */
    private fun takePhoto() {
        val cam = camera ?: return
        if (capturing) return
        capturing = true
        val frame = lastFrame
        val subject = frame?.composition?.frameSubject?.anchor
        val (vx, vy) = if (subject != null) {
            binding.overlay.toView(subject.x, subject.y)
        } else {
            binding.previewView.width / 2f to binding.previewView.height / 2f
        }
        val point = binding.previewView.meteringPointFactory.createPoint(vx, vy)
        val focus = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        var started = false
        val next = Runnable {
            if (started) return@Runnable
            started = true
            waitSteadyThenCapture(frame, SystemClock.elapsedRealtime())
        }
        cam.cameraControl.startFocusAndMetering(focus).addListener(next, ContextCompat.getMainExecutor(this))
        mainHandler.postDelayed(next, 1200)
    }

    private fun waitSteadyThenCapture(frame: GuidanceFrame?, since: Long) {
        if (!levelSensor.isSteady && SystemClock.elapsedRealtime() - since < 800) {
            showCaptureNote("保持稳定…", 900)
            mainHandler.postDelayed({ waitSteadyThenCapture(frame, since) }, 40)
            return
        }
        capture(frame)
    }

    /**
     * Captures at full resolution, then crops to the planned framing, turns the image upright and saves it.
     * What gets saved is exactly the bright area shown in the viewfinder.
     */
    private fun capture(frame: GuidanceFrame?) {
        val capture = imageCapture ?: run {
            capturing = false
            return
        }
        val fileName = "AICamera_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val crop = frame?.composition?.plan?.rect ?: RectN(0f, 0f, 1f, 1f)
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        val checks = frame?.let { checksFor(it) }

        // Remember what the camera saw and checked, for the review in the gallery.
        if (frame != null && checks != null) {
            val tips = frame.tips.filter { it.severity != Severity.INFO }.map { it.text }.distinct()
            reviews.put(fileName, PhotoReview(frame.scene, frame.composition.plan.reason, checks, tips))
        }

        flash()
        capture.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val saved = try {
                    val rotation = image.imageInfo.rotationDegrees
                    val src = image.toBitmap()
                    image.close()
                    val out = PhotoWriter.cropUpright(src, crop, rotation, mirror)
                    val blurry = PhotoWriter.sharpness(out) < Sharpness.BLURRY_BELOW
                    if (blurry) reviews.markBlurry(fileName)
                    PhotoWriter.save(contentResolver, out, fileName)?.let { it to blurry }
                } catch (t: Throwable) {
                    Log.e(TAG, "saving photo failed", t)
                    runCatching { image.close() }
                    null
                }
                runOnUiThread {
                    capturing = false
                    if (isDestroyed) return@runOnUiThread
                    if (saved == null) {
                        toast("保存照片失败")
                        return@runOnUiThread
                    }
                    val (uri, blurry) = saved
                    lastPhotoUri = uri
                    showThumbnail(uri)
                    val note = when {
                        blurry -> "这张可能有点糊，拿稳再拍一张"
                        checks != null -> "已保存 · ${checks.count { it.ok }}/${checks.size} 项达标 · 点左下角看点评"
                        else -> "已保存"
                    }
                    showCaptureNote(note, 3000)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture failed", exception)
                runOnUiThread {
                    capturing = false
                    toast("拍照失败：${exception.message}")
                }
            }
        })
    }

    private fun flash() {
        binding.flash.animate().cancel()
        binding.flash.alpha = 0.85f
        binding.flash.animate().alpha(0f).setDuration(220).start()
    }

    private val hideCaptureNote = Runnable { binding.captureNote.visibility = View.GONE }

    private fun showCaptureNote(text: String, durationMs: Long) {
        binding.captureNote.text = text
        binding.captureNote.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideCaptureNote)
        mainHandler.postDelayed(hideCaptureNote, durationMs)
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
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                lastPhotoUri = uri
                if (uri != null) showThumbnail(uri) else binding.thumbnail.setImageDrawable(null)
            }
        }.start()
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // --------------------------------------------------------------- controls

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.shutter.setOnClickListener { onShutter() }
        binding.btnSwitch.setOnClickListener { switchCamera() }
        binding.thumbnail.setOnClickListener { openGallery() }
        binding.btnPermission.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        binding.btnGrid.setOnClickListener {
            grid = GridMode.entries[(grid.ordinal + 1) % GridMode.entries.size]
            analyzer.grid = grid
            saveSettings()
            renderSettings()
        }
        binding.btnCrop.setOnClickListener {
            cropChoice = CropChoice.entries[(cropChoice.ordinal + 1) % CropChoice.entries.size]
            analyzer.crop = cropChoice
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
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        binding.btnCrop.text = cropChoice.label(portrait)
        binding.btnCrop.setTextColor(if (cropChoice == CropChoice.OFF) Color.WHITE else ContextCompat.getColor(this, R.color.accent))
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
        cropChoice = CropChoice.entries.getOrNull(prefs.getInt("crop", 0)) ?: CropChoice.SAME
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("mode", mode.ordinal)
            .putInt("grid", grid.ordinal)
            .putInt("timer", timerSeconds)
            .putBoolean("voice", voiceOn)
            .putInt("crop", cropChoice.ordinal)
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
