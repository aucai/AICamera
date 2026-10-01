package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aucai.aicamera.core.AimInput
import com.aucai.aicamera.core.Bokeh
import com.aucai.aicamera.core.ClassifierHit
import com.aucai.aicamera.core.FloatMask
import com.aucai.aicamera.core.ExternalFraming
import com.aucai.aicamera.core.GuidanceEngine
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.GuidanceInput
import com.aucai.aicamera.core.LevelState
import com.aucai.aicamera.core.LumaGrid
import com.aucai.aicamera.core.ObjectBox
import com.aucai.aicamera.core.PortraitStyle
import com.aucai.aicamera.core.SubjectKind
import com.aucai.aicamera.core.ViewGeometry

/**
 * Turns each camera frame into an upright, display-oriented bitmap (mirrored for the
 * front camera, like the preview), runs pose and object detection, the scene classifier and the
 * guidance rules on it, and hands the result to [onResult] on the analysis thread, together with
 * the live background-blur layer when portrait blur is on and a person is in view.
 */
class FrameAnalyzer(
    private val context: Context,
    private val isSteady: () -> Boolean,
    private val rotation: () -> FloatArray?,
    private val onResult: (frame: GuidanceFrame, width: Int, height: Int, blur: Bitmap?) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var frontCamera = false
    @Volatile var level: LevelState? = null
    @Volatile var zoom = 1f
    @Volatile var maxZoom = 1f
    @Volatile var style = PortraitStyle.CLOSE
    @Volatile var assistEnabled = true
    /** Automatic crop, light and colour once the shot is framed. */
    @Volatile var enhance = true
    /** The camera can light the subject (a flash unit, or the screen for selfies). */
    @Volatile var hasFlash = false
    /** Exposure value of the latest preview frame at ISO 100 (from the camera's settings); null if unknown. */
    @Volatile var ev100: Float? = null
    /** Exposure compensation in effect, in stops (it shifts [ev100] away from the scene's brightness). */
    @Volatile var evBias = 0f
    /** Portrait background blur (live layer for the preview). */
    @Volatile var bokeh = true
    /** Field of view at zoom 1 for the current camera and orientation. */
    @Volatile var view: ViewGeometry? = null

    /** Set from any thread to drop tracking state, e.g. after switching cameras. */
    @Volatile var resetRequested = false

    /** Called once with the next upright frame and its analysis, e.g. to send it to the cloud. */
    @Volatile var snapshotListener: ((Bitmap, GuidanceFrame) -> Unit)? = null

    /** Framing from the cloud to hand to the engine on the analysis thread. */
    @Volatile var pendingExternal: ExternalFraming? = null
    @Volatile var clearExternal = false

    private val engine = GuidanceEngine()
    private var detector: PoseDetector? = null
    private var detectorFailed = false
    private var objectFinder: ObjectFinder? = null
    private var objectFinderFailed = false
    private var objects: List<ObjectBox> = emptyList()
    private var objectsAt = 0L
    private var classifier: SceneClassifier? = null
    private var classifierFailed = false
    private var classes: List<ClassifierHit> = emptyList()
    private var classesAt = 0L
    private var segmenter: PersonSegmenter? = null
    private var segmenterFailed = false

    override fun analyze(image: ImageProxy) {
        try {
            val upright = uprightBitmap(image)
            val now = SystemClock.elapsedRealtime()
            val pose = poseDetector()?.detect(upright)
            // Objects move slowly compared to people; a few detections a second is plenty.
            if (now - objectsAt >= OBJECT_INTERVAL_MS) {
                objects = objectFinder()?.detect(upright) ?: emptyList()
                objectsAt = now
            }
            // What the whole scene is changes slowly too.
            if (now - classesAt >= CLASSIFY_INTERVAL_MS) {
                classes = classifier()?.classify(upright) ?: emptyList()
                classesAt = now
            }
            val luma = sampleLuma(upright)
            if (resetRequested) {
                resetRequested = false
                engine.reset()
                objects = emptyList()
                classes = emptyList()
            }
            val frameAspect = upright.width.toFloat() / upright.height
            val aim = AimInput(
                frameAspect, level, zoom, maxZoom, isSteady(), style, frontCamera, assistEnabled, rotation(), view,
            )
            val input = GuidanceInput(pose, objects, luma, aim, enhance, hasFlash, ev100?.plus(evBias), classes)
            if (clearExternal) {
                clearExternal = false
                engine.setExternal(null)
            }
            pendingExternal?.let {
                pendingExternal = null
                engine.setExternal(it)
            }
            val frame = engine.analyze(now, input)
            snapshotListener?.let {
                snapshotListener = null
                it(upright, frame)
            }
            val blur = if (bokeh && frame.composition.subject?.kind == SubjectKind.PERSON) blurLayer(upright) else null
            onResult(frame, upright.width, upright.height, blur)
        } catch (t: Throwable) {
            Log.e(TAG, "analysis failed", t)
        } finally {
            image.close()
        }
    }

    /**
     * The live portrait blur: the frame's background, blurred, transparent where the person is. Laid
     * over the sharp live preview it looks like portrait mode. Built small; blur hides that.
     */
    private fun blurLayer(upright: Bitmap): Bitmap? {
        val mask = segmenter()?.segment(upright) ?: return null
        if (mask.mean() < 0.01f) return null
        val crisp = FloatMask(mask.width, mask.height, Bokeh.sharpen(mask.data))
        val landscape = upright.width >= upright.height
        val sw = if (landscape) BLUR_LONG_SIDE else BLUR_LONG_SIDE * upright.width / upright.height
        val sh = if (landscape) BLUR_LONG_SIDE * upright.height / upright.width else BLUR_LONG_SIDE
        val small = Bitmap.createScaledBitmap(upright, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val smallMask = FloatArray(sw * sh) { crisp.sample((it % sw + 0.5f) / sw, (it / sw + 0.5f) / sh) }
        val bg = Bokeh.background(Bokeh.toRgb(px), smallMask, sw, sh, BLUR_RADIUS)
        val ow = sw * 2
        val oh = sh * 2
        return Bitmap.createBitmap(Bokeh.overlay(bg, sw, sh, crisp, ow, oh), ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun classifier(): SceneClassifier? {
        if (classifier == null && !classifierFailed) {
            try {
                classifier = SceneClassifier(context)
            } catch (t: Throwable) {
                Log.e(TAG, "scene model failed to load", t)
                classifierFailed = true
            }
        }
        return classifier
    }

    private fun segmenter(): PersonSegmenter? {
        if (segmenter == null && !segmenterFailed) {
            try {
                segmenter = PersonSegmenter(context)
            } catch (t: Throwable) {
                Log.e(TAG, "segmentation model failed to load", t)
                segmenterFailed = true
            }
        }
        return segmenter
    }

    private fun uprightBitmap(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val m = Matrix()
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (frontCamera) m.postScale(-1f, 1f)
        if (m.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun poseDetector(): PoseDetector? {
        if (detector == null && !detectorFailed) {
            try {
                detector = PoseDetector(context)
            } catch (t: Throwable) {
                Log.e(TAG, "pose model failed to load", t)
                detectorFailed = true
            }
        }
        return detector
    }

    private fun objectFinder(): ObjectFinder? {
        if (objectFinder == null && !objectFinderFailed) {
            try {
                objectFinder = ObjectFinder(context)
            } catch (t: Throwable) {
                Log.e(TAG, "object model failed to load", t)
                objectFinderFailed = true
            }
        }
        return objectFinder
    }

    fun close() {
        detector?.close()
        detector = null
        objectFinder?.close()
        objectFinder = null
        classifier?.close()
        classifier = null
        segmenter?.close()
        segmenter = null
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
        private const val GRID_LONG_SIDE = 64
        private const val OBJECT_INTERVAL_MS = 500L
        private const val CLASSIFY_INTERVAL_MS = 700L
        private const val BLUR_LONG_SIDE = 160
        private const val BLUR_RADIUS = 4

        fun sampleLuma(bitmap: Bitmap): LumaGrid {
            val landscape = bitmap.width >= bitmap.height
            val w = if (landscape) GRID_LONG_SIDE else GRID_LONG_SIDE * bitmap.width / bitmap.height
            val h = if (landscape) GRID_LONG_SIDE * bitmap.height / bitmap.width else GRID_LONG_SIDE
            val small = Bitmap.createScaledBitmap(bitmap, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            var r = 0L
            var g = 0L
            var b = 0L
            val luma = IntArray(px.size) { i ->
                val c = px[i]
                val cr = (c shr 16) and 0xff
                val cg = (c shr 8) and 0xff
                val cb = c and 0xff
                r += cr; g += cg; b += cb
                (299 * cr + 587 * cg + 114 * cb) / 1000
            }
            val n = px.size.toFloat()
            return LumaGrid(small.width, small.height, luma, r / n, g / n, b / n, px)
        }
    }
}
