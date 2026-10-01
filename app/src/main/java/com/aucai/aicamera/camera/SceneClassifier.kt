package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import com.aucai.aicamera.core.ClassifierHit
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.io.Closeable

/** MediaPipe image classifier (EfficientNet-Lite0, 1000 ImageNet classes). Not thread-safe. */
class SceneClassifier(context: Context) : Closeable {

    private val classifier: ImageClassifier

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("efficientnet_lite0.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(8)
            .setScoreThreshold(0.03f)
            .build()
        classifier = ImageClassifier.createFromOptions(context, options)
    }

    fun classify(bitmap: Bitmap): List<ClassifierHit> {
        val result = classifier.classify(BitmapImageBuilder(bitmap).build())
        val categories = result.classificationResult().classifications().firstOrNull()?.categories() ?: return emptyList()
        return categories.map { ClassifierHit(it.index(), it.categoryName(), it.score()) }
    }

    override fun close() = classifier.close()
}
