package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * All guidance math works in "display-normalized" coordinates: x and y in [0, 1]
 * across the frame as the user sees it (already rotated upright and mirrored for
 * the front camera). Angles and distances additionally scale x by the frame's
 * aspect ratio (width / height) so they are not distorted by non-square frames.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    companion object {
        fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
}

data class RectN(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = Vec2((left + right) / 2f, (top + bottom) / 2f)

    fun expand(fx: Float, fy: Float): RectN {
        val dx = width * fx / 2f
        val dy = height * fy / 2f
        return RectN(left - dx, top - dy, right + dx, bottom + dy)
    }

    fun clamp01() = RectN(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))

    companion object {
        fun around(points: List<Vec2>): RectN? {
            if (points.isEmpty()) return null
            return RectN(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
}

/** Direction of segment a→b in degrees; 0 = pointing right, 90 = pointing down. */
fun segmentAngleDeg(a: Vec2, b: Vec2, aspect: Float): Float =
    Math.toDegrees(atan2((b.y - a.y).toDouble(), ((b.x - a.x) * aspect).toDouble())).toFloat()

fun distance(a: Vec2, b: Vec2, aspect: Float): Float {
    val dx = (b.x - a.x) * aspect
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}

/** Angle at [b] formed by a-b-c, in degrees (0..180). */
fun jointAngleDeg(a: Vec2, b: Vec2, c: Vec2, aspect: Float): Float {
    val v1x = (a.x - b.x) * aspect
    val v1y = a.y - b.y
    val v2x = (c.x - b.x) * aspect
    val v2y = c.y - b.y
    val l1 = sqrt(v1x * v1x + v1y * v1y)
    val l2 = sqrt(v2x * v2x + v2y * v2y)
    if (l1 < 1e-6f || l2 < 1e-6f) return 180f
    val cos = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cos.toDouble())).toFloat()
}

/** Deviation of a line from horizontal, folded into -90..90 (direction-independent). */
fun lineTiltDeg(a: Vec2, b: Vec2, aspect: Float): Float {
    var d = segmentAngleDeg(a, b, aspect)
    while (d > 90f) d -= 180f
    while (d < -90f) d += 180f
    return d
}

fun approxEq(a: Float, b: Float, tol: Float) = abs(a - b) <= tol
