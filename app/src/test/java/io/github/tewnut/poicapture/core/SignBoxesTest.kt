package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SignBoxesTest {
    private val nc = 3
    private val stride = 5 + nc

    /** One model row: centre / size in model-input pixels, objectness, class probabilities. */
    private fun row(cx: Float, cy: Float, w: Float, h: Float, obj: Float, vararg p: Float) = floatArrayOf(cx, cy, w, h, obj, *p)

    @Test fun decodesBoxesBackToFractionsOfThePhoto() {
        // a 1280x960 photo is fed at scale 0.5 (640x480, top-left, padded below)
        val out = row(320f, 240f, 64f, 48f, 0.9f, 0.1f, 0.8f, 0.05f)
        val b = SignBoxes.decode(out, stride, 0.5f, 1280, 960).single()
        assertEquals(1, b.cls)
        assertEquals(0.72f, b.score, 1e-4f)
        assertEquals(0.45f, b.x0, 1e-4f); assertEquals(0.55f, b.x1, 1e-4f)
        assertEquals(0.45f, b.y0, 1e-4f); assertEquals(0.55f, b.y1, 1e-4f)
    }

    @Test fun keepsOneBoxPerSignAndDropsWeakOnes() {
        val out = row(100f, 100f, 40f, 40f, 0.9f, 0.9f, 0f, 0f) +
            row(102f, 101f, 40f, 40f, 0.8f, 0f, 0.9f, 0f) +      // the same sign, another class: dropped (class-agnostic)
            row(400f, 100f, 40f, 40f, 0.9f, 0f, 0f, 0.6f) +       // another sign
            row(500f, 300f, 40f, 40f, 0.5f, 0.5f, 0f, 0f)         // 0.25 < KEEP
        val boxes = SignBoxes.decode(out, stride, 1f, 640, 640)
        assertEquals(listOf(0, 2), boxes.map { it.cls })
        assertTrue(boxes.all { it.score >= SignBoxes.KEEP })
    }

    @Test fun readsTheBundledClassList() {
        val f = listOf(File("src/main/assets/signs/classes.tsv"), File("app/src/main/assets/signs/classes.tsv")).first { it.exists() }
        val classes = SignBoxes.classes(f.readText())
        assertEquals(52, classes.size)
        val noParking = classes.first { it.code == "P.131a" }
        assertEquals("prohibitory", noParking.group)
        assertEquals("Cấm đỗ xe", noParking.label)
        assertTrue(classes.none { it.code.startsWith("(") })
    }
}
