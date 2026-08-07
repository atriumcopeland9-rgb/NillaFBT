package com.fbt.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // BlazePose skeleton edges - just the body, no face mesh
    private val connections = listOf(
        11 to 12, // shoulders
        11 to 13, 13 to 15, // left arm
        12 to 14, 14 to 16, // right arm
        11 to 23, 12 to 24, // shoulder -> hip
        23 to 24, // hips
        23 to 25, 25 to 27, // left leg
        24 to 26, 26 to 28, // right leg
        27 to 29, 29 to 31, 27 to 31, // left foot
        28 to 30, 30 to 32, 28 to 32  // right foot
    )

    private var points: List<ImagePoint> = emptyList()
    private var imgWidth: Int = 0
    private var imgHeight: Int = 0

    /** Set true if the source is a front camera preview being mirrored on screen. */
    var mirror: Boolean = true

    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun update(newPoints: List<ImagePoint>, width: Int, height: Int) {
        points = newPoints
        imgWidth = width
        imgHeight = height
        postInvalidate()
    }

    fun clear() {
        points = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() || imgWidth == 0 || imgHeight == 0) return

        // Assumes the PreviewView uses FIT_CENTER scaling - letterboxed, centered.
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = minOf(viewW / imgWidth, viewH / imgHeight)
        val offsetX = (viewW - imgWidth * scale) / 2f
        val offsetY = (viewH - imgHeight * scale) / 2f

        fun toScreen(p: ImagePoint): Pair<Float, Float> {
            val px = offsetX + p.x * imgWidth * scale
            val py = offsetY + p.y * imgHeight * scale
            val finalX = if (mirror) viewW - px else px
            return finalX to py
        }

        for ((a, b) in connections) {
            if (a >= points.size || b >= points.size) continue
            val (x1, y1) = toScreen(points[a])
            val (x2, y2) = toScreen(points[b])
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
        for (p in points) {
            val (x, y) = toScreen(p)
            canvas.drawCircle(x, y, 8f, pointPaint)
        }
    }
}
