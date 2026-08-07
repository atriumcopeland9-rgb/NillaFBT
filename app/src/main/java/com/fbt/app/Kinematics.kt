package com.fbt.app

import kotlin.math.*

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-6f) Vec3(0f, 0f, 1f) else Vec3(x / l, y / l, z / l)
    }
    companion object {
        fun mid(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    /** Unity-style Euler XYZ in degrees, matching the order VRChat's OSC trackers expect. */
    fun toEulerDegrees(): Vec3 {
        // yaw (Y), pitch (X), roll (Z) extraction from quaternion, ZXY convention
        val sinp = 2f * (w * x - y * z)
        val pitch = if (abs(sinp) >= 1f) (PI.toFloat() / 2f) * sign(sinp) else asin(sinp)

        val sinr = 2f * (w * y + z * x)
        val cosr = 1f - 2f * (x * x + y * y)
        val yaw = atan2(sinr, cosr)

        val siny = 2f * (w * z + x * y)
        val cosy = 1f - 2f * (y * y + z * z)
        val roll = atan2(siny, cosy)

        val toDeg = 180f / PI.toFloat()
        return Vec3(pitch * toDeg, yaw * toDeg, roll * toDeg)
    }
}

/** Build a rotation matrix from an orthonormal basis (columns = right, up, forward) and convert to quaternion. */
private fun basisToQuat(right: Vec3, up: Vec3, fwd: Vec3): Quat {
    // matrix elements, column-major (m[col][row])
    val m00 = right.x; val m01 = up.x; val m02 = fwd.x
    val m10 = right.y; val m11 = up.y; val m12 = fwd.y
    val m20 = right.z; val m21 = up.z; val m22 = fwd.z

    val trace = m00 + m11 + m22
    return if (trace > 0f) {
        val s = 0.5f / sqrt(trace + 1f)
        Quat(
            (m21 - m12) * s,
            (m02 - m20) * s,
            (m10 - m01) * s,
            0.25f / s
        )
    } else if (m00 > m11 && m00 > m22) {
        val s = 2f * sqrt(1f + m00 - m11 - m22)
        Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
    } else if (m11 > m22) {
        val s = 2f * sqrt(1f + m11 - m00 - m22)
        Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
    } else {
        val s = 2f * sqrt(1f + m22 - m00 - m11)
        Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
    }
}

/**
 * Builds an orthonormal basis from a primary "up" bone direction and an approximate
 * reference "right" direction (e.g. hip line, shoulder line, heel-to-toe line), then
 * returns the resulting rotation as a quaternion.
 *
 * Coordinate conventions differ between MediaPipe's world landmarks and VRChat/Unity's
 * left-handed Y-up space, so treat the sign of individual axes as something to verify
 * during calibration - flip `up`/`right`/`fwd` signs in this function if a joint looks
 * mirrored or twisted 180 degrees in-game.
 */
fun boneRotation(upDir: Vec3, referenceRight: Vec3): Quat {
    val up = upDir.normalized()
    val rightRaw = referenceRight - up * referenceRight.dot(up) // Gram-Schmidt orthogonalize
    val right = rightRaw.normalized()
    val fwd = right.cross(up).normalized()
    val rightFinal = up.cross(fwd).normalized()
    return basisToQuat(rightFinal, up, fwd)
}

/** One tracked point's full pose. */
data class TrackedPoint(val position: Vec3, val rotation: Quat)

/**
 * Derives the 8 VRChat FBT tracker points (hip, chest, 2x knee, 2x ankle/foot, 2x elbow)
 * from MediaPipe Pose's 33 world landmarks (indices per BlazePose topology).
 */
object BodyMapper {
    // BlazePose landmark indices
    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12
    private const val L_ELBOW = 13
    private const val R_ELBOW = 14
    private const val L_WRIST = 15
    private const val R_WRIST = 16
    private const val L_HIP = 23
    private const val R_HIP = 24
    private const val L_KNEE = 25
    private const val R_KNEE = 26
    private const val L_ANKLE = 27
    private const val R_ANKLE = 28
    private const val L_HEEL = 29
    private const val R_HEEL = 30
    private const val L_FOOT_IDX = 31
    private const val R_FOOT_IDX = 32

    fun map(lm: List<Vec3>): Map<String, TrackedPoint> {
        val lShoulder = lm[L_SHOULDER]; val rShoulder = lm[R_SHOULDER]
        val lHip = lm[L_HIP]; val rHip = lm[R_HIP]
        val lKnee = lm[L_KNEE]; val rKnee = lm[R_KNEE]
        val lAnkle = lm[L_ANKLE]; val rAnkle = lm[R_ANKLE]
        val lHeel = lm[L_HEEL]; val rHeel = lm[R_HEEL]
        val lToe = lm[L_FOOT_IDX]; val rToe = lm[R_FOOT_IDX]
        val lElbow = lm[L_ELBOW]; val rElbow = lm[R_ELBOW]
        val lWrist = lm[L_WRIST]; val rWrist = lm[R_WRIST]

        val hipCenter = Vec3.mid(lHip, rHip)
        val chestCenter = Vec3.mid(lShoulder, rShoulder)
        val hipLine = rHip - lHip
        val shoulderLine = rShoulder - lShoulder

        val hip = TrackedPoint(hipCenter, boneRotation(chestCenter - hipCenter, hipLine))
        val chest = TrackedPoint(chestCenter, boneRotation(chestCenter - hipCenter, shoulderLine))

        val kneeL = TrackedPoint(lKnee, boneRotation(lKnee - lHip, hipLine))
        val kneeR = TrackedPoint(rKnee, boneRotation(rKnee - rHip, hipLine))

        val ankleL = TrackedPoint(lAnkle, boneRotation(lAnkle - lKnee, lToe - lHeel))
        val ankleR = TrackedPoint(rAnkle, boneRotation(rAnkle - rKnee, rToe - rHeel))

        val elbowL = TrackedPoint(lElbow, boneRotation(lWrist - lElbow, shoulderLine))
        val elbowR = TrackedPoint(rElbow, boneRotation(rWrist - rElbow, shoulderLine))

        return mapOf(
            "hip" to hip,
            "chest" to chest,
            "kneeL" to kneeL,
            "kneeR" to kneeR,
            "ankleL" to ankleL,
            "ankleR" to ankleR,
            "elbowL" to elbowL,
            "elbowR" to elbowR,
        )
    }
}
