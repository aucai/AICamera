package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aucai.aicamera.core.AimInput
import com.aucai.aicamera.core.ExternalFraming
import com.aucai.aicamera.core.GuidanceEngine
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.GuidanceInput
import com.aucai.aicamera.core.LevelState
import com.aucai.aicamera.core.LumaGrid
import com.aucai.aicamera.core.ObjectBox
import com.aucai.aicamera.core.PortraitStyle
import com.aucai.aicamera.core.ViewGeometry

/**
 * Turns each camera frame into an upright, display-oriented bitmap (mirrored for the
 * front camera, like the preview), runs pose and object detection and the guidance rules on it,
 * and hands the result to [onResult] on the analysis thread.
 */
class FrameAnalyzer(
    private val context: Context,
    private val isSteady: () -> Boolean,
    private val rotation: () -> FloatArray?,
    private val onResult: (frame: GuidanceFrame, width: Int, height: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var frontCamera = false
    @Volatile var level: LevelState? = null
    @Volatile var zoom = 1f
    @Volatile var maxZoom = 1f
    @Volatile var style = PortraitStyle.CLOSE
    @Volatile var assistEnabled = true
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
            val luma = sampleLuma(upright)
            if (resetRequested) {
                resetRequested = false
                engine.reset()
                objects = emptyList()
            }
            val frameAspect = upright.width.toFloat() / upright.height
            val aim = AimInput(
                frameAspect, level, zoom, maxZoom, isSteady(), style, frontCamera, assistEnabled, rotation(), view,
            )
            val input = GuidanceInput(pose, objects, luma, aim)
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
            onResult(frame, upright.width, upright.height)
        } catch (t: Throwable) {
            Log.e(TAG, "analysis failed", t)
        } finally {
            image.close()
        }
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
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
        private const val GRID_LONG_SIDE = 64
        private const val OBJECT_INTERVAL_MS = 500L

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
            return LumaGrid(small.width, small.height, luma, r / n, g / n, b / n)
        }
    }
}
