package com.aucai.aicamera.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {

    private fun rgb(c: Int) = Triple((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)

    private fun grey(v: Int) = (0xff shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun noneIsIdentityAndMonoIsGrey() {
        assertTrue(PixelLook(ColorMatrices.forFilter(Filter.NONE), 0f).isIdentity)
        val px = intArrayOf(0xFFC83214.toInt())
        PixelLook(ColorMatrices.forFilter(Filter.MONO), 0f).apply(px)
        val (r, g, b) = rgb(px[0])
        assertEquals(r, g)
        assertEquals(g, b)
        // Alpha is kept.
        assertEquals(0xff, (px[0] ushr 24))
    }

    @Test
    fun foodFilterWarmsAndConcatWithIdentityIsNoOp() {
        val px = intArrayOf(grey(128))
        PixelLook(ColorMatrices.forFilter(Filter.FOOD), 0f).apply(px)
        val (r, _, b) = rgb(px[0])
        assertTrue(r > b)
        val m = ColorMatrices.forFilter(Filter.VIVID)
        assertArrayEquals(m, ColorMatrices.concat(ColorMatrices.IDENTITY, m), 1e-5f)
        assertArrayEquals(m, ColorMatrices.concat(m, ColorMatrices.IDENTITY), 1e-5f)
    }

    @Test
    fun fillLightLiftsShadowsMostAndKeepsOrder() {
        val values = (0..255).toList()
        val px = values.map { grey(it) }.toIntArray()
        PixelLook(null, 0.6f).apply(px)
        val out = px.map { rgb(it).first }
        for (i in 1 until out.size) assertTrue("not monotonic at $i", out[i] >= out[i - 1])
        assertEquals(0, out[0])
        assertEquals(255, out[255])
        assertTrue(out[40] - 40 > 25)
        assertTrue(out[40] - 40 > out[220] - 220)
        // Colours keep their hue: a dark red stays red.
        val red = intArrayOf((0xff shl 24) or (60 shl 16) or (20 shl 8) or 20)
        PixelLook(null, 0.6f).apply(red)
        val (r, g, b) = rgb(red[0])
        assertTrue(r > 60 && r > 2 * g && g == b)
    }

    @Test
    fun exifOrientationMapsCrops() {
        val w = 4000
        val h = 3000
        assertArrayEquals(intArrayOf(10, 20, 30, 40), ExifOrientation.uprightToRaw(10, 20, 30, 40, 1, w, h))
        // Rotated 90° clockwise: the upright top-left quarter is the stored bottom-left.
        assertArrayEquals(intArrayOf(0, 1500, 2000, 3000), ExifOrientation.uprightToRaw(0, 0, 1500, 2000, 6, w, h))
        // Rotated 270°: the upright top-left quarter is the stored top-right.
        assertArrayEquals(intArrayOf(2000, 0, 4000, 1500), ExifOrientation.uprightToRaw(0, 0, 1500, 2000, 8, w, h))
        assertArrayEquals(intArrayOf(3900, 2900, 4000, 3000), ExifOrientation.uprightToRaw(0, 0, 100, 100, 3, w, h))
        // The whole picture is the whole stored image in every orientation.
        for (o in 1..8) {
            val uw = if (ExifOrientation.transposes(o)) h else w
            val uh = if (ExifOrientation.transposes(o)) w else h
            assertArrayEquals("orientation $o", intArrayOf(0, 0, w, h), ExifOrientation.uprightToRaw(0, 0, uw, uh, o, w, h))
        }
    }

    private val done = AimState(AimPhase.DONE, target = Vec2(0.5f, 0.5f))

    private fun food(box: RectN = RectN(0.3f, 0.35f, 0.7f, 0.65f)) =
        FrameSubject(SubjectKind.OBJECT, "蛋糕", box.center, box, group = ObjectGroup.FOOD)

    @Test
    fun foodIsCroppedSquareInsideTheFrame() {
        val plan = AutoCrop().update(done, food(), 0.75f, PortraitStyle.CLOSE)
        assertNotNull(plan)
        plan!!
        assertEquals("1:1", plan.label)
        assertEquals(plan.rect.height, plan.rect.width * 0.75f, 1e-3f)
        assertTrue(plan.rect.left >= 0f && plan.rect.top >= 0f && plan.rect.right <= 1f && plan.rect.bottom <= 1f)
        // The food is inside the crop.
        assertTrue(plan.rect.left <= 0.3f && plan.rect.right >= 0.7f)
    }

    @Test
    fun landscapeHorizonIsCroppedWide() {
        val horizon = FrameSubject(SubjectKind.HORIZON, "地平线", Vec2(0.5f, 0.6f), null)
        val plan = AutoCrop().update(done, horizon, 4f / 3f, PortraitStyle.SCENE)!!
        assertEquals("16:9", plan.label)
        assertEquals(16f / 9f, plan.rect.width * (4f / 3f) / plan.rect.height, 1e-3f)
    }

    @Test
    fun cloudFramingIsCroppedAroundTheTarget() {
        val aim = AimState(AimPhase.DONE, target = Vec2(0.5f, 0.5f), view = Vec2(0.8f, 0.9f), external = true)
        val plan = AutoCrop().update(aim, null, 0.75f, PortraitStyle.CLOSE)!!
        assertEquals(0.5f, plan.rect.center.x, 1e-3f)
        assertEquals(0.5f, plan.rect.center.y, 1e-3f)
        assertTrue(plan.rect.width <= 1f && plan.rect.height <= 1f)
    }

    private fun lighting(
        mean: Float = 130f,
        subjectLuma: Float? = null,
        backlit: Boolean = false,
        highRatio: Float = 0f,
        lowRatio: Float = 0f,
        face: Boolean = true,
    ) = LightingResult(
        mean, FloatArray(32), BooleanArray(0), 0, 0, null, highRatio, lowRatio, subjectLuma, backlit, false, emptyList(),
        subjectIsFace = face && subjectLuma != null,
    )

    /** A person facing the camera; [faceWidth] is the eye-to-eye-ish width of the face landmarks. */
    private fun person(faceWidth: Float = 0.1f): PoseFrame {
        val pts = MutableList(33) { Landmark(0.5f, 0.5f, 0f, 0f) }
        val h = faceWidth / 2f
        for (i in 0..10) pts[i] = Landmark(0.5f + (if (i % 2 == 0) h else -h), 0.2f + i * 0.005f, 0f, 0.99f)
        pts[PoseIdx.LEFT_SHOULDER] = Landmark(0.62f, 0.3f, 0f, 0.99f)
        pts[PoseIdx.RIGHT_SHOULDER] = Landmark(0.38f, 0.3f, 0f, 0.99f)
        return PoseFrame(pts, 0.75f)
    }

    @Test
    fun fillFlashOnlyForNearPeopleInBacklightOrDark() {
        val backlit = lighting(subjectLuma = 60f, backlit = true)
        assertTrue(LookAdvisor.wantsFlash(backlit, person(), front = false, hasFlash = true))
        assertFalse(LookAdvisor.wantsFlash(backlit, person(), front = false, hasFlash = false))
        // Too far away for a phone flash.
        assertFalse(LookAdvisor.wantsFlash(backlit, person(faceWidth = 0.02f), front = false, hasFlash = true))
        // No person: no flash (food and pets look bad with it).
        assertFalse(LookAdvisor.wantsFlash(backlit, null, front = false, hasFlash = true))
        // Front camera: the screen only lights the face in the dark.
        assertFalse(LookAdvisor.wantsFlash(lighting(), person(), front = true, hasFlash = true))
        assertTrue(LookAdvisor.wantsFlash(lighting(mean = 35f), person(), front = true, hasFlash = true))
        // With the flash, the software fill is gentler.
        assertTrue(LookAdvisor.softFill(backlit, true, true) < LookAdvisor.softFill(backlit, true, false))
    }

    @Test
    fun exposureFollowsTheFaceThenTheScene() {
        assertEquals(ExposureNeed.UP, LookAdvisor.exposureNeed(lighting(subjectLuma = 70f), person = true))
        assertEquals(ExposureNeed.DOWN, LookAdvisor.exposureNeed(lighting(subjectLuma = 220f), person = true))
        assertEquals(ExposureNeed.OK, LookAdvisor.exposureNeed(lighting(subjectLuma = 140f), person = true))
        assertEquals(ExposureNeed.DOWN, LookAdvisor.exposureNeed(lighting(mean = 150f, highRatio = 0.2f), person = false))
        assertEquals(ExposureNeed.UP, LookAdvisor.exposureNeed(lighting(mean = 60f), person = false))
        // Dark clothes are not a reason to brighten the picture: only the face counts.
        assertEquals(ExposureNeed.OK, LookAdvisor.exposureNeed(lighting(subjectLuma = 50f, face = false), person = true))
        // A small backlit figure: do not darken for the sky.
        assertEquals(
            ExposureNeed.OK,
            LookAdvisor.exposureNeed(lighting(mean = 150f, highRatio = 0.2f, subjectLuma = 50f, backlit = true, face = false), person = true),
        )
        // Night: leave it to the camera.
        assertEquals(ExposureNeed.OK, LookAdvisor.exposureNeed(lighting(mean = 20f), person = false))
    }

    @Test
    fun exposureStepsAThirdAtATimeWithinTwoStops() {
        val e = ExposureAssist()
        val range = -12..12
        val step = 1f / 6f
        assertEquals(2, e.update(0, ExposureNeed.UP, true, range, step))
        // Waits for the camera to settle.
        assertNull(e.update(100, ExposureNeed.UP, true, range, step))
        var t = 600L
        repeat(10) {
            e.update(t, ExposureNeed.UP, true, range, step)
            t += 600
        }
        assertEquals(12, e.index)
        assertNull(e.update(t, ExposureNeed.OK, true, range, step))
        // Framing lost: back to normal at once.
        assertEquals(0, e.update(t + 10, ExposureNeed.UP, false, range, step))
        assertNull(e.update(t + 20, ExposureNeed.UP, false, range, step))
    }

    private val input = GuidanceInput(null, emptyList(), null, AimInput(0.75f, null, 1f, 3f, true, PortraitStyle.CLOSE, false))

    private fun comp(phase: AimPhase) = CompositionResult(null, food(), null, AimState(phase, target = Vec2(0.5f, 0.5f)))

    @Test
    fun lookEngagesOnceFramedAndLingersBriefly() {
        val look = LookEngine(releaseMs = 1500)
        assertFalse(look.update(0, comp(AimPhase.GUIDE), null, null, input).engaged)
        val framed = look.update(100, comp(AimPhase.DONE), null, null, input)
        assertTrue(framed.engaged)
        assertEquals(Filter.FOOD, framed.filter)
        assertEquals("1:1", framed.crop?.label)
        // A wobble out of the ring does not switch it off at once...
        assertTrue(look.update(1000, comp(AimPhase.GUIDE), null, null, input).engaged)
        // ...but losing the framing for a while does.
        assertFalse(look.update(2000, comp(AimPhase.GUIDE), null, null, input).engaged)
        // Turned off in the settings.
        val off = GuidanceInput(null, emptyList(), null, input.aim, enhance = false)
        assertFalse(look.update(2100, comp(AimPhase.DONE), null, null, off).engaged)
    }
}
