package com.aucai.aicamera.core

import kotlin.math.abs

/** One concrete thing the shot gets right or wrong, e.g. "✓ 水平" or "✗ 头顶被切". */
data class Check(val ok: Boolean, val text: String)

/** Plain pass/fail checks instead of an abstract score. */
object Checklist {

    fun build(
        comp: CompositionResult,
        lighting: LightingResult?,
        level: LevelState?,
        pose: PoseFrame?,
        poseTips: List<Tip>,
    ): List<Check> {
        val checks = ArrayList<Check>()
        val subject = comp.subject
        val fs = comp.frameSubject

        checks += when {
            subject == null -> Check(false, "没找到主体")
            comp.aim.phase == AimPhase.DONE -> Check(true, "构图到位")
            else -> Check(false, "构图没对准")
        }

        if (fs != null && fs.kind != SubjectKind.HORIZON) {
            val issue = CompositionRules.completenessIssues(fs).firstOrNull()
            checks += when {
                issue != null -> Check(false, issue)
                fs.kind == SubjectKind.PERSON -> Check(true, "人物完整")
                else -> Check(true, "主体完整")
            }
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
