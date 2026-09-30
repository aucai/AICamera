package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * @property rollDeg how far the phone is rotated clockwise from level (as seen by the user).
 * @property flat the phone is lying (nearly) flat, e.g. shooting food from above.
 * @property tiltX / tiltY for flat mode: tilt towards the screen's right / top edge in degrees.
 * @property pitchDeg 0 when the phone is held upright, 90 when it lies flat facing up or down.
 * @property cameraPitchDeg where the back camera points: positive looking down, negative looking up.
 */
data class LevelState(
    val rollDeg: Float,
    val flat: Boolean,
    val tiltX: Float,
    val tiltY: Float,
    val pitchDeg: Float = 0f,
    val cameraPitchDeg: Float = 0f,
)

object LevelMath {
    /**
     * @param gx gy gz gravity in Android device coordinates (x right, y up, z out of the screen).
     * @param displayRotationDeg current display rotation: 0, 90, 180 or 270.
     */
    fun compute(gx: Float, gy: Float, gz: Float, displayRotationDeg: Int): LevelState {
        val (x, y) = when (displayRotationDeg) {
            90 -> -gy to gx
            180 -> -gx to -gy
            270 -> gy to -gx
            else -> gx to gy
        }
        val g = sqrt(x * x + y * y + gz * gz).coerceAtLeast(1e-3f)
        val flat = abs(gz) > 0.85f * g
        val roll = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
        val tiltX = Math.toDegrees(asin((x / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        val tiltY = Math.toDegrees(asin((y / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        val pitch = Math.toDegrees(asin((abs(gz) / g).coerceIn(0f, 1f).toDouble())).toFloat()
        // Screen tilted towards the sky (gz > 0) means the back camera looks down.
        val cameraPitch = Math.toDegrees(asin((gz / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        return LevelState(roll, flat, tiltX, tiltY, pitch, cameraPitch)
    }
}
