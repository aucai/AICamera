package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One part of the shot score.
 * @property max 0 when the part cannot be judged in this frame (it then does not count).
 * @property note a few words on why it scored what it did.
 */
data class ScoreItem(val category: TipCategory, val points: Int, val max: Int, val note: String) {
    val applicable get() = max > 0
}

data class ShotScore(val total: Int, val items: List<ScoreItem>)

/**
 * Scores the frame on what it actually shows, so a high score has to be earned:
 * composition 35, lighting 35, pose 20, level 10. Parts that cannot be judged are
 * left out and the rest scaled to 100. Without a person, composition gets only a
 * small base score because nothing can be placed.
 */
object ShotScorer {

    const val COMPOSITION_MAX = 35
    const val LIGHT_MAX = 35
    const val POSE_MAX = 20
    const val LEVEL_MAX = 10

    fun score(
        composition: CompositionResult,
        lighting: LightingResult?,
        level: LevelState?,
        pose: PoseFrame?,
        poseTips: List<Tip>,
    ): ShotScore {
        val items = listOf(
            composition(composition),
            light(lighting),
            pose(pose, poseTips),
            level(level),
        )
        val max = items.sumOf { it.max }
        val total = if (max == 0) 0 else (100f * items.sumOf { it.points } / max).roundToInt()
        return ShotScore(total.coerceIn(0, 100), items)
    }

    fun composition(c: CompositionResult): ScoreItem {
        val error = c.placementError
            ?: return ScoreItem(TipCategory.COMPOSITION, 10, COMPOSITION_MAX, "没有人物")
        val placement = 20f * clamp01(1f - error / 0.2f)
        val ids = c.tips.map { it.id }.toSet()
        var framing = 15f
        var problem: String? = null
        fun hit(id: String, penalty: Float, note: String) {
            if (id in ids) {
                framing -= penalty
                if (problem == null) problem = note
            }
        }
        hit("comp.headcut", 10f, "头顶被切")
        hit("comp.jointcut", 8f, "切到关节")
        hit("comp.small", 6f, "人太小")
        hit("comp.headroom", 5f, "头顶太空")
        hit("comp.feet", 3f, "脚离底边远")
        val points = (placement + framing.coerceAtLeast(0f)).roundToInt()
        val note = problem ?: if (placement >= 16f) "位置很好" else "位置偏了"
        return ScoreItem(TipCategory.COMPOSITION, points, COMPOSITION_MAX, note)
    }

    fun light(l: LightingResult?): ScoreItem {
        if (l == null) return ScoreItem(TipCategory.LIGHT, 0, 0, "—")
        // Overall exposure: full marks for a mean luma of 95..165.
        val exposure = 15f * when {
            l.mean < 95f -> clamp01((l.mean - 30f) / 65f)
            l.mean > 165f -> clamp01((230f - l.mean) / 65f)
            else -> 1f
        }
        val clipping = 10f * (1f - clamp01(l.highRatio / 0.15f + l.lowRatio / 0.3f))
        val subjectLuma = l.subjectLuma
        val subject = when {
            l.backlit -> 2f
            l.splitLight -> 5f
            subjectLuma == null -> exposure / 15f * 10f
            subjectLuma in 90f..190f -> 10f
            subjectLuma < 90f -> 10f * clamp01((subjectLuma - 40f) / 50f)
            else -> 10f * clamp01((235f - subjectLuma) / 45f)
        }
        val note = when {
            l.backlit -> "逆光"
            l.mean < 60f -> "偏暗"
            l.highRatio > 0.08f -> "有过曝"
            l.mean > 190f -> "偏亮"
            l.splitLight -> "阴阳脸"
            subjectLuma != null && subjectLuma < 80f -> "人偏暗"
            else -> "光线不错"
        }
        return ScoreItem(TipCategory.LIGHT, (exposure + clipping + subject).roundToInt(), LIGHT_MAX, note)
    }

    fun pose(pose: PoseFrame?, poseTips: List<Tip>): ScoreItem {
        if (pose == null || !pose.hasShoulders) return ScoreItem(TipCategory.POSE, 0, 0, "—")
        var points = POSE_MAX
        for (t in poseTips) {
            points -= when (t.severity) {
                Severity.WARNING -> 10
                Severity.SUGGEST -> 6
                Severity.INFO -> 2
            }
        }
        val first = poseTips.firstOrNull { it.severity != Severity.INFO } ?: poseTips.firstOrNull()
        val note = when (first?.id) {
            null -> "很自然"
            "pose.turn" -> "可以侧身"
            "pose.arms" -> "手臂贴身"
            "pose.shoulders" -> "肩不平"
            "pose.legs" -> "站得太直"
            else -> "可以调整"
        }
        return ScoreItem(TipCategory.POSE, points.coerceAtLeast(0), POSE_MAX, note)
    }

    fun level(level: LevelState?): ScoreItem {
        if (level == null) return ScoreItem(TipCategory.LEVEL, 0, 0, "—")
        val tilt = if (level.flat) maxOf(abs(level.tiltX), abs(level.tiltY)) else abs(level.rollDeg)
        val points = (LEVEL_MAX * clamp01(1f - (tilt - 1f) / 5f)).roundToInt()
        val note = if (tilt <= 2.5f) "很正" else "歪了%.0f°".format(tilt)
        return ScoreItem(TipCategory.LEVEL, points, LEVEL_MAX, note)
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
}
