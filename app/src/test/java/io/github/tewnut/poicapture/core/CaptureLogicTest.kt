package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A straight east–west road in Hà Nội, drawn west → east (bearing 90°). */
val START = LatLon(21.0285, 105.8040)
val WAY = Way(123456, "Đường Thử Nghiệm", "", "secondary", "", (0..10).map { LatLon(START.lat, START.lon + it * 0.0005) })

fun fixAt(i: Int, dLon: Double, accuracy: Double = 5.0, eastward: Boolean = true) = Fix(
    t = 1_800_000_000_000L + i * 1000L,
    lat = START.lat, lon = START.lon + dLon,
    accuracyM = accuracy, speedMps = 3.0, bearingDeg = if (eastward) 90.0 else 270.0,
)

class CaptureLogicTest {
    private fun processor(side: Side = Side.RIGHT) = FixProcessor(listOf(WAY), side, null, maxSpeedKmh = 20, maxAccuracyM = 30.0)

    @Test fun travelDecidedForwardOnFirstMovingFixAlongDrawingDirection() {
        val p = processor()
        assertTrue(p.onFix(fixAt(0, 0.0001)))
        assertEquals(Travel.FORWARD, p.travel)
        assertFalse(p.onFix(fixAt(1, 0.0002)))   // decided only once
        assertEquals(180.0, p.cameraBearing!!, 1e-9) // facing south = right of eastward travel
        val item = p.itemMeta("p_1", "photo", 1L, Side.RIGHT)
        assertEquals("right", item.getString("sideOfWay"))
        assertEquals(123456L, item.getLong("wayId"))
    }

    @Test fun drivingWestwardIsBackwardAndFlipsSideOfWay() {
        val p = processor()
        p.onFix(fixAt(0, 0.003, eastward = false))
        assertEquals(Travel.BACKWARD, p.travel)
        assertEquals("left", p.itemMeta("p_1", "photo", 1L, Side.RIGHT).getString("sideOfWay"))
    }

    @Test fun uTurnWarnsAndPerItemSideFlips() {
        val p = processor()
        p.onFix(fixAt(0, 0.001))
        p.onFix(fixAt(1, 0.0009, eastward = false))
        assertEquals(false, p.travelOk)
        assertTrue(CaptureWarning.UTurn in p.warnings())
        val item = p.itemMeta("p_2", "photo", 1L, Side.RIGHT)
        assertEquals("backward", item.getString("travel"))
        assertEquals("left", item.getString("sideOfWay"))
        assertEquals(Travel.FORWARD, p.travel)    // the session's direction stays as first decided
    }

    @Test fun speedWeakGpsAndOffRoadWarnings() {
        val p = processor()
        p.onFix(Fix(1L, START.lat + 0.001, START.lon, accuracyM = 45.0, speedMps = 8.0, bearingDeg = 90.0))
        val w = p.warnings()
        assertTrue(w.any { it is CaptureWarning.TooFast && it.kmh == 29 })
        assertTrue(CaptureWarning.WeakGps(45) in w)
        assertTrue(CaptureWarning.OffRoad(111) in w)
        assertFalse(p.gpsGood)
    }

    @Test fun bearingAndSpeedDerivedFromPositionsWhenTheFixHasNone() {
        val p = processor()
        p.onFix(Fix(0L, START.lat, START.lon, 5.0))
        p.onFix(Fix(2000L, START.lat, START.lon + 0.0001, 5.0))   // ~10.4 m in 2 s, eastward
        assertEquals(90.0, p.travelBearing!!, 0.1)
        assertEquals(5.2, p.speedMps!!, 0.1)
        assertEquals(Travel.FORWARD, p.travel)
    }

    @Test fun movingWhileReceiverReportsZeroSpeedStillDecidesTravel() {
        val p = processor()
        p.onFix(Fix(0L, START.lat, START.lon, 5.0, speedMps = 0.0, bearingDeg = 0.0))
        p.onFix(Fix(1500L, START.lat, START.lon + 0.0001, 5.0, speedMps = 0.0, bearingDeg = 0.0))  // ~10.4 m in 1.5 s
        assertEquals(6.9, p.speedMps!!, 0.1)
        assertEquals(90.0, p.travelBearing!!, 0.1)
        assertEquals(Travel.FORWARD, p.travel)
        p.onFix(Fix(2500L, START.lat, START.lon + 0.0001, 5.0, speedMps = 0.0))                    // standing still
        assertEquals(0.0, p.speedMps!!, 1e-9)
    }

    @Test fun noMatchOrBearingBeforeAnyMovement() {
        val p = processor()
        val item = p.itemMeta("p_0", "photo", 1L, Side.LEFT)
        assertTrue(item.isNull("lat"))
        assertTrue(item.isNull("sideOfWay"))
        assertNull(p.travel)
    }

    @Test fun overpassParsingRankingAndSameRoadOrdering() {
        val body = """{"elements":[
          {"type":"way","id":2,"tags":{"name":"B","highway":"residential"},"geometry":[{"lat":21.0290,"lon":105.8040},{"lat":21.0290,"lon":105.8050}]},
          {"type":"node","id":9},
          {"type":"way","id":1,"tags":{"name":"A","ref":"QL1A","highway":"primary"},"geometry":[{"lat":21.0285,"lon":105.8040},{"lat":21.0285,"lon":105.8050}]}
        ]}"""
        val ways = Overpass.parseWays(body)
        assertEquals(listOf(2L, 1L), ways.map { it.id })
        val ranked = Overpass.rankNear(ways, LatLon(21.0285, 105.8045))
        assertEquals(1L, ranked.first().first.id)
        assertEquals("QL1A · A", roadLabel(ranked.first().first))
        assertEquals("Unnamed road (track)", roadLabel(Way(3, "", "", "track", "", emptyList())))
        assertNull(roadNameOf(Way(3, "", "", "track", "", emptyList())))
        assertTrue(Overpass.nearQuery(21.0285, 105.8045).contains("way(around:40,21.028500,105.804500)"))
        assertTrue(Overpass.sameRoadQuery(ranked.first().first)!!.contains("[\"ref\"=\"QL1A\"]"))
        assertNull(Overpass.sameRoadQuery(Way(3, "", "", "track", "", listOf(START, START))))
        assertEquals(listOf(1L, 2L), Overpass.orderSameRoad(ways[1], ways).map { it.id })
    }

    @Test fun matchRoadPicksNearestWay() {
        val other = Way(7, "X", "", "residential", "", listOf(LatLon(21.0300, 105.8040), LatLon(21.0300, 105.8060)))
        val m = matchRoad(listOf(other, WAY), LatLon(21.0286, 105.8045))
        assertNotNull(m)
        assertEquals(123456L, m!!.wayId)
        assertEquals(11.1, m.distanceM, 0.3)
    }
}
