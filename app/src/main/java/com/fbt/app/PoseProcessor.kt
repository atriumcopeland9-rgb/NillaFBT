package com.fbt.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/** 2D image-space landmark, normalized [0,1] against the frame that was fed to the model. */
data class ImagePoint(val x: Float, val y: Float)

/**
 * Wraps MediaPipe's PoseLandmarker task in LIVE_STREAM mode.
 * Model file expected at assets/pose_landmarker_full.task (see TERMUX_SETUP.md).
 *
 * onResult      -> 3D metric world landmarks, used to drive OSC tracker output
 * onImagePose   -> 2D normalized image landmarks + the frame's pixel size, used to draw the skeleton overlay
 * onNoPose      -> called when no person was detected in a frame
 * onError       -> called on a PoseLandmarker-internal error (e.g. delegate init failure)
 */
class PoseProcessor(
    context: Context,
    private val onResult: (List<Vec3>, List<Float>) -> Unit,
    private val onImagePose: (List<ImagePoint>, Int, Int) -> Unit = { _, _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onNoPose: () -> Unit = {}
) {
    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .setDelegate(Delegate.CPU) // CPU is slower but far more reliable across devices than GPU
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setResultListener(::handleResult)
            .setErrorListener { e -> onError(e.message ?: "Unknown PoseLandmarker error") }
            .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun process(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, timestampMs)
    }

    private fun handleResult(result: PoseLandmarkerResult, input: MPImage) {
        val worldLandmarksList = result.worldLandmarks()
        val imageLandmarksList = result.landmarks()

        if (worldLandmarksList.isEmpty() || imageLandmarksList.isEmpty()) {
            onNoPose()
            return
        }

        val points = worldLandmarksList[0].map { Vec3(it.x(), it.y(), it.z()) }
        val visibilities = worldLandmarksList[0].map { lm -> lm.visibility().orElse(1f) }
        val imagePoints: List<ImagePoint> = imageLandmarksList[0].map { lm: NormalizedLandmark ->
            ImagePoint(lm.x(), lm.y())
        }

        if (points.size >= 33 && imagePoints.size >= 33) {
            onImagePose(imagePoints, input.width, input.height)
            onResult(points, visibilities)
        } else {
            onNoPose()
        }
    }

    fun close() {
        landmarker.close()
    }
}
