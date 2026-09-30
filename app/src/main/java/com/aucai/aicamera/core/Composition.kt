package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.max

enum class GridMode(val label: String) {
    THIRDS("九宫格"),
    GOLDEN("黄金分割"),
    CENTER("中心"),
    OFF("无网格"),
}

/**
 * @property anchor where the subject is now (smoothed): a person's eyes (body centre line for full-body
 *   shots), an object's centre, or a point on the horizon.
 * @property targetX where the anchor should go horizontally; null when that axis has no target.
 * @property targetY where the anchor should go vertically; null when that axis has no target
 *   (full-body shots only get a vertical line, horizons only a horizontal one).
 * @property placementError normalised distance from anchor to target (measured against thirds when the grid is off).
 */
data class CompositionResult(
    val subject: Subject?,
    val anchor: Vec2?,
    val targetX: Float?,
    val targetY: Float?,
    val aligned: Boolean,
    val placementError: Float?,
    val shot: ShotType?,
    val tips: List<Tip>,
)

/** Stateless composition rules. All advice is phrased as how to move the phone. */
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

    /** Candidate vertical lines. With the grid off, thirds are still used to score the shot. */
    fun targetXs(grid: GridMode): List<Float> = when (grid) {
        GridMode.THIRDS, GridMode.OFF -> listOf(1f / 3f, 2f / 3f)
        GridMode.GOLDEN -> listOf(0.382f, 0.618f)
        GridMode.CENTER -> listOf(0.5f)
    }

    /** Candidate horizontal lines for objects and horizons. */
    fun targetYs(grid: GridMode): List<Float> = when (grid) {
        GridMode.THIRDS, GridMode.OFF -> listOf(1f / 3f, 2f / 3f)
        GridMode.GOLDEN -> listOf(0.382f, 0.618f)
        GridMode.CENTER -> listOf(0.5f)
    }

    /** Where a person's eyes should sit. Full-body shots have no vertical target. */
    fun personTargetY(grid: GridMode, shot: ShotType): Float? = when {
        shot == ShotType.FULL_BODY -> null
        grid == GridMode.GOLDEN -> 0.382f
        else -> 1f / 3f
    }

    /** Candidate target points; a null coordinate means that axis is free. */
    fun candidates(subject: Subject, shot: ShotType?, grid: GridMode): List<Pair<Float?, Float?>> = when (subject.kind) {
        SubjectKind.PERSON -> {
            val y = personTargetY(grid, shot ?: ShotType.HALF_BODY)
            targetXs(grid).map { it to y }
        }
        SubjectKind.OBJECT -> {
            val points = targetXs(grid).flatMap { x -> targetYs(grid).map { y -> x to y } }
            // A plate of food or a single object also reads well dead centre.
            if (subject.group == ObjectGroup.FOOD && grid != GridMode.CENTER) points + (0.5f to 0.5f) else points
        }
        SubjectKind.HORIZON -> targetYs(grid).map { null to it }
    }

    fun anchorOf(subject: Subject, pose: PoseFrame?, shot: ShotType?): Vec2 =
        if (subject.kind == SubjectKind.PERSON && pose != null && shot != null) anchorOf(pose, shot) else subject.anchor

    fun anchorOf(pose: PoseFrame, shot: ShotType): Vec2 {
        val eyes = pose.eyes
        if (shot == ShotType.CLOSE_UP) return eyes
        val bodyX = if (pose.hasHips) (pose.shoulderMid.x + pose.hipMid.x) / 2f else pose.shoulderMid.x
        return Vec2(bodyX, eyes.y)
    }

    /**
     * dx > 0: the subject has to move right in the frame, i.e. move the phone left.
     * dy > 0: the subject has to move down in the frame, i.e. raise the phone.
     */
    fun placementTip(dx: Float, dy: Float, grid: GridMode, subject: Subject, xOnly: Boolean): Tip {
        val move = if (abs(dx) >= abs(dy)) {
            if (dx > 0) "手机向左移一点" else "手机向右移一点"
        } else {
            if (dy > 0) "手机往上抬一点" else "手机往下放一点"
        }
        val point = when {
            grid == GridMode.CENTER -> "画面正中"
            grid == GridMode.GOLDEN -> "黄金分割点"
            else -> "三分点"
        }
        val goal = when (subject.kind) {
            SubjectKind.PERSON -> when {
                grid == GridMode.CENTER -> "让人在画面正中"
                xOnly -> "让人落在竖线上"
                else -> "让眼睛落在$point"
            }
            SubjectKind.OBJECT -> "让${subject.label}落在$point"
            SubjectKind.HORIZON -> if (grid == GridMode.CENTER) "让地平线在画面中间" else "让地平线落在横线上"
        }
        return Tip("comp.place", TipCategory.COMPOSITION, Severity.SUGGEST, "$move，$goal")
    }

    fun framingTips(pose: PoseFrame, shot: ShotType): List<Tip> {
        val tips = ArrayList<Tip>()
        val headTop = pose.headTop
        if (headTop != null && shot != ShotType.CLOSE_UP) {
            if (headTop < 0.01f) {
                tips += Tip("comp.headcut", TipCategory.COMPOSITION, Severity.WARNING, "头顶被切了，手机往上抬一点")
            } else if (shot == ShotType.HALF_BODY && headTop > 0.28f) {
                tips += Tip("comp.headroom", TipCategory.COMPOSITION, Severity.SUGGEST, "头顶空太多，手机往下放一点")
            }
        }

        val kneeCut = listOf(PoseIdx.LEFT_KNEE, PoseIdx.RIGHT_KNEE).any {
            pose.visible(it, 0.4f) && pose.landmarks[it].y in 0.94f..1.06f
        }
        val ankleCut = listOf(PoseIdx.LEFT_ANKLE, PoseIdx.RIGHT_ANKLE).any {
            pose.visible(it, 0.4f) && pose.landmarks[it].y in 0.95f..1.05f
        }
        when {
            kneeCut -> tips += Tip("comp.jointcut", TipCategory.COMPOSITION, Severity.WARNING, "底边切在膝盖了，手机往下移拍全身，或往上移只拍到大腿")
            ankleCut -> tips += Tip("comp.jointcut", TipCategory.COMPOSITION, Severity.WARNING, "底边切到脚踝了，手机往下移一点把脚拍全")
        }

        if (shot == ShotType.FULL_BODY) {
            val feet = maxOf(pose.landmarks[PoseIdx.LEFT_ANKLE].y, pose.landmarks[PoseIdx.RIGHT_ANKLE].y)
            if (feet < 0.85f) {
                tips += Tip("comp.feet", TipCategory.COMPOSITION, Severity.SUGGEST, "镜头稍微往上仰，让脚底贴近画面底边，更显腿长")
            }
            val body = pose.bodyBounds()
            if (body != null && body.height < 0.4f) {
                tips += Tip("comp.small", TipCategory.COMPOSITION, Severity.SUGGEST, "人太小了，拿着手机走近一点")
            }
        }
        return tips
    }
}

/**
 * Keeps composition guidance steady from frame to frame:
 * - the subject position is smoothed so the marker does not jitter;
 * - once a target is picked it stays put until the subject is clearly closer to another one;
 * - the shot type (close-up / half / full body) only changes after it has been seen for several frames;
 * - "aligned" has hysteresis so it does not flicker at the edge.
 */
class CompositionTracker(
    private val switchMargin: Float = 0.12f,
    private val smoothing: Float = 0.35f,
    private val lostResetMs: Long = 1500,
) {
    private var anchor: Vec2? = null
    private var locked: Pair<Float?, Float?>? = null
    private var lockKey: String? = null
    private var subject: Subject? = null
    private var shot: ShotType? = null
    private var pendingShot: ShotType? = null
    private var pendingCount = 0
    private var aligned = false
    private var lastSeenMs = Long.MIN_VALUE / 2

    fun reset() {
        anchor = null
        locked = null
        lockKey = null
        subject = null
        shot = null
        pendingShot = null
        pendingCount = 0
        aligned = false
    }

    fun update(nowMs: Long, subjectNow: Subject?, pose: PoseFrame?, level: LevelState?, grid: GridMode): CompositionResult {
        val tips = ArrayList<Tip>()
        level?.let { tips += CompositionRules.levelTips(it) }

        // Hold on to the last subject briefly so a missed detection does not make everything jump.
        val subject = subjectNow ?: subject?.takeIf { nowMs - lastSeenMs <= lostResetMs }
        if (subject == null) {
            reset()
            return CompositionResult(null, null, null, null, false, null, null, tips)
        }
        if (subjectNow != null) lastSeenMs = nowMs
        val sameSubject = this.subject?.let { it.kind == subject.kind && it.label == subject.label } ?: false
        if (!sameSubject) {
            anchor = null
            locked = null
            aligned = false
        }
        this.subject = subject

        val shot = if (subject.kind == SubjectKind.PERSON && pose != null && pose.hasShoulders) stableShot(pose.shotType) else shot
        val raw = CompositionRules.anchorOf(subject, pose?.takeIf { it.hasShoulders }, shot)
        val prev = anchor
        val a = if (prev == null || abs(raw.x - prev.x) > 0.25f || abs(raw.y - prev.y) > 0.25f) {
            raw
        } else {
            Vec2(prev.x + smoothing * (raw.x - prev.x), prev.y + smoothing * (raw.y - prev.y))
        }
        anchor = a

        // Pick (or keep) the target point.
        val candidates = CompositionRules.candidates(subject, shot, grid)
        fun dist(c: Pair<Float?, Float?>): Float =
            max(c.first?.let { abs(it - a.x) } ?: 0f, c.second?.let { abs(it - a.y) } ?: 0f)
        val nearest = candidates.minBy { dist(it) }
        val key = "${grid.name}/${subject.kind}/${shot?.name}"
        val current = locked?.takeIf { lockKey == key && it in candidates }
        val target = if (current == null || dist(nearest) + switchMargin < dist(current)) nearest else current
        locked = target
        lockKey = key

        val dx = target.first?.let { it - a.x } ?: 0f
        val dy = target.second?.let { it - a.y } ?: 0f
        val error = max(abs(dx), abs(dy))
        aligned = if (aligned) error < EXIT_ALIGNED else error < ENTER_ALIGNED

        val showTarget = grid != GridMode.OFF
        if (showTarget && !aligned) {
            tips += CompositionRules.placementTip(dx, dy, grid, subject, xOnly = target.second == null)
        }
        if (subject.kind == SubjectKind.PERSON && pose != null && pose.hasShoulders && shot != null) {
            tips += CompositionRules.framingTips(pose, shot)
        }
        return CompositionResult(
            subject = subject,
            anchor = a,
            targetX = if (showTarget) target.first else null,
            targetY = if (showTarget) target.second else null,
            aligned = showTarget && aligned,
            placementError = error,
            shot = if (subject.kind == SubjectKind.PERSON) shot else null,
            tips = tips,
        )
    }

    private fun stableShot(raw: ShotType): ShotType {
        val cur = shot
        if (cur == null || raw == cur) {
            shot = raw
            pendingCount = 0
            return raw
        }
        if (raw == pendingShot) pendingCount++ else {
            pendingShot = raw
            pendingCount = 1
        }
        if (pendingCount >= SHOT_CHANGE_FRAMES) {
            shot = raw
            pendingCount = 0
            return raw
        }
        return cur
    }

    companion object {
        const val ENTER_ALIGNED = 0.05f
        const val EXIT_ALIGNED = 0.08f
        private const val SHOT_CHANGE_FRAMES = 6
    }
}
