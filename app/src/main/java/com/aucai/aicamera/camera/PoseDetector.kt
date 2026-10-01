package com.aucai.aicamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.aucai.aicamera.core.Landmark
import com.aucai.aicamera.core.PoseFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable

/** MediaPipe Pose Landmarker running on-device. Not thread-safe: call from one thread. */
class PoseDetector(context: Context) : Closeable {

    private val landmarker: PoseLandmarker
    private var lastTimestamp = 0L

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): PoseFrame? {
        // VIDEO mode requires strictly increasing timestamps.
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestamp) ts = lastTimestamp + 1
        lastTimestamp = ts

        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val points = result.landmarks().firstOrNull() ?: return null
        val aspect = bitmap.width.toFloat() / bitmap.height
        return PoseFrame(points.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(1f)) }, aspect)
    }

    override fun close() = landmarker.close()
}
