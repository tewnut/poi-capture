package io.github.tewnut.poicapture.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetSceneTest {
    private val s = StreetScroll

    /** What the picture shows at [offset]: each item in view as (left edge, kind, taken). */
    private fun picture(offset: Float, width: Float = 300f, cam: Float = 150f) =
        s.visible(width, offset).map { p -> Triple(Math.round(p.left * 100) / 100f, p.item.kind, s.passed(cam, p) >= 0f) }

    @Test fun theLoopIsSeamless() {
        // one period on: the same items at the same places, the same ones taken, lane marks and bob in place
        for (o in listOf(0f, 37f, 200f)) assertEquals(picture(o), picture(o + s.PERIOD))
        assertEquals(s.laneShift(0f), s.laneShift(s.PERIOD), 1e-3f)
        assertEquals(s.bob(0f), s.bob(s.PERIOD), 1e-3f)
    }

    @Test fun eachTargetIsTakenOnlyOnceThePhonePassesIt() {
        val cam = 150f
        for (o in listOf(0f, 90f, 260f)) for (p in s.visible(300f, o))
            assertEquals(p.targetCentre <= cam, s.passed(cam, p) >= 0f)
        // the street has all three kinds of target the rides take: shop signs, house numbers, a traffic sign
        val kinds = s.ITEMS.map { it.kind }.toSet()
        assertTrue(StreetScroll.Kind.SHOP in kinds && StreetScroll.Kind.POLE in kinds &&
            (StreetScroll.Kind.HOUSE in kinds || StreetScroll.Kind.TUBE in kinds))
        assertTrue(s.flash(0f) == 1f && s.flash(20f) == 0f)
        assertTrue(s.framed(10f) && !s.framed(-1f) && !s.framed(51f))
    }

    @Test fun theItemsInViewCoverThePictureAndFitThePeriod() {
        for (k in 0..30) {
            val o = k * 17f
            val items = s.visible(300f, o)
            assertTrue(items.minOf { it.left } <= 60f && items.maxOf { it.left + it.item.w } >= 240f)   // at most the widest gap empty
        }
        assertTrue(s.ITEMS.all { it.x + it.w <= s.PERIOD })
        assertFalse(s.ITEMS.zipWithNext().any { (a, b) -> a.x + a.w > b.x })   // no overlap
    }

    @Test fun theStillPictureHasASignJustTaken() {
        val width = 300f
        val o = s.stillOffset(width)
        val cam = width / 2
        assertTrue(s.visible(width, o).any { s.framed(s.passed(cam, it)) })
    }
}
