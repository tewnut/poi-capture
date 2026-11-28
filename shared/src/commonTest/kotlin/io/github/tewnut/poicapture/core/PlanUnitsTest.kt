package io.github.tewnut.poicapture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Unit numbers read on a floor plan (docs/indoor.md §7 `units`), the nearest unit, the walk's diagnostics summary. */
class PlanUnitsTest {
    @Test fun unitLabels() {
        for ((w, lvl) in listOf("L2-15" to "2", "B1-03" to "B1", "l3.k05" to "3", "215" to "2", "2.15" to "2", "K-05" to "2",
                "K05" to null, "15" to "3", "215A" to "2", "SH12" to "1", "(L2-15)" to "2")) {
            assertNotNull(PlanUnits.unitOf(w, level = lvl), "$w on level $lvl")
        }
        assertEquals("L2-15", PlanUnits.unitOf("l2–15", level = "2"))
        assertEquals("SH 12", PlanUnits.unitOf("SH", "12", "2"))
        assertEquals("K 5", PlanUnits.unitOf("k", "5", null))
    }

    @Test fun notUnits() {
        for ((w, lvl) in listOf("45K" to "2", "299.000" to "2", "1.500đ" to null, "0909123456" to "2", "2019" to "2",
                "1998" to null, "10:00" to "2", "50%" to null, "315" to "2", "3.15" to "2", "L2" to "2", "B1" to "B1",
                "ZARA" to "2", "WC" to "2", "TEL" to null, "$5" to null)) {
            assertNull(PlanUnits.unitOf(w, level = lvl), "$w on level $lvl")
        }
        assertNull(PlanUnits.unitOf("TEL", "19", null), "a phone, not a unit")
        assertNull(PlanUnits.unitOf("HOTLINE", "1900", null))
    }

    @Test fun readsALineOfWordsWithTheirBoxes() {
        fun w(t: String, l: Int, top: Int, r: Int, b: Int) = PlanUnits.Word(t, intArrayOf(l, top, r, b))
        val units = PlanUnits.read(listOf(
            listOf(w("L2-15", 100, 200, 160, 220), w("ZARA", 170, 200, 240, 220)),
            listOf(w("SH", 400, 500, 420, 516), w("12", 424, 500, 440, 516)),
            listOf(w("Hotline", 10, 10, 60, 20), w("0909123456", 70, 10, 160, 20)),
        ), "2")
        assertEquals(listOf("L2-15", "SH 12"), units.map { it.unit })
        assertEquals(130.0, units[0].x); assertEquals(210.0, units[0].y); assertEquals(20.0, units[0].h)
        assertEquals(420.0, units[1].x)
    }

    @Test fun nearestUnitToThePosition() {
        val o = LatLon(10.8, 106.74)
        val t = PlanToMap(o, 0.1, 0.0)                  // 10 px = 1 m, north up
        val units = listOf(PlanUnit("L2-01", 100.0, 100.0, 12.0), PlanUnit("L2-02", 300.0, 100.0, 12.0))
        val at = t.toMap(280.0, 120.0)
        val (u, d) = assertNotNull(PlanUnits.nearest(units, t, at))
        assertEquals("L2-02", u.unit)
        assertEquals(2.83, d, 0.05)
        assertNull(PlanUnits.nearest(units, t, t.toMap(3000.0, 3000.0)), "too far from any unit")
    }

    @Test fun diagnosticsSummary() {
        val d = IndoorDiag()
        repeat(10) { d.step("detector") }
        repeat(3) { d.step("accelerometer") }
        d.anchor("gps", 20.0, 0.0, anchoredBefore = false)     // the first fixes a guess: no correction
        d.anchor("tap", 4.0, 80.0, anchoredBefore = true)      // 5 %
        d.anchor("plan", -2.0, 20.0, anchoredBefore = true)    // 10 %
        d.anchor("tap", 1.0, 2.0, anchoredBefore = true)       // too short for a %
        d.line("lookup"); d.line("lookup"); d.line("pdr")
        val s = d.summary()
        assertEquals(mapOf("step" to 13, "anchor" to 4, "lookup" to 2, "pdr" to 1), s["lines"])
        assertEquals(mapOf("detector" to 10, "accelerometer" to 3), s["steps"])
        assertEquals(mapOf("gps" to 1, "tap" to 2, "plan" to 1), s["anchors"])
        assertEquals(3, s["corrections"])
        assertEquals(2.3, s["meanCorrectionM"])
        assertEquals(7.5, s["meanCorrectionPct"])
        assertNull(IndoorDiag().summary()["meanCorrectionM"])
    }
}

class PdrEventsTest {
    @Test fun anchorsReportTheirCorrections() {
        val o = LatLon(10.8, 106.74)
        val f = LocalFrame(o)
        val p = Pdr(f)
        val events = mutableListOf<String>()
        p.onEvent = { e, _ -> events += e }
        p.alignTo(0.0, raw = 0.0)
        p.onYaw(0.0)
        repeat(40) { p.onStep() }                      // 28 m north by the steps
        p.anchor(f.ll(Xy(0.0, 32.0)))                  // really 32 m: longer steps
        assertEquals(listOf("restep"), events.filter { it == "restep" })
    }

    @Test fun wallClampsAreReported() {
        val o = LatLon(10.8, 106.74)
        val f = LocalFrame(o)
        val ring = listOf(Xy(-10.0, -10.0), Xy(10.0, -10.0), Xy(10.0, 10.0), Xy(-10.0, 10.0)).map { f.ll(it) }
        val p = Pdr(f, footprint = listOf(ring))
        val events = mutableListOf<Pair<String, Map<String, Double>>>()
        p.onEvent = { e, v -> events += e to v }
        p.alignTo(90.0, raw = 0.0); p.onYaw(0.0)
        repeat(20) { p.onStep() }                      // 14 m east: the wall at 10 m
        p.onYaw(180.0)                                 // back west, inside again
        p.onStep()
        val clamp = events.firstOrNull { it.first == "clamp" }
        assertNotNull(clamp)
        assertEquals(true, clamp.second.getValue("pushedM") > 2.0)
        repeat(40) { p.onYaw(0.0); p.onStep() }        // and out through the wall for good
        assertEquals(true, events.any { it.first == "release" })
    }
}
