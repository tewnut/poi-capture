package io.github.tewnut.poicapture.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Floor plans (docs/indoor.md §7): the plan → map transform, the first placement, the board finder. */
class FloorPlanTest {
    private val o = LatLon(10.8, 106.74)
    private val f = LocalFrame(o)

    private fun near(expected: Double, actual: Double, tol: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")
    private fun nearAngle(expected: Double, actual: Double, tol: Double, what: String = "") =
        assertTrue(Geo.angleDiff(expected, actual) <= tol, "$what: expected $expected° ± $tol, got $actual")

    @Test fun pixelsToMetresAsInTheDoc() {
        val t = PlanToMap(o, 0.1, 0.0)
        val right = f.xy(t.toMap(100.0, 0.0)); near(10.0, right.x, 1e-6, "x right = east"); near(0.0, right.y, 1e-6)
        val down = f.xy(t.toMap(0.0, 100.0)); near(0.0, down.x, 1e-6); near(-10.0, down.y, 1e-6, "y down = south")
        // turned 90° clockwise: the plan's top points east, its x axis south
        val r = PlanToMap(o, 0.1, 90.0)
        val up = f.xy(r.toMap(0.0, -100.0)); near(10.0, up.x, 1e-6, "top → east"); near(0.0, up.y, 1e-6)
        val x = f.xy(r.toMap(100.0, 0.0)); near(0.0, x.x, 1e-6); near(-10.0, x.y, 1e-6, "x → south")
    }

    @Test fun toPlanIsTheInverse() {
        val t = PlanToMap(o, 0.061, 33.5)
        for ((x, y) in listOf(0.0 to 0.0, 812.0 to 640.0, 2048.0 to 1365.0, -50.0 to 300.0)) {
            val back = t.toPlan(t.toMap(x, y))
            near(x, back.x, 1e-3, "x"); near(y, back.y, 1e-3, "y")
        }
    }

    @Test fun twoPinnedPointsGiveTheTransformBack() {
        val truth = PlanToMap(f.ll(Xy(-40.0, 25.0)), 0.07, 212.0)
        val p1 = Xy(300.0, 200.0); val p2 = Xy(1700.0, 1100.0)
        val t = assertNotNull(PlanToMap.fromTwoPoints(p1, truth.toMap(p1.x, p1.y), p2, truth.toMap(p2.x, p2.y)))
        near(0.07, t.scaleMPerPx, 1e-6, "scale")
        nearAngle(212.0, t.rotationDeg, 1e-4, "rotation")
        near(0.0, Geo.distanceM(truth.origin, t.origin), 0.01, "origin")
        assertNull(PlanToMap.fromTwoPoints(p1, o, p1, o), "the same point twice")
    }

    @Test fun pinchAndTwistKeepThePivot() {
        val t = PlanToMap(o, 0.05, 10.0)
        val pivotPx = Xy(500.0, 400.0)
        val pivot = t.toMap(pivotPx.x, pivotPx.y)
        val a = t.adjusted(pivot, 1.5, 20.0)
        near(0.075, a.scaleMPerPx, 1e-9); near(30.0, a.rotationDeg, 1e-9)
        near(0.0, Geo.distanceM(pivot, a.toMap(pivotPx.x, pivotPx.y)), 0.01, "the pivot stays")
        val m = t.moved(o, f.ll(Xy(3.0, -4.0)))
        near(5.0, Geo.distanceM(t.toMap(0.0, 0.0), m.toMap(0.0, 0.0)), 0.05, "dragged 5 m")
    }

    /** A 100 m × 40 m building whose long side runs at 30° (NNE). */
    private val ring: List<LatLon> = run {
        val c = listOf(Xy(-50.0, -20.0), Xy(50.0, -20.0), Xy(50.0, 20.0), Xy(-50.0, 20.0))
        // x axis turned to bearing 30°: rotate the east axis 60° counter-clockwise = -60° clockwise
        c.map { f.ll(rotateCw(it, -60.0)) }
    }

    @Test fun orientedBoundingBox() {
        val b = assertNotNull(PlanFit.obb(ring))
        near(0.0, Geo.distanceM(o, b.center), 0.05, "centre")
        val long = if (b.lengthM > b.widthM) b.axisDeg else b.axisDeg + 90
        near(100.0, maxOf(b.lengthM, b.widthM), 0.05); near(40.0, minOf(b.lengthM, b.widthM), 0.05)
        assertTrue(Geo.angleDiff(30.0, long) < 0.01 || Geo.angleDiff(210.0, long) < 0.01, "long axis at 30°: $long")
    }

    @Test fun planFitsTheFootprintAndTheCameraPicksItsWayUp() {
        // a 2000 × 800 px plan drawn with its long side across: its x axis must run along the building (30° or 210°)
        val fit = assertNotNull(PlanFit.fitToFootprint(2000, 800, ring, upHintDeg = 290.0))
        near(1.0, fit.coverage, 1e-6, "same shape")
        near(0.05, fit.toMap.scaleMPerPx, 1e-6, "100 m / 2000 px")
        // x axis bearing = rotation + 90: 30° → top at 300° (near the hint 290°), not 210° → top at 120°
        nearAngle(300.0, fit.toMap.rotationDeg, 0.01, "rotation")
        near(0.0, Geo.distanceM(o, fit.toMap.toMap(1000.0, 400.0)), 0.05, "plan centre on the building's")
        val flipped = assertNotNull(PlanFit.fitToFootprint(2000, 800, ring, upHintDeg = 120.0))
        nearAngle(120.0, flipped.toMap.rotationDeg, 0.01, "facing the other way")
        // a tall plan (800 × 2000): turned a quarter so its long side still runs along the building
        val tall = assertNotNull(PlanFit.fitToFootprint(800, 2000, ring, upHintDeg = 30.0))
        nearAngle(30.0, tall.toMap.rotationDeg, 0.01, "top along the long side")
        near(1.0, tall.coverage, 1e-6)
    }

    @Test fun noFootprintGuessPutsTheDotWhereTheWalkerIs() {
        val g = PlanFit.guess(2000, 1000, o, Xy(400.0, 700.0), 45.0)
        near(0.0, Geo.distanceM(o, g.toMap(400.0, 700.0)), 0.01)
        near(0.075, g.scaleMPerPx, 1e-9); near(45.0, g.rotationDeg, 1e-9)
    }

    @Test fun straightenedSizeFromTheCorners() {
        val (w, h) = PlanFit.straightSize(listOf(Xy(100.0, 100.0), Xy(3100.0, 200.0), Xy(3000.0, 2100.0), Xy(200.0, 2000.0)))
        assertTrue(w == 2048 || w == 2047, "long edge capped: $w")
        near(1900.0 / 2900.0 * w, h.toDouble(), 2.0, "aspect kept")
        assertEquals(300 to 200, PlanFit.straightSize(listOf(Xy(0.0, 0.0), Xy(300.0, 0.0), Xy(300.0, 200.0), Xy(0.0, 200.0))))
    }

    @Test fun findsABrightBoardOnADarkPhoto() {
        val w = 200; val h = 150
        val quad = listOf(Xy(40.0, 30.0), Xy(170.0, 20.0), Xy(160.0, 130.0), Xy(30.0, 120.0))
        fun inside(x: Double, y: Double): Boolean {
            for (i in 0..3) {
                val a = quad[i]; val b = quad[(i + 1) % 4]
                if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return false
            }
            return true
        }
        val img = IntArray(w * h) { i -> if (inside((i % w).toDouble(), (i / w).toDouble())) 215 + (i * 7) % 11 else 30 + (i * 13) % 9 }
        val found = assertNotNull(BoardFinder.find(img, w, h))
        for (k in 0..3) {
            near(quad[k].x, found[k].x * (w - 1), 3.0, "corner $k x"); near(quad[k].y, found[k].y * (h - 1), 3.0, "corner $k y")
        }
        // nothing but noise: no board
        assertNull(BoardFinder.find(IntArray(w * h) { (it * 31) % 7 + 100 }, w, h))
    }
}
