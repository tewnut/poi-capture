package io.github.tewnut.poicapture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The blurry flag of a house-number ride against what was readable on the rides of 24 Nov 2026 (all at 1/500 s). */
class BlurBudgetTest {
    private val exposure = 1 / 500.0

    /** Portrait 2160 x 3840, 42.4° across, as on all eleven rides. */
    private fun motionBlurry(kmh: Double, facadeM: Double, widthPx: Int = 2160, hFov: Double = 42.4,
                             target: SweepTarget = SweepTarget.ADDRESSES): Boolean {
        val motion = BlurCheck.motionPx(kmh / 3.6, exposure, hFov, widthPx, facadeM)
        return BlurCheck.judge(motion, BlurCheck.maxMotionPx(widthPx, hFov, facadeM, target), null, emptyList()).motion
    }

    @Test fun nguyenThanhSonIsNotBlurryAnyMore() {
        // 21-24 km/h, fronts assumed 4 m: the old budget (8 px at 2560 → 6.8 px) flagged 32 photos of 38, yet every
        // number was read. 24 km/h is 9.3 px of smear there.
        val motion = BlurCheck.motionPx(24 / 3.6, exposure, 42.4, 2160, 4.0)
        assertEquals(9.3, motion, 0.1)
        assertTrue(motion > BlurCheck.maxMotionPx(2160))
        assertFalse(motionBlurry(24.0, 4.0))
        assertFalse(motionBlurry(27.0, 4.0))                    // Đồng Văn Cống: 226 read at 28 km/h
        assertEquals(13.3, BlurCheck.smearMm(24 / 3.6, exposure), 0.05)
    }

    @Test fun past29KmhTheNumberIsAtRisk() {
        // Nguyễn Thị Định at 30 km/h: blue enamel still read, small engraved / dark plates lost
        assertTrue(motionBlurry(30.0, 6.0))
        assertFalse(motionBlurry(28.0, 6.0))
    }

    @Test fun theVerdictDoesNotDependOnHowThePhoneIsHeldOrTheAssumedDistance() {
        for (d in listOf(3.0, 4.0, 6.0, 8.0)) {
            assertEquals(motionBlurry(30.0, d), motionBlurry(30.0, d, 3840, 69.1), "$d m")
            assertFalse(motionBlurry(25.0, d, 3840, 69.1))
            assertTrue(motionBlurry(32.0, d))
        }
    }

    @Test fun shopsKeepTheirPixelBudget() {
        assertEquals(8.0, BlurCheck.maxMotionPx(2560, 70.0, 6.0, SweepTarget.SHOPS), 1e-9)
        assertEquals(BlurCheck.maxMotionPx(2160), BlurCheck.maxMotionPx(2160, 42.4, 4.0, SweepTarget.SHOPS), 1e-9)
    }

    @Test fun theRiderIsToldOnlyWhenRidingTooFastOrWhenEveryPhotoIsSoft() {
        val ok = BlurCheck.Verdict(motion = false, soft = false)
        val fast = BlurCheck.Verdict(motion = true, soft = false)
        val soft = BlurCheck.Verdict(motion = false, soft = true)
        assertFalse(BlurCheck.warnRider(listOf(ok, ok, soft)))          // a truck passing close
        assertFalse(BlurCheck.warnRider(listOf(ok, soft, soft)))
        assertTrue(BlurCheck.warnRider(listOf(soft, soft, soft)))       // a vibrating mount, a dirty lens
        assertFalse(BlurCheck.warnRider(listOf(ok, ok, fast)))
        assertTrue(BlurCheck.warnRider(listOf(fast, ok, fast)))
        assertTrue(BlurCheck.warnRider(listOf(fast, soft, fast)))
        assertTrue(BlurCheck.judge(null, 1.0, 100.0, List(6) { 1000.0 }).soft)
        assertFalse(BlurCheck.judge(null, 1.0, 100.0, List(4) { 1000.0 }).soft)   // too few to judge
    }
}
