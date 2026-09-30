package com.aucai.aicamera.core

import kotlin.math.abs

/** One concrete thing the shot gets right or wrong, e.g. "✓ 水平" or "✗ 头顶被切". */
data class Check(val ok: Boolean, val text: String)

/** Plain pass/fail checks instead of an abstract score. */
object Checklist {

    fun build(
        comp: CompositionResult,
        sceneTips: List<Tip>,
        lighting: LightingResult?,
        level: LevelState?,
        pose: PoseFrame?,
        poseTips: List<Tip>,
    ): List<Check> {
        val checks = ArrayList<Check>()
        val ids = (comp.tips + sceneTips).map { it.id }.toSet()
        val subject = comp.subject

        checks += when {
            subject == null -> Check(false, "没找到主体")
            "comp.room" in ids -> Check(false, "主体太靠边")
            else -> Check(true, "构图到位")
        }

        when (subject?.kind) {
            SubjectKind.PERSON -> checks += when {
                "comp.headcut" in ids -> Check(false, "头顶被切")
                "comp.feetcut" in ids -> Check(false, "脚被切")
                "comp.jointcut" in ids -> Check(false, "切到关节")
                "comp.small" in ids -> Check(false, "人太小")
                else -> Check(true, "人物完整")
            }
            SubjectKind.OBJECT -> checks += when {
                "comp.objcut" in ids -> Check(false, "主体被切")
                "comp.objsmall" in ids || "scene.food.close" in ids -> Check(false, "主体太小")
                else -> Check(true, "主体完整")
            }
            else -> Unit
        }

        if (level != null) {
            val tilt = if (level.flat) maxOf(abs(level.tiltX), abs(level.tiltY)) else abs(level.rollDeg)
            val limit = if (level.flat) 3f else 2.5f
            checks += if (tilt <= limit) Check(true, "水平") else Check(false, "歪了%.0f°".format(tilt))
        }

        if (lighting != null) {
            checks += when {
                lighting.backlit -> Check(false, "逆光")
                lighting.mean < 45f -> Check(false, "太暗")
                lighting.highRatio > 0.12f -> Check(false, "过曝")
                lighting.splitLight -> Check(false, "阴阳脸")
                else -> Check(true, "曝光正常")
            }
        }

        if (pose != null && pose.hasShoulders && subject?.kind == SubjectKind.PERSON) {
            val first = poseTips.firstOrNull { it.severity != Severity.INFO }
            checks += when (first?.id) {
                null -> Check(true, "姿势自然")
                "pose.turn" -> Check(false, "可以侧身")
                "pose.arms" -> Check(false, "手臂贴身")
                "pose.shoulders" -> Check(false, "肩不平")
                else -> Check(false, "姿势可调整")
            }
        }
        return checks
    }
}
