package com.aucai.aicamera.core

/** Blur check: variance of the Laplacian over a grayscale image. Low values mean few sharp edges. */
object Sharpness {

    /** Below this (on a ~1000 px wide image) a photo is probably shaken or out of focus. */
    const val BLURRY_BELOW = 40.0

    fun laplacianVariance(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4 * gray[i]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
