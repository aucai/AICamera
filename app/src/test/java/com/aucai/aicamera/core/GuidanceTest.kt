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

    private fun aimInput(
        style: PortraitStyle = PortraitStyle.CLOSE,
        zoom: Float = 1f,
        pitch: Float = 0f,
        front: Boolean = false,
        steady: Boolean = true,
    ) = AimInput(portrait, LevelState(0f, false, 0f, 90f, 0f, pitch), zoom, 3f, steady, style, front)

    private fun CompositionTracker.feed(now: Long, pose: PoseFrame?, input: AimInput = aimInput()) =
        update(now, SubjectPicker.pick(pose, emptyList(), null), pose, input)

    /** Feeds the same pose for [frames] frames 100 ms apart, starting at [start]. */
    private fun CompositionTracker.run(
        pose: PoseFrame,
        input: AimInput = aimInput(),
        start: Long = 0,
        frames: Int = 10,
    ): CompositionResult {
        var r = feed(start, pose, input)
        for (i in 1 until frames) r = feed(start + i * 100L, pose, input)
        return r
    }

    /** The pose moved by [dx], [dy]. */
    private fun PoseFrame.shifted(dx: Float, dy: Float) =
        PoseFrame(landmarks.map { it.copy(x = it.x + dx, y = it.y + dy) }, aspect)

    /** The pose as seen after zooming in by [k] around the frame centre. */
    private fun PoseFrame.zoomed(k: Float) =
        PoseFrame(landmarks.map { it.copy(x = 0.5f + (it.x - 0.5f) * k, y = 0.5f + (it.y - 0.5f) * k) }, aspect)

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

    /** Bright sky above row [edge], dark ground below. */
    private fun skyGrid(edge: Int, w: Int = 16, h: Int = 24) =
        LumaGrid(w, h, IntArray(w * h) { if (it / w < edge) 210 else 70 }, 150f, 150f, 150f)

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
    fun sharpnessSeparatesEdgesFromFlat() {
        val w = 64
        val h = 64
        val checker = IntArray(w * h) { if ((it % w / 4 + it / w / 4) % 2 == 0) 20 else 230 }
        val flat = IntArray(w * h) { 128 }
        assertTrue(Sharpness.laplacianVariance(checker, w, h) > Sharpness.BLURRY_BELOW)
        assertTrue(Sharpness.laplacianVariance(flat, w, h) < Sharpness.BLURRY_BELOW)
    }

    @Test
    fun recommendationWaitsForTheSubjectToSettle() {
        val t = CompositionTracker()
        assertEquals(AimPhase.IDLE, t.feed(0, person(fullBody = false)).aim.phase)
        val r = t.run(person(fullBody = false), start = 100, frames = 5)
        assertEquals(AimPhase.GUIDE, r.aim.phase)
        assertNotNull(r.aim.target)
        assertTrue(r.aim.hint, r.aim.hint.contains("圆点"))
    }

    @Test
    fun targetIsFixedToTheSceneAndMovesWithIt() {
        val t = CompositionTracker()
        val p = person(fullBody = false)
        val before = t.run(p).aim.target!!
        // Panning the phone moves everything in the frame; the target must move by the same amount.
        val after = t.run(p.shifted(0.1f, 0.05f), start = 2000, frames = 15).aim.target!!
        assertEquals(before.x + 0.1f, after.x, 0.01f)
        assertEquals(before.y + 0.05f, after.y, 0.01f)
    }

    @Test
    fun recommendationDoesNotJumpWithJitter() {
        val t = CompositionTracker()
        val p = person(fullBody = false)
        var r = t.run(p)
        val offset = r.aim.target!!.x - r.frameSubject!!.anchor.x
        for ((i, dx) in listOf(0.01f, -0.012f, 0.008f, -0.01f, 0.012f).withIndex()) {
            r = t.feed(2000L + i * 100, p.shifted(dx, 0f))
            assertEquals(offset, r.aim.target!!.x - r.frameSubject!!.anchor.x, 1e-4f)
        }
    }

    @Test
    fun aimHoldZoomDone() {
        val t = CompositionTracker()
        val p = person(fullBody = false)
        val target = t.run(p).aim.target!!
        // Point the phone so the target is in the centre ring.
        val aimed = p.shifted(0.5f - target.x, 0.5f - target.y)
        var r = t.run(aimed, start = 2000, frames = 12)
        assertTrue("phase ${r.aim.phase}", r.aim.phase == AimPhase.HOLD || r.aim.phase == AimPhase.ZOOM || r.aim.phase == AimPhase.DONE)
        r = t.run(aimed, start = 4000, frames = 6)
        assertEquals(AimPhase.ZOOM, r.aim.phase)
        val zoom = r.aim.zoomTo!!
        assertTrue("zoom $zoom", zoom > 1.05f && zoom <= 3f)
        // After the camera zooms in, the scene is magnified around the centre.
        r = t.run(aimed.zoomed(zoom), aimInput(zoom = zoom), start = 6000, frames = 3)
        assertEquals(AimPhase.DONE, r.aim.phase)
        assertTrue(r.aim.reason, r.aim.reason.contains("拉近"))
    }

    @Test
    fun peopleInSceneStyleNeverZooms() {
        val t = CompositionTracker()
        val input = aimInput(style = PortraitStyle.SCENE)
        val p = person(fullBody = false)
        val target = t.run(p, input).aim.target!!
        val aimed = p.shifted(0.5f - target.x, 0.5f - target.y)
        val r = t.run(aimed, input, start = 2000, frames = 20)
        assertEquals(AimPhase.DONE, r.aim.phase)
        assertFalse(r.aim.reason, r.aim.reason.contains("拉近"))
    }

    @Test
    fun personLookingRightGetsRoomAhead() {
        val r = CompositionTracker().run(person(fullBody = false, lookRight = true))
        assertEquals(1, r.frameSubject!!.facing)
        // The recommended view centre is to the right of the person: space ahead of their gaze.
        assertTrue(r.aim.target!!.x > r.frameSubject!!.anchor.x)
        assertTrue(r.aim.reason, r.aim.reason.contains("朝右看"))
    }

    @Test
    fun fullBodyAsksForASlightlyLowAngleFirst() {
        // Camera looking 10° down at a full-body shot.
        val r = CompositionTracker().run(person(fullBody = true), aimInput(pitch = 10f))
        assertEquals(AimPhase.ANGLE, r.aim.phase)
        assertTrue(r.aim.angle!!.offsetDeg > 0f)
        assertTrue(r.aim.hint, r.aim.hint.contains("仰"))
        // At the right angle the assistant moves on to aiming.
        val ok = CompositionTracker().run(person(fullBody = true), aimInput(pitch = -6f), frames = 15)
        assertTrue("phase ${ok.aim.phase}", ok.aim.phase != AimPhase.ANGLE)
    }

    @Test
    fun frontCameraSkipsAngleAdvice() {
        val r = CompositionTracker().run(person(fullBody = true), aimInput(pitch = 20f, front = true))
        assertTrue(r.aim.phase != AimPhase.ANGLE)
    }

    @Test
    fun nothingToAimAtWithoutASubject() {
        val r = CompositionTracker().feed(0, null)
        assertEquals(AimPhase.IDLE, r.aim.phase)
        assertNull(r.aim.target)
    }

    @Test
    fun noRecommendationWhileThePhoneMoves() {
        val r = CompositionTracker().run(person(fullBody = false), aimInput(steady = false))
        assertEquals(AimPhase.IDLE, r.aim.phase)
    }

    @Test
    fun horizonIsAimedToAThirdLine() {
        val g = skyGrid(12)
        val y = HorizonDetector.detect(g)
        assertNotNull(y)
        assertEquals(0.5f, y!!, 0.05f)
        val subject = SubjectPicker.pick(null, emptyList(), g)!!
        assertEquals(SubjectKind.HORIZON, subject.kind)
        val t = CompositionTracker()
        var r = t.update(0, subject, null, aimInput())
        for (i in 1..6) r = t.update(i * 100L, subject, null, aimInput())
        assertTrue(r.aim.reason, r.aim.reason.contains("地平线"))
        // The target sits a third away from the horizon: centred horizontally.
        assertEquals(0.5f, r.aim.target!!.x, 1e-3f)
    }

    @Test
    fun objectRecommendationNamesTheObject() {
        val s = SubjectPicker.pick(null, listOf(ObjectBox("cup", 0.8f, RectN(0.6f, 0.1f, 0.75f, 0.25f))), null)!!
        val t = CompositionTracker()
        // Food is shot at about 45°; held upright the assistant asks for that first.
        assertEquals(AimPhase.ANGLE, t.update(0, s, null, aimInput()).aim.phase)
        val tilted = aimInput(pitch = 45f)
        var r = t.update(100, s, null, tilted)
        for (i in 2..12) r = t.update(i * 100L, s, null, tilted)
        assertTrue(r.aim.reason, r.aim.reason.contains("杯子"))
        assertEquals("美食 · 杯子", SceneAdvisor.describe(s, null, null))
    }

    private fun goodLight() = LightingAnalyzer.analyze(LumaGrid(4, 4, IntArray(16) { 130 }, 130f, 130f, 130f), null)

    @Test
    fun checksNameConcreteProblems() {
        val p = person(fullBody = false, shoulderTilt = 0.05f)
        val comp = CompositionTracker().run(p)
        val c = Checklist.build(comp, goodLight(), LevelState(6f, false, 0f, 0f), p, PoseCoach.analyze(p))
        assertTrue(c.contains(Check(false, "构图没对准")))
        assertTrue(c.contains(Check(false, "歪了6°")))
        assertTrue(c.contains(Check(true, "曝光正常")))
        assertTrue(c.any { !it.ok && it.text == "肩不平" })
        assertTrue(c.contains(Check(true, "人物完整")))
        val empty = Checklist.build(CompositionTracker().feed(0, null), goodLight(), level, null, emptyList())
        assertEquals(Check(false, "没找到主体"), empty.first())
    }

    @Test
    fun parsesAdviceWithFencesAndThousandScaleBox() {
        val text = """
            好的，下面是建议：
            ```json
            {"scene":"公园里半身人像，顺光","good":"光线柔和","problem":"人物太靠中间",
             "advice":"手机往右移一点","steps":["向右移","拉近一点"],"pose":"微微侧身",
             "crop":[100,50,700,850]}
            ```
        """.trimIndent()
        val a = CloudPrompts.parseAdvice(text, 480, 640)!!
        assertEquals("手机往右移一点", a.advice)
        assertEquals(listOf("向右移", "拉近一点"), a.steps)
        assertEquals(RectN(0.1f, 0.05f, 0.7f, 0.85f), a.crop)
    }

    @Test
    fun boxInPixelsOrFractionsIsNormalised() {
        // Values above 1000 can only be pixels of the sent image (we ask for 0..1000).
        val px = CloudPrompts.parseAdvice("""{"advice":"x","crop":[96,128,864,1152]}""", 960, 1280)!!
        assertEquals(0.1f, px.crop!!.left, 1e-4f)
        assertEquals(0.9f, px.crop!!.bottom, 1e-4f)
        val frac = CloudPrompts.parseAdvice("""{"advice":"x","crop":[0.2,0.2,0.8,0.8]}""", 480, 640)!!
        assertEquals(RectN(0.2f, 0.2f, 0.8f, 0.8f), frac.crop)
    }

    @Test
    fun wholeImageOrBrokenBoxMeansNoCrop() {
        assertNull(CloudPrompts.parseAdvice("""{"advice":"x","crop":[0,0,1000,1000]}""", 480, 640)!!.crop)
        assertNull(CloudPrompts.parseAdvice("""{"advice":"x","crop":[500,500,510,510]}""", 480, 640)!!.crop)
        assertNull(CloudPrompts.parseAdvice("""{"advice":"x","crop":"left"}""", 480, 640)!!.crop)
        assertNull(CloudPrompts.parseAdvice("对不起，我无法回答", 480, 640))
    }

    @Test
    fun parsesReview() {
        val r = CloudPrompts.parseReview("""{"good":"光线好","improve":"地平线歪了","nextTime":"拍之前看水平仪"}""")!!
        assertEquals("地平线歪了", r.improve)
    }

    @Test
    fun cloudFramingDrivesTheAimAndSkipsAngleAdvice() {
        val t = CompositionTracker()
        val p = person(fullBody = true)
        // Looking down at a full-body shot would normally trigger angle advice first.
        val input = aimInput(pitch = 15f)
        t.feed(0, p, input)
        t.aim.setExternal(ExternalFraming(Vec2(0.5f, 0.5f), 1f, "往右移", offset = Vec2(0.1f, 0.05f)))
        val r = t.feed(100, p, input)
        assertTrue(r.aim.external)
        assertEquals(AimPhase.GUIDE, r.aim.phase)
        val a = r.frameSubject!!.anchor
        assertEquals(a.x + 0.1f, r.aim.target!!.x, 1e-3f)
        assertEquals(a.y + 0.05f, r.aim.target!!.y, 1e-3f)
        assertEquals(Vec2(0.5f, 0.5f), r.aim.view)
        assertEquals("往右移", r.aim.reason)
        // Dropping it goes back to the built-in recommendation.
        t.aim.setExternal(null)
        assertFalse(t.feed(200, p, input).aim.external)
    }

    // ---- Pinning targets in space with the orientation sensor --------------------------------

    private val geometry = ViewGeometry(0.5f, 0.66f)

    /** Phone held upright facing north, turned [yawDeg] to the right and [pitchDeg] up. */
    private fun phone(yawDeg: Float = 0f, pitchDeg: Float = 0f): FloatArray {
        // Device→world for an upright phone facing north: x→east, y→up, z (towards the user)→south.
        val base = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val p = Math.toRadians(pitchDeg.toDouble())
        // Tilting the camera up rotates about the device x axis.
        val pitch = floatArrayOf(
            1f, 0f, 0f,
            0f, Math.cos(p).toFloat(), -Math.sin(p).toFloat(),
            0f, Math.sin(p).toFloat(), Math.cos(p).toFloat(),
        )
        val y = Math.toRadians(-yawDeg.toDouble())
        val yaw = floatArrayOf(
            Math.cos(y).toFloat(), -Math.sin(y).toFloat(), 0f,
            Math.sin(y).toFloat(), Math.cos(y).toFloat(), 0f,
            0f, 0f, 1f,
        )
        return mul(yaw, mul(base, pitch))
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    @Test
    fun anchorRoundTrips() {
        val r = phone(20f, 5f)
        val p = Vec2(0.3f, 0.7f)
        val back = SceneAnchor.toScreen(SceneAnchor.toWorld(p, r, geometry), r, geometry)!!
        assertEquals(p.x, back.x, 1e-4f)
        assertEquals(p.y, back.y, 1e-4f)
    }

    @Test
    fun turningRightMovesTheTargetLeft() {
        val world = SceneAnchor.toWorld(Vec2(0.5f, 0.5f), phone(), geometry)
        val after = SceneAnchor.toScreen(world, phone(yawDeg = 10f), geometry)!!
        val expected = 0.5f - Math.tan(Math.toRadians(10.0)).toFloat() / (2f * geometry.tanHalfW)
        assertEquals(expected, after.x, 1e-3f)
        assertEquals(0.5f, after.y, 1e-3f)
        // Tilting up moves it down.
        assertTrue(SceneAnchor.toScreen(world, phone(pitchDeg = 8f), geometry)!!.y > 0.5f)
        // Turned all the way round: behind the camera.
        assertNull(SceneAnchor.toScreen(world, phone(yawDeg = 170f), geometry))
    }

    private fun pinnedInput(yawDeg: Float = 0f, style: PortraitStyle = PortraitStyle.SCENE) =
        aimInput(style = style).copy(rotation = phone(yawDeg), view = geometry)

    @Test
    fun pinnedTargetSurvivesLosingTheSubject() {
        val t = CompositionTracker()
        val p = person(fullBody = false)
        var r = t.run(p, pinnedInput())
        assertEquals(AimPhase.GUIDE, r.aim.phase)
        val before = r.aim.target!!
        // The person drops out of detection while the phone is still: the target stays where it was.
        for (i in 0..20) r = t.feed(2000L + i * 100, null, pinnedInput())
        assertEquals(AimPhase.GUIDE, r.aim.phase)
        assertEquals(before.x, r.aim.target!!.x, 1e-3f)
        assertEquals(before.y, r.aim.target!!.y, 1e-3f)
    }

    @Test
    fun turningThePhoneBringsThePinnedTargetIntoTheRing() {
        val t = CompositionTracker()
        val p = person(fullBody = false)
        val target = t.run(p, pinnedInput()).aim.target!!
        // Work out how far to turn so the target reaches the centre, then turn (no subject needed).
        val yaw = Math.toDegrees(Math.atan(((target.x - 0.5f) * 2f * geometry.tanHalfW).toDouble())).toFloat()
        val world = SceneAnchor.toWorld(target, phone(), geometry)
        val turned = SceneAnchor.toScreen(world, phone(yawDeg = yaw), geometry)!!
        assertEquals(0.5f, turned.x, 1e-3f)
        var r = t.feed(3000, null, pinnedInput(yawDeg = yaw))
        // Vertical offset is untouched by a pure turn; only check the horizontal part moved to centre.
        assertEquals(0.5f, r.aim.target!!.x, 1e-3f)
        assertTrue(r.aim.world != null)
    }

    @Test
    fun cloudFramingPinnedInSpaceWithoutASubject() {
        val t = CompositionTracker()
        val input = pinnedInput()
        t.run(person(fullBody = false), input)
        val world = SceneAnchor.toWorld(Vec2(0.7f, 0.4f), phone(), geometry)
        t.aim.setExternal(ExternalFraming(Vec2(0.6f, 0.6f), 1f, "向右一点", world = world))
        val r = t.feed(3000, null, input)
        assertTrue(r.aim.external)
        assertEquals(0.7f, r.aim.target!!.x, 1e-3f)
        assertEquals(0.4f, r.aim.target!!.y, 1e-3f)
    }
}
