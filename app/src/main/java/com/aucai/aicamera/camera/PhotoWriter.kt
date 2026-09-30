package com.aucai.aicamera.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.aucai.aicamera.core.RectN
import com.aucai.aicamera.core.Sharpness
import com.aucai.aicamera.core.displayToSource
import kotlin.math.roundToInt

/** Crops a captured frame to the planned framing, turns it upright and saves it to the gallery. */
object PhotoWriter {

    /**
     * @param crop the framing in display-normalized coordinates.
     * @param rotation how far the camera image must be rotated clockwise to be upright.
     * @param mirror front camera: flip horizontally like the preview.
     */
    fun cropUpright(src: Bitmap, crop: RectN, rotation: Int, mirror: Boolean): Bitmap {
        val s = displayToSource(crop.clamp01(), rotation, mirror)
        val x = (s.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (s.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (s.width * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (s.height * src.height).roundToInt().coerceIn(1, src.height - y)
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, x, y, w, h, m, true)
    }

    fun save(resolver: ContentResolver, bitmap: Bitmap, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val stream = resolver.openOutputStream(uri) ?: error("cannot open output")
            stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Laplacian variance on a ~1000 px copy; see [Sharpness.BLURRY_BELOW]. */
    fun sharpness(bitmap: Bitmap): Double {
        val scale = 1000f / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            (299 * ((c shr 16) and 0xff) + 587 * ((c shr 8) and 0xff) + 114 * (c and 0xff)) / 1000
        }
        return Sharpness.laplacianVariance(gray, small.width, small.height)
    }
}
