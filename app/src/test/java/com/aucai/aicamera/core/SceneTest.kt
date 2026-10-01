package com.aucai.aicamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    /** A 16x24 grid: [top] colour above row [edge], [bottom] below. */
    private fun grid(top: Int, bottom: Int, edge: Int = 10, w: Int = 16, h: Int = 24): LumaGrid {
        val colours = IntArray(w * h) { if (it / w < edge) top else bottom }
        val luma = IntArray(w * h) {
            val c = colours[it]
            (299 * ((c shr 16) and 0xff) + 587 * ((c shr 8) and 0xff) + 114 * (c and 0xff)) / 1000
        }
        return LumaGrid(w, h, luma, 128f, 128f, 128f, colours)
    }

    private val blue = rgb(100, 160, 230)
    private val leaf = rgb(70, 130, 50)
    private val wall = rgb(200, 195, 190)
    private val orange = rgb(240, 120, 40)
    private val dark = rgb(40, 35, 30)

    private fun evidence(
        grid: LumaGrid? = null,
        ev: Float? = 12f,
        classes: List<ClassifierHit> = emptyList(),
        objects: List<ObjectBox> = emptyList(),
        person: Boolean = false,
        mean: Float = 128f,
    ) = SceneEvidence(person, objects, classes, grid?.let { ColorStats.from(it) } ?: ColorStats.NONE, mean, ev)

    private fun hit(index: Int, name: String, score: Float) = ClassifierHit(index, name, score)

    @Test
    fun coloursAreMeasured() {
        val c = ColorStats.from(grid(blue, leaf))
        assertTrue(c.skyBlue > 0.9f)
        assertTrue(c.green > 0.5f)
        assertEquals(0f, ColorStats.from(grid(wall, wall)).skyBlue, 1e-6f)
    }

    @Test
    fun peopleAnimalsAndFoodComeFirst() {
        assertEquals(SceneKind.PORTRAIT, SceneRecognizer.recognize(evidence(person = true)).kind)
        val dog = SceneRecognizer.recognize(
            evidence(objects = listOf(ObjectBox("dog", 0.8f, RectN(0.2f, 0.3f, 0.7f, 0.9f))), classes = listOf(hit(207, "golden retriever", 0.6f))),
        )
        assertEquals(SceneGuess(SceneKind.DOG, "金毛"), dog)
        assertEquals(SceneGuess(SceneKind.CAT, "狸花猫"), SceneRecognizer.recognize(evidence(classes = listOf(hit(281, "tabby", 0.5f)))))
        assertEquals(SceneGuess(SceneKind.FOOD, "披萨"), SceneRecognizer.recognize(evidence(classes = listOf(hit(963, "pizza", 0.5f)))))
        // A breed the model is unsure of is not named.
        val unsure = SceneRecognizer.recognize(evidence(classes = listOf(hit(207, "golden retriever", 0.25f), hit(208, "Labrador retriever", 0.2f))))
        assertEquals(SceneGuess(SceneKind.DOG, null), unsure)
    }

    @Test
    fun flowersAreColourAmongLeaves() {
        // Pink blooms among leaves, below the sky band.
        val pink = rgb(230, 80, 150)
        val colours = IntArray(16 * 24) { i -> if (i / 16 >= 10 && (i % 16) in 4..9) pink else leaf }
        val g = LumaGrid(16, 24, IntArray(16 * 24) { 120 }, 128f, 128f, 128f, colours)
        assertEquals(SceneKind.FLOWER, SceneRecognizer.recognize(evidence(g, ev = 12f)).kind)
    }

    @Test
    fun skyOnlyCountsOutdoors() {
        assertEquals(SceneKind.BLUE_SKY, SceneRecognizer.recognize(evidence(grid(blue, leaf), ev = 13f)).kind)
        // A blue wall in a room is not sky.
        assertEquals(SceneKind.INDOOR, SceneRecognizer.recognize(evidence(grid(blue, wall), ev = 6f)).kind)
        assertEquals(SceneKind.GREENERY, SceneRecognizer.recognize(evidence(grid(wall, leaf, edge = 4), ev = 12f)).kind)
        assertEquals(SceneKind.SUNSET, SceneRecognizer.recognize(evidence(grid(orange, dark), ev = 10f)).kind)
        assertEquals(SceneKind.SNOW, SceneRecognizer.recognize(evidence(grid(blue, rgb(240, 240, 245), edge = 6), ev = 14f)).kind)
        // White walls indoors are not snow.
        assertEquals(SceneKind.INDOOR, SceneRecognizer.recognize(evidence(grid(wall, rgb(240, 240, 245)), ev = 7f)).kind)
    }

    @Test
    fun nightDocumentsWaterAndBuildings() {
        assertEquals(SceneKind.NIGHT, SceneRecognizer.recognize(evidence(ev = 2f)).kind)
        assertEquals(SceneGuess(SceneKind.DOCUMENT, "菜单"), SceneRecognizer.recognize(evidence(classes = listOf(hit(922, "menu", 0.5f)), ev = 7f)))
        assertEquals(SceneGuess(SceneKind.WATER, "海边"), SceneRecognizer.recognize(evidence(classes = listOf(hit(978, "seashore", 0.5f)))))
        assertEquals(SceneGuess(SceneKind.BUILDING, "教堂"), SceneRecognizer.recognize(evidence(classes = listOf(hit(497, "church", 0.4f)))))
        // Outdoors with nothing in particular.
        val grey = rgb(120, 120, 125)
        assertEquals(SceneKind.OUTDOOR, SceneRecognizer.recognize(evidence(grid(grey, grey), ev = 12f)).kind)
        // Unknown brightness and nothing to go on.
        assertEquals(SceneKind.UNKNOWN, SceneRecognizer.recognize(evidence(grid(wall, wall), ev = null)).kind)
    }

    @Test
    fun badgeChangesOnlyWhenANewSceneHolds() {
        val t = SceneTracker()
        repeat(10) { t.update(SceneGuess(SceneKind.FOOD, "披萨")) }
        assertEquals(SceneGuess(SceneKind.FOOD, "披萨"), t.update(SceneGuess(SceneKind.FOOD, "披萨")))
        // One odd frame changes nothing.
        assertEquals(SceneKind.FOOD, t.update(SceneGuess(SceneKind.INDOOR)).kind)
        // A few frames of something else do.
        var g = t.update(SceneGuess(SceneKind.CAT))
        repeat(6) { g = t.update(SceneGuess(SceneKind.CAT, "狸花猫")) }
        assertEquals(SceneGuess(SceneKind.CAT, "狸花猫"), g)
        assertNotEquals(SceneKind.FOOD, g.kind)
    }

    @Test
    fun describeNamesSceneShotAndLight() {
        assertEquals("人像 · 半身", SceneAdvisor.describe(SceneGuess(SceneKind.PORTRAIT), ShotType.HALF_BODY, null))
        assertEquals("狗 · 柯基", SceneAdvisor.describe(SceneGuess(SceneKind.DOG, "柯基"), null, null))
        assertEquals("没认出场景", SceneAdvisor.describe(SceneGuess(SceneKind.UNKNOWN), null, null))
        assertNull(SceneRecognizer.tip(SceneKind.FOOD))
        assertTrue(SceneRecognizer.tip(SceneKind.NIGHT)!!.contains("拿稳"))
    }

    @Test
    fun scenesPickTheirLook() {
        assertEquals(Filter.FOOD, Filter.forScene(SceneKind.FOOD))
        assertEquals(Filter.SKY, Filter.forScene(SceneKind.BLUE_SKY))
        assertEquals(Filter.PET, Filter.forScene(SceneKind.DOG))
        assertEquals(Filter.NONE, Filter.forScene(SceneKind.UNKNOWN))
    }
}
