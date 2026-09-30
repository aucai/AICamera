package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour looks. Applied to the preview as a colour matrix and to the saved photo. */
enum class Filter(val label: String) {
    NONE("原图"),
    NATURAL("自然"),
    FOOD("美味"),
    VIVID("鲜艳"),
    FRESH("清新"),
    FILM("胶片"),
    MONO("黑白"),
}

/** 4x5 colour matrices in android.graphics.ColorMatrix layout (row-major, offsets in 0..255). */
object ColorMatrices {

    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun forFilter(f: Filter): FloatArray = when (f) {
        Filter.NONE -> IDENTITY.copyOf()
        // Portraits: a touch warmer and brighter, slightly softer contrast for skin.
        Filter.NATURAL -> build(saturation = 1.06f, contrast = 0.97f, brightness = 5f, warmth = 0.35f)
        // Food: warm and rich.
        Filter.FOOD -> build(saturation = 1.28f, contrast = 1.06f, brightness = 4f, warmth = 0.6f)
        // Landscapes and flowers: punchy colour.
        Filter.VIVID -> build(saturation = 1.32f, contrast = 1.08f)
        // Pets and things: bright and airy.
        Filter.FRESH -> build(saturation = 1.08f, contrast = 0.94f, brightness = 10f, warmth = -0.35f)
        Filter.FILM -> build(saturation = 0.82f, contrast = 0.9f, brightness = 8f, warmth = 0.45f)
        Filter.MONO -> build(saturation = 0f, contrast = 1.15f)
    }

    /** Saturation first, then contrast/brightness around mid grey, then colour temperature. */
    fun build(saturation: Float = 1f, contrast: Float = 1f, brightness: Float = 0f, warmth: Float = 0f): FloatArray {
        var m = saturation(saturation)
        m = concat(contrast(contrast, brightness), m)
        m = concat(warmth(warmth), m)
        return m
    }

    fun saturation(s: Float): FloatArray {
        val inv = 1f - s
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun contrast(c: Float, brightness: Float = 0f): FloatArray {
        val o = 128f * (1f - c) + brightness
        return floatArrayOf(
            c, 0f, 0f, 0f, o,
            0f, c, 0f, 0f, o,
            0f, 0f, c, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Positive is warmer (more red, less blue). */
    fun warmth(w: Float): FloatArray = floatArrayOf(
        1f + 0.08f * w, 0f, 0f, 0f, 0f,
        0f, 1f + 0.01f * w, 0f, 0f, 0f,
        0f, 0f, 1f - 0.08f * w, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** The matrix that applies [b] first, then [a]. */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (i in 0..3) {
            for (j in 0..4) {
                var v = 0f
                for (k in 0..3) v += a[i * 5 + k] * b[k * 5 + j]
                if (j == 4) v += a[i * 5 + 4]
                out[i * 5 + j] = v
            }
        }
        return out
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        if (t >= 1f) b.copyOf() else FloatArray(20) { a[it] + (b[it] - a[it]) * t }
}

/**
 * The per-pixel edit applied to a saved photo: "fill light" (lifts shadows and midtones, keeping
 * colours) followed by a colour matrix.
 */
class PixelLook(private val matrix: FloatArray?, fill: Float) {

    /** Gain per luma value, 8.8 fixed point; null without fill light. */
    private val gain: IntArray? = if (fill < 0.01f) null else {
        val amount = fill.coerceIn(0f, 1f)
        IntArray(256) { l ->
            if (l == 0) 256 else {
                val x = l / 255f
                (fillCurve(x, amount) / x * 256f).roundToInt()
            }
        }
    }

    val isIdentity get() = gain == null && (matrix == null || matrix.contentEquals(ColorMatrices.IDENTITY))

    /** Edits ARGB pixels in place. */
    fun apply(px: IntArray, from: Int = 0, to: Int = px.size) {
        val g = gain
        val m = matrix?.takeUnless { it.contentEquals(ColorMatrices.IDENTITY) }
        for (i in from until to) {
            val c = px[i]
            var r = (c shr 16) and 0xff
            var gr = (c shr 8) and 0xff
            var b = c and 0xff
            if (g != null) {
                val k = g[(77 * r + 150 * gr + 29 * b) shr 8]
                r = min(255, (r * k + 128) shr 8)
                gr = min(255, (gr * k + 128) shr 8)
                b = min(255, (b * k + 128) shr 8)
            }
            if (m != null) {
                val nr = m[0] * r + m[1] * gr + m[2] * b + m[4]
                val ng = m[5] * r + m[6] * gr + m[7] * b + m[9]
                val nb = m[10] * r + m[11] * gr + m[12] * b + m[14]
                r = clamp(nr)
                gr = clamp(ng)
                b = clamp(nb)
            }
            px[i] = (c and -0x1000000) or (r shl 16) or (gr shl 8) or b
        }
    }

    companion object {
        /**
         * Brightens shadows most, midtones some, highlights barely. Stays monotonic for
         * amount <= 1 (the slope is 1 + 2.5 * amount * (1 - x)^2 * (1 - 4x) >= 0).
         */
        fun fillCurve(x: Float, amount: Float): Float {
            val d = 1f - x
            return (x + 2.5f * amount * x * d * d * d).coerceIn(0f, 1f)
        }

        private fun clamp(v: Float): Int = when {
            v <= 0f -> 0
            v >= 255f -> 255
            else -> (v + 0.5f).toInt()
        }
    }
}

enum class ExposureNeed { UP, DOWN, OK }

/** How a framed shot is cropped: [rect] in display-normalized coordinates, [label] like "1:1". */
data class CropPlan(val rect: RectN, val label: String)

/**
 * What the camera does automatically once the shot is framed.
 * @property engaged the aiming guide finished (dot in the ring), so the automatic look applies.
 * @property crop how the photo is cropped; null = keep the whole frame.
 * @property filter the colour look that suits the subject.
 * @property softFill 0..1 shadow lift for the saved photo (software fill light).
 * @property flash fire the flash (screen flash on the front camera) as fill light.
 * @property exposure which way exposure compensation should move.
 */
data class LookPlan(
    val engaged: Boolean,
    val crop: CropPlan?,
    val filter: Filter,
    val softFill: Float,
    val flash: Boolean,
    val exposure: ExposureNeed,
) {
    companion object {
        val OFF = LookPlan(false, null, Filter.NONE, 0f, false, ExposureNeed.OK)
    }
}

object LookAdvisor {

    fun filterFor(s: FrameSubject?): Filter = when {
        s == null -> Filter.NONE
        s.kind == SubjectKind.PERSON -> Filter.NATURAL
        s.kind == SubjectKind.HORIZON -> Filter.VIVID
        s.group == ObjectGroup.FOOD -> Filter.FOOD
        s.group == ObjectGroup.PLANT -> Filter.VIVID
        else -> Filter.FRESH
    }

    /**
     * People: keep the face between darkish and bright. Otherwise: avoid large blown areas and a
     * dim overall picture. Very dark scenes are left alone (longer exposures would only blur).
     */
    fun exposureNeed(l: LightingResult, person: Boolean): ExposureNeed {
        if (l.mean < 30f) return ExposureNeed.OK
        val face = l.subjectLuma?.takeIf { l.subjectIsFace }
        if (person && face != null) {
            return when {
                face < 100f -> ExposureNeed.UP
                face > 190f -> ExposureNeed.DOWN
                else -> ExposureNeed.OK
            }
        }
        val scene = when {
            l.highRatio > 0.10f && l.mean > 90f -> ExposureNeed.DOWN
            l.mean > 190f -> ExposureNeed.DOWN
            l.mean < 75f && l.highRatio < 0.03f -> ExposureNeed.UP
            else -> ExposureNeed.OK
        }
        // A backlit person whose face is too small to measure: darkening for the sky would lose them.
        return if (person && l.backlit && scene == ExposureNeed.DOWN) ExposureNeed.OK else scene
    }

    /**
     * Fill flash for a person: in backlight or dim light and near enough for a phone flash to reach
     * (face at least 5% of the frame width, roughly 3 m). On the front camera the screen lights the
     * face, which only helps in the dark.
     */
    fun wantsFlash(l: LightingResult, pose: PoseFrame?, front: Boolean, hasFlash: Boolean): Boolean {
        if (!hasFlash || pose == null || !pose.hasShoulders) return false
        if (front) return l.mean < 50f
        val face = pose.faceBounds() ?: return false
        if (face.width < 0.05f) return false
        return l.backlit || l.mean < 50f
    }

    fun softFill(l: LightingResult, person: Boolean, flash: Boolean): Float {
        var f = 0f
        if (person && l.backlit) f = max(f, 0.6f)
        if (person && l.splitLight) f = max(f, 0.35f)
        if (l.mean < 60f) f = max(f, 0.35f)
        if (l.lowRatio > 0.15f) f = max(f, 0.3f)
        // The flash already lights the subject; only even out what it does not reach.
        return if (flash) f * 0.4f else f
    }
}

/**
 * The final crop of a framed shot: an aspect ratio that suits the subject, placed by the same
 * composition rules as the aiming guide but kept inside the frame. Smoothed so it does not jitter.
 */
class AutoCrop(private val smoothing: Float = 0.3f) {

    private var rect: RectN? = null
    private var label: String? = null

    fun reset() {
        rect = null
        label = null
    }

    fun update(aim: AimState, s: FrameSubject?, frameAspect: Float, style: PortraitStyle): CropPlan? {
        val planned = plan(aim, s, frameAspect, style)
        if (planned == null) {
            // Subject briefly lost: keep the last crop.
            return current()
        }
        val (want, lbl) = planned
        val prev = rect
        rect = if (prev == null || lbl != label || jump(prev, want)) want else RectN(
            prev.left + smoothing * (want.left - prev.left),
            prev.top + smoothing * (want.top - prev.top),
            prev.right + smoothing * (want.right - prev.right),
            prev.bottom + smoothing * (want.bottom - prev.bottom),
        )
        label = lbl
        return current()
    }

    private fun current(): CropPlan? {
        val r = rect ?: return null
        val l = label ?: return null
        // Nothing worth cropping.
        if (r.width > 0.985f && r.height > 0.985f) return null
        return CropPlan(r, l)
    }

    private fun jump(a: RectN, b: RectN) =
        abs(a.left - b.left) > 0.2f || abs(a.top - b.top) > 0.2f || abs(a.width - b.width) > 0.2f

    companion object {
        private val RATIOS = listOf(
            1f to "1:1", 4f / 3f to "4:3", 3f / 4f to "3:4", 16f / 9f to "16:9", 9f / 16f to "9:16",
            3f / 2f to "3:2", 2f / 3f to "2:3", 4f / 5f to "4:5", 5f / 4f to "5:4",
        )

        /** Output width / height for the subject, with its label. */
        fun aspectFor(s: FrameSubject, frameAspect: Float): Pair<Float, String> = when {
            s.group == ObjectGroup.FOOD || s.group == ObjectGroup.PLANT -> 1f to "1:1"
            s.kind == SubjectKind.HORIZON && frameAspect > 1f -> 16f / 9f to "16:9"
            else -> nearest(frameAspect)
        }

        /** The common aspect ratio closest to [a]. */
        fun nearest(a: Float): Pair<Float, String> = RATIOS.minBy { abs(ln(it.first / a)) }

        fun plan(aim: AimState, s: FrameSubject?, frameAspect: Float, style: PortraitStyle): Pair<RectN, String>? {
            val view = aim.view
            val target = aim.target
            if (aim.external && view != null && target != null) return external(view, target, frameAspect)
            if (s == null) return null
            val (outAspect, label) = aspectFor(s, frameAspect)
            // People in their surroundings and landscapes keep their width; others may be trimmed more.
            val wide = s.kind == SubjectKind.HORIZON || (s.kind == SubjectKind.PERSON && style == PortraitStyle.SCENE)
            val options = FramingOptions(
                scales = if (wide) floatArrayOf(1f, 0.92f) else floatArrayOf(1f, 0.92f, 0.85f, 0.78f),
                clamp = true,
                zoomCost = 0.8f,
                closeUp = s.kind == SubjectKind.PERSON && style == PortraitStyle.CLOSE,
            )
            val best = FramingPlanner.candidates(s, frameAspect, outAspect, GridMode.THIRDS, options).minBy { it.cost }
            return best.rect to label
        }

        /** The cloud model's framing around the aimed-at point, snapped to a common aspect ratio. */
        private fun external(view: Vec2, target: Vec2, frameAspect: Float): Pair<RectN, String> {
            val (aspect, label) = nearest(view.x * frameAspect / view.y.coerceAtLeast(1e-3f))
            var h = view.y
            var w = h * aspect / frameAspect
            val over = max(w, h)
            if (over > 1f) {
                w /= over
                h /= over
            }
            val left = (target.x - w / 2f).coerceIn(0f, 1f - w)
            val top = (target.y - h / 2f).coerceIn(0f, 1f - h)
            return RectN(left, top, left + w, top + h) to label
        }
    }
}

/**
 * Decides when the automatic look applies: from the moment the aiming guide finishes until the
 * framing has been lost for a moment (so small wobbles do not switch it on and off).
 */
class LookEngine(private val releaseMs: Long = 1500) {

    private val crop = AutoCrop()
    private var lastDone = NEVER

    fun reset() {
        crop.reset()
        lastDone = NEVER
    }

    fun update(
        nowMs: Long,
        comp: CompositionResult,
        lighting: LightingResult?,
        pose: PoseFrame?,
        input: GuidanceInput,
    ): LookPlan {
        val aim = comp.aim
        val s = comp.frameSubject
        val filter = LookAdvisor.filterFor(s)
        if (!input.enhance || aim.phase == AimPhase.IDLE) {
            reset()
            return LookPlan.OFF.copy(filter = filter)
        }
        if (aim.phase == AimPhase.DONE) lastDone = nowMs
        if (lastDone == NEVER || nowMs - lastDone > releaseMs) {
            reset()
            return LookPlan.OFF.copy(filter = filter)
        }
        val a = input.aim
        val person = s?.kind == SubjectKind.PERSON
        val cropPlan = crop.update(aim, s, a.frameAspect, a.style)
        if (lighting == null) return LookPlan(true, cropPlan, filter, 0f, false, ExposureNeed.OK)
        val flash = LookAdvisor.wantsFlash(lighting, pose, a.frontCamera, input.hasFlash)
        return LookPlan(
            engaged = true,
            crop = cropPlan,
            filter = filter,
            softFill = LookAdvisor.softFill(lighting, person, flash),
            flash = flash,
            exposure = LookAdvisor.exposureNeed(lighting, person),
        )
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * Nudges exposure compensation a third of a stop at a time, giving the camera time to settle in
 * between, until the picture is in range. Only ever undoes its own changes.
 */
class ExposureAssist(private val intervalMs: Long = 500, private val maxEv: Float = 2f) {

    /** The index last asked for. */
    var index = 0
        private set
    private var changedAt = Long.MIN_VALUE / 2

    fun reset() {
        index = 0
        changedAt = Long.MIN_VALUE / 2
    }

    /** @return the exposure compensation index to set now, or null to leave it as it is. */
    fun update(nowMs: Long, need: ExposureNeed, active: Boolean, range: IntRange, stepEv: Float): Int? {
        if (stepEv <= 0f || range.isEmpty()) return null
        val target = if (!active) {
            0.coerceIn(range)
        } else {
            if (nowMs - changedAt < intervalMs) return null
            val per = max(1, (1f / 3f / stepEv).roundToInt())
            val limit = floor(maxEv / stepEv + 1e-3f).toInt()
            val next = when (need) {
                ExposureNeed.UP -> index + per
                ExposureNeed.DOWN -> index - per
                ExposureNeed.OK -> return null
            }
            next.coerceIn(max(range.first, -limit), min(range.last, limit))
        }
        if (target == index) return null
        index = target
        changedAt = nowMs
        return target
    }
}

/** EXIF orientation (1..8) handling for cropping a photo stored sideways or mirrored. */
object ExifOrientation {

    /** Orientations that swap width and height. */
    fun transposes(o: Int) = o in 5..8

    /**
     * Maps a rectangle in the upright picture to the stored pixels ([rawW] x [rawH]).
     * @return left, top, right, bottom.
     */
    fun uprightToRaw(left: Int, top: Int, right: Int, bottom: Int, o: Int, rawW: Int, rawH: Int): IntArray {
        val a = point(left, top, o, rawW, rawH)
        val b = point(right, bottom, o, rawW, rawH)
        return intArrayOf(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    /** Where an upright point is in the stored image. */
    fun point(x: Int, y: Int, o: Int, w: Int, h: Int): Pair<Int, Int> = when (o) {
        2 -> (w - x) to y
        3 -> (w - x) to (h - y)
        4 -> x to (h - y)
        5 -> y to x
        6 -> y to (h - x)
        7 -> (w - y) to (h - x)
        8 -> (w - y) to x
        else -> x to y
    }
}
