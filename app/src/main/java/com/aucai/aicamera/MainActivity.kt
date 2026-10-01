package com.aucai.aicamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aucai.aicamera.camera.FrameAnalyzer
import com.aucai.aicamera.camera.PhotoEdits
import com.aucai.aicamera.camera.PersonSegmenter
import com.aucai.aicamera.camera.PhotoProcessor
import com.aucai.aicamera.cloud.CloudException
import com.aucai.aicamera.cloud.CloudSettings
import com.aucai.aicamera.cloud.VisionClient
import com.aucai.aicamera.core.CloudAdvice
import com.aucai.aicamera.core.ColorMatrices
import com.aucai.aicamera.core.ExposureAssist
import com.aucai.aicamera.core.Filter
import com.aucai.aicamera.core.LookParams
import com.aucai.aicamera.core.SceneKind
import com.aucai.aicamera.core.SceneRecognizer
import com.aucai.aicamera.core.lerp
import com.aucai.aicamera.ui.LookEffects
import com.aucai.aicamera.core.CloudPrompts
import com.aucai.aicamera.core.ExternalFraming
import com.aucai.aicamera.core.SceneAnchor
import com.aucai.aicamera.core.SceneLight
import com.aucai.aicamera.core.Vec2
import com.aucai.aicamera.core.ViewGeometry
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.aucai.aicamera.ui.CloudSettingsDialog
import android.content.ContentValues
import android.graphics.ImageDecoder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.aucai.aicamera.camera.LevelSensor
import com.aucai.aicamera.core.AimPhase
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.PortraitStyle
import com.aucai.aicamera.core.Severity
import com.aucai.aicamera.core.Sharpness
import com.aucai.aicamera.core.SubjectKind
import com.aucai.aicamera.core.TipStabilizer
import com.aucai.aicamera.databinding.ActivityMainBinding
import com.aucai.aicamera.ui.PhotoReview
import com.aucai.aicamera.ui.ReviewStore
import com.aucai.aicamera.ui.Speaker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var levelSensor: LevelSensor
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cloudExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val poseTipStabilizer = TipStabilizer(showAfterMs = 800, hideAfterMs = 1500)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val reviews by lazy { ReviewStore(this) }
    private var speaker: Speaker? = null

    private var camera: Camera? = null
    private var boundInfo: CameraInfo? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var extensionsManager: ExtensionsManager? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Settings
    private var assistOn = true
    private var gridOn = true
    private var timerSeconds = 0
    private var voiceOn = false
    private var style = PortraitStyle.CLOSE
    private var enhanceOn = true
    private var bokehOn = true
    /** A filter the user picked; null = choose one for the scene once framed. */
    private var filterChoice: Filter? = null

    // Live state
    private var lastFrame: GuidanceFrame? = null
    private var lastPhase = AimPhase.IDLE
    private var doneSince = 0L
    private var lastPhotoUri: Uri? = null
    private var countdownLeft = 0
    private var capturing = false
    private var zoomAnimator: ValueAnimator? = null
    private var zoomAnimTarget = 0f
    private var manualFocusAt = 0L
    private var faceMeterAt = 0L
    private var faceMeterX = -1f
    private var faceMeterY = -1f
    private var adviceLoading = false
    private var extensionActive = false
    private val exposureAssist = ExposureAssist()
    private var previewFilter = Filter.NONE
    private var previewLook = LookParams.IDENTITY
    private var filterAnimator: ValueAnimator? = null
    private val layerPaint = Paint()
    private var shaderFailed = false
    private var exposureScene = SceneKind.UNKNOWN
    /** Finds the person in saved photos; only used on [photoExecutor]. */
    private var photoSegmenter: PersonSegmenter? = null

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

        levelSensor = LevelSensor(
            this,
            onChange = { level ->
                analyzer.level = level
                binding.overlay.level = level
            },
            // Redraw the aiming target as fast as the orientation sensor reports.
            onRotation = { binding.overlay.invalidate() },
        )
        levelSensor.displayRotationDeg = displayRotationDegrees()
        analyzer = FrameAnalyzer(this, { levelSensor.isSteady }, { levelSensor.rotation() }) { frame, w, h, blur ->
            runOnUiThread { onFrame(frame, w, h, blur) }
        }
        binding.overlay.projector = { world ->
            val r = levelSensor.rotation()
            val g = analyzer.view?.zoomed(analyzer.zoom)
            if (r != null && g != null) SceneAnchor.toScreen(world, r, g) else null
        }
        analyzer.style = style
        analyzer.assistEnabled = assistOn
        analyzer.enhance = enhanceOn
        analyzer.bokeh = bokehOn
        // Selfie fill light: the screen turns white and bright while the photo is taken.
        binding.screenFlash.setScreenFlashWindow(window)

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
        zoomAnimator?.cancel()
        filterAnimator?.cancel()
        analysisExecutor.execute { analyzer.close() }
        analysisExecutor.shutdown()
        photoExecutor.execute { photoSegmenter?.close() }
        photoExecutor.shutdown()
        cloudExecutor.shutdown()
        speaker?.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = displayRotation()
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        levelSensor.displayRotationDeg = displayRotationDegrees()
        analyzer.view = viewForDisplay()
        analyzer.resetRequested = true
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
        val fullResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(fullResolution)
            .setTargetRotation(rotation)
            .build()
        try {
            capture.screenFlash = binding.screenFlash.screenFlash
        } catch (e: Exception) {
            Log.w(TAG, "screen flash unavailable", e)
        }
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
        watchExposure(analysisBuilder)
        val analysis = analysisBuilder.build()
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
        val cam = try {
            bound ?: provider.bindToLifecycle(this, base, preview, capture, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            toast("相机启动失败：${e.message}")
            return
        }
        camera = cam
        analyzer.hasFlash = lensFacing == CameraSelector.LENS_FACING_FRONT || cam.cameraInfo.hasFlashUnit()
        exposureAssist.reset()
        if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(0)
        }
        lensView = lensGeometry(cam)
        analyzer.view = viewForDisplay()
        extensionActive = bound != null
        showQualityStatus()
        this.preview = preview
        imageCapture = capture
        imageAnalysis = analysis

        // The composition assistant needs to know the zoom it works at and how far it may go.
        boundInfo?.zoomState?.removeObservers(this)
        boundInfo = cam.cameraInfo
        cam.cameraInfo.zoomState.observe(this) { z ->
            analyzer.zoom = z.zoomRatio
            analyzer.maxZoom = min(z.maxZoomRatio, MAX_ASSIST_ZOOM).coerceAtLeast(z.zoomRatio)
        }
        analyzer.resetRequested = true
        lastFrame = null
        binding.overlay.clear()
    }

    /**
     * Reads the exposure the camera chose for each frame, which says how bright the scene really is
     * (a room and daylight look alike once the camera has adjusted to them).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun watchExposure(builder: ImageAnalysis.Builder) {
        analyzer.ev100 = null
        try {
            Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                    if (time <= 0L || iso <= 0) return
                    val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
                    val aperture = result.get(CaptureResult.LENS_APERTURE)?.takeIf { it > 0f } ?: 1.8f
                    analyzer.ev100 = SceneLight.ev100(aperture, time, iso * boost / 100)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "exposure readout unavailable", e)
        }
    }

    /** tan(half field of view) across the long and short side of the 4:3 frame, at zoom 1. */
    private var lensView = 0.66f to 0.5f

    @OptIn(ExperimentalCamera2Interop::class)
    private fun lensGeometry(cam: Camera): Pair<Float, Float> = try {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal == null || focal <= 0f || size == null) {
            0.66f to 0.5f
        } else {
            val short = min(size.width, size.height) / 2f / focal
            // 4:3 frames: a wider sensor gets its sides cropped.
            val long = min(maxOf(size.width, size.height) / 2f / focal, short * 4f / 3f)
            long to short
        }
    } catch (e: Exception) {
        // Roughly a typical main camera (about 67° across the long side).
        0.66f to 0.5f
    }

    private fun viewForDisplay(): ViewGeometry {
        val (long, short) = lensView
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val landscape = displayRotationDegrees() % 180 != 0
        return if (landscape) ViewGeometry(long, short, front) else ViewGeometry(short, long, front)
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        zoomAnimator?.cancel()
        if (hasCameraPermission()) startCamera()
    }

    private fun meterAt(viewX: Float, viewY: Float, flags: Int, cancelAfterSec: Long? = 5) {
        val cam = camera ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(viewX, viewY)
        val builder = FocusMeteringAction.Builder(point, flags)
        if (cancelAfterSec == null) builder.disableAutoCancel() else builder.setAutoCancelDuration(cancelAfterSec, TimeUnit.SECONDS)
        cam.cameraControl.startFocusAndMetering(builder.build())
    }

    /** Smoothly moves the camera zoom, the way the assistant "finishes" a composition. */
    private fun animateZoomTo(target: Float) {
        val cam = camera ?: return
        if (zoomAnimator?.isRunning == true && abs(zoomAnimTarget - target) < 0.02f) return
        val from = cam.cameraInfo.zoomState.value?.zoomRatio ?: return
        zoomAnimator?.cancel()
        zoomAnimTarget = target
        zoomAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 600
            addUpdateListener { camera?.cameraControl?.setZoomRatio(it.animatedValue as Float) }
            start()
        }
    }

    // ----------------------------------------------------------------- frames

    private fun onFrame(frame: GuidanceFrame, width: Int, height: Int, blur: Bitmap?) {
        if (isFinishing) return
        lastFrame = frame
        binding.overlay.update(frame, width, height)
        val now = SystemClock.elapsedRealtime()
        val aim = frame.composition.aim
        val subject = frame.composition.subject

        if (aim.phase != lastPhase) {
            if (aim.phase == AimPhase.DONE) doneSince = now
            if (aim.phase == AimPhase.HOLD || aim.phase == AimPhase.DONE) {
                binding.overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            lastPhase = aim.phase
        }
        if (aim.phase == AimPhase.ZOOM) aim.zoomTo?.let { animateZoomTo(it) }
        binding.shutter.ready = aim.phase == AimPhase.DONE

        autoMeterOnFace(frame, now)
        updateExposure(frame, now)
        showLook(effectiveFilter(frame))
        showBlur(blur)

        // Scene badge, like a phone maker's "AI" label: "美食 · 披萨", "狗 · 柯基", "蓝天".
        binding.sceneChip.visibility = if (frame.sceneLabel.isNotEmpty()) View.VISIBLE else View.GONE
        binding.sceneChip.text = frame.sceneLabel
        renderLookChips(frame)

        val person = subject?.kind == SubjectKind.PERSON
        binding.styleChips.visibility = if (person) View.VISIBLE else View.INVISIBLE
        binding.chipClose.visibility = if (assistOn) View.VISIBLE else View.GONE
        binding.chipScene.visibility = if (assistOn) View.VISIBLE else View.GONE

        showHint(hintFor(frame, now))
    }

    /**
     * One short line at a time: the current step; once framed a pose, angle or light tip; with
     * nothing to aim at, a tip for the scene.
     */
    private fun hintFor(frame: GuidanceFrame, now: Long): String {
        val aim = frame.composition.aim
        val poseTip = poseTipStabilizer.update(now, frame.poseTips.filter { it.severity != Severity.INFO })
            .firstOrNull()?.text
        val lightTip = frame.lighting?.let {
            when {
                it.mean < 45f && frame.sceneKind != SceneKind.NIGHT -> "光线太暗，靠近光源或开灯"
                it.highRatio > 0.2f -> "画面太亮，换个角度避开强光"
                else -> null
            }
        }
        val sceneTip = SceneRecognizer.tip(frame.sceneKind)
        if (!assistOn) return lightTip ?: sceneTip ?: ""
        val angleTip = aim.angle?.text
        return when (aim.phase) {
            AimPhase.IDLE -> lightTip ?: angleTip ?: sceneTip
                ?: if (frame.sceneKind == SceneKind.UNKNOWN) "对准想拍的人或物" else ""
            AimPhase.DONE -> if (now - doneSince < 1500) doneHint(frame) else poseTip ?: angleTip ?: lightTip ?: doneHint(frame)
            else -> aim.hint
        }
    }

    /** "Framed", plus what the camera does about it. */
    private fun doneHint(frame: GuidanceFrame): String {
        val parts = ArrayList<String>()
        frame.look.takeIf { it.engaged }?.crop?.let { parts += "裁成 ${it.label}" }
        if (bokehOn && frame.composition.subject?.kind == SubjectKind.PERSON) parts += "背景虚化"
        val filter = effectiveFilter(frame)
        if (filter != Filter.NONE) parts += "${filter.label}风格"
        if (parts.isEmpty()) return frame.composition.aim.hint
        return "构图完成（${parts.joinToString("、")}），可以拍了"
    }

    // ------------------------------------------------------------ auto look

    /** The user's look, or the one that suits the recognised scene. */
    private fun effectiveFilter(frame: GuidanceFrame?): Filter =
        filterChoice ?: if (frame != null && enhanceOn) frame.look.filter else Filter.NONE

    /**
     * Brightens or darkens a third of a stop at a time until the subject (or the scene: white snow,
     * a rich sunset) is well exposed. A new scene starts again from the camera's own exposure.
     */
    private fun updateExposure(frame: GuidanceFrame, now: Long) {
        val cam = camera ?: return
        // Leave exposure alone while taking a photo, and for a while after the user tapped to meter.
        if (capturing || now - manualFocusAt < 5000) return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val sceneChanged = frame.sceneKind != exposureScene
        exposureScene = frame.sceneKind
        val active = enhanceOn && !sceneChanged
        exposureAssist.update(now, frame.look.exposure, active, range.lower..range.upper, state.exposureCompensationStep.toFloat())
            ?.let { cam.cameraControl.setExposureCompensationIndex(it) }
        analyzer.evBias = currentEv()
    }

    private fun currentEv(): Float {
        val state = camera?.cameraInfo?.exposureState ?: return 0f
        if (!state.isExposureCompensationSupported) return 0f
        return state.exposureCompensationIndex * state.exposureCompensationStep.toFloat()
    }

    /** What the camera is doing about the light right now, e.g. "闪光补光 · 提亮 +0.7EV". */
    private fun lightActions(frame: GuidanceFrame): List<String> {
        if (!enhanceOn) return emptyList()
        val look = frame.look
        val out = ArrayList<String>()
        if (look.flash && analyzer.hasFlash) out += if (lensFacing == CameraSelector.LENS_FACING_FRONT) "屏幕补光" else "闪光补光"
        val ev = currentEv()
        if (abs(ev) >= 0.1f) out += "%s %+.1fEV".format(if (ev > 0) "提亮" else "压暗", ev)
        if (look.softFill >= 0.1f) out += "暗部补光"
        return out
    }

    private fun renderLookChips(frame: GuidanceFrame?) {
        val filter = effectiveFilter(frame)
        binding.filterChip.visibility = View.VISIBLE
        binding.filterChip.text = when {
            filterChoice != null -> "风格 · ${filter.label}"
            filter != Filter.NONE -> "风格 · ${filter.label}（自动）"
            else -> "风格 · 自动"
        }
        val light = frame?.let { lightActions(it) } ?: emptyList()
        val backlit = frame?.lighting?.backlit == true && frame.composition.subject?.kind == SubjectKind.PERSON
        binding.lightBadge.visibility = if (light.isNotEmpty() || backlit) View.VISIBLE else View.GONE
        binding.lightBadge.text = if (light.isNotEmpty()) light.joinToString(" · ") else getString(R.string.backlight_fix)
    }

    /** Tap the style chip: automatic, then each hand-picked look in turn. */
    private fun nextFilter() {
        val order = listOf<Filter?>(null) + Filter.MANUAL
        filterChoice = order[(order.indexOf(filterChoice) + 1) % order.size]
        saveSettings()
        showLook(effectiveFilter(lastFrame))
        renderLookChips(lastFrame)
    }

    /** Shows the look on the live preview, fading between looks. */
    private fun showLook(f: Filter) {
        if (f == previewFilter) return
        previewFilter = f
        val from = previewLook
        val to = f.params
        filterAnimator?.cancel()
        filterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { applyLook(lerp(from, to, it.animatedValue as Float)) }
            start()
        }
    }

    /**
     * The preview is a TextureView, so an effect on its container recolours the live picture (and
     * the blur layer). Android 13+ runs the exact look as a GPU shader; older phones get the
     * closest colour matrix.
     */
    private fun applyLook(p: LookParams) {
        previewLook = p
        val v = binding.previewContainer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed) {
            try {
                v.setRenderEffect(if (p.isIdentity) null else LookEffects.renderEffect(p))
                return
            } catch (e: Exception) {
                Log.w(TAG, "look shader unavailable, using a colour matrix", e)
                shaderFailed = true
                v.setRenderEffect(null)
            }
        }
        if (p.isIdentity) {
            if (v.layerType != View.LAYER_TYPE_NONE) v.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        layerPaint.colorFilter = ColorMatrixColorFilter(ColorMatrices.approximate(p))
        if (v.layerType != View.LAYER_TYPE_HARDWARE) v.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint) else v.setLayerPaint(layerPaint)
    }

    /**
     * Portrait blur in the live picture: the blurred background is laid over the sharp preview. It
     * lags a frame or two behind, so it only shows while the phone is held still.
     */
    private fun showBlur(blur: Bitmap?) {
        val v = binding.blurLayer
        if (blur != null) v.setImageBitmap(blur)
        val show = blur != null && bokehOn && levelSensor.isSteady
        val target = if (show) 1f else 0f
        if (v.tag != target) {
            v.tag = target
            v.animate().cancel()
            v.animate().alpha(target).setDuration(if (show) 250 else 120).start()
        }
    }

    private fun showHint(text: String) {
        if (text.isEmpty()) {
            binding.hint.visibility = View.INVISIBLE
            return
        }
        if (binding.hint.text != text) {
            binding.hint.text = text
            if (voiceOn && countdownLeft == 0) speaker?.say(text)
        }
        binding.hint.visibility = View.VISIBLE
    }

    /**
     * Keeps exposure right for the person automatically (this is what fixes backlit faces), unless
     * the user tapped to focus a moment ago. Exposure only, so focus does not hunt.
     */
    private fun autoMeterOnFace(frame: GuidanceFrame, now: Long) {
        if (now - manualFocusAt < 5000) return
        val face = frame.pose?.faceBounds()?.clamp01() ?: return
        val c = face.center
        val moved = hypot(c.x - faceMeterX, c.y - faceMeterY) > 0.08f
        if (!moved && now - faceMeterAt < 3000) return
        faceMeterAt = now
        faceMeterX = c.x
        faceMeterY = c.y
        val (vx, vy) = binding.overlay.toView(c.x, c.y)
        meterAt(vx, vy, FocusMeteringAction.FLAG_AE, cancelAfterSec = null)
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
        // A new metering action replaces the automatic face exposure, so meter on the subject too;
        // otherwise a backlit face would go dark again at the moment of capture.
        val flags = if (subject != null) FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF
        val focus = FocusMeteringAction.Builder(point, flags)
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
     * Without edits, saves the camera's own JPEG straight to the gallery (no re-compressing). Once
     * framed, the photo is cropped, lit and coloured the way the preview showed it. Either way a small
     * copy is then checked for blur.
     */
    private fun capture(pressed: GuidanceFrame?) {
        val capture = imageCapture ?: run {
            capturing = false
            return
        }
        val fileName = "AICamera_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT

        // The latest framing, if the shot is still framed; otherwise what was on screen at the press.
        val frame = lastFrame?.takeIf { it.look.engaged } ?: pressed ?: lastFrame
        val look = frame?.look?.takeIf { enhanceOn }
        val crop = look?.takeIf { it.engaged }?.crop
        val person = frame?.composition?.subject?.kind == SubjectKind.PERSON
        val edits = PhotoEdits(crop?.rect, effectiveFilter(frame), look?.softFill ?: 0f, bokeh = bokehOn && person)
        val flash = look?.flash == true && analyzer.hasFlash
        setFlash(capture, flash, front)
        val ev = currentEv()
        val editText = describeEdits(edits, crop?.label, flash, front, ev)

        // Remember what the camera saw, for the review in the gallery.
        if (frame != null) {
            val aim = frame.composition.aim
            val tips = (frame.poseTips + (frame.lighting?.tips ?: emptyList()))
                .filter { it.severity != Severity.INFO }
                .map { it.text }
                .distinct()
            val reason = if (aim.phase == AimPhase.DONE) aim.reason else ""
            reviews.put(fileName, PhotoReview(frame.scene, reason, frame.checks, tips, edits = editText))
        }

        val metadata = ImageCapture.Metadata().apply {
            // Save selfies mirrored, exactly as they looked in the preview.
            isReversedHorizontal = front
        }
        val raw = File(cacheDir, "capture_$fileName")
        val options = if (edits.isEmpty) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
            }
            ImageCapture.OutputFileOptions
                .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                .setMetadata(metadata)
                .build()
        } else {
            ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
        }

        if (!flash || !front) flash()
        capture.takePicture(options, photoExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // The picture is taken; editing it need not hold up the next one.
                if (!edits.isEmpty) runOnUiThread { capturing = false }
                val uri = if (edits.isEmpty) {
                    output.savedUri
                } else {
                    saveEdited(raw, fileName, edits) { blurred ->
                        // Nobody found to keep sharp: say so in the review instead of claiming a blur.
                        if (edits.bokeh && !blurred) {
                            reviews.setEdits(fileName, describeEdits(edits.copy(bokeh = false), crop?.label, flash, front, ev))
                        }
                    }
                }
                val blurry = uri != null && isBlurry(uri)
                if (blurry) reviews.markBlurry(fileName)
                runOnUiThread {
                    // (Edited photos released the shutter already; a newer capture may be under way.)
                    if (edits.isEmpty) capturing = false
                    if (isDestroyed) return@runOnUiThread
                    if (uri == null) {
                        toast("保存照片失败")
                        return@runOnUiThread
                    }
                    lastPhotoUri = uri
                    showThumbnail(uri)
                    val note = when {
                        blurry -> "这张可能有点糊，拿稳再拍一张"
                        editText.isNotEmpty() -> "已保存（$editText）"
                        else -> "已保存，点左下角看点评"
                    }
                    showCaptureNote(note, 2500)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture failed", exception)
                raw.delete()
                runOnUiThread {
                    capturing = false
                    toast("拍照失败：${exception.message}")
                }
            }
        })
    }

    /** Flash as fill light: the flash unit, or the screen for selfies. Off otherwise. */
    private fun setFlash(capture: ImageCapture, on: Boolean, front: Boolean) {
        val mode = when {
            !on -> ImageCapture.FLASH_MODE_OFF
            front -> ImageCapture.FLASH_MODE_SCREEN
            else -> ImageCapture.FLASH_MODE_ON
        }
        try {
            capture.flashMode = mode
        } catch (e: Exception) {
            Log.w(TAG, "flash mode $mode not supported", e)
            runCatching { capture.flashMode = ImageCapture.FLASH_MODE_OFF }
        }
    }

    private fun describeEdits(edits: PhotoEdits, cropLabel: String?, flash: Boolean, front: Boolean, ev: Float): String {
        val parts = ArrayList<String>()
        if (edits.crop != null && cropLabel != null) parts += "裁成 $cropLabel"
        if (edits.bokeh) parts += "背景虚化"
        if (edits.filter != Filter.NONE) parts += "${edits.filter.label}风格"
        if (flash) parts += if (front) "屏幕补光" else "闪光补光"
        if (abs(ev) >= 0.1f) parts += "%s %+.1fEV".format(if (ev > 0) "提亮" else "压暗", ev)
        if (edits.fill >= 0.1f) parts += "暗部补光"
        return parts.joinToString("、")
    }

    /**
     * Applies the edits to the camera's JPEG and adds the result to the gallery. If editing fails
     * (e.g. not enough memory), the untouched photo is saved instead, so nothing is lost.
     */
    /**
     * @param onEdited called with whether the background was blurred, once the edits succeeded (not
     *   called when the original had to be saved instead).
     */
    private fun saveEdited(raw: File, fileName: String, edits: PhotoEdits, onEdited: (blurred: Boolean) -> Unit): Uri? {
        val edited = File(cacheDir, "edited_$fileName")
        try {
            val source = try {
                val blurred = PhotoProcessor.process(raw, edited, edits) { photoSegmenter()?.segment(it) }
                onEdited(blurred)
                edited
            } catch (t: Throwable) {
                Log.e(TAG, "editing failed, saving the original", t)
                reviews.setEdits(fileName, "")
                raw
            }
            return insertJpeg(source, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "saving failed", e)
            return null
        } finally {
            raw.delete()
            edited.delete()
        }
    }

    private fun photoSegmenter(): PersonSegmenter? {
        photoSegmenter?.let { return it }
        return try {
            PersonSegmenter(this).also { photoSegmenter = it }
        } catch (t: Throwable) {
            Log.e(TAG, "segmentation model failed to load", t)
            null
        }
    }

    private fun insertJpeg(file: File, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = contentResolver.openOutputStream(uri) ?: error("cannot write to the gallery")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun isBlurry(uri: Uri): Boolean = try {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = 1000f / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            (299 * ((c shr 16) and 0xff) + 587 * ((c shr 8) and 0xff) + 114 * (c and 0xff)) / 1000
        }
        Sharpness.laplacianVariance(gray, bmp.width, bmp.height) < Sharpness.BLURRY_BELOW
    } catch (e: Exception) {
        false
    }

    // -------------------------------------------------------------- cloud AI

    /** Sends the current view to the cloud model and shows its advice; its framing drives the aiming guide. */
    private fun requestAdvice() {
        if (adviceLoading) return
        val config = CloudSettings.load(this)
        if (!config.ready) {
            CloudSettingsDialog.show(this) { requestAdvice() }
            return
        }
        adviceLoading = true
        binding.advicePanel.visibility = View.VISIBLE
        binding.adviceProgress.visibility = View.VISIBLE
        binding.adviceText.text = "AI 正在看画面…"
        binding.overlay.staticFrame = null
        analyzer.clearExternal = true
        val zoomAtShot = analyzer.zoom
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        analyzer.snapshotListener = { image, frame ->
            val rotationAtShot = levelSensor.rotation()?.copyOf()
            val viewAtShot = analyzer.view?.zoomed(analyzer.zoom)
            cloudExecutor.execute {
                val result = try {
                    val prompt = CloudPrompts.adviceRequest(image.width, image.height, contextFor(frame), front)
                    val text = VisionClient(config).ask(CloudPrompts.SYSTEM, prompt, image)
                    CloudPrompts.parseAdvice(text, image.width, image.height)
                        ?.let { Result.success(it) }
                        ?: Result.failure(CloudException("AI 的回答格式不对，再试一次"))
                } catch (e: CloudException) {
                    Result.failure(e)
                }
                runOnUiThread { showAdvice(result, frame, zoomAtShot, rotationAtShot, viewAtShot) }
            }
        }
    }

    /** What the camera already knows, to give the model some context. */
    private fun contextFor(frame: GuidanceFrame): String {
        val parts = arrayListOf(frame.scene)
        frame.composition.frameSubject?.let { if (it.facing != 0) parts += if (it.facing > 0) "人物朝画面右侧" else "人物朝画面左侧" }
        levelHint()?.let { parts += it }
        return parts.joinToString("；")
    }

    private fun levelHint(): String? {
        val lv = analyzer.level ?: return null
        if (lv.flat) return "手机平放俯拍"
        val parts = ArrayList<String>()
        if (abs(lv.rollDeg) > 3f) parts += "手机歪了%.0f°".format(abs(lv.rollDeg))
        if (abs(lv.cameraPitchDeg) > 10f) parts += if (lv.cameraPitchDeg > 0) "镜头向下俯拍" else "镜头向上仰拍"
        return parts.joinToString("，").ifEmpty { null }
    }

    private fun showAdvice(
        result: Result<CloudAdvice>,
        frame: GuidanceFrame,
        zoomAtShot: Float,
        rotationAtShot: FloatArray?,
        viewAtShot: ViewGeometry?,
    ) {
        adviceLoading = false
        if (isDestroyed) return
        binding.adviceProgress.visibility = View.GONE
        val advice = result.getOrElse {
            binding.adviceText.text = "失败：${it.message}\n（长按「AI建议」可以修改云端设置）"
            return
        }
        val good = ContextCompat.getColor(this, R.color.good)
        val bad = ContextCompat.getColor(this, R.color.bad)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val t = SpannableStringBuilder()
        fun line(text: String, vararg spans: Any) {
            if (text.isBlank()) return
            if (t.isNotEmpty()) t.append("\n")
            val start = t.length
            t.append(text)
            for (sp in spans) t.setSpan(sp, start, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        line(advice.scene, ForegroundColorSpan(secondary))
        if (advice.good.isNotBlank()) line("✓ " + advice.good, ForegroundColorSpan(good))
        if (advice.problem.isNotBlank()) line("✗ " + advice.problem, ForegroundColorSpan(bad))
        line(advice.advice, StyleSpan(android.graphics.Typeface.BOLD), ForegroundColorSpan(accent))
        advice.steps.forEachIndexed { i, step -> line("${i + 1}. $step") }
        if (advice.pose.isNotBlank()) line("姿势：" + advice.pose)

        val crop = advice.crop
        val anchor = frame.composition.frameSubject?.anchor
        // Pin the framing to where the phone was pointing when the picture was sent, so it stays put
        // in the scene however the phone moves afterwards.
        val world = if (crop != null && rotationAtShot != null && viewAtShot != null) {
            SceneAnchor.toWorld(crop.center, rotationAtShot, viewAtShot)
        } else null
        when {
            crop != null && (world != null || anchor != null) -> {
                analyzer.pendingExternal = ExternalFraming(
                    size = Vec2(crop.width, crop.height),
                    zoomBase = zoomAtShot,
                    reason = advice.advice,
                    world = world,
                    offset = anchor?.let { Vec2(crop.center.x - it.x, crop.center.y - it.y) },
                )
                line("黄色虚线框是推荐取景：慢慢转动手机，把圆点对进中间的圈", ForegroundColorSpan(secondary))
            }
            crop != null -> {
                binding.overlay.staticFrame = crop
                line("黄色虚线框是推荐取景（移动手机后会消失）", ForegroundColorSpan(secondary))
            }
        }
        binding.adviceText.text = t
        if (voiceOn) speaker?.say(advice.advice, force = true)
    }

    private fun closeAdvice() {
        binding.advicePanel.visibility = View.GONE
        binding.overlay.staticFrame = null
        analyzer.clearExternal = true
    }

    private fun showQualityStatus() {
        binding.qualityChip.visibility = View.VISIBLE
        binding.qualityChip.text = if (extensionActive) "厂商画质增强：开" else "厂商画质增强：本机不支持"
        if (!extensionActive) mainHandler.postDelayed({ binding.qualityChip.visibility = View.GONE }, 4000)
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

    // --------------------------------------------------------------- controls

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.shutter.setOnClickListener { onShutter() }
        binding.btnSwitch.setOnClickListener { switchCamera() }
        binding.thumbnail.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        binding.btnPermission.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        binding.btnAssist.setOnClickListener {
            assistOn = !assistOn
            analyzer.assistEnabled = assistOn
            saveSettings()
            renderSettings()
        }
        binding.btnGrid.setOnClickListener {
            gridOn = !gridOn
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
        binding.btnEnhance.setOnClickListener {
            enhanceOn = !enhanceOn
            analyzer.enhance = enhanceOn
            saveSettings()
            renderSettings()
            toast(if (enhanceOn) "按场景自动调色、调光补光，对准后自动裁剪" else "已关闭自动美化，照片保持原样")
        }
        binding.filterChip.setOnClickListener { nextFilter() }
        binding.btnAi.setOnClickListener { requestAdvice() }
        binding.btnAi.setOnLongClickListener {
            CloudSettingsDialog.show(this)
            true
        }
        binding.adviceClose.setOnClickListener { closeAdvice() }
        binding.chipClose.setOnClickListener { chooseStyle(PortraitStyle.CLOSE) }
        binding.chipScene.setOnClickListener { chooseStyle(PortraitStyle.SCENE) }
        binding.chipBlur.setOnClickListener {
            bokehOn = !bokehOn
            analyzer.bokeh = bokehOn
            saveSettings()
            renderSettings()
        }

        // Tap to focus/meter, pinch to zoom.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoom = cam.cameraInfo.zoomState.value ?: return false
                zoomAnimator?.cancel()
                val next = (zoom.zoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                cam.cameraControl.setZoomRatio(next)
                return true
            }
        })
        binding.previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !scale.isInProgress && e.eventTime - e.downTime < 250) {
                manualFocusAt = SystemClock.elapsedRealtime()
                meterAt(e.x, e.y, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                v.performClick()
            }
            true
        }
    }

    private fun chooseStyle(s: PortraitStyle) {
        if (style == s) return
        style = s
        analyzer.style = s
        // "People in their surroundings" is framed at the widest view.
        if (s == PortraitStyle.SCENE) {
            camera?.cameraInfo?.zoomState?.value?.minZoomRatio?.let { animateZoomTo(maxOf(1f, it)) }
        }
        saveSettings()
        renderSettings()
    }

    private fun renderSettings() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        binding.overlay.gridOn = gridOn
        binding.btnAssist.text = if (assistOn) "构图助手" else "助手关"
        binding.btnAssist.setTextColor(if (assistOn) accent else Color.WHITE)
        binding.btnGrid.text = if (gridOn) "网格开" else "网格关"
        binding.btnGrid.setTextColor(if (gridOn) accent else Color.WHITE)
        binding.btnTimer.text = if (timerSeconds == 0) "定时关" else "${timerSeconds}秒"
        binding.btnTimer.setTextColor(if (timerSeconds == 0) Color.WHITE else accent)
        binding.btnVoice.text = if (voiceOn) "语音开" else "语音关"
        binding.btnVoice.setTextColor(if (voiceOn) accent else Color.WHITE)
        binding.btnEnhance.text = if (enhanceOn) "美化开" else "美化关"
        binding.btnEnhance.setTextColor(if (enhanceOn) accent else Color.WHITE)
        binding.btnVoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, if (voiceOn) R.drawable.ic_voice_on else R.drawable.ic_voice_off, 0, 0
        )
        binding.chipClose.isSelected = style == PortraitStyle.CLOSE
        binding.chipScene.isSelected = style == PortraitStyle.SCENE
        binding.chipBlur.isSelected = bokehOn
        if (!assistOn) binding.overlay.clear()
        if (voiceOn) ensureSpeaker()
    }

    private fun ensureSpeaker() {
        if (speaker == null) speaker = Speaker(this)
    }

    private fun loadSettings() {
        assistOn = prefs.getBoolean("assist", true)
        gridOn = prefs.getBoolean("gridOn", true)
        timerSeconds = prefs.getInt("timer", 0)
        voiceOn = prefs.getBoolean("voice2", false)
        style = PortraitStyle.entries.getOrNull(prefs.getInt("style", 0)) ?: PortraitStyle.CLOSE
        enhanceOn = prefs.getBoolean("enhance", true)
        bokehOn = prefs.getBoolean("bokeh", true)
        filterChoice = Filter.entries.getOrNull(prefs.getInt("filter", -1))
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("assist", assistOn)
            .putBoolean("gridOn", gridOn)
            .putInt("timer", timerSeconds)
            .putBoolean("voice2", voiceOn)
            .putInt("style", style.ordinal)
            .putBoolean("enhance", enhanceOn)
            .putBoolean("bokeh", bokehOn)
            .putInt("filter", filterChoice?.ordinal ?: -1)
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
        /** Beyond this the assistant does not zoom on its own (digital zoom gets soft). */
        private const val MAX_ASSIST_ZOOM = 2f
    }
}
