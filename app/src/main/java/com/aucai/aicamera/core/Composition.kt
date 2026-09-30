package com.aucai.aicamera.core

import kotlin.math.abs

enum class GridMode(val label: String) {
    THIRDS("九宫格"),
    GOLDEN("黄金分割"),
    CENTER("中心"),
    OFF("无网格"),
}

/**
 * @property anchor where the subject currently is (eyes for close-ups, body centre otherwise).
 * @property target where the subject should go; null when there is no suggestion.
 */
data class CompositionResult(
    val anchor: Vec2?,
    val target: Vec2?,
    val aligned: Boolean,
    val tips: List<Tip>,
)

object CompositionAnalyzer {

    private const val TOLERANCE = 0.06f

    fun analyze(pose: PoseFrame?, level: LevelState?, grid: GridMode, frontCamera: Boolean): CompositionResult {
        val tips = ArrayList<Tip>()
        level?.let { tips += levelTips(it) }
        if (pose == null || !pose.hasShoulders) {
            return CompositionResult(null, null, false, tips)
        }

        val shot = pose.shotType
        val eyes = pose.eyes
        val bodyX = if (pose.hasHips) (pose.shoulderMid.x + pose.hipMid.x) / 2f else pose.shoulderMid.x
        val anchor = Vec2(if (shot == ShotType.CLOSE_UP) eyes.x else bodyX, eyes.y)

        var target: Vec2? = null
        var aligned = false
        targetFor(grid, shot, anchor)?.let { t ->
            target = t
            val dx = t.x - anchor.x
            val dy = t.y - anchor.y
            aligned = abs(dx) < TOLERANCE && abs(dy) < TOLERANCE
            placementTip(dx, dy, frontCamera, grid)?.let { tips += it }
        }

        tips += framingTips(pose, shot)
        return CompositionResult(anchor, target, aligned, tips)
    }

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

    /** Nearest power point for the anchor. Full-body shots only get a horizontal target. */
    fun targetFor(grid: GridMode, shot: ShotType, anchor: Vec2): Vec2? {
        val (xs, y) = when (grid) {
            GridMode.OFF -> return null
            GridMode.THIRDS -> listOf(1f / 3f, 2f / 3f) to 1f / 3f
            GridMode.GOLDEN -> listOf(0.382f, 0.618f) to 0.382f
            GridMode.CENTER -> listOf(0.5f) to 1f / 3f
        }
        // Close-ups also read well centred.
        val candidates = if (shot == ShotType.CLOSE_UP && grid != GridMode.CENTER) xs + 0.5f else xs
        val tx = candidates.minBy { abs(it - anchor.x) }
        val ty = if (shot == ShotType.FULL_BODY) anchor.y else y
        return Vec2(tx, ty)
    }

    private fun placementTip(dx: Float, dy: Float, frontCamera: Boolean, grid: GridMode): Tip? {
        if (abs(dx) < TOLERANCE && abs(dy) < TOLERANCE) return null
        val goal = when (grid) {
            GridMode.CENTER -> "画面中间"
            GridMode.GOLDEN -> "黄金分割点"
            else -> "三分点"
        }
        val text = if (abs(dx) >= abs(dy)) {
            // dx > 0: the subject needs to move right in the frame.
            if (frontCamera) {
                if (dx > 0) "人往画面右边挪一点，落在${goal}上" else "人往画面左边挪一点，落在${goal}上"
            } else {
                if (dx > 0) "手机向左移一点，让人物落在${goal}上" else "手机向右移一点，让人物落在${goal}上"
            }
        } else {
            // dy > 0: the eyes need to move down in the frame, i.e. raise the phone.
            if (dy > 0) "手机往上抬一点，眼睛放在上三分线" else "手机往下放一点，眼睛放在上三分线"
        }
        return Tip("comp.place", TipCategory.COMPOSITION, Severity.SUGGEST, text)
    }

    private fun framingTips(pose: PoseFrame, shot: ShotType): List<Tip> {
        val tips = ArrayList<Tip>()
        val headTop = pose.headTop
        if (headTop != null && shot != ShotType.CLOSE_UP) {
            if (headTop < 0.01f) {
                tips += Tip("comp.headcut", TipCategory.COMPOSITION, Severity.WARNING, "头顶被切掉了，手机往上抬一点")
            } else if (shot == ShotType.HALF_BODY && headTop > 0.28f) {
                tips += Tip("comp.headroom", TipCategory.COMPOSITION, Severity.SUGGEST, "头顶留白太多，手机往下放一点")
            }
        }

        val kneeCut = listOf(PoseIdx.LEFT_KNEE, PoseIdx.RIGHT_KNEE).any {
            pose.visible(it, 0.4f) && pose.landmarks[it].y in 0.94f..1.06f
        }
        val ankleCut = listOf(PoseIdx.LEFT_ANKLE, PoseIdx.RIGHT_ANKLE).any {
            pose.visible(it, 0.4f) && pose.landmarks[it].y in 0.95f..1.05f
        }
        when {
            kneeCut -> tips += Tip("comp.jointcut", TipCategory.COMPOSITION, Severity.WARNING, "底边正好切在膝盖，拍全身或者只拍到大腿")
            ankleCut -> tips += Tip("comp.jointcut", TipCategory.COMPOSITION, Severity.WARNING, "底边切到脚踝了，把脚完整拍进来")
        }

        if (shot == ShotType.FULL_BODY) {
            val feet = maxOf(pose.landmarks[PoseIdx.LEFT_ANKLE].y, pose.landmarks[PoseIdx.RIGHT_ANKLE].y)
            if (feet < 0.85f) {
                tips += Tip("comp.feet", TipCategory.COMPOSITION, Severity.SUGGEST, "镜头稍微上仰，让脚底贴近画面底边，更显腿长")
            }
            val body = pose.bodyBounds()
            if (body != null && body.height < 0.4f) {
                tips += Tip("comp.small", TipCategory.COMPOSITION, Severity.SUGGEST, "人物太小了，走近一点")
            }
        }
        return tips
    }
}
