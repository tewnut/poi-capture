package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanFacadeTest {
    @Test fun planFromFacadeDistance() {
        val p6 = CapturePlan.of(6.0)                      // default 70° field of view
        assertEquals(8.4, p6.coverageM, 0.05)             // 2 · 6 · tan(35°)
        assertEquals(3.78, p6.photoEveryM, 0.05)          // at the latest every 45 % of a frame: each point on ≥ 2 photos
        assertEquals(20, p6.rateLimitKmh)                 // 3.78 m × 1.5 shots/s
        assertEquals(24, p6.blurLimitKmh)                 // 8 px · 6 m / (1/250 s · 1828 px)
        assertEquals(20, p6.maxSpeedKmh)
        val p4 = CapturePlan.of(4.0)
        assertEquals(2.52, p4.photoEveryM, 0.01)          // no 3 m floor any more: the frames, not the GPS, time the photos
        assertTrue(p4.maxSpeedKmh < p6.maxSpeedKmh)
        val p12 = CapturePlan.of(12.0)
        assertEquals(7.56, p12.photoEveryM, 0.05)
        assertEquals(CapturePlan.SPEED_CAP_KMH, p12.maxSpeedKmh)
        assertEquals(CapturePlan.of(6.0, 80.0).hFovDeg, 80.0, 0.0)
        assertTrue(CapturePlan.of(6.0, 80.0).photoEveryM > p6.photoEveryM)   // wider lens: fewer photos
    }

    @Test fun destinationMatchesDistanceAndBearing() {
        val p = Geo.destination(START, 180.0, 6.0)
        assertEquals(6.0, Geo.distanceM(START, p), 0.01)
        assertEquals(180.0, Geo.bearing(START, p), 0.1)
        val q = Geo.destination(START, 33.0, 250.0)
        assertEquals(250.0, Geo.distanceM(START, q), 0.1)
        assertEquals(33.0, Geo.bearing(START, q), 0.1)
    }

    @Test fun itemsCarryAFacadePointOnTheBuildingSide() {
        val p = FixProcessor(listOf(WAY), Side.RIGHT, null, 20, 30.0, fixedRoad = false, facadeDistanceM = 6.0)
        p.onFix(fixAt(0, 0.0)); p.onFix(fixAt(1, 0.0001))                 // riding east
        val item = p.itemMeta("p", "photo", 1L, Side.RIGHT)
        assertEquals("assumed", item.getString("cameraBearingSource"))
        assertEquals(90.0, item.getDouble("cameraYawToTravelDeg"), 1e-9)
        val cam = LatLon(item.getDouble("lat"), item.getDouble("lon"))
        val fac = LatLon(item.getDouble("facadeLat"), item.getDouble("facadeLon"))
        assertEquals(6.0, Geo.distanceM(cam, fac), 0.01)
        assertEquals(180.0, Geo.bearing(cam, fac), 0.1)                   // south = right of eastward travel
        assertTrue(fac.lat < START.lat)                                    // off the road, on the building side
        assertEquals("roadType", item.getString("facadeSource"))
        p.cameraYawDeg = 80.0                                              // measured: 10° toward the front
        val m = p.itemMeta("p2", "photo", 2L, Side.RIGHT)
        assertEquals("measured", m.getString("cameraBearingSource"))
        assertEquals(170.0, m.getDouble("cameraBearing"), 1e-9)
    }

    @Test fun itemsBeforeTheMeasurementAreCorrected() {
        val item = JSONObject().put("id", "p").put("kind", "photo").put("lat", START.lat).put("lon", START.lon)
            .put("travelBearing", 90.0).put("travel", "forward").put("cameraBearing", 180.0)
            .put("cameraBearingSource", "assumed").put("sideOfTravel", "right").put("sideOfWay", "right")
        val left = MountEstimate(-85.0, CameraSide.LEFT, 0.95, 8)
        val patch = Remeasure.patch(item, left, 6.0)!!
        assertEquals(5.0, patch.getDouble("cameraBearing"), 1e-9)          // 90 − 85
        assertEquals("left", patch.getString("sideOfTravel"))
        assertEquals("left", patch.getString("sideOfWay"))
        assertEquals("measured", patch.getString("cameraBearingSource"))
        assertTrue(patch.getDouble("facadeLat") > START.lat)               // now north of the road
        assertNull(Remeasure.patch(JSONObject(item.toString()).put("cameraBearingSource", "measured"), left, 6.0))
        assertNull(Remeasure.patch(item, MountEstimate(5.0, CameraSide.FRONT, 0.9, 8), 6.0))   // front: no side change
    }

    @Test fun sessionsFromAppVersion03StillLoad() {
        val json = javaClass.classLoader!!.getResourceAsStream("legacy_session_v03.json")!!.bufferedReader().readText()
        val s = Session.fromJson(JSONObject(json))
        assertEquals(Vehicle.MOTORBIKE, s.vehicle)
        assertNull(s.plan)
        assertNull(s.mount)
        assertEquals(6.0, s.settings.facadeDistanceM, 0.0)                 // default when absent
        assertTrue(s.road.auto)
        assertEquals(46, s.road.ways.size)
        val out = s.toJson()
        assertEquals(10.0, out.getJSONObject("settings").getDouble("photoEveryM"), 0.0)   // legacy spacing kept
        assertNotNull(Session.fromJson(out))                               // round trip
        assertEquals(Vehicle.UNSPECIFIED, Vehicle.of(null))
    }

    @Test fun compassYawFromTheFirstRide() {
        // Mai Chí Thọ, 11 Nov: travel ~63°, camera compass ~121° (assumed 153°): 32° forward of perpendicular
        val c = CompassYaw()
        val rnd = java.util.Random(1)
        repeat(14) { c.add(63.0 + 58 + rnd.nextGaussian() * 5, 63.0, 3.3) }
        assertNull(c.estimate())                                 // not enough readings yet
        c.add(121.0, 63.0, 1.0)                                  // too slow: ignored
        repeat(20) { c.add(63.0 + 58 + rnd.nextGaussian() * 5, 63.0, 3.3) }
        val e = c.estimate()!!
        assertEquals(58.0, e.yawDeg, 3.0)
        assertEquals(CameraSide.RIGHT, e.side)
        assertEquals("compassMount", e.source)
        // a compass-mount yaw on an assumed item is applied, and labelled as such
        val item = JSONObject().put("cameraBearingSource", "assumed").put("travelBearing", 63.0).put("lat", 10.78).put("lon", 106.74)
        val p = Remeasure.patch(item, e, 30.0)!!
        assertEquals("compassMount", p.getString("cameraBearingSource"))
        assertEquals(121.0, p.getDouble("cameraBearing"), 3.0)
        // readings all over the place (a disturbed compass): no estimate
        val bad = CompassYaw()
        repeat(40) { bad.add(rnd.nextDouble() * 360, 63.0, 3.3) }
        assertNull(bad.estimate())
    }
}
