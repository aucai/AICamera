package com.aucai.aicamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BokehTest {

    @Test
    fun blurKeepsFlatAreasAndSpreadsDetail() {
        val w = 20
        val h = 10
        val flat = FloatArray(w * h) { 0.4f }
        Bokeh.blur(flat, w, h, 1, 3).forEach { assertEquals(0.4f, it, 1e-5f) }
        val dot = FloatArray(w * h).also { it[5 * w + 10] = 1f }
        val out = Bokeh.blur(dot, w, h, 1, 2)
        assertTrue(out[5 * w + 10] < 0.5f)
        assertTrue(out[5 * w + 12] > 0f)
    }

    @Test
    fun backgroundDoesNotGlowWithThePersonsColours() {
        val w = 40
        val h = 20
        // A red person on the left half, blue background on the right.
        val mask = FloatArray(w * h) { if (it % w < 20) 1f else 0f }
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            if (i % w < 20) rgb[3 * i] = 1f else rgb[3 * i + 2] = 1f
        }
        val bg = Bokeh.background(rgb, mask, w, h, 4)
        // Just outside the person the blurred background is still blue, not purple.
        val i = 10 * w + 21
        assertTrue(bg[3 * i] < 0.05f)
        assertTrue(bg[3 * i + 2] > 0.95f)
        // A plain blur would have mixed the red in.
        val plain = Bokeh.blur(rgb, w, h, 3, 4)
        assertTrue(plain[3 * i] > 0.2f)
    }

    @Test
    fun guidedFilterSnapsTheMaskToEdgesInThePicture() {
        val w = 48
        val h = 16
        // The picture's edge is at x = 20; the segmenter's coarse mask overshoots to x = 23.
        val guide = FloatArray(w * h) { if (it % w < 20) 0.9f else 0.1f }
        val coarse = FloatArray(w * h) { if (it % w < 23) 1f else 0f }
        val refined = Bokeh.guidedFilter(guide, coarse, w, h, 4, 1e-3f)
        val row = 8 * w
        fun edge(m: FloatArray) = (0 until w).first { m[row + it] < 0.5f }
        assertTrue("refined edge ${edge(refined)}", abs(edge(refined) - 20) < abs(edge(coarse) - 20))
    }

    @Test
    fun compositeKeepsThePersonAndReplacesTheRest() {
        val w = 8
        val h = 4
        val orig = IntArray(w * h) { 0xFF102030.toInt() }
        val bg = FloatArray(4 * 2 * 3) { if (it % 3 == 0) 1f else 0f } // red, 4 x 2
        val keep = orig.copyOf()
        Bokeh.composite(keep, w, 0, h, h, FloatArray(8) { 1f }, bg, 4, 2)
        assertTrue(keep.contentEquals(orig))
        val replaced = orig.copyOf()
        Bokeh.composite(replaced, w, 0, h, h, FloatArray(8) { 0f }, bg, 4, 2)
        replaced.forEach { assertEquals(0xFFFF0000.toInt(), it) }
    }

    @Test
    fun liveLayerIsTransparentOverThePerson() {
        val mask = FloatMask(2, 1, floatArrayOf(1f, 0f))
        val bg = FloatArray(2 * 1 * 3) { 0.5f }
        val out = Bokeh.overlay(bg, 2, 1, mask, 4, 1)
        assertEquals(0, out[0] ushr 24)
        assertEquals(255, out[3] ushr 24)
        assertEquals(0.5f, mask.sample(0.5f, 0.5f), 1e-5f)
    }
}
