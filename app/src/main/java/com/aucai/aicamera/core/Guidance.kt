package com.aucai.aicamera.core

enum class Mode(val label: String, val categories: Set<TipCategory>) {
    SMART("智能", TipCategory.entries.toSet()),
    COMPOSITION("构图", setOf(TipCategory.LEVEL, TipCategory.COMPOSITION)),
    POSE("姿势", setOf(TipCategory.POSE)),
    LIGHT("光线", setOf(TipCategory.LIGHT)),
}

class GuidanceInput(
    val pose: PoseFrame?,
    val objects: List<ObjectBox>,
    val luma: LumaGrid?,
    val level: LevelState?,
    val grid: GridMode,
    /** Width / height of the analysed frame. */
    val frameAspect: Float,
    /** Width / height of the photo to crop to; null keeps the whole frame. */
    val outAspect: Float?,
)

class GuidanceFrame(
    val pose: PoseFrame?,
    val composition: CompositionResult,
    val lighting: LightingResult?,
    val checks: List<Check>,
    /** Short description of what the camera sees, e.g. "人像 · 半身 · 逆光". */
    val scene: String,
    val tips: List<Tip>,
)

/** Runs every analyzer on each frame. Keeps tracking state between frames; call from one thread. */
class GuidanceEngine {

    private val composition = CompositionTracker()

    fun reset() = composition.reset()

    fun analyze(nowMs: Long, input: GuidanceInput): GuidanceFrame {
        val subject = SubjectPicker.pick(input.pose, input.objects, input.luma, composition.currentSubject)
        val comp = composition.update(
            nowMs, subject, input.pose, input.level, input.grid, input.frameAspect, input.outAspect,
        )
        val sceneTips = SceneAdvisor.tips(comp.subject, input.level)
        val lighting = input.luma?.let { LightingAnalyzer.analyze(it, input.pose) }
        val poseTips = PoseCoach.analyze(input.pose)
        val checks = Checklist.build(comp, sceneTips, lighting, input.level, input.pose, poseTips)
        val scene = SceneAdvisor.describe(comp.subject, comp.shot, lighting)
        val tips = comp.tips + sceneTips + poseTips + (lighting?.tips ?: emptyList())
        return GuidanceFrame(input.pose, comp, lighting, checks, scene, tips)
    }
}
