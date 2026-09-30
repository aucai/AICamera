package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aucai.aicamera.core.GridMode
import com.aucai.aicamera.core.GuidanceEngine
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.GuidanceInput
import com.aucai.aicamera.core.LevelState
import com.aucai.aicamera.core.LumaGrid

/**
 * Turns each camera frame into an upright, display-oriented bitmap (mirrored for the
 * front camera, like the preview), runs pose detection and the guidance rules on it,
 * and hands the result to [onResult] on the analysis thread.
 */
class FrameAnalyzer(
    private val context: Context,
    private val onResult: (frame: GuidanceFrame, width: Int, height: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var frontCamera = false
    @Volatile var level: LevelState? = null
    @Volatile var grid = GridMode.THIRDS

    private var detector: PoseDetector? = null
    private var detectorFailed = false

    override fun analyze(image: ImageProxy) {
        try {
            val upright = uprightBitmap(image)
            val pose = poseDetector()?.detect(upright)
            val luma = sampleLuma(upright)
            val frame = GuidanceEngine.analyze(GuidanceInput(pose, luma, level, grid, frontCamera))
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

    fun close() {
        detector?.close()
        detector = null
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
        private const val GRID_LONG_SIDE = 64

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
