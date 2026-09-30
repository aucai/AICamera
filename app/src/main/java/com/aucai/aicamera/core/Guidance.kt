package com.aucai.aicamera.core

class GuidanceInput(
    val pose: PoseFrame?,
    val objects: List<ObjectBox>,
    val luma: LumaGrid?,
    val aim: AimInput,
)

class GuidanceFrame(
    val pose: PoseFrame?,
    val composition: CompositionResult,
    val lighting: LightingResult?,
    /** Advice on the person's pose, strongest first. */
    val poseTips: List<Tip>,
    /** Pass/fail checks, kept with each photo for the review. */
    val checks: List<Check>,
    /** Short description of what the camera sees, e.g. "人像 · 半身 · 逆光". */
    val scene: String,
)

/** Runs every analyzer on each frame. Keeps tracking state between frames; call from one thread. */
class GuidanceEngine {

    private val composition = CompositionTracker()

    fun reset() = composition.reset()

    fun analyze(nowMs: Long, input: GuidanceInput): GuidanceFrame {
        val subject = SubjectPicker.pick(input.pose, input.objects, input.luma, composition.currentSubject)
        val comp = composition.update(nowMs, subject, input.pose, input.aim)
        val lighting = input.luma?.let { LightingAnalyzer.analyze(it, input.pose) }
        val poseTips = PoseCoach.analyze(input.pose).sortedByDescending { it.severity.ordinal }
        val checks = Checklist.build(comp, lighting, input.aim.level, input.pose, poseTips)
        val scene = SceneAdvisor.describe(comp.subject, comp.shot, lighting)
        return GuidanceFrame(input.pose, comp, lighting, poseTips, checks, scene)
    }
}
