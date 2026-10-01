package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a look changes a picture. All amounts are roughly -1..1 (0 = unchanged).
 * @property exposure brightness in stops.
 * @property shadows lifts (+) or deepens (-) the dark parts; this is also the "fill light".
 * @property highlights recovers (-) or brightens (+) the bright parts.
 * @property contrast an S-curve (+) or a flatter picture (-).
 * @property vibrance more colour where there is little, leaving skin and already strong colours alone.
 * @property warmth warmer (+) or cooler (-) white balance.
 * @property sky deeper, richer blue sky; [green] richer foliage; [warm] richer reds and oranges (food, sunsets).
 * @property fade lifted blacks, like film.
 */
data class LookParams(
    val exposure: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val contrast: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sky: Float = 0f,
    val green: Float = 0f,
    val warm: Float = 0f,
    val fade: Float = 0f,
    val mono: Boolean = false,
) {
    val isIdentity get() = this == IDENTITY

    /** Adds software fill light (0..1) to the shadows. */
    fun withFill(fill: Float) = if (fill < 0.01f) this else copy(shadows = (shadows + fill).coerceAtMost(1f))

    companion object {
        val IDENTITY = LookParams()
    }
}

/** Looks: one per kind of scene, plus a few to pick by hand. */
enum class Filter(val label: String, val params: LookParams) {
    NONE("原图", LookParams.IDENTITY),
    NATURAL("自然", LookParams(shadows = 0.12f, highlights = -0.2f, contrast = 0.1f, vibrance = 0.18f)),
    PORTRAIT("人像", LookParams(exposure = 0.1f, shadows = 0.18f, highlights = -0.25f, contrast = 0.08f, vibrance = 0.12f, warmth = 0.15f)),
    FOOD("美食", LookParams(exposure = 0.15f, shadows = 0.12f, highlights = -0.15f, contrast = 0.18f, vibrance = 0.35f, saturation = 0.08f, warmth = 0.3f, warm = 0.4f)),
    LANDSCAPE("风光", LookParams(shadows = 0.15f, highlights = -0.35f, contrast = 0.2f, vibrance = 0.35f, sky = 0.4f, green = 0.3f)),
    SKY("蓝天", LookParams(shadows = 0.1f, highlights = -0.4f, contrast = 0.15f, vibrance = 0.3f, sky = 0.65f, green = 0.2f)),
    GREEN("绿植", LookParams(shadows = 0.12f, highlights = -0.2f, contrast = 0.15f, vibrance = 0.3f, green = 0.6f)),
    SUNSET("日落", LookParams(exposure = -0.15f, highlights = -0.3f, contrast = 0.2f, vibrance = 0.3f, warmth = 0.25f, warm = 0.55f)),
    NIGHT("夜景", LookParams(shadows = 0.15f, highlights = -0.45f, contrast = 0.12f, vibrance = 0.15f, warmth = -0.1f)),
    FLOWER("花卉", LookParams(shadows = 0.1f, highlights = -0.2f, contrast = 0.12f, vibrance = 0.35f, saturation = 0.1f, green = 0.2f, warm = 0.2f)),
    PET("萌宠", LookParams(exposure = 0.1f, shadows = 0.22f, highlights = -0.2f, contrast = 0.1f, vibrance = 0.15f, warmth = 0.1f)),
    SNOW("雪景", LookParams(exposure = 0.2f, highlights = -0.2f, contrast = 0.1f, vibrance = 0.1f, warmth = -0.2f, sky = 0.3f)),
    BUILDING("建筑", LookParams(shadows = 0.15f, highlights = -0.3f, contrast = 0.22f, vibrance = 0.15f, sky = 0.3f)),
    DOCUMENT("文档", LookParams(exposure = 0.25f, contrast = 0.35f, saturation = -0.3f)),
    FRESH("清新", LookParams(exposure = 0.2f, shadows = 0.2f, contrast = -0.1f, vibrance = 0.1f, warmth = -0.25f, fade = 0.2f)),
    FILM("胶片", LookParams(highlights = -0.2f, contrast = 0.1f, saturation = -0.2f, warmth = 0.3f, fade = 0.5f)),
    MONO("黑白", LookParams(contrast = 0.3f, mono = true));

    companion object {
        /** The looks offered when picking by hand. */
        val MANUAL = listOf(NONE, PORTRAIT, FOOD, LANDSCAPE, FRESH, FILM, MONO)

        fun forScene(k: SceneKind): Filter = when (k) {
            SceneKind.PORTRAIT -> PORTRAIT
            SceneKind.FOOD -> FOOD
            SceneKind.CAT, SceneKind.DOG, SceneKind.ANIMAL -> PET
            SceneKind.FLOWER -> FLOWER
            SceneKind.GREENERY -> GREEN
            SceneKind.BLUE_SKY -> SKY
            SceneKind.SUNSET -> SUNSET
            SceneKind.NIGHT -> NIGHT
            SceneKind.SNOW -> SNOW
            SceneKind.WATER, SceneKind.MOUNTAIN, SceneKind.OUTDOOR -> LANDSCAPE
            SceneKind.BUILDING -> BUILDING
            SceneKind.DOCUMENT -> DOCUMENT
            SceneKind.OBJECT, SceneKind.INDOOR -> NATURAL
            SceneKind.UNKNOWN -> NONE
        }
    }
}

/**
 * The per-pixel look. [ToneCurve] and [apply] are the reference; the live preview runs the same
 * maths as a GPU shader ([LookShader]).
 */
object LookMath {

    /** Tone curve on luma 0..1: shadows, highlights, contrast, then fade. Monotonic for the ranges used. */
    fun tone(x0: Float, p: LookParams): Float {
        var y = x0
        val d = 1f - y
        y += if (p.shadows >= 0f) 2.5f * p.shadows * y * d * d * d else p.shadows * 0.6f * y * d * d
        y += p.highlights * 1.2f * y * y * y * (1f - y)
        val s = y * y * (3f - 2f * y)
        y += p.contrast * (s - y)
        y = p.fade * 0.08f + y * (1f - p.fade * 0.08f)
        return y.coerceIn(0f, 1f)
    }

    fun hueWeight(h: Float, center: Float, width: Float): Float {
        var d = abs(h - center)
        if (d > 180f) d = 360f - d
        return max(0f, 1f - d / width)
    }
}

/** A precomputed [LookParams] for fast use on many pixels. */
class PixelLook(private val p: LookParams) {

    private val lut = FloatArray(LUT + 1) { LookMath.tone(it / LUT.toFloat(), p) }
    private val wr = 1f + 0.1f * p.warmth
    private val wb = 1f - 0.1f * p.warmth
    private val gain = 2f.pow(p.exposure / 2.2f)
    private val sat = 1f + p.saturation

    val isIdentity get() = p.isIdentity

    private fun tone(x: Float): Float {
        val f = x.coerceIn(0f, 1f) * LUT
        val i = f.toInt().coerceAtMost(LUT - 1)
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }

    /** Edits ARGB pixels in place. */
    fun apply(px: IntArray, from: Int = 0, to: Int = px.size) {
        if (isIdentity) return
        for (i in from until to) {
            val c = px[i]
            var r = ((c shr 16) and 0xff) / 255f * wr * gain
            var g = ((c shr 8) and 0xff) / 255f * gain
            var b = (c and 0xff) / 255f * wb * gain
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val t = tone(l)
            if (l > 1e-4f) {
                val k = t / l
                r *= k
                g *= k
                b *= k
            } else {
                r += t
                g += t
                b += t
            }
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val d = mx - mn
            var f = 0f
            var shift = 0f
            if (!p.mono && d > 1e-4f) {
                val h = ColorStats.hue(r, g, b, mx, d)
                val sv = d.coerceIn(0f, 1f)
                val skin = LookMath.hueWeight(h, 25f, 25f) * (if (sv > 0.1f && sv < 0.6f) 1f else 0.3f)
                f = sat * (1f + p.vibrance * (1f - sv) * (1f - 0.6f * skin))
                val wSky = LookMath.hueWeight(h, 215f, 40f)
                f *= 1f + p.sky * 0.5f * wSky + p.green * 0.45f * LookMath.hueWeight(h, 110f, 45f) +
                    p.warm * 0.4f * LookMath.hueWeight(h, 25f, 30f)
                shift = -p.sky * 0.08f * wSky * sv
            }
            r = t + shift + (r - t) * f
            g = t + shift + (g - t) * f
            b = t + shift + (b - t) * f
            px[i] = (c and -0x1000000) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    private companion object {
        const val LUT = 1024

        fun to8(v: Float): Int = when {
            v <= 0f -> 0
            v >= 1f -> 255
            else -> (v * 255f + 0.5f).toInt()
        }
    }
}

/** 4x5 colour matrices in android.graphics.ColorMatrix layout, for phones without shader effects. */
object ColorMatrices {

    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** The closest a colour matrix gets to a look (no tone curve, no colour-specific changes). */
    fun approximate(p: LookParams): FloatArray {
        if (p.isIdentity) return IDENTITY.copyOf()
        val s = if (p.mono) 0f else 1f + p.saturation + 0.5f * p.vibrance + 0.15f * (p.sky + p.green + p.warm)
        var m = saturation(s)
        val gain = 2f.pow(p.exposure / 2.2f)
        m = concat(contrast(1f + 0.35f * p.contrast - 0.05f * p.fade, 255f * (0.06f * p.shadows + 0.03f * p.fade)), m)
        m = concat(scale(gain * (1f + 0.1f * p.warmth), gain, gain * (1f - 0.1f * p.warmth)), m)
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

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
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
}

/** Blends two looks, for fading between them. */
fun lerp(a: LookParams, b: LookParams, t: Float): LookParams {
    if (t >= 1f) return b
    fun f(x: Float, y: Float) = x + (y - x) * t
    return LookParams(
        f(a.exposure, b.exposure), f(a.shadows, b.shadows), f(a.highlights, b.highlights), f(a.contrast, b.contrast),
        f(a.vibrance, b.vibrance), f(a.saturation, b.saturation), f(a.warmth, b.warmth), f(a.sky, b.sky),
        f(a.green, b.green), f(a.warm, b.warm), f(a.fade, b.fade), if (t < 0.5f) a.mono else b.mono,
    )
}

/**
 * The look as an AGSL shader for the live preview (Android 13+). Same maths as [PixelLook].
 * Uniforms: wb (white balance and exposure gains), shadows, highlights, contrast, fade, sat,
 * vib, sky, green, warm, mono (0 or 1).
 */
object LookShader {
    const val SOURCE = """
uniform shader content;
uniform float3 wb;
uniform float shadows;
uniform float highlights;
uniform float contrast;
uniform float fade;
uniform float sat;
uniform float vib;
uniform float sky;
uniform float green;
uniform float warm;
uniform float mono;

float tone(float x) {
    float y = x;
    float d = 1.0 - y;
    if (shadows >= 0.0) {
        y = y + 2.5 * shadows * y * d * d * d;
    } else {
        y = y + shadows * 0.6 * y * d * d;
    }
    y = y + highlights * 1.2 * y * y * y * (1.0 - y);
    float s = y * y * (3.0 - 2.0 * y);
    y = y + contrast * (s - y);
    y = fade * 0.08 + y * (1.0 - fade * 0.08);
    return clamp(y, 0.0, 1.0);
}

float hueWeight(float h, float c, float w) {
    float d = abs(h - c);
    if (d > 180.0) {
        d = 360.0 - d;
    }
    return max(0.0, 1.0 - d / w);
}

half4 main(float2 p) {
    half4 src = content.eval(p);
    float a = float(src.a);
    float3 c = float3(src.rgb);
    if (a > 0.0) {
        c = c / a;
    }
    c = c * wb;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    float t = tone(clamp(l, 0.0, 1.0));
    if (l > 0.0001) {
        c = c * (t / l);
    } else {
        c = c + float3(t);
    }
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float f = 0.0;
    float shift = 0.0;
    if (mono < 0.5 && d > 0.0001) {
        float h = 0.0;
        if (mx == c.r) {
            h = (c.g - c.b) / d;
            if (h < 0.0) {
                h = h + 6.0;
            }
        } else if (mx == c.g) {
            h = (c.b - c.r) / d + 2.0;
        } else {
            h = (c.r - c.g) / d + 4.0;
        }
        h = h * 60.0;
        float sv = clamp(d, 0.0, 1.0);
        float skin = hueWeight(h, 25.0, 25.0);
        if (sv <= 0.1 || sv >= 0.6) {
            skin = skin * 0.3;
        }
        f = sat * (1.0 + vib * (1.0 - sv) * (1.0 - 0.6 * skin));
        float wSky = hueWeight(h, 215.0, 40.0);
        f = f * (1.0 + sky * 0.5 * wSky + green * 0.45 * hueWeight(h, 110.0, 45.0) + warm * 0.4 * hueWeight(h, 25.0, 30.0));
        shift = -sky * 0.08 * wSky * sv;
    }
    float3 o = clamp(float3(t + shift) + (c - float3(t)) * f, 0.0, 1.0);
    return half4(half3(o * a), half(a));
}
"""
}

enum class ExposureNeed { UP, DOWN, OK }

/** How a framed shot is cropped: [rect] in display-normalized coordinates, [label] like "1:1". */
data class CropPlan(val rect: RectN, val label: String)

/**
 * What the camera does automatically.
 * @property engaged the aiming guide finished (dot in the ring), so the photo is cropped.
 * @property crop how the photo is cropped; null = keep the whole frame.
 * @property filter the look that suits the scene (applied as soon as the scene is recognised).
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

    /**
     * People: keep the face between darkish and bright. Snow and white paper should look white, and a
     * sunset a little dark so its colours stay rich. Otherwise: avoid large blown areas and a dim
     * picture. Very dark scenes are left alone (longer exposures would only blur).
     */
    fun exposureNeed(l: LightingResult, person: Boolean, scene: SceneKind = SceneKind.UNKNOWN): ExposureNeed {
        if (l.mean < 30f) return ExposureNeed.OK
        val face = l.subjectLuma?.takeIf { l.subjectIsFace }
        if (person && face != null) {
            return when {
                face < 100f -> ExposureNeed.UP
                face > 190f -> ExposureNeed.DOWN
                else -> ExposureNeed.OK
            }
        }
        when (scene) {
            SceneKind.SNOW -> return if (l.mean < 150f && l.highRatio < 0.1f) ExposureNeed.UP else ExposureNeed.OK
            SceneKind.DOCUMENT -> return if (l.mean < 140f && l.highRatio < 0.1f) ExposureNeed.UP else ExposureNeed.OK
            SceneKind.SUNSET -> return if (l.mean > 115f || l.highRatio > 0.06f) ExposureNeed.DOWN else ExposureNeed.OK
            SceneKind.NIGHT -> return if (l.highRatio > 0.08f) ExposureNeed.DOWN else ExposureNeed.OK
            else -> Unit
        }
        val general = when {
            l.highRatio > 0.10f && l.mean > 90f -> ExposureNeed.DOWN
            l.mean > 190f -> ExposureNeed.DOWN
            l.mean < 75f && l.highRatio < 0.03f -> ExposureNeed.UP
            else -> ExposureNeed.OK
        }
        // A backlit person whose face is too small to measure: darkening for the sky would lose them.
        return if (person && l.backlit && general == ExposureNeed.DOWN) ExposureNeed.OK else general
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
 * It never magnifies much past 2x in total (zoom times crop): beyond that photos get soft.
 */
class AutoCrop(private val smoothing: Float = 0.3f) {

    private var rect: RectN? = null
    private var label: String? = null

    fun reset() {
        rect = null
        label = null
    }

    fun update(
        aim: AimState,
        s: FrameSubject?,
        frameAspect: Float,
        style: PortraitStyle,
        scene: SceneKind = SceneKind.UNKNOWN,
        zoom: Float = 1f,
    ): CropPlan? {
        val planned = plan(aim, s, frameAspect, style, scene, zoom)
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
        /** Highest total magnification (camera zoom x crop) before photos get visibly soft. */
        const val MAX_MAGNIFICATION = 2.2f

        private val RATIOS = listOf(
            1f to "1:1", 4f / 3f to "4:3", 3f / 4f to "3:4", 16f / 9f to "16:9", 9f / 16f to "9:16",
            3f / 2f to "3:2", 2f / 3f to "2:3", 4f / 5f to "4:5", 5f / 4f to "5:4",
        )

        /** Output width / height for the subject, with its label. */
        fun aspectFor(s: FrameSubject, frameAspect: Float, scene: SceneKind = SceneKind.UNKNOWN): Pair<Float, String> = when {
            s.group == ObjectGroup.FOOD || s.group == ObjectGroup.PLANT || scene == SceneKind.FOOD || scene == SceneKind.FLOWER ->
                1f to "1:1"
            frameAspect > 1f && (s.kind == SubjectKind.HORIZON || SceneRecognizer.isLandscape(scene)) -> 16f / 9f to "16:9"
            else -> nearest(frameAspect)
        }

        /** The common aspect ratio closest to [a]. */
        fun nearest(a: Float): Pair<Float, String> = RATIOS.minBy { abs(ln(it.first / a)) }

        fun plan(
            aim: AimState,
            s: FrameSubject?,
            frameAspect: Float,
            style: PortraitStyle,
            scene: SceneKind = SceneKind.UNKNOWN,
            zoom: Float = 1f,
        ): Pair<RectN, String>? {
            val view = aim.view
            val target = aim.target
            if (aim.external && view != null && target != null) return external(view, target, frameAspect)
            if (s == null) return null
            val (outAspect, label) = aspectFor(s, frameAspect, scene)
            // People in their surroundings and landscapes keep their width; others may be trimmed more,
            // as long as the photo keeps enough pixels.
            val wide = s.kind == SubjectKind.HORIZON || (s.kind == SubjectKind.PERSON && style == PortraitStyle.SCENE)
            val all = if (wide) floatArrayOf(1f, 0.92f) else floatArrayOf(1f, 0.92f, 0.85f, 0.78f)
            val scales = all.filter { it >= 0.99f || zoom / it <= MAX_MAGNIFICATION }.ifEmpty { listOf(1f) }
            val options = FramingOptions(
                scales = scales.toFloatArray(),
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
 * The automatic look. The colour look, exposure and fill light follow the recognised scene all the
 * time; the crop applies from the moment the aiming guide finishes until the framing has been lost
 * for a moment (so small wobbles do not switch it on and off).
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
        scene: SceneKind = SceneKind.UNKNOWN,
    ): LookPlan {
        val aim = comp.aim
        val s = comp.frameSubject
        val filter = Filter.forScene(scene)
        if (!input.enhance) {
            reset()
            return LookPlan.OFF.copy(filter = filter)
        }
        if (aim.phase == AimPhase.DONE) lastDone = nowMs
        val engaged = aim.phase != AimPhase.IDLE && lastDone != NEVER && nowMs - lastDone <= releaseMs
        if (!engaged) {
            crop.reset()
            if (aim.phase == AimPhase.IDLE) lastDone = NEVER
        }
        val a = input.aim
        val person = s?.kind == SubjectKind.PERSON
        val cropPlan = if (engaged) crop.update(aim, s, a.frameAspect, a.style, scene, a.zoom) else null
        if (lighting == null) return LookPlan(engaged, cropPlan, filter, 0f, false, ExposureNeed.OK)
        val flash = LookAdvisor.wantsFlash(lighting, pose, a.frontCamera, input.hasFlash)
        return LookPlan(
            engaged = engaged,
            crop = cropPlan,
            filter = filter,
            softFill = LookAdvisor.softFill(lighting, person, flash),
            flash = flash,
            exposure = LookAdvisor.exposureNeed(lighting, person, scene),
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

    /** Where a point of the stored image is in the upright picture, both normalized (0..1). */
    fun rawToUpright(x: Float, y: Float, o: Int): Pair<Float, Float> = when (o) {
        2 -> (1f - x) to y
        3 -> (1f - x) to (1f - y)
        4 -> x to (1f - y)
        5 -> y to x
        6 -> (1f - y) to x
        7 -> (1f - y) to (1f - x)
        8 -> y to (1f - x)
        else -> x to y
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
