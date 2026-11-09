package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurCheckTest {
    @Test fun motionSmearFollowsSpeedExposureAndDistance() {
        // 20 km/h, 1/500 s, 70° lens, 2560 px, shops 6 m away: about 3 px, fine
        val fast = BlurCheck.motionPx(20 / 3.6, 1 / 500.0, 70.0, 2560, 6.0)
        assertEquals(3.4, fast, 0.2)
        assertFalse(BlurCheck.blurry(fast, 2560, null, emptyList()))
        // same ride in the shade at 1/120 s: about 14 px, smeared
        val slow = BlurCheck.motionPx(20 / 3.6, 1 / 120.0, 70.0, 2560, 6.0)
        assertTrue(slow > 12)
        assertTrue(BlurCheck.blurry(slow, 2560, null, emptyList()))
    }

    @Test fun aSoftPhotoAmongSharpOnesIsFlagged() {
        val sharp = checker(16, 16, 255)
        val soft = checker(16, 16, 40)
        val s = BlurCheck.sharpness(sharp, 16, 16)
        val recent = List(6) { s }
        assertFalse(BlurCheck.blurry(null, 2560, s, recent))
        assertTrue(BlurCheck.blurry(null, 2560, BlurCheck.sharpness(soft, 16, 16), recent))
        assertFalse(BlurCheck.blurry(null, 2560, BlurCheck.sharpness(soft, 16, 16), recent.take(3)))   // too few to judge
    }

    private fun checker(w: Int, h: Int, contrast: Int) = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) contrast else 0 }
}
