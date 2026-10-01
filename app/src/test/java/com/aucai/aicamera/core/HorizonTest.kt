package com.aucai.aicamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonTest {

    /** Bright above row [edge], dark below. */
    private fun grid(edge: Int, w: Int = 16, h: Int = 24) =
        LumaGrid(w, h, IntArray(w * h) { if (it / w < edge) 210 else 70 }, 150f, 150f, 150f)

    /** Portrait, typical main camera. */
    private val view = ViewGeometry(0.5f, 0.66f)

    private fun input(pitch: Float = 0f, front: Boolean = false, flat: Boolean = false, view: ViewGeometry? = this.view) =
        AimInput(0.75f, LevelState(0f, flat, 0f, 90f, 0f, pitch), 1f, 3f, true, PortraitStyle.CLOSE, front, view = view)

    private fun confirmed(edge: Int, input: AimInput, ev: Float? = 12f): Float? {
        val t = HorizonTracker()
        var y: Float? = null
        repeat(6) { y = t.update(grid(edge), input, ev) }
        return y
    }

    @Test
    fun skylineAtOrAboveEyeLevelIsAHorizon() {
        assertNotNull(confirmed(12, input()))
        // Mountains or buildings well above eye level.
        assertNotNull(confirmed(6, input()))
        // Looking up at a skyline.
        assertNotNull(confirmed(18, input(pitch = -20f)))
    }

    @Test
    fun linesBelowEyeLevelAreNotHorizons() {
        // A table edge or the top of a cabinet, seen with the phone held level.
        assertNull(confirmed(18, input()))
        // Looking down at something: whatever line is in view is below eye level.
        assertNull(confirmed(12, input(pitch = 30f)))
        assertNull(confirmed(12, input(flat = true)))
        // Without the field of view, only a roughly level phone is trusted.
        assertNull(confirmed(12, input(pitch = 40f, view = null)))
        assertNotNull(confirmed(12, input(view = null)))
    }

    @Test
    fun dimScenesAndSelfiesHaveNoHorizon() {
        // A room is far darker than daylight, whatever the picture looks like after auto exposure.
        assertNull(confirmed(12, input(), ev = 6f))
        assertNotNull(confirmed(12, input(), ev = null))
        assertNull(confirmed(12, input(front = true)))
    }

    @Test
    fun aHorizonMustBeSeenSeveralFramesInARow() {
        val t = HorizonTracker(confirmFrames = 5)
        repeat(4) { assertNull(t.update(grid(12), input(), 12f)) }
        assertNotNull(t.update(grid(12), input(), 12f))
        // One frame without it starts over.
        assertNull(t.update(grid(0), input(), 12f))
        assertNull(t.update(grid(12), input(), 12f))
    }

    @Test
    fun elevationFollowsTiltAndFieldOfView() {
        assertEquals(0f, HorizonTracker.elevationDeg(0.5f, 0f, 0.66f), 1e-4f)
        assertEquals(-10f, HorizonTracker.elevationDeg(0.5f, 10f, 0.66f), 1e-4f)
        // The top edge is half the vertical field of view above the middle.
        assertEquals(Math.toDegrees(Math.atan(0.66)).toFloat(), HorizonTracker.elevationDeg(0f, 0f, 0.66f), 1e-3f)
    }

    @Test
    fun exposureValueTellsRoomsFromDaylight() {
        val room = SceneLight.ev100(1.8f, 20_000_000L, 400)
        val sunny = SceneLight.ev100(1.8f, 500_000L, 50)
        assertEquals(5.34f, room, 0.05f)
        assertEquals(13.66f, sunny, 0.05f)
        assertTrue(room < HorizonTracker.MIN_OUTDOOR_EV)
        assertFalse(sunny < HorizonTracker.MIN_OUTDOOR_EV)
    }
}
