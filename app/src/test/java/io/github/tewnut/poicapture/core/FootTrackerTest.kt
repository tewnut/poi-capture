package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootTrackerTest {
    private val start = LatLon(10.7770, 106.7565)
    private fun fix(ll: LatLon, acc: Double, s: Double) = Fix(t = (s * 1000).toLong(), lat = ll.lat, lon = ll.lon, accuracyM = acc,
        altitudeM = null, speedMps = null, bearingDeg = null, elapsedNanos = (s * 1e9).toLong())

    @Test fun walksOnStepsAndRefusesAConfidentJump() {
        val t = FootTracker(strideM = 0.7)
        assertTrue(t.onFix(fix(start, 3.0, 0.0)))
        t.onHeading(90.0)                                   // walking east
        repeat(20) { t.onStep((1 + it) * 600_000_000L) }    // 14 m
        val est = t.estimate(13_000_000_000L)
        assertEquals(FootTracker.Source.BRIDGE, est.source)
        assertEquals(14.0, Geo.distanceM(start, est.ll!!), 0.5)
        // a fix claiming 3 m but 50 m north (an arcade's bounce): not trusted
        assertFalse(t.onFix(fix(Geo.destination(start, 0.0, 50.0), 3.0, 13.5)))
        // a fix that agrees with the steps: trusted, and it becomes the anchor
        assertTrue(t.onFix(fix(Geo.destination(start, 90.0, 15.0), 4.0, 14.0)))
        assertEquals(FootTracker.Source.GPS, t.estimate(14_500_000_000L).source)
    }

    @Test fun gpsThatKeepsAgreeingWithItselfWins() {
        val t = FootTracker()
        assertTrue(t.onFix(fix(start, 3.0, 0.0)))
        val elsewhere = Geo.destination(start, 180.0, 40.0)
        assertFalse(t.onFix(fix(elsewhere, 4.0, 1.0)))
        assertFalse(t.onFix(fix(Geo.destination(elsewhere, 10.0, 2.0), 4.0, 2.0)))
        assertTrue(t.onFix(fix(Geo.destination(elsewhere, 200.0, 2.0), 4.0, 3.0)))   // the third: the steps were wrong
    }

    @Test fun unsureAfterALongBridgeAndIndoorPlaces() {
        val t = FootTracker(strideM = 0.7)
        t.onFix(fix(start, 3.0, 0.0)); t.onHeading(0.0)
        repeat(100) { t.onStep((1 + it) * 600_000_000L) }   // 70 m without a trusted fix
        val far = t.estimate(61_000_000_000L)
        assertTrue(far.unsure)
        val near = FootTracker.Estimate(start, FootTracker.Source.GPS, 3.0, 0.0, 1.0)
        val c = PlacePosition.check(listOf(far, far, near), listOf(true, true, false))
        assertTrue(c.indoor)
        assertTrue(c.unsure)
        val ok = PlacePosition.check(listOf(near, near), listOf(false, false))
        assertFalse(ok.unsure); assertFalse(ok.indoor)
    }

    @Test fun defaultPinIsThePhotosMedoid() {
        // first photo from the mall entrance, 50 m away; the others at the shop
        val shop = Geo.destination(start, 220.0, 50.0)
        val pts = listOf(start, shop, Geo.destination(shop, 90.0, 3.0), Geo.destination(shop, 0.0, 2.0))
        val m = PlacePosition.medoid(pts)!!
        assertTrue(Geo.distanceM(m, shop) < 4.0)
    }

    @Test fun stepsFromTheAccelerometer() {
        val d = StepDetector()
        var n = 0
        var t = 0L
        repeat(500) { i ->                                   // 10 s at 50 Hz, walking at ~1.7 steps a second
            val a = 9.81 + 2.5 * kotlin.math.sin(i * 2 * Math.PI / 30.0)
            if (d.add(t, a) != null) n++
            t += 20_000_000L
        }
        assertTrue("steps $n", n in 14..19)
        assertNotNull(n)
    }
}
