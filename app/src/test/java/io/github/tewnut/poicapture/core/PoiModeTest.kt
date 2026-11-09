package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PoiModeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun session(kind: SessionKind) = Session(
        id = "s_${kind.json}", createdAt = "2026-11-09T01:00:00Z", startedAt = "2026-11-09T01:01:00Z",
        contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_MANUAL,
        sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), kind = kind,
    )

    private fun photo(id: String, tMs: Long, poiId: String? = null, role: PhotoRole? = null) = JSONObject()
        .put("id", id).put("kind", "photo").put("tMs", tMs).put("lat", START.lat).put("lon", START.lon)
        .apply { if (poiId != null) put("poiId", poiId).put("role", role!!.json) }

    @Test fun placesCarryTheirPhotosACoverAndTheReviewedPin() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = session(SessionKind.POI)
        store.create(s)
        val gps = Fix(1L, START.lat, START.lon, 8.0)
        store.appendPoi(s.id, Pois.new("poi_1", 1000L, gps))
        store.appendPoi(s.id, Pois.new("poi_2", 5000L, gps))
        for ((id, t, role) in listOf(Triple("p_1", 1100L, PhotoRole.SIGN), Triple("p_2", 1200L, PhotoRole.FACADE), Triple("p_3", 1300L, PhotoRole.INSIDE))) {
            store.appendItem(s.id, photo(id, t, "poi_1", role)); store.photoFile(s.id, id).writeBytes(byteArrayOf(1))
        }
        val pin = LatLon(START.lat - 0.00005, START.lon)
        store.patchPoi(s.id, "poi_1", Pois.review(pin, false,
            mapOf("name" to " Phở Thìn ", "phone" to "", "houseNumber" to "13"), PoiStatus.EXISTS, 2000L))

        val m = Exporter.manifest(store, s.id, nowMs = 9000L)
        assertEquals("poi-capture/3", m.getString("schema"))
        assertEquals("poi", m.getString("kind"))
        val pois = m.getJSONArray("pois")
        assertEquals(2, pois.length())
        val p1 = pois.getJSONObject(0)
        assertEquals(listOf("p_1", "p_2", "p_3"), (0 until 3).map { p1.getJSONArray("photos").getString(it) })
        assertEquals("p_2", p1.getString("cover"))                         // the front photo, not the first one
        assertEquals("pin", p1.getJSONObject("position").getString("source"))
        assertEquals(pin.lat, p1.getJSONObject("position").getDouble("lat"), 0.0)
        assertEquals("Phở Thìn", p1.getJSONObject("fields").getString("name"))
        assertFalse(p1.getJSONObject("fields").has("phone"))               // empty fields are left out
        assertEquals("exists", p1.getString("status"))
        val p2 = pois.getJSONObject(1)
        assertTrue(p2.getBoolean("noPhotos"))
        assertEquals("gps", p2.getJSONObject("position").getString("source"))
        assertTrue(Exporter.fileName(s).startsWith("poi-capture_dia-diem_"))
    }

    @Test fun marksInASweepCollectThePhotosAroundThem() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = session(SessionKind.SWEEP)
        store.create(s)
        for ((i, t) in listOf(500L, 4000L, 9000L, 20000L).withIndex()) {
            store.appendItem(s.id, photo("p_$i", t)); store.photoFile(s.id, "p_$i").writeBytes(byteArrayOf(1))
        }
        store.appendMark(s.id, JSONObject().put("id", "m_1").put("tMs", 6000L).put("lat", START.lat).put("lon", START.lon))
        val m = Exporter.manifest(store, s.id)
        val mark = m.getJSONArray("marks").getJSONObject(0)
        assertEquals(listOf("p_1", "p_2"), (0 until mark.getJSONArray("photoIds").length()).map { mark.getJSONArray("photoIds").getString(it) })
        assertEquals(0, m.getJSONArray("pois").length())
        assertEquals("sweep", m.getString("kind"))
    }

    @Test fun measuringTheCameraDirectionNeverRewritesPhotosOfAPlace() {
        val est = MountEstimate(90.0, CameraSide.RIGHT, 0.95, 8)
        val poiPhoto = photo("p_1", 1L, "poi_1", PhotoRole.SIGN).put("travelBearing", 0.0)
            .put("cameraBearing", 42.0).put("cameraBearingSource", "compass")
        assertEquals(null, Remeasure.patch(poiPhoto, est, 6.0))
        val noSource = photo("p_2", 2L, "poi_1", PhotoRole.FACADE).put("travelBearing", 0.0)
        assertEquals(null, Remeasure.patch(noSource, est, 6.0))       // a poiId alone is enough
        assertTrue(Exporter.fileName(session(SessionKind.POI)).let { !it.contains("_right") })
    }

    @Test fun olderSessionsAreSweeps() {
        val json = javaClass.classLoader!!.getResourceAsStream("legacy_session_v03.json")!!.bufferedReader().readText()
        assertEquals(SessionKind.SWEEP, Session.fromJson(JSONObject(json)).kind)
        assertEquals(PhotoRole.FACADE, PhotoRole.of("facade"))
        assertEquals(PoiStatus.NEW, PoiStatus.of(null))
    }
}
