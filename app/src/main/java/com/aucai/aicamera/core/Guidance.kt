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
    val frontCamera: Boolean,
)

class GuidanceFrame(
    val pose: PoseFrame?,
    val composition: CompositionResult,
    val lighting: LightingResult?,
    val tips: List<Tip>,
)

/** Runs every analyzer on one frame. Pure function of its input. */
object GuidanceEngine {
    fun analyze(input: GuidanceInput): GuidanceFrame {
        val composition = CompositionAnalyzer.analyze(input.pose, input.level, input.grid, input.frontCamera)
        val lighting = input.luma?.let { LightingAnalyzer.analyze(it, input.pose) }
        val tips = composition.tips + PoseCoach.analyze(input.pose) + (lighting?.tips ?: emptyList())
        return GuidanceFrame(input.pose, composition, lighting, tips)
    }
}
