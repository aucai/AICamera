package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.sqrt

/** For people: frame the person (zoom in) or the person in their surroundings (no zoom). */
enum class PortraitStyle(val label: String) {
    CLOSE("人物特写"),
    SCENE("人景合一"),
}

/** Per-frame facts the aiming assistant needs besides the subject. */
data class AimInput(
    /** Width / height of the analysed frame. */
    val frameAspect: Float,
    val level: LevelState?,
    /** Current camera zoom ratio. */
    val zoom: Float,
    /** Highest zoom ratio the assistant may ask for. */
    val maxZoom: Float,
    /** The phone is being held still. */
    val steady: Boolean,
    val style: PortraitStyle,
    val frontCamera: Boolean,
    val enabled: Boolean = true,
    /** Phone orientation (display-aligned rotation matrix, see [SceneAnchor]); null without the sensor. */
    val rotation: FloatArray? = null,
    /** Field of view at zoom 1; null when unknown. */
    val view: ViewGeometry? = null,
)

enum class AimPhase {
    /** Nothing to suggest (no subject yet, or still settling). */
    IDLE,
    /** Tilt the phone up/down first. */
    ANGLE,
    /** Move the phone until the target dot sits in the centre ring. */
    GUIDE,
    /** Aligned; waiting for the phone to be still. */
    HOLD,
    /** The camera zooms to the recommended framing. */
    ZOOM,
    /** Framed; ready to shoot. */
    DONE,
}

/** @property offsetDeg current minus recommended camera pitch: positive means looking too far down. */
data class AngleGuide(val offsetDeg: Float, val text: String)

/**
 * @property target where the recommended view centre is now, in display-normalized coordinates.
 *   It is fixed to the scene, so it moves as the phone moves; aim the centre ring at it.
 * @property zoomTo zoom ratio the camera should move to (ZOOM phase only).
 * @property reason why this framing was recommended.
 * @property view size (normalized, at the current zoom) of the recommended framing around [target];
 *   set when the framing came from the cloud model so it can be drawn.
 * @property external the framing came from the cloud model rather than the built-in rules.
 * @property world the target as a direction in space, so the UI can redraw it at sensor rate.
 */
data class AimState(
    val phase: AimPhase,
    val target: Vec2? = null,
    val zoomTo: Float? = null,
    val angle: AngleGuide? = null,
    val hint: String = "",
    val reason: String = "",
    val view: Vec2? = null,
    val external: Boolean = false,
    val world: FloatArray? = null,
)

/**
 * A framing chosen by the cloud model.
 * @property world the recommended view centre as a direction in space (see [SceneAnchor]); this is
 *   what keeps it fixed to the scene.
 * @property offset recommended view centre minus the subject anchor at [zoomBase], when there was a
 *   subject; used as a fallback without the orientation sensor and to correct drift.
 * @property size recommended view width/height (normalized) at [zoomBase].
 */
data class ExternalFraming(
    val size: Vec2,
    val zoomBase: Float,
    val reason: String,
    val world: FloatArray? = null,
    val offset: Vec2? = null,
)

/** Recommended camera angle per kind of subject (back camera). */
object AngleAdvisor {

    /** Recommended pitch (positive = looking down) and how far off is still fine; null = no advice. */
    fun recommend(s: FrameSubject): Pair<Float, Float>? = when (s.kind) {
        // Slightly from below makes legs look longer; half-body shots look best at eye level.
        SubjectKind.PERSON -> if (s.shot == ShotType.FULL_BODY) -6f to 5f else 0f to 10f
        SubjectKind.OBJECT -> when (s.group) {
            ObjectGroup.FOOD -> 45f to 15f
            ObjectGroup.PET -> 10f to 15f
            else -> null
        }
        SubjectKind.HORIZON -> 0f to 6f
    }

    fun text(s: FrameSubject, offsetDeg: Float): String {
        val tiltUp = offsetDeg > 0
        return when {
            s.kind == SubjectKind.PERSON && s.shot == ShotType.FULL_BODY ->
                if (tiltUp) "镜头微微往上仰，拍全身更显高" else "镜头往下压一点，别仰得太多"
            s.group == ObjectGroup.FOOD -> if (tiltUp) "镜头往上抬一点" else "手机往前倾，斜 45° 拍美食更有食欲"
            s.group == ObjectGroup.PET -> if (tiltUp) "蹲低一点，平视${s.label}拍更生动" else "镜头往下压一点"
            else -> if (tiltUp) "镜头往上抬一点，平着拍" else "镜头往下压一点，平着拍"
        }
    }
}

/**
 * Aim-to-compose, the way phone makers do it: the best framing is worked out once and then fixed to
 * the scene. The user moves the phone until the target dot sits in the fixed centre ring; once they
 * hold still, the camera zooms in to finish the framing.
 */
class AimAssist(
    private val settleMs: Long = 400,
    private val holdMs: Long = 400,
) {
    private class Recommendation(
        /** View centre minus subject anchor at [zoomBase]; null when not tied to a subject. */
        val offset: Vec2?,
        val zoomMul: Float,
        val zoomBase: Float,
        val reason: String,
        val size: Vec2? = null,
        /** View centre as a direction in space; slowly corrected from the picture. */
        var world: FloatArray? = null,
        var offscreenSince: Long = NEVER,
    )

    private var rec: Recommendation? = null
    private var phase = AimPhase.IDLE
    private var subjectSince = NEVER
    private var holdSince = 0L
    private var angleOkSince = NEVER
    private var angleDone = false
    private var angleWasOff = false
    private var style: PortraitStyle? = null

    val hasExternal get() = rec?.size != null

    /** Aim at the cloud model's framing instead of the built-in one, until the subject changes. */
    fun setExternal(f: ExternalFraming?) {
        if (f == null) {
            if (hasExternal) {
                rec = null
                phase = AimPhase.IDLE
            }
            return
        }
        val zoomMul = (1f / maxOf(f.size.x, f.size.y)).coerceAtLeast(1f)
        rec = Recommendation(f.offset, zoomMul, f.zoomBase, f.reason, f.size, f.world)
        phase = AimPhase.GUIDE
        // The model already judged the angle; do not interrupt with angle advice.
        angleDone = true
    }

    /**
     * The tracked subject went away or changed. A recommendation pinned to a direction in space
     * survives this (detections drop out while the phone moves); one tied to the subject does not.
     */
    fun subjectChanged() {
        subjectSince = NEVER
        if (rec?.world == null) {
            rec = null
            phase = AimPhase.IDLE
        }
    }

    fun reset() {
        rec = null
        phase = AimPhase.IDLE
        subjectSince = NEVER
        angleOkSince = NEVER
        angleDone = false
        angleWasOff = false
    }

    fun update(nowMs: Long, s: FrameSubject?, input: AimInput): AimState {
        if (!input.enabled) {
            reset()
            return AimState(AimPhase.IDLE)
        }
        if (style != input.style) {
            style = input.style
            rec = null
            phase = AimPhase.IDLE
        }
        val rotation = input.rotation
        val geometry = input.view?.zoomed(input.zoom)
        val pinned = rec?.world != null && rotation != null && geometry != null
        if (s == null && !pinned) {
            subjectChanged()
            phase = AimPhase.IDLE
            return AimState(AimPhase.IDLE)
        }
        if (s != null && subjectSince == NEVER) subjectSince = nowMs

        // 1. Angle first: tilting changes the vertical framing, so settle it before recommending.
        if (rec == null && s != null) angleState(nowMs, s, input)?.let { return it }

        // 2. Recommend once the subject has been there a moment and the phone is still; then keep it.
        var r = rec
        if (r == null) {
            if (s == null || nowMs - subjectSince < settleMs || !input.steady) return AimState(AimPhase.IDLE)
            r = recommend(s, input)
            rec = r
            phase = AimPhase.GUIDE
        }

        // 3. Where the target is now. The offset was measured at the zoom of the time; zooming magnifies it.
        val k = input.zoom / r.zoomBase
        val fromSubject = if (s != null && r.offset != null) {
            Vec2(s.anchor.x + r.offset.x * k, s.anchor.y + r.offset.y * k)
        } else null
        val world = r.world
        val target: Vec2? = if (world != null && rotation != null && geometry != null) {
            val fromSensor = SceneAnchor.toScreen(world, rotation, geometry)
            // Turning the phone is tracked exactly by the sensor; moving it sideways is not, so let the
            // picture pull the target back gently while both agree roughly.
            if (fromSensor != null && fromSubject != null && input.steady && distance(fromSensor, fromSubject, input.frameAspect) < 0.15f) {
                r.world = SceneAnchor.blend(world, SceneAnchor.toWorld(fromSubject, rotation, geometry), 0.08f)
            }
            fromSensor
        } else fromSubject

        val onScreen = target != null && target.x in 0f..1f && target.y in 0f..1f
        if (onScreen) r.offscreenSince = NEVER else if (r.offscreenSince == NEVER) r.offscreenSince = nowMs
        // Gave up on it: pointed elsewhere for a good while.
        if (!onScreen && nowMs - r.offscreenSince > GIVE_UP_MS) {
            reset()
            return AimState(AimPhase.IDLE)
        }

        val error = if (target == null) Float.MAX_VALUE else distance(target, Vec2(0.5f, 0.5f), input.frameAspect)
        val zoomGoal = (r.zoomBase * r.zoomMul).coerceAtMost(input.maxZoom)

        phase = when (phase) {
            AimPhase.HOLD -> when {
                error > EXIT -> AimPhase.GUIDE
                nowMs - holdSince >= holdMs && input.steady ->
                    if (zoomGoal > input.zoom * 1.05f) AimPhase.ZOOM else AimPhase.DONE
                else -> AimPhase.HOLD
            }
            AimPhase.ZOOM -> when {
                error > LOST -> AimPhase.GUIDE
                abs(input.zoom - zoomGoal) <= zoomGoal * 0.04f -> AimPhase.DONE
                else -> AimPhase.ZOOM
            }
            AimPhase.DONE -> if (error > LOST) AimPhase.GUIDE else AimPhase.DONE
            else -> if (error < ENTER) {
                holdSince = nowMs
                AimPhase.HOLD
            } else AimPhase.GUIDE
        }

        val hint = when (phase) {
            AimPhase.GUIDE -> when {
                target == null -> "目标在身后，转回来找圆点"
                else -> offscreenDirection(target)?.let { "向${it}转动手机，把圆点对进中间的圈" }
                    ?: "慢慢转动手机，把圆点对进中间的圈"
            }
            AimPhase.HOLD -> "对准了，保持不动"
            AimPhase.ZOOM -> "正在拉近…"
            AimPhase.DONE -> "构图完成，可以拍了"
            else -> ""
        }
        val view = r.size?.let { Vec2(it.x * k, it.y * k) }
        return AimState(
            phase, target, if (phase == AimPhase.ZOOM) zoomGoal else null, null, hint, r.reason, view, r.size != null,
            r.world?.copyOf(),
        )
    }

    private fun angleState(nowMs: Long, s: FrameSubject, input: AimInput): AimState? {
        val level = input.level ?: return null
        if (input.frontCamera) return null
        // Flat over food is a top-down shot, which is also good.
        if (level.flat && s.group == ObjectGroup.FOOD) return null
        if (level.flat) return null
        val (target, tolerance) = AngleAdvisor.recommend(s) ?: return null
        val offset = level.cameraPitchDeg - target
        // Once the angle was right, allow more slack before asking again.
        val limit = if (angleDone) tolerance * 2f else tolerance
        if (abs(offset) > limit) {
            angleDone = false
            angleWasOff = true
            angleOkSince = NEVER
            phase = AimPhase.ANGLE
            val text = AngleAdvisor.text(s, offset)
            return AimState(AimPhase.ANGLE, angle = AngleGuide(offset, text), hint = text)
        }
        if (!angleDone) {
            // Only confirm the angle when the user actually had to adjust it.
            if (!angleWasOff) {
                angleDone = true
                return null
            }
            if (angleOkSince == NEVER) angleOkSince = nowMs
            if (nowMs - angleOkSince < 300) {
                return AimState(AimPhase.ANGLE, angle = AngleGuide(offset, "角度正好，保持"), hint = "角度正好，保持")
            }
            angleDone = true
            // Start aiming afresh from the new angle.
            subjectSince = nowMs - settleMs
        }
        return null
    }

    private fun recommend(s: FrameSubject, input: AimInput): Recommendation {
        val base = when {
            s.kind == SubjectKind.PERSON && input.style == PortraitStyle.CLOSE -> FramingOptions(
                scales = floatArrayOf(1f, 0.85f, 0.7f, 0.6f, 0.5f, 0.4f), clamp = false, zoomCost = 0.2f, closeUp = true,
            )
            s.kind == SubjectKind.PERSON || s.kind == SubjectKind.HORIZON ->
                FramingOptions(scales = floatArrayOf(1f), clamp = false)
            else -> FramingOptions(scales = floatArrayOf(1f, 0.85f, 0.7f, 0.6f, 0.5f), clamp = false, zoomCost = 0.4f)
        }
        // Never plan a zoom the camera cannot do.
        val maxMul = input.maxZoom / input.zoom
        val scales = base.scales.filter { 1f / it <= maxMul + 1e-3f }.ifEmpty { listOf(1f) }
        val options = base.copy(scales = scales.toFloatArray())
        val best = FramingPlanner.candidates(s, input.frameAspect, input.frameAspect, GridMode.THIRDS, options)
            .minBy { it.cost }
        val center = best.rect.center
        val rotation = input.rotation
        val geometry = input.view?.zoomed(input.zoom)
        return Recommendation(
            offset = Vec2(center.x - s.anchor.x, center.y - s.anchor.y),
            zoomMul = 1f / best.config.scale,
            zoomBase = input.zoom,
            reason = FramingPlanner.reason(s, best.config),
            world = if (rotation != null && geometry != null) SceneAnchor.toWorld(center, rotation, geometry) else null,
        )
    }

    private fun offscreenDirection(t: Vec2): String? = when {
        t.x < 0f -> "左"
        t.x > 1f -> "右"
        t.y < 0f -> "上"
        t.y > 1f -> "下"
        else -> null
    }

    companion object {
        /** Distances in frame heights: enter alignment, drop it, and consider the framing lost. */
        const val ENTER = 0.05f
        const val EXIT = 0.08f
        const val LOST = 0.14f
        /** Drop a recommendation whose target has been off screen this long. */
        const val GIVE_UP_MS = 8000L
        private const val NEVER = Long.MIN_VALUE
    }
}
