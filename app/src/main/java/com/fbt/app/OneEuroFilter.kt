package com.fbt.app

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter (Casiez, Roussel, Vogel 2012) - adapts smoothing strength to speed:
 * heavy smoothing when nearly still (kills jitter), lighter smoothing when moving fast.
 *
 * Tuned here for HEAVY smoothing by default (low minCutoff, low beta) since the priority
 * is jitter removal over raw responsiveness. This trades in some lag - see LandmarkSmoother
 * for the full picture including occlusion freezing.
 */
class OneEuroFilter(
    private var minCutoff: Float = 0.4f,
    private var beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long? = null

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, timestampMs: Long): Float {
        val tPrevVal = tPrev
        if (tPrevVal == null) {
            tPrev = timestampMs
            xPrev = x
            return x
        }
        var dt = (timestampMs - tPrevVal) / 1000f
        if (dt <= 0f) dt = 1f / 30f
        tPrev = timestampMs

        val dx = (x - (xPrev ?: x)) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        dxPrev = dxHat

        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * (xPrev ?: x)
        xPrev = xHat
        return xHat
    }

    fun reset() {
        xPrev = null
        tPrev = null
        dxPrev = 0f
    }
}

/** Simple exponential moving average - used as a second smoothing pass on top of One Euro. */
class EmaFilter(private val alpha: Float = 0.25f) {
    private var value: Float? = null
    fun filter(x: Float): Float {
        val prev = value
        val out = if (prev == null) x else alpha * x + (1 - alpha) * prev
        value = out
        return out
    }
    fun reset() { value = null }
}

/** Filters a Vec3 stream: One Euro per axis, then a light EMA pass for extra smoothing. */
class Vec3Filter(minCutoff: Float = 0.4f, beta: Float = 0.007f, emaAlpha: Float = 0.35f) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)
    private val fz = OneEuroFilter(minCutoff, beta)
    private val ex = EmaFilter(emaAlpha)
    private val ey = EmaFilter(emaAlpha)
    private val ez = EmaFilter(emaAlpha)

    fun filter(v: Vec3, timestampMs: Long) = Vec3(
        ex.filter(fx.filter(v.x, timestampMs)),
        ey.filter(fy.filter(v.y, timestampMs)),
        ez.filter(fz.filter(v.z, timestampMs))
    )

    fun reset() {
        fx.reset(); fy.reset(); fz.reset()
        ex.reset(); ey.reset(); ez.reset()
    }
}

/**
 * Applies heavy filtering to all 33 pose landmarks, with two extra precision measures:
 *
 * 1. Visibility gating - MediaPipe reports a confidence ("visibility") per landmark each
 *    frame. When a joint is occluded (folded leg while sitting, limb behind torso while
 *    lying down, etc.), visibility drops. Below `visibilityThreshold`, this class stops
 *    feeding that landmark's new (unreliable) position into the filter and instead holds
 *    the last trusted position - so an occluded leg freezes in place rather than being
 *    dragged toward a wrong guess.
 *
 * 2. Camera-motion awareness - if the phone itself is moving (see ImuMotionGate), a sudden
 *    landmark jump is legitimate (the reference frame changed) and filters are reset rather
 *    than fought against. If the phone is stationary, a sudden jump is far more likely to be
 *    model noise/misdetection, so it gets suppressed instead.
 */
class LandmarkSmoother(
    minCutoff: Float = 0.4f,
    beta: Float = 0.007f,
    emaAlpha: Float = 0.35f,
    private val visibilityThreshold: Float = 0.5f,
    private val maxPlausibleJumpMeters: Float = 0.35f // per-frame; tune down for stricter gating
) {
    private val filters = List(33) { Vec3Filter(minCutoff, beta, emaAlpha) }
    private val lastTrusted = arrayOfNulls<Vec3>(33)

    fun smooth(
        landmarks: List<Vec3>,
        visibilities: List<Float>,
        timestampMs: Long,
        cameraIsMoving: Boolean
    ): List<Vec3> {
        if (landmarks.size != 33) return landmarks

        return List(33) { i ->
            val raw = landmarks[i]
            val vis = visibilities.getOrElse(i) { 1f }
            val prevTrusted = lastTrusted[i]

            val jumpTooLarge = !cameraIsMoving && prevTrusted != null &&
                (raw - prevTrusted).length() > maxPlausibleJumpMeters

            val useRaw = vis >= visibilityThreshold && !jumpTooLarge
            val input = if (useRaw) raw else (prevTrusted ?: raw)

            if (useRaw) lastTrusted[i] = raw

            filters[i].filter(input, timestampMs)
        }
    }

    fun reset() {
        filters.forEach { it.reset() }
        for (i in lastTrusted.indices) lastTrusted[i] = null
    }
}
