package com.aucai.aicamera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import com.aucai.aicamera.core.ColorMatrices
import com.aucai.aicamera.core.ExifOrientation
import com.aucai.aicamera.core.Filter
import com.aucai.aicamera.core.PixelLook
import com.aucai.aicamera.core.RectN
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What to do to a photo after it is taken.
 * @property crop in display-normalized coordinates of the upright picture (as seen in the preview).
 * @property fill 0..1 software fill light.
 */
data class PhotoEdits(val crop: RectN?, val filter: Filter, val fill: Float) {
    val isEmpty get() = crop == null && filter == Filter.NONE && fill < 0.01f
}

/**
 * Crops and colours a JPEG from the camera. Only the cropped region is decoded, at full resolution,
 * and the picture keeps its stored orientation (plus EXIF), so no extra copy is needed to rotate it.
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

    fun process(src: File, dst: File, edits: PhotoEdits) {
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
        try {
            val look = PixelLook(ColorMatrices.forFilter(edits.filter), edits.fill)
            if (!look.isIdentity) applyLook(bmp, look)
            FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bmp.recycle()
        }
        copyExif(exif, dst, orientation)
    }

    /** A strip at a time, so only a small pixel buffer is needed next to the bitmap. */
    private fun applyLook(bmp: Bitmap, look: PixelLook) {
        val w = bmp.width
        val rows = (262_144 / w).coerceAtLeast(1)
        val buf = IntArray(w * rows)
        var y = 0
        while (y < bmp.height) {
            val n = min(rows, bmp.height - y)
            bmp.getPixels(buf, 0, w, 0, y, w, n)
            look.apply(buf, 0, w * n)
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            y += n
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
