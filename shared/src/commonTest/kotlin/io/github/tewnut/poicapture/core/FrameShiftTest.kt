package io.github.tewnut.poicapture.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameShiftTest {
    private val w = 320
    private val h = 180

    /** A street: vertical structures (shop borders, signs) of random width and shade, a little noise. */
    private fun street(width: Int, seed: Int): IntArray {
        val r = Random(seed)
        val col = IntArray(width)
        var x = 0
        while (x < width) { val run = 3 + r.nextInt(20); val v = 30 + r.nextInt(200); for (i in x until minOf(width, x + run)) col[i] = v; x += run }
        return IntArray(width * h) { i -> (col[i % width] + r.nextInt(7) - 3).coerceIn(0, 255) }   // index y * width + x
    }

    private fun crop(scene: IntArray, sceneW: Int, offset: Int, still: IntArray? = null, stillBand: Int = -1): IntArray =
        IntArray(w * h) { i ->
            val y = i / w; val x = i % w
            if (still != null && y * FrameShift.BANDS / h == stillBand) still[i] else scene[y * sceneW + offset + x]
        }

    @Test fun measuresTheSlideOfTheScene() {
        val scene = street(1000, 1)
        val a = FrameShift.profiles(crop(scene, 1000, 100), w, h)
        for (k in listOf(3, 17, 40, 90)) {
            val b = FrameShift.profiles(crop(scene, 1000, 100 + k), w, h)
            val s = FrameShift.between(a, b)!!
            assertEquals(k.toDouble() / (w - 1), abs(s.fraction), 0.6 / w, "shift $k")
        }
        // the other way: the opposite sign
        val back = FrameShift.between(a, FrameShift.profiles(crop(scene, 1000, 80), w, h))!!
        assertTrue(back.fraction * FrameShift.between(a, FrameShift.profiles(crop(scene, 1000, 120), w, h))!!.fraction < 0)
    }

    @Test fun oneBandRidingAlongDoesNotDecide() {
        val scene = street(1000, 2)
        val bike = crop(street(1000, 3), 1000, 0)          // a motorbike beside us: the same in both frames
        val a = FrameShift.profiles(crop(scene, 1000, 200, bike, 4), w, h)
        val b = FrameShift.profiles(crop(scene, 1000, 230, bike, 4), w, h)
        val s = FrameShift.between(a, b)!!
        assertEquals(30.0 / (w - 1), abs(s.fraction), 1.0 / w)
    }

    @Test fun aBlankFrameTellsNothing() {
        val grey = IntArray(w * h) { 128 }
        assertNull(FrameShift.between(FrameShift.profiles(grey, w, h), FrameShift.profiles(grey, w, h)))
    }

    @Test fun overlapTriggerWindow() {
        val t = OverlapTrigger()
        t.start()
        assertTrue(t.due(steady = false))                  // first photo right away
        t.shot()
        repeat(5) { t.addFrameShift(0.05) }                // 25 %: too early even when steady
        assertFalse(t.due(steady = true))
        t.addFrameShift(0.06)                              // 31 %: a steady moment is enough
        assertFalse(t.due(steady = false))
        assertTrue(t.due(steady = true))
        repeat(3) { t.addFrameShift(0.05) }                // 46 %: due whatever the shake
        assertTrue(t.due(steady = false))
        assertEquals(1.0, t.measuredShare, 1e-9)
        t.shot()
        repeat(20) { t.addFrameShift(if (it % 2 == 0) 0.1 else -0.1) }   // shaking, not moving
        assertFalse(t.due(steady = true))
        t.addSpeed(speedMps = 4.0, dtS = 0.5, coverageM = 5.0)          // 40 % from the GPS speed
        assertTrue(t.due(steady = true))
        assertFalse(t.behind)
        t.addSpeed(4.0, 0.5, 5.0)
        assertTrue(t.behind)
    }
}
