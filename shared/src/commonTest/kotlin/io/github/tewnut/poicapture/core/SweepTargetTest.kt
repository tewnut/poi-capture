package io.github.tewnut.poicapture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepTargetTest {
    @Test fun houseNumbersAreDenserAndSlowerThanShops() {
        val shops = CapturePlan.of(6.0)
        val numbers = CapturePlan.of(6.0, target = SweepTarget.ADDRESSES)
        assertEquals(SweepTarget.SHOPS, shops.target)
        assertTrue(numbers.photoEveryM < shops.photoEveryM * 0.6, "${numbers.photoEveryM} vs ${shops.photoEveryM}")
        assertTrue(numbers.maxSpeedKmh <= shops.maxSpeedKmh, "${numbers.maxSpeedKmh} vs ${shops.maxSpeedKmh}")
    }

    @Test fun houseNumbersAllowUpTo20KmhOnACityStreet() {
        val n = CapturePlan.of(6.0, target = SweepTarget.ADDRESSES)
        assertEquals(SweepTarget.ADDRESSES_SPEED_CAP_KMH, n.maxSpeedKmh)            // 16 mm at 1/500 s allows 29: capped
        assertEquals(29, n.blurLimitKmh)
        // the budget is a smear on the wall: the same for close fronts, portrait or landscape
        val close = CapturePlan.of(3.0, CapturePlan.DEFAULT_HFOV_PORTRAIT, 2250, target = SweepTarget.ADDRESSES)
        assertEquals(29, close.blurLimitKmh)
        assertEquals(SweepTarget.ADDRESSES_SPEED_CAP_KMH, close.maxSpeedKmh)
    }

    @Test fun signsAreShotByDistance() {
        val p = CapturePlan.of(6.0, target = SweepTarget.SIGNS)
        assertEquals(SweepTarget.SIGN_EVERY_M, p.photoEveryM)
        // the trigger counts distance / coverage up to `latest`: one photo every SIGN_EVERY_M
        assertEquals(SweepTarget.SIGN_EVERY_M, p.coverageM * SweepTarget.SIGNS.latest, 1e-9)
        assertTrue(p.maxSpeedKmh in 20..CapturePlan.SPEED_CAP_KMH)
        assertTrue(SweepTarget.SIGNS.byDistance)
    }

    @Test fun anUnknownTargetIsShops() {
        assertEquals(SweepTarget.SHOPS, SweepTarget.of(null))
        assertEquals(SweepTarget.SHOPS, SweepTarget.of("whatever"))
        assertEquals(SweepTarget.SIGNS, SweepTarget.of("signs"))
    }
}
