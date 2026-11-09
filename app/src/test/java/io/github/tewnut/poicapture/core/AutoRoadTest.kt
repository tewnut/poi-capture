package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Automatic road mode, heading-aware matching and U-turn detection independent of way drawing direction. */
class AutoRoadTest {
    @get:Rule val tmp = TemporaryFolder()

    // east–west main street (WAY, drawn west → east) crossed by a north–south street at lon 105.8060
    private val cross = Way(777, "Phố Cắt Ngang", "", "residential", "",
        listOf(LatLon(21.0270, 105.8060), LatLon(21.0300, 105.8060)))

    private fun step(i: Int, lon: Double, bearing: Double, lat: Double = START.lat) =
        Fix(1_800_000_000_000L + i * 1000L, lat, lon, 5.0, speedMps = 4.0, bearingDeg = bearing)

    @Test fun atAnIntersectionTheRoadAlongTheTravelDirectionWins() {
        // 4 m north of the main street, 2 m west of the cross street
        val p = LatLon(START.lat + 0.000036, 105.8060 - 0.0000193)
        assertEquals(777L, matchRoad(listOf(WAY, cross), p)!!.wayId)                       // no heading: nearest
        assertEquals(WAY.id, matchRoad(listOf(WAY, cross), p, travelBearing = 90.0)!!.wayId)
        assertEquals(777L, matchRoad(listOf(WAY, cross), p, travelBearing = 0.0)!!.wayId)
    }

    @Test fun roadSplitIntoWaysDrawnInOppositeDirectionsGivesNoFalseUTurn() {
        val west = Way(1, "Phố Dài", "", "secondary", "", listOf(LatLon(START.lat, 105.8040), LatLon(START.lat, 105.8060)))
        val eastReversed = Way(2, "Phố Dài", "", "secondary", "", listOf(LatLon(START.lat, 105.8080), LatLon(START.lat, 105.8060)))
        val p = FixProcessor(listOf(west, eastReversed), Side.RIGHT, null, 20, 30.0)
        for (i in 0..30) p.onFix(step(i, 105.8041 + i * 0.0001, 90.0))   // rides from way 1 onto way 2
        assertTrue(p.warnings().isEmpty())
        val item = p.itemMeta("p", "photo", 1L, Side.RIGHT)
        assertEquals(2L, item.getLong("wayId"))
        assertEquals("backward", item.getString("travel"))              // against way 2's drawing direction…
        assertEquals("left", item.getString("sideOfWay"))               // …so the right of travel is the way's left
    }

    @Test fun cornerTurnIsNotAUTurnButReversingIs() {
        val p = FixProcessor(listOf(WAY, cross), Side.RIGHT, null, 20, 30.0)
        p.onFix(step(0, 105.8050, 90.0))
        p.onFix(step(1, 105.8060, 0.0, lat = START.lat + 0.0001))       // 90° left turn into the cross street
        assertEquals(true, p.travelOk)
        p.onFix(step(2, 105.8060, 180.0, lat = START.lat + 0.00005))    // turns back: U-turn
        assertEquals(false, p.travelOk)
        assertTrue(CaptureWarning.UTurn in p.warnings())
        p.onFix(step(3, 105.8060, 0.0, lat = START.lat + 0.0001))       // and back again
        assertEquals(true, p.travelOk)
    }

    @Test fun autoModeWithoutRoadDataStillRecordsSideAndCameraBearing() {
        val p = FixProcessor(emptyList(), Side.LEFT, null, 20, 30.0, fixedRoad = false)
        p.onFix(step(0, 105.8050, 90.0))
        p.onFix(step(1, 105.8051, 90.0))
        assertFalse(p.hasRoadData)
        assertNull(p.roadName())
        assertTrue(p.warnings().isEmpty())
        val item = p.itemMeta("p", "photo", 1L, Side.LEFT)
        assertEquals("left", item.getString("sideOfTravel"))
        assertEquals(0.0, item.getDouble("cameraBearing"), 1e-9)       // east − 90° = north
        assertTrue(item.isNull("wayId"))
        assertTrue(item.isNull("sideOfWay"))
        assertTrue(item.isNull("roadName"))
    }

    @Test fun autoModeSilentWhenRidingOffTheKnownRoadsAndPicksUpRoadsLoadedLater() {
        val p = FixProcessor(emptyList(), Side.RIGHT, null, 20, 30.0, fixedRoad = false)
        p.onFix(step(0, 105.8050, 90.0, lat = START.lat + 0.002))      // 220 m from WAY
        p.setWays(listOf(WAY))
        assertTrue(p.warnings().isEmpty())
        assertTrue(p.itemMeta("p", "photo", 1L, Side.RIGHT).isNull("wayId"))   // too far: no road for this item
        p.onFix(step(1, 105.8051, 90.0))
        assertEquals("Đường Thử Nghiệm", p.roadName())
        assertEquals(Travel.FORWARD, p.travel)
        assertEquals("Đường Thử Nghiệm", p.itemMeta("p", "photo", 1L, Side.RIGHT).getString("roadName"))
    }

    @Test fun gridCellsAroundAPoint() {
        val p = LatLon(21.0251, 105.8501)                               // 11 m from the cell's south edge
        val cells = RoadCells.around(p, 200.0)
        assertEquals(RoadCells.cellOf(p), cells.first())
        assertTrue(cells.size in 2..4)
        assertTrue(cells.first().contains(p))
        assertTrue(Overpass.cellQuery(cells.first()).contains("way(21.025000,105.850000,21.030000,105.855000)"))
        assertEquals(1, RoadCells.around(LatLon(21.0275, 105.8525), 200.0).size)   // cell centre: only its own cell
    }

    @Test fun autoRoadJsonAndTrackStats() {
        val r = Road.auto(listOf(WAY))
        val back = Road.fromJson(JSONObject(r.toJson().toString()))
        assertTrue(back.auto)
        assertNull(back.primaryWayId)
        assertEquals("auto", r.toJson().getString("mode"))
        val track = tmp.newFile("track.jsonl")
        for (i in 0..10) track.appendText(fixAt(i, i * 0.0001).toTrackJson(90.0).toString() + "\n")
        track.appendText(fixAt(11, 0.01).toTrackJson(90.0).toString() + "\n")   // 1 km jump in 1 s: ignored
        val st = TrackStats.of(track)
        assertEquals(103.8, st.distanceM, 1.0)
        assertEquals(11_000L, st.durationMs)
        val items = listOf("A", "B", "A", "").map { JSONObject().put("roadName", it) }
        assertEquals(listOf("A", "B"), TrackStats.mainRoads(items))
        val stray = (1..39).map { JSONObject().put("roadName", JSONObject.NULL) } + JSONObject().put("roadName", "Phố Lạc")
        assertEquals(emptyList<String>(), TrackStats.mainRoads(stray))      // 1 of 40: not the session's road
    }
}
