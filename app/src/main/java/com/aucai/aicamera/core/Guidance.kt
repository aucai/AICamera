package com.aucai.aicamera.core

enum class Mode(val label: String, val categories: Set<TipCategory>) {
    SMART("智能", TipCategory.entries.toSet()),
    COMPOSITION("构图", setOf(TipCategory.LEVEL, TipCategory.COMPOSITION)),
    POSE("姿势", setOf(TipCategory.POSE)),
    LIGHT("光线", setOf(TipCategory.LIGHT)),
}

class GuidanceInput(
    val pose: PoseFrame?,
    val luma: LumaGrid?,
    val level: LevelState?,
    val grid: GridMode,
)

class GuidanceFrame(
    val pose: PoseFrame?,
    val composition: CompositionResult,
    val lighting: LightingResult?,
    val score: ShotScore,
    val tips: List<Tip>,
)

/** Runs every analyzer on each frame. Keeps composition state between frames; call from one thread. */
class GuidanceEngine {

    private val composition = CompositionTracker()

    fun reset() = composition.reset()

    fun analyze(nowMs: Long, input: GuidanceInput): GuidanceFrame {
        val comp = composition.update(nowMs, input.pose, input.level, input.grid)
        val lighting = input.luma?.let { LightingAnalyzer.analyze(it, input.pose) }
        val poseTips = PoseCoach.analyze(input.pose)
        val score = ShotScorer.score(comp, lighting, input.level, input.pose, poseTips)
        val tips = comp.tips + poseTips + (lighting?.tips ?: emptyList())
        return GuidanceFrame(input.pose, comp, lighting, score, tips)
    }
}
