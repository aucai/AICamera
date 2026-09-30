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
        lookRight: Boolean = false,
    ): PoseFrame {
        val pts = MutableList(33) { Landmark(0.5f + dx, 0.5f + dy, 0f, 0f) }
        fun set(i: Int, x: Float, y: Float) {
            pts[i] = Landmark(x + dx, y + dy, 0f, 0.99f)
        }
        set(PoseIdx.NOSE, if (lookRight) 0.525f else 0.50f, 0.20f)
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
    private val portrait = 0.75f

    private fun CompositionTracker.feed(
        now: Long,
        pose: PoseFrame?,
        crop: CropChoice = CropChoice.SAME,
        grid: GridMode = GridMode.THIRDS,
    ) = update(now, SubjectPicker.pick(pose, emptyList(), null), pose, level, grid, portrait, crop.aspect(portrait))

    /** Runs the tracker long enough for the crop to settle. */
    private fun settle(pose: PoseFrame, crop: CropChoice = CropChoice.SAME): CompositionResult {
        val t = CompositionTracker()
        var r = t.feed(0, pose, crop)
        repeat(30) { r = t.feed(100L * (it + 1), pose, crop) }
        return r
    }

    private fun eyesInCrop(r: CompositionResult): Vec2 {
        val a = r.frameSubject!!.anchor
        val c = r.plan.rect
        return Vec2((a.x - c.left) / c.width, (a.y - c.top) / c.height)
    }

    @Test
    fun halfBodyCropPutsEyesOnUpperThird() {
        val r = settle(person(dx = 0.05f, fullBody = false))
        val e = eyesInCrop(r)
        assertEquals(1f / 3f, e.y, 0.06f)
        assertTrue("reason: ${r.plan.reason}", r.plan.reason.contains("三分线"))
        // Head must stay inside the crop.
        assertTrue(r.frameSubject!!.headTop!! >= r.plan.rect.top)
    }

    @Test
    fun personLookingRightGetsRoomOnTheRight() {
        val r = settle(person(dx = 0.02f, fullBody = false, lookRight = true))
        assertEquals(1, r.frameSubject!!.facing)
        assertTrue("x in crop ${eyesInCrop(r).x}", eyesInCrop(r).x < 0.5f)
        assertTrue(r.plan.reason, r.plan.reason.contains("朝右看"))
    }

    @Test
    fun fullBodyCropKeepsFeetAndHead() {
        val r = settle(person(fullBody = true))
        val s = r.frameSubject!!
        assertEquals(ShotType.FULL_BODY, s.shot)
        assertTrue(s.feetY!! <= r.plan.rect.bottom + 1e-3f)
        assertTrue(s.headTop!! >= r.plan.rect.top - 1e-3f)
        assertTrue(r.tips.none { it.id == "comp.headcut" || it.id == "comp.feetcut" })
    }

    @Test
    fun cropBottomAvoidsKnees() {
        val r = settle(person(fullBody = false), CropChoice.SQUARE)
        for (j in r.frameSubject!!.joints) assertTrue(kotlin.math.abs(r.plan.rect.bottom - j) >= 0.035f)
    }

    @Test
    fun cropStaysPutWhileSubjectJitters() {
        val t = CompositionTracker()
        var r = t.feed(0, person(fullBody = false))
        repeat(30) { r = t.feed(100L * (it + 1), person(fullBody = false)) }
        val settled = r.plan.rect
        for ((i, dx) in listOf(0.01f, -0.01f, 0.008f, -0.006f, 0.01f).withIndex()) {
            r = t.feed(4000L + i * 100, person(dx = dx, fullBody = false))
            assertEquals(settled.left, r.plan.rect.left, 0.02f)
            assertEquals(settled.top, r.plan.rect.top, 0.02f)
        }
    }

    @Test
    fun noCropWhenTurnedOff() {
        val r = settle(person(fullBody = false), CropChoice.OFF)
        assertEquals(RectN(0f, 0f, 1f, 1f), r.plan.rect)
        assertEquals("", r.plan.reason)
    }

    @Test
    fun squareCropHasSquareShape() {
        val r = settle(person(fullBody = false), CropChoice.SQUARE)
        // In pixels: width * frameAspect == height for a square.
        assertEquals(r.plan.rect.height, r.plan.rect.width * portrait, 0.01f)
    }

    @Test
    fun subjectAtEdgeAsksToMoveThePhone() {
        // Close-up at the far right: even the best crop cannot put the eyes on a third.
        val r = settle(person(dx = 0.45f, fullBody = false))
        val tip = r.tips.firstOrNull { it.id == "comp.room" }
        assertNotNull(tip)
        assertTrue(tip!!.text, tip.text.startsWith("手机"))
    }

    @Test
    fun displayToSourceInvertsRotationAndMirror() {
        val r = RectN(0.1f, 0.2f, 0.5f, 0.6f)
        assertEquals(r, displayToSource(r, 0, false))
        // 90° clockwise: display (x, y) came from source (y, 1 - x).
        assertEquals(RectN(0.2f, 0.5f, 0.6f, 0.9f), displayToSource(r, 90, false))
        // Mirrored first: x → 1 - x.
        assertEquals(RectN(0.5f, 0.2f, 0.9f, 0.6f), displayToSource(r, 0, true))
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

    private fun checks(pose: PoseFrame?, lvl: LevelState = level): List<Check> {
        val comp = if (pose == null) CompositionTracker().feed(0, null) else settle(pose)
        return Checklist.build(comp, emptyList(), goodLight(), lvl, pose, PoseCoach.analyze(pose))
    }

    @Test
    fun emptyFrameFailsTheSubjectCheck() {
        val c = checks(null)
        assertEquals(Check(false, "没找到主体"), c.first())
    }

    @Test
    fun checksNameConcreteProblems() {
        val p = person(fullBody = false, shoulderTilt = 0.05f)
        val c = checks(p, LevelState(6f, false, 0f, 0f))
        assertTrue(c.contains(Check(false, "歪了6°")))
        assertTrue(c.contains(Check(true, "曝光正常")))
        assertTrue(c.any { !it.ok && it.text == "肩不平" })
        assertTrue(c.contains(Check(true, "人物完整")))
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
        val r = CompositionTracker().update(0, subject, null, level, GridMode.THIRDS, portrait, portrait)
        assertTrue(r.plan.reason, r.plan.reason.contains("地平线"))
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
        val r = CompositionTracker().update(0, s, null, level, GridMode.THIRDS, portrait, portrait)
        assertTrue(r.plan.reason, r.plan.reason.contains("杯子"))
        // The whole cup stays inside the crop.
        val c = r.plan.rect
        assertTrue(c.left <= 0.75f + 1e-3f && c.right >= 0.9f - 1e-3f && c.top <= 0.1f + 1e-3f && c.bottom >= 0.25f - 1e-3f)
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
