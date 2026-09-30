package com.aucai.aicamera.core

/** A small downsampled copy of the frame: per-cell luma (0..255) plus average colour. */
class LumaGrid(
    val width: Int,
    val height: Int,
    val luma: IntArray,
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
) {
    init {
        require(luma.size == width * height)
    }

    /** Mean luma of the cells whose centres fall inside [r]; null when none do. */
    fun mean(r: RectN): Float? = meanWhere { x, y -> x in r.left..r.right && y in r.top..r.bottom }

    fun meanOutside(r: RectN): Float? = meanWhere { x, y -> !(x in r.left..r.right && y in r.top..r.bottom) }

    private inline fun meanWhere(pred: (Float, Float) -> Boolean): Float? {
        var sum = 0L
        var n = 0
        for (j in 0 until height) {
            val cy = (j + 0.5f) / height
            for (i in 0 until width) {
                if (pred((i + 0.5f) / width, cy)) {
                    sum += luma[j * width + i]
                    n++
                }
            }
        }
        return if (n == 0) null else sum.toFloat() / n
    }
}

data class LightingResult(
    val mean: Float,
    /** 32-bin luma histogram, normalised so the tallest bin is 1. */
    val histogram: FloatArray,
    /** Per-cell flag for blown highlights, same layout as the grid. */
    val clipped: BooleanArray,
    val gridWidth: Int,
    val gridHeight: Int,
    /** Where to meter when the user taps the fix button. */
    val meterPoint: Vec2?,
    /** Share of cells that are blown out / crushed black. */
    val highRatio: Float,
    val lowRatio: Float,
    /** Mean luma of the person (face if large enough, else upper body); null without a person. */
    val subjectLuma: Float?,
    val backlit: Boolean,
    val splitLight: Boolean,
    val tips: List<Tip>,
)

object LightingAnalyzer {

    const val HIGH_CLIP = 250
    const val LOW_CLIP = 8

    fun analyze(grid: LumaGrid, pose: PoseFrame?): LightingResult {
        val n = grid.luma.size
        val bins = IntArray(32)
        val clipped = BooleanArray(n)
        var sum = 0L
        var high = 0
        var low = 0
        for (i in 0 until n) {
            val v = grid.luma[i]
            sum += v
            bins[(v shr 3).coerceIn(0, 31)]++
            if (v >= HIGH_CLIP) {
                high++
                clipped[i] = true
            }
            if (v <= LOW_CLIP) low++
        }
        val mean = sum.toFloat() / n
        val highRatio = high.toFloat() / n
        val lowRatio = low.toFloat() / n
        val maxBin = bins.max().coerceAtLeast(1)
        val hist = FloatArray(32) { bins[it].toFloat() / maxBin }

        val face = pose?.faceBounds()?.clamp01()
        val body = pose?.coreBounds()?.clamp01()
        // Use the face when it spans a few grid cells, otherwise the upper body.
        val subject = when {
            face != null && face.width * grid.width >= 3f -> face
            body != null && body.width > 0.05f -> body
            else -> null
        }

        val tips = ArrayList<Tip>()
        var subjectLuma: Float? = null
        var backlit = false
        var splitLight = false
        if (mean < 45f) {
            tips += Tip("light.dark", TipCategory.LIGHT, Severity.WARNING, "光线太暗，靠近光源或者开灯", TipAction.EXPOSURE_UP)
        }

        if (subject != null) {
            val subj = grid.mean(subject)
            val bg = grid.meanOutside(subject)
            subjectLuma = subj
            if (subj != null && bg != null && subj < 85f && bg - subj > 55f) {
                backlit = true
                tips += Tip("light.backlit", TipCategory.LIGHT, Severity.WARNING, "逆光：人物比背景暗很多", TipAction.METER_SUBJECT)
            }
            if (subj != null && subject === face && subj > 215f) {
                tips += Tip("light.facebright", TipCategory.LIGHT, Severity.SUGGEST, "脸部太亮了", TipAction.EXPOSURE_DOWN)
            }
        }

        if (face != null && face.width * grid.width >= 6f) {
            val midX = face.center.x
            val l = grid.mean(RectN(face.left, face.top, midX, face.bottom))
            val r = grid.mean(RectN(midX, face.top, face.right, face.bottom))
            if (l != null && r != null) {
                val lo = minOf(l, r)
                val hi = maxOf(l, r)
                if (lo < 110f && hi / lo.coerceAtLeast(1f) > 1.8f) {
                    splitLight = true
                    tips += Tip("light.split", TipCategory.LIGHT, Severity.SUGGEST, "脸一侧偏暗，让脸稍微转向光源")
                }
            }
        }

        if (highRatio > 0.12f) {
            tips += Tip("light.blown", TipCategory.LIGHT, Severity.SUGGEST, "大片过曝（红色斜纹区域）", TipAction.EXPOSURE_DOWN)
        } else if (highRatio > 0.05f && lowRatio > 0.12f) {
            tips += Tip("light.contrast", TipCategory.LIGHT, Severity.INFO, "明暗反差太大，可以换到阴影处拍")
        }

        val rb = grid.meanR / grid.meanB.coerceAtLeast(1f)
        if (mean > 45f) {
            if (rb > 1.5f) {
                tips += Tip("light.warm", TipCategory.LIGHT, Severity.INFO, "光线偏暖黄")
            } else if (rb < 1f / 1.3f) {
                tips += Tip("light.cool", TipCategory.LIGHT, Severity.INFO, "光线偏冷蓝")
            }
        }

        return LightingResult(
            mean, hist, clipped, grid.width, grid.height, subject?.center,
            highRatio, lowRatio, subjectLuma, backlit, splitLight, tips,
        )
    }
}
