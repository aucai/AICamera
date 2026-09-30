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
 * Picks the crop that best composes the subject: tries every combination of rule point and zoom level,
 * scores each crop on placement, not cutting heads/feet/joints, filling the frame and keeping resolution.
 */
object FramingPlanner {

    val SCALES = floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)

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

    fun candidates(s: FrameSubject, frameAspect: Float, outAspect: Float, rule: GridMode): List<FramingCandidate> {
        val out = ArrayList<FramingCandidate>()
        for (scale in SCALES) {
            val (cw, ch) = cropSize(frameAspect, outAspect, scale)
            for ((tx, ty) in targets(s, rule)) {
                val config = FramingConfig(tx, ty, scale)
                val (rect, overflow) = rectFor(s, config, cw, ch)
                out += FramingCandidate(config, rect, cost(s, rect, config), overflow)
            }
        }
        return out
    }

    /** The crop for a config, clamped into the frame, plus how far it had to be pushed back in. */
    fun rectFor(s: FrameSubject, c: FramingConfig, cw: Float, ch: Float): Pair<RectN, FloatArray> {
        val left = if (c.tx != null) s.anchor.x - c.tx * cw else 0.5f - cw / 2f
        val top = when {
            s.kind == SubjectKind.PERSON && s.shot == ShotType.FULL_BODY && s.feetY != null -> s.feetY - 0.95f * ch
            c.ty != null -> s.anchor.y - c.ty * ch
            else -> 0.5f - ch / 2f
        }
        val overflow = floatArrayOf(max(0f, -left), max(0f, -top), max(0f, left + cw - 1f), max(0f, top + ch - 1f))
        val l = left.coerceIn(0f, max(0f, 1f - cw))
        val t = top.coerceIn(0f, max(0f, 1f - ch))
        return RectN(l, t, l + cw, t + ch) to overflow
    }

    fun cost(s: FrameSubject, r: RectN, c: FramingConfig): Float {
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
        // Every crop costs resolution; only crop when it clearly helps.
        cost += 0.6f * (1f - c.scale)
        return cost
    }

    /** Why this crop, in plain words. */
    fun reason(s: FrameSubject, c: FramingConfig): String {
        val side = when (c.tx) {
            null -> ""
            0.5f -> "画面正中"
            else -> if (c.tx < 0.5f) "左侧三分线" else "右侧三分线"
        }
        val zoom = if (c.scale < 0.99f) "，放大 %.1f 倍".format(1f / c.scale) else ""
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
data class FramingPlan(
    val rect: RectN,
    val reason: String,
    val config: FramingConfig?,
    val overflow: FloatArray,
)

/**
 * Keeps the crop calm: the chosen rule point / zoom only changes when another option is clearly
 * better for several frames in a row, and the rectangle glides to its new place instead of jumping.
 */
class FramingTracker(
    private val switchMargin: Float = 0.25f,
    private val switchFrames: Int = 4,
    private val glide: Float = 0.3f,
) {
    private var config: FramingConfig? = null
    private var pending: FramingConfig? = null
    private var pendingCount = 0
    private var rect: RectN? = null

    fun reset() {
        config = null
        pending = null
        pendingCount = 0
        rect = null
    }

    fun update(s: FrameSubject?, frameAspect: Float, outAspect: Float?, rule: GridMode): FramingPlan {
        if (outAspect == null) {
            reset()
            return FramingPlan(RectN(0f, 0f, 1f, 1f), "", null, FloatArray(4))
        }
        if (s == null) {
            config = null
            val (cw, ch) = FramingPlanner.cropSize(frameAspect, outAspect, 1f)
            val target = RectN(0.5f - cw / 2f, 0.5f - ch / 2f, 0.5f + cw / 2f, 0.5f + ch / 2f)
            return FramingPlan(glideTo(target), "没找到主体，保持原画面", null, FloatArray(4))
        }
        val candidates = FramingPlanner.candidates(s, frameAspect, outAspect, rule)
        val best = candidates.minBy { it.cost }
        val current = candidates.firstOrNull { it.config == config }
        val chosen = when {
            current == null -> best
            best.cost + switchMargin < current.cost -> {
                if (pending == best.config) pendingCount++ else {
                    pending = best.config
                    pendingCount = 1
                }
                if (pendingCount >= switchFrames) best else current
            }
            else -> {
                pending = null
                pendingCount = 0
                current
            }
        }
        if (chosen.config != config) {
            pending = null
            pendingCount = 0
        }
        config = chosen.config
        return FramingPlan(glideTo(chosen.rect), FramingPlanner.reason(s, chosen.config), chosen.config, chosen.overflow)
    }

    private fun glideTo(target: RectN): RectN {
        val prev = rect
        val next = if (prev == null) target else RectN(
            prev.left + glide * (target.left - prev.left),
            prev.top + glide * (target.top - prev.top),
            prev.right + glide * (target.right - prev.right),
            prev.bottom + glide * (target.bottom - prev.bottom),
        )
        rect = next
        return next
    }
}

data class CompositionResult(
    val subject: Subject?,
    val frameSubject: FrameSubject?,
    val shot: ShotType?,
    val plan: FramingPlan,
    val tips: List<Tip>,
)

object CompositionRules {

    fun levelTips(level: LevelState): List<Tip> {
        if (level.flat) {
            val tilt = maxOf(abs(level.tiltX), abs(level.tiltY))
            return if (tilt > 3f) {
                listOf(Tip("level.flat", TipCategory.LEVEL, Severity.SUGGEST, "俯拍时把手机放平（偏了%.0f°）".format(tilt)))
            } else emptyList()
        }
        val roll = level.rollDeg
        if (abs(roll) <= 2.5f) return emptyList()
        val severity = if (abs(roll) > 6f) Severity.WARNING else Severity.SUGGEST
        // Rotated clockwise → the right side dropped → raise the right side.
        val side = if (roll > 0) "右" else "左"
        return listOf(Tip("level.roll", TipCategory.LEVEL, severity, "手机${side}侧抬高一点（歪了%.0f°）".format(abs(roll))))
    }

    fun anchorOf(pose: PoseFrame, shot: ShotType): Vec2 {
        val eyes = pose.eyes
        if (shot == ShotType.CLOSE_UP) return eyes
        val bodyX = if (pose.hasHips) (pose.shoulderMid.x + pose.hipMid.x) / 2f else pose.shoulderMid.x
        return Vec2(bodyX, eyes.y)
    }

    /**
     * Problems the crop cannot fix, so the phone has to move: the subject is too close to an edge
     * for the chosen framing, or a head, feet or joint would still be cut.
     */
    fun framingTips(s: FrameSubject, plan: FramingPlan, cropping: Boolean): List<Tip> {
        val tips = ArrayList<Tip>()
        val r = plan.rect
        if (s.kind == SubjectKind.PERSON) {
            val head = s.headTop
            if (head != null && s.shot != ShotType.CLOSE_UP && head < r.top + 0.005f) {
                tips += Tip("comp.headcut", TipCategory.COMPOSITION, Severity.WARNING, "头顶被切了，手机往上抬一点")
            }
            val feet = s.feetY
            if (s.shot == ShotType.FULL_BODY && feet != null && feet > r.bottom + 0.01f) {
                tips += Tip("comp.feetcut", TipCategory.COMPOSITION, Severity.WARNING, "脚被切了，手机往下放一点")
            }
            if (s.joints.any { abs(r.bottom - it) < 0.035f }) {
                tips += Tip("comp.jointcut", TipCategory.COMPOSITION, Severity.WARNING, "底边切在膝盖或脚踝上了，手机往下移拍全身，或往上移只拍到大腿")
            }
            if (s.shot == ShotType.FULL_BODY && s.headTop != null && s.feetY != null && (s.feetY - s.headTop) / r.height < 0.4f) {
                tips += Tip("comp.small", TipCategory.COMPOSITION, Severity.SUGGEST, "人太小了，拿着手机走近一点")
            }
        }
        if (cropping && plan.config != null) {
            val o = plan.overflow
            val worst = o.indices.maxBy { o[it] }
            if (o[worst] > 0.05f) {
                val text = when (worst) {
                    0 -> "手机向左移一点，左边留点空间"
                    1 -> if (s.kind == SubjectKind.PERSON) "手机往上抬一点，头顶留点空间" else "手机往上抬一点，上面留点空间"
                    2 -> "手机向右移一点，右边留点空间"
                    else -> "手机往下放一点，下面留点空间"
                }
                tips += Tip("comp.room", TipCategory.COMPOSITION, Severity.SUGGEST, text)
            }
        }
        return tips
    }
}

/**
 * Tracks the subject across frames and plans the crop:
 * - a missed detection keeps the last subject for a moment instead of dropping everything;
 * - the subject position is smoothed; the shot type only changes after several frames.
 */
class CompositionTracker(
    private val smoothing: Float = 0.35f,
    private val lostResetMs: Long = 1500,
) {
    private val framing = FramingTracker()
    private var subject: Subject? = null
    private var anchor: Vec2? = null
    private var shot: ShotType? = null
    private var pendingShot: ShotType? = null
    private var pendingCount = 0
    private var lastSeenMs = Long.MIN_VALUE / 2

    val currentSubject get() = subject

    fun reset() {
        framing.reset()
        subject = null
        anchor = null
        shot = null
        pendingShot = null
        pendingCount = 0
    }

    fun update(
        nowMs: Long,
        subjectNow: Subject?,
        pose: PoseFrame?,
        level: LevelState?,
        grid: GridMode,
        frameAspect: Float,
        outAspect: Float?,
    ): CompositionResult {
        val tips = ArrayList<Tip>()
        level?.let { tips += CompositionRules.levelTips(it) }

        val held = subject?.takeIf { nowMs - lastSeenMs <= lostResetMs }
        val subject = subjectNow ?: held
        if (subjectNow != null) lastSeenMs = nowMs
        if (subject == null || (subjectNow != null && held != null && !sameSubject(held, subjectNow))) {
            anchor = null
            shot = null
        }
        this.subject = subject
        if (subject == null) {
            val plan = framing.update(null, frameAspect, outAspect, grid)
            return CompositionResult(null, null, null, plan, tips)
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
        val plan = framing.update(fs, frameAspect, outAspect, grid)
        tips += CompositionRules.framingTips(fs, plan, cropping = outAspect != null)
        return CompositionResult(subject, fs, if (subject.kind == SubjectKind.PERSON) shot else null, plan, tips)
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

/** Output aspect for the auto-crop. TALL is 9:16 when the phone is upright and 16:9 when it is sideways. */
enum class CropChoice {
    SAME,
    SQUARE,
    TALL,
    OFF;

    fun aspect(frameAspect: Float): Float? = when (this) {
        SAME -> frameAspect
        SQUARE -> 1f
        TALL -> if (frameAspect < 1f) 9f / 16f else 16f / 9f
        OFF -> null
    }

    fun label(portrait: Boolean): String = when (this) {
        SAME -> if (portrait) "取景 3:4" else "取景 4:3"
        SQUARE -> "取景 1:1"
        TALL -> if (portrait) "取景 9:16" else "取景 16:9"
        OFF -> "不裁剪"
    }
}
