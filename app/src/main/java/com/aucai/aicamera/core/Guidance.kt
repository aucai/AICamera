package com.aucai.aicamera.core

class GuidanceInput(
    val pose: PoseFrame?,
    val objects: List<ObjectBox>,
    val luma: LumaGrid?,
    val aim: AimInput,
    /** Automatic crop, light and colour once the shot is framed. */
    val enhance: Boolean = true,
    /** The camera can light the subject (flash unit, or the screen for the front camera). */
    val hasFlash: Boolean = false,
    /** Scene brightness (EV at ISO 100) from the camera's exposure; null when unknown. */
    val sceneEv: Float? = null,
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
    /** What the camera does automatically for this shot (crop, light, colour). */
    val look: LookPlan = LookPlan.OFF,
)

/** Runs every analyzer on each frame. Keeps tracking state between frames; call from one thread. */
class GuidanceEngine {

    private val composition = CompositionTracker()
    private val look = LookEngine()
    private val horizon = HorizonTracker()

    fun reset() {
        composition.reset()
        look.reset()
        horizon.reset()
    }

    /** Use the cloud model's framing for the current subject (null drops it). */
    fun setExternal(f: ExternalFraming?) = composition.aim.setExternal(f)

    fun analyze(nowMs: Long, input: GuidanceInput): GuidanceFrame {
        val horizonY = horizon.update(input.luma, input.aim, input.sceneEv)
        val subject = SubjectPicker.pick(input.pose, input.objects, horizonY, composition.currentSubject)
        val comp = composition.update(nowMs, subject, input.pose, input.aim)
        val lighting = input.luma?.let { LightingAnalyzer.analyze(it, input.pose) }
        val poseTips = PoseCoach.analyze(input.pose).sortedByDescending { it.severity.ordinal }
        val checks = Checklist.build(comp, lighting, input.aim.level, input.pose, poseTips)
        val scene = SceneAdvisor.describe(comp.subject, comp.shot, lighting)
        val plan = look.update(nowMs, comp, lighting, input.pose, input)
        return GuidanceFrame(input.pose, comp, lighting, poseTips, checks, scene, plan)
    }
}
