package com.aucai.aicamera.core

import kotlin.math.max
import kotlin.math.min

/** A soft mask, 0..1 per pixel (e.g. how sure the segmenter is that a pixel is the person). */
class FloatMask(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(data.size == width * height)
    }

    /** Bilinear sample at normalized coordinates (0..1). */
    fun sample(nx: Float, ny: Float): Float = Bokeh.bilinear(data, width, height, 1, 0, nx * width - 0.5f, ny * height - 0.5f)

    fun mean(): Float = data.average().toFloat()
}

/**
 * Portrait background blur: keep the person sharp and blur everything else, like a phone's
 * portrait mode. Works on a small copy (mask refinement, background blur) and composites at full size.
 */
object Bokeh {

    /** Splits ARGB pixels into interleaved r, g, b floats (0..1). */
    fun toRgb(px: IntArray): FloatArray {
        val out = FloatArray(px.size * 3)
        for (i in px.indices) {
            val c = px[i]
            out[3 * i] = ((c shr 16) and 0xff) / 255f
            out[3 * i + 1] = ((c shr 8) and 0xff) / 255f
            out[3 * i + 2] = (c and 0xff) / 255f
        }
        return out
    }

    fun luma(rgb: FloatArray): FloatArray =
        FloatArray(rgb.size / 3) { 0.299f * rgb[3 * it] + 0.587f * rgb[3 * it + 1] + 0.114f * rgb[3 * it + 2] }

    /** Box blur with radius [r] (separable running sums, edges clamped). [ch] interleaved channels. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, ch: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val n = (2 * r + 1).toFloat()
        val acc = FloatArray(ch)
        for (y in 0 until h) {
            val row = y * w
            acc.fill(0f)
            for (k in -r..r) {
                val x = k.coerceIn(0, w - 1)
                for (c in 0 until ch) acc[c] += src[(row + x) * ch + c]
            }
            for (x in 0 until w) {
                for (c in 0 until ch) tmp[(row + x) * ch + c] = acc[c] / n
                val add = min(x + r + 1, w - 1)
                val sub = max(x - r, 0)
                for (c in 0 until ch) acc[c] += src[(row + add) * ch + c] - src[(row + sub) * ch + c]
            }
        }
        for (x in 0 until w) {
            acc.fill(0f)
            for (k in -r..r) {
                val y = k.coerceIn(0, h - 1)
                for (c in 0 until ch) acc[c] += tmp[(y * w + x) * ch + c]
            }
            for (y in 0 until h) {
                for (c in 0 until ch) out[(y * w + x) * ch + c] = acc[c] / n
                val add = min(y + r + 1, h - 1)
                val sub = max(y - r, 0)
                for (c in 0 until ch) acc[c] += tmp[(add * w + x) * ch + c] - tmp[(sub * w + x) * ch + c]
            }
        }
        return out
    }

    /** Three box blurs: close to a Gaussian, and rounder-looking out-of-focus areas. */
    fun blur(src: FloatArray, w: Int, h: Int, ch: Int, r: Int): FloatArray =
        boxBlur(boxBlur(boxBlur(src, w, h, ch, r), w, h, ch, r), w, h, ch, r)

    /** Makes a soft mask crisper: below [lo] is background, above [hi] is the person. */
    fun sharpen(m: FloatArray, lo: Float = 0.3f, hi: Float = 0.7f): FloatArray = FloatArray(m.size) {
        val t = ((m[it] - lo) / (hi - lo)).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }

    /**
     * Guided filter (He et al.): smooths [mask] while snapping its edges to edges in [guide], so the
     * cut-out follows hair and shoulders instead of the segmenter's coarse outline.
     */
    fun guidedFilter(guide: FloatArray, mask: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = guide.size
        val ip = FloatArray(n) { guide[it] * mask[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val meanI = boxBlur(guide, w, h, 1, r)
        val meanP = boxBlur(mask, w, h, 1, r)
        val meanIp = boxBlur(ip, w, h, 1, r)
        val meanIi = boxBlur(ii, w, h, 1, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val cov = meanIp[i] - meanI[i] * meanP[i]
            val v = meanIi[i] - meanI[i] * meanI[i]
            a[i] = cov / (v + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val ma = boxBlur(a, w, h, 1, r)
        val mb = boxBlur(b, w, h, 1, r)
        return FloatArray(n) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    /**
     * The blurred background with the person taken out first (blur of the background only, divided by
     * how much background each blur covered), so the person's colours do not glow around them.
     */
    fun background(rgb: FloatArray, mask: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val n = w * h
        val weighted = FloatArray(n * 4)
        for (i in 0 until n) {
            val bgw = 1f - mask[i]
            weighted[4 * i] = rgb[3 * i] * bgw
            weighted[4 * i + 1] = rgb[3 * i + 1] * bgw
            weighted[4 * i + 2] = rgb[3 * i + 2] * bgw
            weighted[4 * i + 3] = bgw
        }
        val blurred = blur(weighted, w, h, 4, r)
        val plain = blur(rgb, w, h, 3, r)
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val cover = blurred[4 * i + 3]
            if (cover > 0.05f) {
                out[3 * i] = blurred[4 * i] / cover
                out[3 * i + 1] = blurred[4 * i + 1] / cover
                out[3 * i + 2] = blurred[4 * i + 2] / cover
            } else {
                // Deep inside the person: nothing of the background nearby; it is hidden anyway.
                out[3 * i] = plain[3 * i]
                out[3 * i + 1] = plain[3 * i + 1]
                out[3 * i + 2] = plain[3 * i + 2]
            }
        }
        return out
    }

    /** Bilinear sample of channel [c] of an interleaved image at pixel coordinates (x, y). */
    fun bilinear(src: FloatArray, w: Int, h: Int, ch: Int, c: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt()
        val y0 = yc.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0
        val fy = yc - y0
        val a = src[(y0 * w + x0) * ch + c]
        val b = src[(y0 * w + x1) * ch + c]
        val d = src[(y1 * w + x0) * ch + c]
        val e = src[(y1 * w + x1) * ch + c]
        val top = a + (b - a) * fx
        val bottom = d + (e - d) * fx
        return top + (bottom - top) * fy
    }

    /**
     * Blends a strip of the full-size picture ([rows] rows starting at [y0], [width] wide, of a
     * picture [fullH] high) with the small blurred background, keeping the person per [mask]
     * (same size as the background, [smallW] x [smallH]).
     */
    fun composite(
        px: IntArray,
        width: Int,
        y0: Int,
        rows: Int,
        fullH: Int,
        mask: FloatArray,
        bg: FloatArray,
        smallW: Int,
        smallH: Int,
    ) {
        val sx = smallW.toFloat() / width
        val sy = smallH.toFloat() / fullH
        for (j in 0 until rows) {
            val y = (y0 + j + 0.5f) * sy - 0.5f
            for (x in 0 until width) {
                val xs = (x + 0.5f) * sx - 0.5f
                val m = bilinear(mask, smallW, smallH, 1, 0, xs, y)
                if (m > 0.995f) continue
                val i = j * width + x
                val c = px[i]
                val k = 1f - m
                val r = ((c shr 16) and 0xff) * m + bilinear(bg, smallW, smallH, 3, 0, xs, y) * 255f * k
                val g = ((c shr 8) and 0xff) * m + bilinear(bg, smallW, smallH, 3, 1, xs, y) * 255f * k
                val b = (c and 0xff) * m + bilinear(bg, smallW, smallH, 3, 2, xs, y) * 255f * k
                px[i] = (c and -0x1000000) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
    }

    /**
     * The live preview's blur layer ([outW] x [outH] ARGB): the blurred background where there is
     * no person, transparent where there is, to lay over the sharp live picture.
     */
    fun overlay(bg: FloatArray, bgW: Int, bgH: Int, mask: FloatMask, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        val sx = bgW.toFloat() / outW
        val sy = bgH.toFloat() / outH
        for (y in 0 until outH) {
            val ny = (y + 0.5f) / outH
            val by = (y + 0.5f) * sy - 0.5f
            for (x in 0 until outW) {
                val m = mask.sample((x + 0.5f) / outW, ny)
                val alpha = to8((1f - m) * 255f)
                if (alpha == 0) continue
                val bx = (x + 0.5f) * sx - 0.5f
                val r = to8(bilinear(bg, bgW, bgH, 3, 0, bx, by) * 255f)
                val g = to8(bilinear(bg, bgW, bgH, 3, 1, bx, by) * 255f)
                val b = to8(bilinear(bg, bgW, bgH, 3, 2, bx, by) * 255f)
                out[y * outW + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun to8(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> (v + 0.5f).toInt()
    }
}
