package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.aucai.aicamera.core.ObjectBox
import com.aucai.aicamera.core.RectN
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/** MediaPipe object detector (EfficientDet-Lite0, 80 COCO classes). Not thread-safe. */
class ObjectFinder(context: Context) : Closeable {

    private val detector: ObjectDetector
    private var lastTimestamp = 0L

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setMaxResults(5)
            .setScoreThreshold(0.4f)
            // People are handled by the pose model.
            .setCategoryDenylist(listOf("person"))
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): List<ObjectBox> {
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestamp) ts = lastTimestamp + 1
        lastTimestamp = ts

        val result = detector.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { d ->
            val c = d.categories().firstOrNull() ?: return@mapNotNull null
            val b = d.boundingBox()
            ObjectBox(c.categoryName(), c.score(), RectN(b.left / w, b.top / h, b.right / w, b.bottom / h).clamp01())
        }
    }

    override fun close() = detector.close()
}
