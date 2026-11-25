package io.github.tewnut.poicapture.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdrTest {
    private val origin = LatLon(10.8, 106.74)
    private val frame = LocalFrame(origin)

    private fun near(expected: Double, actual: Double, tol: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")

    private fun walk(p: Pdr, rawYaw: Double, steps: Int) { p.onYaw(rawYaw); repeat(steps) { p.onStep() } }

    @Test fun straightWalk() {
        val p = Pdr(frame)
        p.alignTo(90.0, raw = 0.0)          // the phone's yaw 0 is east
        walk(p, 0.0, 100)
        near(70.0, p.position.x, 1e-6, "east")
        near(0.0, p.position.y, 1e-6, "north")
        near(70.0, p.sinceAnchorM, 1e-6, "since anchor")
        near(3.5, p.uncertaintyM, 1e-6, "5 % of 70 m")
        near(70.0, Geo.distanceM(origin, p.latLon), 0.2, "on the globe")
    }

    @Test fun aTurn() {
        val p = Pdr(frame)
        p.alignTo(0.0, raw = 0.0)
        walk(p, 0.0, 20)                    // 14 m north
        walk(p, 90.0, 10)                   // then 7 m east
        near(7.0, p.position.x, 1e-6)
        near(14.0, p.position.y, 1e-6)
        near(21.0, p.walkedM, 1e-6)
        assertEquals(30, p.steps)
    }

    @Test fun noDirectionYetDoesNotMove() {
        val p = Pdr(frame)
        assertTrue(!p.onStep())
        assertEquals(Xy(0.0, 0.0), p.position)
    }

    @Test fun anchorCorrectsDriftAndHeading() {
        // the yaw is 10° off: the walker goes due north, the steps say 10° east of north
        val p = Pdr(frame)
        p.alignTo(10.0, raw = 0.0)
        walk(p, 0.0, 86)                    // ≈ 60 m
        assertTrue(p.position.x > 9.0)
        val truth = frame.ll(Xy(0.0, 60.2))
        val r = p.anchor(truth)
        near(0.0, p.position.x, 1e-6); near(60.2, p.position.y, 1e-6)
        assertEquals(0.0, p.sinceAnchorM)
        assertNotNull(r.rotationDeg)
        near(-10.0, r.rotationDeg!!, 0.2, "rotation")
        near(0.0, p.offsetDeg, 0.2, "offset after")
        // the next stretch goes where the walker goes
        walk(p, 0.0, 50)
        near(0.0, p.position.x, 0.3)
    }

    @Test fun stepLengthReEstimatedFromTwoAnchors() {
        val p = Pdr(frame)
        p.alignTo(0.0, raw = 0.0)
        walk(p, 0.0, 80)                    // 56 m by 0.7 m steps; really 80 × 0.62 = 49.6 m
        val r = p.anchor(frame.ll(Xy(0.0, 49.6)))
        near(0.62, r.stepLengthM!!, 0.005, "step length")
        near(0.62, p.stepLengthM, 0.005)
        walk(p, 0.0, 10)
        near(49.6 + 6.2, p.position.y, 0.1)
    }

    @Test fun shortOrWindingStretchKeepsTheEstimates() {
        val p = Pdr(frame)
        p.alignTo(0.0, raw = 0.0)
        walk(p, 0.0, 6)                     // too few steps
        val r1 = p.anchor(frame.ll(Xy(0.0, 3.0)))
        assertNull(r1.stepLengthM); assertNull(r1.rotationDeg)
        walk(p, 0.0, 15); walk(p, 180.0, 15)   // out and back
        val r2 = p.anchor(frame.ll(Xy(0.0, 3.0)))
        assertNull(r2.stepLengthM); assertNull(r2.rotationDeg)
        assertEquals(0.7, p.stepLengthM)
    }

    @Test fun anchorWithDirectionRealigns() {
        val p = Pdr(frame)
        p.onYaw(123.0)
        p.anchor(origin, headingDeg = 270.0)
        assertEquals(270.0, p.headingDeg!!, 1e-9)
        walk(p, 123.0, 10)
        near(-7.0, p.position.x, 1e-6)
    }

    @Test fun clampSlidesAlongTheWall() {
        // a 20 × 20 m building, the walker in the middle heading north-east
        val sq = listOf(Xy(-10.0, -10.0), Xy(10.0, -10.0), Xy(10.0, 10.0), Xy(-10.0, 10.0)).map { frame.ll(it) }
        val p = Pdr(frame, footprint = listOf(sq))
        p.alignTo(45.0, raw = 0.0)
        walk(p, 0.0, 25)                    // 17.5 m: would end at (12.4, 12.4)
        assertTrue(p.isInside(p.position), "still inside: ${p.position}")
        assertTrue(p.position.x > 9.0 && p.position.y > 9.0, "in the corner: ${p.position}")
        // along the north wall going east: slides, stays at y ≈ 10
        val q = Pdr(frame, footprint = listOf(sq))
        q.alignTo(20.0, raw = 0.0)
        walk(q, 0.0, 14)                    // reaches the north wall
        val x0 = q.position.x
        walk(q, 0.0, 4)
        assertTrue(q.position.y < 10.0 && q.position.y > 9.0, "on the wall: ${q.position}")
        assertTrue(q.position.x > x0, "slid east")
    }

    @Test fun clampLetsTheWalkerOutAfterPushingThroughADoor() {
        val sq = listOf(Xy(-10.0, -10.0), Xy(10.0, -10.0), Xy(10.0, 10.0), Xy(-10.0, 10.0)).map { frame.ll(it) }
        val p = Pdr(frame, footprint = listOf(sq))
        p.alignTo(0.0, raw = 0.0)
        walk(p, 0.0, 60)                    // 42 m north: through the wall after RELEASE_M of pushing
        assertTrue(!p.isInside(p.position), "outside: ${p.position}")
        // outside, nothing holds the walker
        val y = p.position.y
        walk(p, 0.0, 5)
        near(y + 3.5, p.position.y, 1e-6)
    }

    @Test fun noFootprintNoClamp() {
        val p = Pdr(frame)
        assertTrue(!p.hasFootprint)
        p.alignTo(0.0, raw = 0.0)
        walk(p, 0.0, 10)
        near(7.0, p.position.y, 1e-6)
    }

    @Test fun unanchoredStartIsUnsure() {
        val p = Pdr(frame, anchored = false)
        assertEquals(Pdr.UNANCHORED_M, p.uncertaintyM)
        p.anchor(origin)
        assertEquals(Pdr.MIN_UNCERTAINTY_M, p.uncertaintyM)
    }

    // ---------- floors ----------

    private fun hPa(heightM: Double) = 1008.0 - heightM * FloorDetector.HPA_PER_M

    @Test fun floorChangeAfterAnEscalator() {
        val f = FloorDetector()
        val rnd = Random(7)
        val s = 1_000_000_000L
        var t = 0L
        val out = mutableListOf<Int>()
        fun feed(h: Double) { f.add(t, hPa(h) + rnd.nextDouble(-0.03, 0.03))?.let { out += it }; t += s }
        repeat(30) { feed(0.0) }                          // walking on the floor: noise only
        assertTrue(out.isEmpty())
        for (i in 1..30) feed(i * 5.0 / 30)               // escalator: 5 m in 30 s
        repeat(10) { feed(5.0) }                          // off the escalator
        assertEquals(listOf(1), out, "one level up, once")
        f.rebase()
        repeat(20) { feed(5.0) }
        assertEquals(listOf(1), out)
        for (i in 1..20) feed(5.0 - i * 9.5 / 20)         // down two floors by lift
        repeat(10) { feed(-4.5) }
        assertEquals(listOf(1, -2), out)
    }

    @Test fun noiseAndSmallChangesAreNotAFloor() {
        val f = FloorDetector()
        val rnd = Random(3)
        var t = 0L
        repeat(600) {
            // ±0.15 hPa spikes (doors, air conditioning) and a 1.5 m ramp (a mezzanine)
            val spike = if (it % 37 == 0) 0.15 else 0.0
            assertNull(f.add(t, hPa(minOf(1.5, it / 100.0)) + rnd.nextDouble(-0.04, 0.04) + spike))
            t += 1_000_000_000L
        }
        near(1.5, f.heightM!!, 0.5)
    }

    @Test fun levelNames() {
        assertEquals("1", Levels.name(0)); assertEquals("3", Levels.name(2)); assertEquals("B2", Levels.name(-2))
        assertEquals(-1, Levels.parse("B1")); assertEquals(0, Levels.parse("G")); assertEquals(1, Levels.parse("L2"))
        assertEquals(listOf(0, 1), Levels.osmLevels("0;1"))
        assertEquals(listOf(-2, -1, 0), Levels.osmLevels("-2-0"))
        assertEquals(-2..3, Levels.range(4, 2))
        assertEquals(0..5, Levels.range(null, null, current = 5))
    }
}
