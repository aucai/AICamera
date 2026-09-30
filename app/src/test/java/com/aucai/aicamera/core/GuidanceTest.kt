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

    private fun CompositionTracker.feed(now: Long, pose: PoseFrame?, grid: GridMode = GridMode.THIRDS) =
        update(now, SubjectPicker.pick(pose, emptyList(), null), pose, level, grid)

    private fun analyze(pose: PoseFrame?, grid: GridMode = GridMode.THIRDS) = CompositionTracker().feed(0, pose, grid)

    @Test
    fun centredPersonIsGuidedToNearestThird() {
        val r = analyze(person(dx = 0.1f))
        assertEquals(2f / 3f, r.targetX!!, 1e-4f)
        assertNull("full-body shots only get a vertical line", r.targetY)
        assertFalse(r.aligned)
        val tip = r.tips.first { it.id == "comp.place" }
        // Subject must move right in frame → move the phone left.
        assertTrue(tip.text, tip.text.startsWith("手机向左移"))
    }

    @Test
    fun adviceIsAlwaysAboutThePhone() {
        for (dx in listOf(-0.3f, -0.1f, 0.1f, 0.3f)) {
            for (full in listOf(true, false)) {
                val r = analyze(person(dx = dx, fullBody = full))
                r.tips.filter { it.category == TipCategory.COMPOSITION }.forEach {
                    assertTrue(it.text, it.text.contains("手机") || it.text.contains("镜头"))
                }
            }
        }
    }

    @Test
    fun targetStaysPutWhileSubjectMovesTowardsIt() {
        val t = CompositionTracker()
        // Start just left of centre → locks onto the left third.
        val first = t.feed(0, person(dx = -0.02f))
        assertEquals(1f / 3f, first.targetX!!, 1e-4f)
        // Wobble across the middle: the target must not jump to the right third.
        for ((i, dx) in listOf(0.02f, 0.05f, -0.01f, 0.04f).withIndex()) {
            val r = t.feed(100L * (i + 1), person(dx = dx))
            assertEquals(1f / 3f, r.targetX!!, 1e-4f)
        }
        // Clearly on the right third → switching is fine.
        var r = t.feed(1000, person(dx = 2f / 3f - 0.5f))
        repeat(10) { r = t.feed(1100L + it * 100, person(dx = 2f / 3f - 0.5f)) }
        assertEquals(2f / 3f, r.targetX!!, 1e-4f)
    }

    @Test
    fun halfBodyTargetIsFixedOnScreen() {
        val t = CompositionTracker()
        val a = t.feed(0, person(dy = -0.05f, fullBody = false))
        val b = t.feed(100, person(dy = 0.05f, fullBody = false))
        assertEquals(1f / 3f, a.targetY!!, 1e-4f)
        assertEquals(a.targetY!!, b.targetY!!, 1e-6f)
        assertEquals(a.targetX!!, b.targetX!!, 1e-6f)
    }

    @Test
    fun personOnThirdIsAligned() {
        val t = CompositionTracker()
        var r = t.feed(0, person(dx = 2f / 3f - 0.5f))
        repeat(5) { r = t.feed(100L * (it + 1), person(dx = 2f / 3f - 0.5f)) }
        assertTrue(r.aligned)
        assertTrue(r.tips.none { it.id == "comp.place" })
    }

    @Test
    fun gridOffGivesNoTargetButStillScoresPlacement() {
        val r = analyze(person(), GridMode.OFF)
        assertNull(r.targetX)
        assertNotNull(r.placementError)
    }

    @Test
    fun tiltedPhoneAsksToRaiseTheLowSide() {
        val tips = CompositionRules.levelTips(LevelState(5f, false, 0f, 0f))
        assertEquals(1, tips.size)
        assertTrue(tips[0].text.contains("右侧抬高"))
        assertTrue(CompositionRules.levelTips(LevelState(1f, false, 0f, 0f)).isEmpty())
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
        val r = analyze(person(dy = 0.07f)) // ankles land at y = 1.0
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

    private fun goodLight() = LightingAnalyzer.analyze(LumaGrid(4, 4, IntArray(16) { 130 }, 130f, 130f, 130f), null)

    @Test
    fun emptyFrameCannotScoreHigh() {
        val comp = analyze(null)
        val score = ShotScorer.score(comp, emptyList(), goodLight(), level, null, emptyList())
        assertTrue("score ${score.total}", score.total < 75)
        assertEquals("没有主体", score.items.first { it.category == TipCategory.COMPOSITION }.note)
    }

    @Test
    fun wellPlacedPersonScoresHigherThanBadlyPlaced() {
        val good = person(dx = 2f / 3f - 0.5f, fullBody = false, dy = 1f / 3f - 0.18f)
        val bad = person(dx = 0.25f, fullBody = false, dy = 0.2f)
        val sGood = ShotScorer.score(analyze(good), emptyList(), goodLight(), level, good, emptyList())
        val sBad = ShotScorer.score(analyze(bad), emptyList(), goodLight(), level, bad, emptyList())
        assertTrue("${sGood.total} vs ${sBad.total}", sGood.total > sBad.total + 10)
        assertEquals("位置很好", sGood.items.first { it.category == TipCategory.COMPOSITION }.note)
    }

    @Test
    fun poseAndLevelProblemsLowerTheScore() {
        val p = person(dx = 2f / 3f - 0.5f, fullBody = false, dy = 1f / 3f - 0.18f)
        val comp = analyze(p)
        val clean = ShotScorer.score(comp, emptyList(), goodLight(), level, p, emptyList())
        val tilted = ShotScorer.score(comp, emptyList(), goodLight(), LevelState(6f, false, 0f, 0f), p, PoseCoach.analyze(p))
        assertTrue(tilted.total < clean.total)
        assertEquals("歪了6°", tilted.items.first { it.category == TipCategory.LEVEL }.note)
    }

    /** Bright sky above row [edge], dark ground below. */
    private fun skyGrid(edge: Int, w: Int = 16, h: Int = 24) =
        LumaGrid(w, h, IntArray(w * h) { if (it / w < edge) 210 else 70 }, 150f, 150f, 150f)

    @Test
    fun horizonFoundAndGuidedToAThird() {
        val g = skyGrid(12)
        val y = HorizonDetector.detect(g)
        assertNotNull(y)
        assertEquals(0.5f, y!!, 0.05f)
        val subject = SubjectPicker.pick(null, emptyList(), g)!!
        assertEquals(SubjectKind.HORIZON, subject.kind)
        val r = CompositionTracker().update(0, subject, null, level, GridMode.THIRDS)
        assertNull(r.targetX)
        assertNotNull(r.targetY)
        assertTrue(r.tips.first { it.id == "comp.place" }.text.contains("地平线"))
    }

    @Test
    fun flatFrameHasNoHorizon() {
        assertNull(HorizonDetector.detect(LumaGrid(16, 24, IntArray(16 * 24) { 128 }, 128f, 128f, 128f)))
    }

    @Test
    fun petBeatsFurnitureAsSubject() {
        val objects = listOf(
            ObjectBox("couch", 0.9f, RectN(0f, 0.3f, 1f, 1f)),
            ObjectBox("cat", 0.6f, RectN(0.4f, 0.5f, 0.6f, 0.7f)),
        )
        val s = SubjectPicker.pick(null, objects, null)!!
        assertEquals("猫", s.label)
        assertEquals(ObjectGroup.PET, s.group)
    }

    @Test
    fun objectAdviceNamesTheObjectAndMovesThePhone() {
        val s = SubjectPicker.pick(null, listOf(ObjectBox("cup", 0.8f, RectN(0.75f, 0.1f, 0.9f, 0.25f))), null)!!
        val r = CompositionTracker().update(0, s, null, level, GridMode.THIRDS)
        val tip = r.tips.first { it.id == "comp.place" }
        assertTrue(tip.text, tip.text.startsWith("手机") && tip.text.contains("杯子"))
        // Held upright over food → suggest a higher angle.
        val tips = SceneAdvisor.tips(s, LevelState(0f, false, 0f, 90f, pitchDeg = 5f))
        assertTrue(tips.any { it.id == "scene.food.angle" })
        assertEquals("美食 · 杯子", SceneAdvisor.describe(s, null, null))
    }

    @Test
    fun sharpnessSeparatesEdgesFromFlat() {
        val w = 64
        val h = 64
        val checker = IntArray(w * h) { if ((it % w / 4 + it / w / 4) % 2 == 0) 20 else 230 }
        val flat = IntArray(w * h) { 128 }
        assertTrue(Sharpness.laplacianVariance(checker, w, h) > Sharpness.BLURRY_BELOW)
        assertTrue(Sharpness.laplacianVariance(flat, w, h) < Sharpness.BLURRY_BELOW)
    }
}
