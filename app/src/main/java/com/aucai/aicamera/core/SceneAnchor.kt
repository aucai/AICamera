package com.aucai.aicamera.core

import kotlin.math.sqrt

/**
 * How wide the camera sees, as tan(half field of view) across the display width and height,
 * at the current zoom and display orientation.
 * @property front front camera with a mirrored preview.
 */
data class ViewGeometry(val tanHalfW: Float, val tanHalfH: Float, val front: Boolean = false) {
    fun zoomed(zoom: Float) = copy(tanHalfW = tanHalfW / zoom, tanHalfH = tanHalfH / zoom)
}

/**
 * Pins a point of the view to a direction in space using the phone's orientation sensor, the way
 * phone makers keep a composition target glued to the scene: it moves exactly opposite to how the
 * phone turns, at sensor rate, and does not depend on recognising anything in the picture.
 *
 * Rotation matrices are 3x3 row-major, device→world (as from SensorManager.getRotationMatrixFromVector),
 * already remapped so that the device axes line up with the display: x right, y up, z towards the user.
 */
object SceneAnchor {

    /** World direction (unit vector) of a display-normalized point. */
    fun toWorld(p: Vec2, r: FloatArray, g: ViewGeometry): FloatArray {
        val u = (2f * p.x - 1f) * g.tanHalfW
        val v = (2f * p.y - 1f) * g.tanHalfH
        // The back camera looks along -z, the front one along +z (its preview is mirrored, so x stays).
        val d = floatArrayOf(u, -v, if (g.front) 1f else -1f)
        return normalize(mul(r, d))
    }

    /** Display-normalized position of a world direction, or null when it is behind the camera. */
    fun toScreen(world: FloatArray, r: FloatArray, g: ViewGeometry): Vec2? {
        val d = mulTransposed(r, world)
        val forward = if (g.front) d[2] else -d[2]
        if (forward < 0.05f) return null
        return Vec2(0.5f + d[0] / forward / (2f * g.tanHalfW), 0.5f - d[1] / forward / (2f * g.tanHalfH))
    }

    /** Moves [a] a fraction [t] of the way towards [b] (both unit vectors). */
    fun blend(a: FloatArray, b: FloatArray, t: Float) =
        normalize(FloatArray(3) { a[it] + t * (b[it] - a[it]) })

    private fun mul(r: FloatArray, v: FloatArray) = FloatArray(3) { i ->
        r[3 * i] * v[0] + r[3 * i + 1] * v[1] + r[3 * i + 2] * v[2]
    }

    private fun mulTransposed(r: FloatArray, v: FloatArray) = FloatArray(3) { i ->
        r[i] * v[0] + r[3 + i] * v[1] + r[6 + i] * v[2]
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
}
