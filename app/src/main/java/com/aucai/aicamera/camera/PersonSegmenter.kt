package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import com.aucai.aicamera.core.FloatMask
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.Closeable
import java.nio.ByteOrder

/** MediaPipe selfie segmenter: how likely each pixel is to be a person. Not thread-safe. */
class PersonSegmenter(context: Context) : Closeable {

    private val segmenter: ImageSegmenter

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("selfie_segmenter.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context, options)
    }

    /** The person mask for an upright picture, or null when the model gave nothing usable. */
    fun segment(bitmap: Bitmap): FloatMask? {
        val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
        // The selfie model has a single output: the person.
        val mask = result.confidenceMasks().orElse(null)?.lastOrNull() ?: return null
        val w = mask.width
        val h = mask.height
        val bytes = ByteBufferExtractor.extract(mask)
        val data = FloatArray(w * h)
        bytes.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(data)
        if (!plausible(data)) {
            bytes.duplicate().order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(data)
            if (!plausible(data)) return null
        }
        return FloatMask(w, h, data)
    }

    private fun plausible(data: FloatArray): Boolean {
        val step = (data.size / 64).coerceAtLeast(1)
        for (i in data.indices step step) {
            val v = data[i]
            if (v.isNaN() || v < -0.01f || v > 1.01f) return false
        }
        return true
    }

    override fun close() = segmenter.close()
}
