package com.fbt.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Uses the phone's own gyroscope (a real IMU reading, independent of the camera image) to
 * tell the difference between:
 *  - "the phone/camera actually moved" -> a sudden landmark jump is legitimate, filters
 *    should reset rather than fight it
 *  - "the phone is sitting still" -> a sudden landmark jump is almost certainly pose-model
 *    noise or a misdetection (e.g. occluded limb), and should be suppressed
 *
 * This is the IMU half of the precision pipeline - it doesn't track your limbs (a single
 * phone can't), it tracks whether the *reference frame itself* moved.
 */
class ImuMotionGate(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var lastAngularSpeed = 0f
    @Volatile private var lastMovingTimestamp = 0L

    // rad/s - above this, we consider the phone to be actively moving
    private val movingThreshold = 0.15f
    // how long to keep treating the camera as "moving" after motion stops, to avoid
    // snapping filters back on right as the phone settles (settling itself causes jitter)
    private val settleWindowMs = 400L

    fun start() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    val isCameraMoving: Boolean
        get() {
            if (gyroscope == null) return false // no gyro available - fail open, rely on visibility gating alone
            val recentlyMoving = (System.currentTimeMillis() - lastMovingTimestamp) < settleWindowMs
            return lastAngularSpeed > movingThreshold || recentlyMoving
        }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val (x, y, z) = event.values
        val speed = sqrt(x * x + y * y + z * z)
        lastAngularSpeed = speed
        if (speed > movingThreshold) lastMovingTimestamp = System.currentTimeMillis()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not needed */ }
}
