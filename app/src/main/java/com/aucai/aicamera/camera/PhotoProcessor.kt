package com.aucai.aicamera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import com.aucai.aicamera.core.Bokeh
import com.aucai.aicamera.core.ExifOrientation
import com.aucai.aicamera.core.Filter
import com.aucai.aicamera.core.FloatMask
import com.aucai.aicamera.core.PixelLook
import com.aucai.aicamera.core.RectN
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What to do to a photo after it is taken.
 * @property crop in display-normalized coordinates of the upright picture (as seen in the preview).
 * @property fill 0..1 software fill light.
 * @property bokeh blur the background behind the person.
 */
data class PhotoEdits(val crop: RectN?, val filter: Filter, val fill: Float, val bokeh: Boolean = false) {
    val isEmpty get() = crop == null && filter == Filter.NONE && fill < 0.01f && !bokeh
}

/**
 * Crops, blurs the background and colours a JPEG from the camera. Only the cropped region is
 * decoded, at full resolution, and the picture keeps its stored orientation (plus EXIF), so no
 * extra copy is needed to rotate it. The pixel work is split over the CPU cores.
 */
object PhotoProcessor {

    /** Above this many pixels the region is decoded at half size, to stay clear of running out of memory. */
    private const val MAX_PIXELS = 30_000_000L

    private val KEEP_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
    )

    /** Size of the copy the person is found on and the background blurred on. */
    private const val SMALL_SIDE = 512

    /**
     * @param segment finds the person in an upright picture (for the background blur).
     * @return whether the background blur was applied (false when no person was found).
     */
    fun process(src: File, dst: File, edits: PhotoEdits, segment: ((Bitmap) -> FloatMask?)? = null): Boolean {
        val exif = ExifInterface(src.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        require(rawW > 0 && rawH > 0) { "not an image" }

        val swap = ExifOrientation.transposes(orientation)
        val upW = if (swap) rawH else rawW
        val upH = if (swap) rawW else rawH
        val c = edits.crop?.clamp01() ?: RectN(0f, 0f, 1f, 1f)
        val r = ExifOrientation.uprightToRaw(
            (c.left * upW).roundToInt(), (c.top * upH).roundToInt(),
            (c.right * upW).roundToInt(), (c.bottom * upH).roundToInt(),
            orientation, rawW, rawH,
        )
        val region = Rect(r[0].coerceIn(0, rawW), r[1].coerceIn(0, rawH), r[2].coerceIn(0, rawW), r[3].coerceIn(0, rawH))
        require(region.width() > 16 && region.height() > 16) { "crop too small" }

        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(src.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(src.absolutePath, false)
        } ?: error("cannot decode")
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = if (region.width().toLong() * region.height() > MAX_PIXELS) 2 else 1
        }
        val bmp = try {
            decoder.decodeRegion(region, options) ?: error("cannot decode region")
        } finally {
            decoder.recycle()
        }
        var blurred = false
        try {
            val blur = if (edits.bokeh && segment != null) prepareBlur(bmp, orientation, segment) else null
            val look = PixelLook(edits.filter.params.withFill(edits.fill))
            if (blur != null || !look.isIdentity) {
                val w = bmp.width
                val h = bmp.height
                forStrips(bmp) { px, y0, rows ->
                    blur?.let { Bokeh.composite(px, w, y0, rows, h, it.mask, it.bg, it.w, it.h) }
                    look.apply(px)
                }
            }
            blurred = blur != null
            FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bmp.recycle()
        }
        copyExif(exif, dst, orientation)
        return blurred
    }

    private class Blur(val mask: FloatArray, val bg: FloatArray, val w: Int, val h: Int)

    /**
     * Finds the person on a small upright copy, brings the mask back to the stored orientation,
     * snaps its edges to the picture's and blurs the background without the person in it.
     */
    private fun prepareBlur(bmp: Bitmap, orientation: Int, segment: (Bitmap) -> FloatMask?): Blur? {
        val scale = SMALL_SIDE.toFloat() / max(bmp.width, bmp.height)
        val sw = max(1, (bmp.width * scale).roundToInt())
        val sh = max(1, (bmp.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val m = orientationMatrix(orientation)
        val upright = if (m.isIdentity) small else Bitmap.createBitmap(small, 0, 0, sw, sh, m, true)
        val found = segment(upright) ?: return null
        // Nobody there (or barely): blurring would only spoil the photo.
        if (found.mean() < 0.02f) return null
        val raw = FloatArray(sw * sh) {
            val (ux, uy) = ExifOrientation.rawToUpright((it % sw + 0.5f) / sw, (it / sw + 0.5f) / sh, orientation)
            found.sample(ux, uy)
        }
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val rgb = Bokeh.toRgb(px)
        // Snap the edges to the picture, then firm them up again so the background next to the
        // person is not left half sharp.
        val snapped = Bokeh.guidedFilter(Bokeh.luma(rgb), Bokeh.sharpen(raw), sw, sh, r = 6, eps = 1e-3f)
        val mask = Bokeh.sharpen(snapped, 0.2f, 0.8f)
        val bg = Bokeh.background(rgb, mask, sw, sh, r = max(2, SMALL_SIDE / 56))
        return Blur(mask, bg, sw, sh)
    }

    /** Rotation/mirroring that makes a stored picture upright, per its EXIF orientation. */
    private fun orientationMatrix(o: Int) = Matrix().apply {
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    /** Runs [work] on strips of rows in parallel; each strip gets its own pixel buffer. */
    private fun forStrips(bmp: Bitmap, work: (px: IntArray, y0: Int, rows: Int) -> Unit) {
        val w = bmp.width
        val h = bmp.height
        val rows = (131_072 / w).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        try {
            val jobs = (0 until h step rows).map { y0 ->
                pool.submit {
                    val n = min(rows, h - y0)
                    val px = IntArray(w * n)
                    synchronized(bmp) { bmp.getPixels(px, 0, w, 0, y0, w, n) }
                    work(px, y0, n)
                    synchronized(bmp) { bmp.setPixels(px, 0, w, 0, y0, w, n) }
                }
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun copyExif(from: ExifInterface, dst: File, orientation: Int) {
        try {
            val out = ExifInterface(dst.absolutePath)
            for (tag in KEEP_TAGS) from.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "AICamera")
            out.saveAttributes()
        } catch (_: Exception) {
            // The photo is fine without its metadata.
        }
    }
}
