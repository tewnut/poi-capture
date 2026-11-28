package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignBoostTest {
    private val s = 1_000_000_000L
    private fun box(x0: Float, y0: Float, w: Float, score: Float = 0.8f) = SignBox(x0, y0, x0 + w, y0 + w, score, 0)

    @Test fun denserWhileASignIsInViewThenBackToNormal() {
        val b = SignBoost()
        assertEquals(1.0, b.speedUp(0), 0.0)
        b.onFrame(10 * s, listOf(box(0.6f, 0.3f, 0.02f)))
        assertEquals(b.factor, b.speedUp(11 * s), 0.0)
        assertEquals(1.0, b.speedUp(13 * s), 0.0)        // nothing seen for more than 2 s
    }

    @Test fun weakBoxesDoNotCount() {
        val b = SignBoost()
        assertFalse(b.onFrame(s, listOf(box(0.95f, 0.3f, 0.04f, score = 0.3f))))
        assertFalse(b.active(s))
    }

    @Test fun oneExtraPhotoWhenANearSignReachesTheEdge() {
        val b = SignBoost()
        assertFalse(b.onFrame(s, listOf(box(0.7f, 0.3f, 0.05f))))              // near, not at the edge yet
        assertFalse(b.onFrame(s, listOf(box(0.93f, 0.3f, 0.02f))))             // at the edge but still far (small)
        assertTrue(b.onFrame(2 * s, listOf(box(0.90f, 0.3f, 0.06f))))          // near and leaving on the right
        assertFalse(b.onFrame(2 * s + s / 2, listOf(box(0.92f, 0.3f, 0.06f)))) // not again within 1.5 s
        assertTrue(b.onFrame(4 * s, listOf(box(0.4f, 0.02f, 0.05f))))          // a gantry sign at the top
        assertEquals(2, b.edgeShots)
    }
}
