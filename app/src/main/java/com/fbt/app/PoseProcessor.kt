package com.fbt.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Wraps MediaPipe's PoseLandmarker task in LIVE_STREAM mode.
 * Model file expected at assets/pose_landmarker_full.task (see TERMUX_SETUP.md).
 */
class PoseProcessor(
    context: Context,
    private val onResult: (List<Vec3>) -> Unit
) {
    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .setDelegate(Delegate.GPU)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setResultListener(::handleResult)
            .setErrorListener { /* keep running; drop frame on transient errors */ }
            .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun process(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, timestampMs)
    }

    private fun handleResult(result: PoseLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val worldLandmarksList = result.worldLandmarks()
        if (worldLandmarksList.isEmpty()) return
        val points = worldLandmarksList[0].map { Vec3(it.x(), it.y(), it.z()) }
        if (points.size >= 33) onResult(points)
    }

    fun close() {
        landmarker.close()
    }
}
