package com.aucai.aicamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {

    /** A standing person facing the camera, shifted horizontally by [dx]. Frame is 3:4 portrait. */
    private fun person(
        dx: Float = 0f,
        dy: Float = 0f,
        fullBody: Boolean = true,
        shoulderTilt: Float = 0f,
    ): PoseFrame {
        val pts = MutableList(33) { Landmark(0.5f + dx, 0.5f + dy, 0f, 0f) }
        fun set(i: Int, x: Float, y: Float) {
            pts[i] = Landmark(x + dx, y + dy, 0f, 0.99f)
        }
        set(PoseIdx.NOSE, 0.50f, 0.20f)
        set(PoseIdx.LEFT_EYE, 0.52f, 0.18f)
        set(PoseIdx.RIGHT_EYE, 0.48f, 0.18f)
        set(PoseIdx.LEFT_EAR, 0.54f, 0.19f)
        set(PoseIdx.RIGHT_EAR, 0.46f, 0.19f)
        set(9, 0.49f, 0.22f)
        set(10, 0.51f, 0.22f)
        set(PoseIdx.LEFT_SHOULDER, 0.60f, 0.28f + shoulderTilt)
        set(PoseIdx.RIGHT_SHOULDER, 0.40f, 0.28f - shoulderTilt)
        set(PoseIdx.LEFT_ELBOW, 0.66f, 0.42f)
        set(PoseIdx.RIGHT_ELBOW, 0.34f, 0.42f)
        set(PoseIdx.LEFT_WRIST, 0.68f, 0.55f)
        set(PoseIdx.RIGHT_WRIST, 0.32f, 0.55f)
        set(PoseIdx.LEFT_HIP, 0.56f, 0.55f)
        set(PoseIdx.RIGHT_HIP, 0.44f, 0.55f)
        set(PoseIdx.LEFT_KNEE, 0.56f, 0.72f)
        set(PoseIdx.RIGHT_KNEE, 0.44f, 0.72f)
        if (fullBody) {
            set(PoseIdx.LEFT_ANKLE, 0.56f, 0.93f)
            set(PoseIdx.RIGHT_ANKLE, 0.44f, 0.93f)
        } else {
            pts[PoseIdx.LEFT_ANKLE] = Landmark(0.56f + dx, 1.2f, 0f, 0.1f)
            pts[PoseIdx.RIGHT_ANKLE] = Landmark(0.44f + dx, 1.2f, 0f, 0.1f)
        }
        return PoseFrame(pts, 0.75f)
    }

    private val level = LevelState(0f, false, 0f, 90f)

    @Test
    fun centredPersonIsGuidedToNearestThird() {
        val r = CompositionAnalyzer.analyze(person(dx = 0.1f), level, GridMode.THIRDS, frontCamera = false)
        assertNotNull(r.target)
        assertEquals(2f / 3f, r.target!!.x, 1e-4f)
        assertFalse(r.aligned)
        val tip = r.tips.first { it.id == "comp.place" }
        // Subject must move right in frame → pan the phone left.
        assertTrue(tip.text, tip.text.contains("向左"))
    }

    @Test
    fun frontCameraAdviceTalksAboutThePerson() {
        val r = CompositionAnalyzer.analyze(person(dx = 0.1f), level, GridMode.THIRDS, frontCamera = true)
        assertTrue(r.tips.first { it.id == "comp.place" }.text.startsWith("人往画面右边"))
    }

    @Test
    fun personOnThirdIsAligned() {
        val r = CompositionAnalyzer.analyze(person(dx = 2f / 3f - 0.5f), level, GridMode.THIRDS, frontCamera = false)
        assertTrue(r.aligned)
        assertTrue(r.tips.none { it.id == "comp.place" })
    }

    @Test
    fun gridOffGivesNoTarget() {
        val r = CompositionAnalyzer.analyze(person(), level, GridMode.OFF, frontCamera = false)
        assertNull(r.target)
    }

    @Test
    fun tiltedPhoneAsksToRaiseTheLowSide() {
        val tips = CompositionAnalyzer.levelTips(LevelState(5f, false, 0f, 0f))
        assertEquals(1, tips.size)
        assertTrue(tips[0].text.contains("右侧抬高"))
        assertTrue(CompositionAnalyzer.levelTips(LevelState(1f, false, 0f, 0f)).isEmpty())
    }

    @Test
    fun levelMathRollSign() {
        // Phone rotated 10° clockwise: gravity reaction leans towards -x.
        val s = LevelMath.compute(-1.7f, 9.65f, 0f, 0)
        assertEquals(10f, s.rollDeg, 0.5f)
        assertFalse(s.flat)
        assertTrue(LevelMath.compute(0f, 0.5f, 9.8f, 0).flat)
    }

    @Test
    fun ankleCropIsFlagged() {
        val p = person(dy = 0.07f) // ankles land at y = 1.0
        val r = CompositionAnalyzer.analyze(p, level, GridMode.THIRDS, frontCamera = false)
        assertTrue(r.tips.any { it.id == "comp.jointcut" })
    }

    @Test
    fun poseCoachFlagsShoulderTilt() {
        assertTrue(PoseCoach.analyze(person(shoulderTilt = 0.05f)).any { it.id == "pose.shoulders" })
        assertTrue(PoseCoach.analyze(person()).none { it.id == "pose.shoulders" })
    }

    @Test
    fun poseCoachSuggestsTurningWhenSquare() {
        assertTrue(PoseCoach.analyze(person()).any { it.id == "pose.turn" })
    }

    @Test
    fun backlightDetected() {
        val w = 12
        val h = 16
        val pose = person(fullBody = false)
        val luma = IntArray(w * h) { i ->
            val x = (i % w + 0.5f) / w
            val y = (i / w + 0.5f) / h
            if (x in 0.38f..0.62f && y in 0.1f..0.6f) 40 else 230
        }
        val r = LightingAnalyzer.analyze(LumaGrid(w, h, luma, 200f, 200f, 200f), pose)
        val tip = r.tips.firstOrNull { it.id == "light.backlit" }
        assertNotNull(tip)
        assertEquals(TipAction.METER_SUBJECT, tip!!.action)
        assertNotNull(r.meterPoint)
    }

    @Test
    fun darkAndBlownFramesAreFlagged() {
        val dark = LightingAnalyzer.analyze(LumaGrid(4, 4, IntArray(16) { 20 }, 20f, 20f, 20f), null)
        assertTrue(dark.tips.any { it.id == "light.dark" })
        val blown = LightingAnalyzer.analyze(LumaGrid(4, 4, IntArray(16) { if (it < 4) 255 else 120 }, 120f, 120f, 120f), null)
        assertTrue(blown.tips.any { it.id == "light.blown" })
        assertTrue(blown.clipped[0])
    }

    @Test
    fun stabilizerDelaysAndHolds() {
        val s = TipStabilizer(showAfterMs = 400, hideAfterMs = 800)
        val tip = Tip("a", TipCategory.POSE, Severity.SUGGEST, "x")
        assertTrue(s.update(0, listOf(tip)).isEmpty())
        assertEquals(1, s.update(500, listOf(tip)).size)
        assertEquals(1, s.update(1000, emptyList()).size) // still held
        assertTrue(s.update(1400, emptyList()).isEmpty())
    }

    @Test
    fun scorePenalisesTips() {
        assertEquals(100, ShotScore.of(emptyList()))
        val warn = Tip("w", TipCategory.LIGHT, Severity.WARNING, "")
        val sug = Tip("s", TipCategory.POSE, Severity.SUGGEST, "")
        assertEquals(74, ShotScore.of(listOf(warn, sug)))
    }
}
