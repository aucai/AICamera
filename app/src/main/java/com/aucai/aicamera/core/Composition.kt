package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which composition rule the auto-framing follows. */
enum class GridMode(val label: String) {
    THIRDS("三分法"),
    GOLDEN("黄金分割"),
    CENTER("中心构图"),
    OFF("无网格"),
}

/**
 * Everything the framing planner needs to know about the subject, in display-normalized coordinates.
 * @property anchor the point to place: a person's eyes, an object's centre, a point on the horizon.
 * @property extent what should stay inside the photo (a person from head top to the lowest visible
 *   body part; an object's box).
 * @property joints y of knees and ankles, where the bottom edge should not cut.
 * @property facing -1 looking towards the left of the screen, 1 towards the right, 0 straight on.
 */
data class FrameSubject(
    val kind: SubjectKind,
    val label: String,
    val anchor: Vec2,
    val extent: RectN?,
    val shot: ShotType? = null,
    val headTop: Float? = null,
    val feetY: Float? = null,
    val joints: List<Float> = emptyList(),
    val facing: Int = 0,
    val group: ObjectGroup? = null,
    val shoulderY: Float? = null,
) {
    companion object {
        fun from(subject: Subject, pose: PoseFrame?, shot: ShotType?, anchor: Vec2): FrameSubject {
            if (subject.kind != SubjectKind.PERSON || pose == null || !pose.hasShoulders) {
                return FrameSubject(subject.kind, subject.label, anchor, subject.box, group = subject.group)
            }
            val body = listOf(
                PoseIdx.LEFT_EAR, PoseIdx.RIGHT_EAR, PoseIdx.LEFT_SHOULDER, PoseIdx.RIGHT_SHOULDER,
                PoseIdx.LEFT_ELBOW, PoseIdx.RIGHT_ELBOW, PoseIdx.LEFT_WRIST, PoseIdx.RIGHT_WRIST,
                PoseIdx.LEFT_HIP, PoseIdx.RIGHT_HIP, PoseIdx.LEFT_KNEE, PoseIdx.RIGHT_KNEE,
                PoseIdx.LEFT_ANKLE, PoseIdx.RIGHT_ANKLE,
            ).filter { pose.visible(it) }.map { pose.pt(it) }
            val headTop = pose.headTop ?: anchor.y
            val bottom = body.maxOfOrNull { it.y } ?: 1f
            val extent = RectN(
                (body.minOfOrNull { it.x } ?: anchor.x).coerceIn(0f, 1f),
                headTop,
                (body.maxOfOrNull { it.x } ?: anchor.x).coerceIn(0f, 1f),
                bottom.coerceAtMost(1f),
            )
            val joints = listOf(PoseIdx.LEFT_KNEE, PoseIdx.RIGHT_KNEE, PoseIdx.LEFT_ANKLE, PoseIdx.RIGHT_ANKLE)
                .filter { pose.visible(it, 0.4f) }
                .map { pose.landmarks[it].y }
            val feet = if (shot == ShotType.FULL_BODY) {
                max(pose.landmarks[PoseIdx.LEFT_ANKLE].y, pose.landmarks[PoseIdx.RIGHT_ANKLE].y) + 0.02f
            } else null
            return FrameSubject(
                SubjectKind.PERSON, subject.label, anchor, extent, shot, headTop, feet, joints, facing(pose),
                shoulderY = pose.shoulderMid.y,
            )
        }

        /** Which way the face points, from the nose position relative to the ears. */
        fun facing(pose: PoseFrame): Int {
            val nose = pose.pt(PoseIdx.NOSE)
            val le = pose.visible(PoseIdx.LEFT_EAR)
            val re = pose.visible(PoseIdx.RIGHT_EAR)
            val offset = when {
                le && re -> {
                    val l = pose.pt(PoseIdx.LEFT_EAR)
                    val r = pose.pt(PoseIdx.RIGHT_EAR)
                    val width = abs(l.x - r.x).coerceAtLeast(1e-3f)
                    (nose.x - (l.x + r.x) / 2f) / width
                }
                // Profile: only one ear shows, and the face points away from it.
                le -> if (nose.x > pose.pt(PoseIdx.LEFT_EAR).x) 1f else -1f
                re -> if (nose.x > pose.pt(PoseIdx.RIGHT_EAR).x) 1f else -1f
                else -> 0f
            }
            return when {
                offset > 0.2f -> 1
                offset < -0.2f -> -1
                else -> 0
            }
        }
    }
}

/** Where the subject goes inside the crop (null = that axis is not constrained) and how much to zoom. */
data class FramingConfig(val tx: Float?, val ty: Float?, val scale: Float)

data class FramingCandidate(
    val config: FramingConfig,
    val rect: RectN,
    val cost: Float,
    /** How far the ideal crop wanted to reach past each frame edge (left, top, right, bottom). */
    val overflow: FloatArray,
)

/**
 * How much the planner may change the view.
 * @property scales view sizes to try, as a fraction of the current view (0.5 = zoom in 2x).
 * @property clamp keep the view inside the current frame (cropping) or let it move (the phone will pan).
 * @property zoomCost how much each step of zoom is discouraged.
 * @property closeUp for people: prefer framing head to waist.
 */
data class FramingOptions(
    val scales: FloatArray = floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.6f),
    val clamp: Boolean = true,
    val zoomCost: Float = 0.6f,
    val closeUp: Boolean = false,
)

/**
 * Picks the view that best composes the subject: tries every combination of rule point and zoom level,
 * scores each on placement, not cutting heads/feet/joints, filling the frame and keeping resolution.
 */
object FramingPlanner {

    val SCALES = FramingOptions().scales

    /** Crop width/height (normalized to the frame) for an output aspect ratio at a zoom scale. */
    fun cropSize(frameAspect: Float, outAspect: Float, scale: Float): Pair<Float, Float> {
        val (w, h) = if (outAspect <= frameAspect) (outAspect / frameAspect) to 1f else 1f to (frameAspect / outAspect)
        return w * scale to h * scale
    }

    fun xs(rule: GridMode) = when (rule) {
        GridMode.THIRDS, GridMode.OFF -> listOf(1f / 3f, 2f / 3f)
        GridMode.GOLDEN -> listOf(0.382f, 0.618f)
        GridMode.CENTER -> listOf(0.5f)
    }

    fun ys(rule: GridMode) = when (rule) {
        GridMode.THIRDS, GridMode.OFF -> listOf(1f / 3f, 2f / 3f)
        GridMode.GOLDEN -> listOf(0.382f, 0.618f)
        GridMode.CENTER -> listOf(0.5f)
    }

    fun targets(s: FrameSubject, rule: GridMode): List<Pair<Float?, Float?>> = when (s.kind) {
        SubjectKind.PERSON -> {
            val eyeLine = if (rule == GridMode.GOLDEN) 0.382f else 1f / 3f
            val xs = if (rule == GridMode.CENTER) xs(rule) else xs(rule) + 0.5f
            // Full-body shots are placed vertically by the feet, not the eyes.
            xs.map { it to if (s.shot == ShotType.FULL_BODY) null else eyeLine }
        }
        SubjectKind.OBJECT -> {
            val points = xs(rule).flatMap { x -> ys(rule).map { y -> x to y } }
            if (rule != GridMode.CENTER) points + (0.5f to 0.5f) else points
        }
        SubjectKind.HORIZON -> ys(rule).map { null to it }
    }

    fun candidates(
        s: FrameSubject,
        frameAspect: Float,
        outAspect: Float,
        rule: GridMode,
        options: FramingOptions = FramingOptions(),
    ): List<FramingCandidate> {
        val out = ArrayList<FramingCandidate>()
        for (scale in options.scales) {
            val (cw, ch) = cropSize(frameAspect, outAspect, scale)
            for ((tx, ty) in targets(s, rule)) {
                val config = FramingConfig(tx, ty, scale)
                val (rect, overflow) = rectFor(s, config, cw, ch, options.clamp)
                out += FramingCandidate(config, rect, cost(s, rect, config, options), overflow)
            }
        }
        return out
    }

    /** The crop for a config, clamped into the frame, plus how far it had to be pushed back in. */
    fun rectFor(s: FrameSubject, c: FramingConfig, cw: Float, ch: Float, clamp: Boolean = true): Pair<RectN, FloatArray> {
        val left = if (c.tx != null) s.anchor.x - c.tx * cw else 0.5f - cw / 2f
        val top = when {
            s.kind == SubjectKind.PERSON && s.shot == ShotType.FULL_BODY && s.feetY != null -> s.feetY - 0.95f * ch
            c.ty != null -> s.anchor.y - c.ty * ch
            else -> 0.5f - ch / 2f
        }
        val overflow = floatArrayOf(max(0f, -left), max(0f, -top), max(0f, left + cw - 1f), max(0f, top + ch - 1f))
        if (!clamp) return RectN(left, top, left + cw, top + ch) to overflow
        val l = left.coerceIn(0f, max(0f, 1f - cw))
        val t = top.coerceIn(0f, max(0f, 1f - ch))
        return RectN(l, t, l + cw, t + ch) to overflow
    }

    fun cost(s: FrameSubject, r: RectN, c: FramingConfig, options: FramingOptions = FramingOptions()): Float {
        var cost = 0f
        val ax = (s.anchor.x - r.left) / r.width
        val ay = (s.anchor.y - r.top) / r.height
        c.tx?.let { cost += 3f * abs(ax - it) }
        c.ty?.let { cost += 3f * abs(ay - it) }

        when (s.kind) {
            SubjectKind.PERSON -> {
                s.headTop?.let { h ->
                    val hc = (h - r.top) / r.height
                    if (hc < 0.02f) cost += 4f + 20f * (0.02f - hc)
                    else if (s.shot != ShotType.FULL_BODY && hc > 0.2f) cost += 3f * (hc - 0.2f)
                }
                s.extent?.let { e ->
                    val cut = max(0f, r.left - e.left) + max(0f, e.right - r.right)
                    cost += 6f * cut / r.width
                }
                for (j in s.joints) if (abs(r.bottom - j) < 0.035f) cost += 2.5f
                if (s.shot == ShotType.FULL_BODY && s.feetY != null && s.headTop != null) {
                    val fc = (s.feetY - r.top) / r.height
                    if (fc > 0.99f) cost += 4f + 20f * (fc - 0.99f)
                    val fill = (s.feetY - s.headTop) / r.height
                    if (fill < 0.75f) cost += 2f * (0.75f - fill)
                }
                if (c.tx != null && c.tx != 0.5f && s.facing != 0) {
                    // Leave space on the side the person looks towards.
                    val roomAhead = if (s.facing > 0) c.tx < 0.5f else c.tx > 0.5f
                    cost += if (roomAhead) -0.3f else 0.4f
                }
                if (c.tx == 0.5f) cost += if (s.facing == 0) 0.05f else 0.3f
                if (options.closeUp && s.headTop != null && s.shoulderY != null && s.shot != ShotType.FULL_BODY) {
                    // Head to waist is about three head-to-shoulder lengths.
                    val want = (s.shoulderY - s.headTop) * 3.2f
                    if (want > 0.02f) cost += 2f * abs(r.height / want - 1f)
                }
            }
            SubjectKind.OBJECT -> {
                s.extent?.let { b ->
                    val area = (b.width * b.height).coerceAtLeast(1e-4f)
                    val iw = max(0f, min(b.right, r.right) - max(b.left, r.left))
                    val ih = max(0f, min(b.bottom, r.bottom) - max(b.top, r.top))
                    cost += 6f * (1f - iw * ih / area)
                    val fill = area / (r.width * r.height)
                    if (fill < 0.12f) cost += 2f * (0.12f - fill) / 0.12f
                    if (fill > 0.6f) cost += 2f * (fill - 0.6f)
                }
                val centerFriendly = s.group == ObjectGroup.FOOD || s.group == ObjectGroup.PLANT
                if (c.tx == 0.5f && c.ty == 0.5f && !centerFriendly) cost += 0.3f
            }
            SubjectKind.HORIZON -> Unit
        }
        // Every zoom step costs resolution and context; only zoom when it clearly helps.
        cost += options.zoomCost * (1f - c.scale)
        if (!options.clamp) {
            // Do not ask the user to swing the phone far away from what they are pointing at.
            val dx = r.center.x - 0.5f
            val dy = r.center.y - 0.5f
            cost += 3f * max(0f, kotlin.math.sqrt(dx * dx + dy * dy) - 0.3f)
        }
        return cost
    }

    /** Why this crop, in plain words. */
    fun reason(s: FrameSubject, c: FramingConfig): String {
        val side = when (c.tx) {
            null -> ""
            0.5f -> "画面正中"
            else -> if (c.tx < 0.5f) "左侧三分线" else "右侧三分线"
        }
        val zoom = if (c.scale < 0.99f) "，拉近到 %.1f 倍".format(1f / c.scale) else ""
        val text = when (s.kind) {
            SubjectKind.PERSON -> when {
                s.shot == ShotType.FULL_BODY -> "全身：人物在$side，脚底贴近底边"
                c.tx != null && c.tx != 0.5f && s.facing != 0 && (s.facing > 0) == (c.tx < 0.5f) ->
                    "人物朝${if (s.facing > 0) "右" else "左"}看，放在$side，前方留出视线空间"
                else -> "眼睛放在上三分线，人物在$side"
            }
            SubjectKind.OBJECT ->
                if (c.tx == 0.5f && c.ty == 0.5f) "${s.label}居中，画面稳定" else "${s.label}放在三分点上"
            SubjectKind.HORIZON ->
                if ((c.ty ?: 0.5f) < 0.5f) "地平线在上三分线，突出地面" else "地平线在下三分线，突出天空"
        }
        return text + zoom
    }
}

/**
 * The crop shown in the viewfinder and applied to the photo.
 * @property rect smoothed crop, what is drawn now.
 * @property reason why the camera framed it this way.
 */
data class CompositionResult(
    val subject: Subject?,
    val frameSubject: FrameSubject?,
    val shot: ShotType?,
    val aim: AimState,
)

object CompositionRules {

    fun anchorOf(pose: PoseFrame, shot: ShotType): Vec2 {
        val eyes = pose.eyes
        if (shot == ShotType.CLOSE_UP) return eyes
        val bodyX = if (pose.hasHips) (pose.shoulderMid.x + pose.hipMid.x) / 2f else pose.shoulderMid.x
        return Vec2(bodyX, eyes.y)
    }

    /** Parts of a person cut off by the frame edges, for the photo review. */
    fun completenessIssues(s: FrameSubject): List<String> {
        if (s.kind != SubjectKind.PERSON) {
            val b = s.extent ?: return emptyList()
            val touches = b.left < 0.01f || b.top < 0.01f || b.right > 0.99f || b.bottom > 0.99f
            return if (touches && b.width * b.height < 0.6f) listOf("主体被切") else emptyList()
        }
        val out = ArrayList<String>()
        val head = s.headTop
        if (head != null && s.shot != ShotType.CLOSE_UP && head < 0.005f) out += "头顶被切"
        val feet = s.feetY
        if (s.shot == ShotType.FULL_BODY && feet != null && feet > 1.01f) out += "脚被切"
        if (s.joints.any { abs(1f - it) < 0.035f }) out += "切到关节"
        return out
    }
}

/**
 * Tracks the subject across frames and runs the aiming assistant on it:
 * - a missed detection keeps the last subject for a moment instead of dropping everything;
 * - the subject position is smoothed; the shot type only changes after several frames;
 * - a new subject starts a new recommendation.
 */
class CompositionTracker(
    private val smoothing: Float = 0.35f,
    private val lostResetMs: Long = 1500,
) {
    val aim = AimAssist()
    private var subject: Subject? = null
    private var anchor: Vec2? = null
    private var shot: ShotType? = null
    private var pendingShot: ShotType? = null
    private var pendingCount = 0
    private var lastSeenMs = Long.MIN_VALUE / 2

    val currentSubject get() = subject

    fun reset() {
        aim.reset()
        subject = null
        anchor = null
        shot = null
        pendingShot = null
        pendingCount = 0
    }

    fun update(nowMs: Long, subjectNow: Subject?, pose: PoseFrame?, input: AimInput): CompositionResult {
        val held = subject?.takeIf { nowMs - lastSeenMs <= lostResetMs }
        val subject = subjectNow ?: held
        if (subjectNow != null) lastSeenMs = nowMs
        if (subject == null || (subjectNow != null && held != null && !sameSubject(held, subjectNow))) {
            anchor = null
            shot = null
            aim.reset()
        }
        this.subject = subject
        if (subject == null) {
            return CompositionResult(null, null, null, aim.update(nowMs, null, input))
        }

        val livePose = pose?.takeIf { it.hasShoulders && subject.kind == SubjectKind.PERSON }
        if (livePose != null) stableShot(livePose.shotType)
        val raw = if (livePose != null) CompositionRules.anchorOf(livePose, shot!!) else subject.anchor
        val prev = anchor
        val a = if (prev == null || abs(raw.x - prev.x) > 0.25f || abs(raw.y - prev.y) > 0.25f) {
            raw
        } else {
            Vec2(prev.x + smoothing * (raw.x - prev.x), prev.y + smoothing * (raw.y - prev.y))
        }
        anchor = a

        val fs = FrameSubject.from(subject, livePose, shot, a)
        val state = aim.update(nowMs, fs, input)
        return CompositionResult(subject, fs, if (subject.kind == SubjectKind.PERSON) shot else null, state)
    }

    private fun sameSubject(a: Subject, b: Subject): Boolean {
        if (a.kind != b.kind) return false
        if (a.kind != SubjectKind.OBJECT) return true
        val ab = a.box ?: return a.label == b.label
        val bb = b.box ?: return a.label == b.label
        return iou(ab, bb) > 0.3f
    }

    private fun stableShot(raw: ShotType) {
        val cur = shot
        if (cur == null || raw == cur) {
            shot = raw
            pendingCount = 0
            return
        }
        if (raw == pendingShot) pendingCount++ else {
            pendingShot = raw
            pendingCount = 1
        }
        if (pendingCount >= SHOT_CHANGE_FRAMES) {
            shot = raw
            pendingCount = 0
        }
    }

    companion object {
        private const val SHOT_CHANGE_FRAMES = 6
    }
}

fun iou(a: RectN, b: RectN): Float {
    val iw = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val ih = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val inter = iw * ih
    val union = a.width * a.height + b.width * b.height - inter
    return if (union <= 0f) 0f else inter / union
}

