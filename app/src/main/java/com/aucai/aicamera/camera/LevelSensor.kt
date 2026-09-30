package com.aucai.aicamera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.aucai.aicamera.core.LevelMath
import com.aucai.aicamera.core.LevelState

/** Reports how level the phone is, relative to the current display rotation. */
class LevelSensor(context: Context, private val onChange: (LevelState) -> Unit) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = FloatArray(3)
    private var primed = false

    /** 0, 90, 180 or 270. */
    @Volatile var displayRotationDeg = 0

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        // Low-pass filter so the level line does not jitter.
        val k = if (primed) 0.2f else 1f
        for (i in 0..2) g[i] += k * (event.values[i] - g[i])
        primed = true
        onChange(LevelMath.compute(g[0], g[1], g[2], displayRotationDeg))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
