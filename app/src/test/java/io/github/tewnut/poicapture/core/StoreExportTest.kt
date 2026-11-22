package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class StoreExportTest {
    @Test fun aSessionIsNamedAfterItsStreetAndDay() {
        val items = List(5) { org.json.JSONObject().put("roadName", "Nguyễn Thanh Sơn") } + org.json.JSONObject().put("roadName", "Đường số 6")
        val utc7 = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
        org.junit.Assert.assertEquals("Nguyễn Thanh Sơn 2026-11-10 00h30", TrackStats.sessionName(items, "2026-11-09T17:30:00Z", "?", utc7))
        org.junit.Assert.assertEquals("No street 2026-11-09 08h00", TrackStats.sessionName(emptyList(), "2026-11-09T01:00:00Z", "No street", utc7))
    }

    @get:Rule val tmp = TemporaryFolder()

    private fun session(road: Road = Road.of(WAY, listOf(WAY)), kind: SessionKind = SessionKind.SWEEP) = Session(
        id = "s_test", createdAt = "2026-11-08T03:00:00Z", startedAt = "2026-11-08T03:01:00Z",
        contributor = "tester", note = "", vehicle = Vehicle.MOTORBIKE, mode = Mode.PHOTO_AUTO,
        sideOfTravel = Side.RIGHT, road = road, settings = Settings(), kind = kind,
    )

    @Test fun sessionRoundTripsThroughJson() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = session().apply { travel = Travel.FORWARD; sideOfWay = Side.RIGHT }
        store.create(s)
        val back = store.load("s_test")!!
        assertEquals(Travel.FORWARD, back.travel)
        assertEquals(Side.RIGHT, back.sideOfWay)
        assertNull(back.endedAt)
        assertEquals(WAY.geometry, back.road.ways.single().geometry)
        assertEquals(10, back.toJson().getJSONObject("settings").getInt("photoEveryM"))
        assertEquals(listOf("s_test"), store.list().map { it.id })
    }

    @Test fun itemsArePatchedAndTornLinesSkipped() {
        val store = SessionStore(tmp.newFolder("sessions"))
        store.create(session())
        store.appendItem("s_test", JSONObject().put("id", "v_1").put("kind", "video").put("tMs", 1))
        store.patchItem("s_test", "v_1", JSONObject().put("endMs", 99))
        File(store.dir("s_test"), "items.jsonl").appendText("{\"id\":\"p_tor")   // crash mid-write
        val items = store.items("s_test")
        assertEquals(1, items.size)
        assertEquals(99, items[0].getInt("endMs"))
        assertFalse(items[0].has("_patch"))
    }

    @Test fun exportZipHasManifestTrackRawLogsAndMedia() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = session()
        store.create(s)
        val p = FixProcessor(listOf(WAY), Side.RIGHT, null, 20, 30.0)
        for (i in 0..2) {
            val f = fixAt(i, i * 0.0001)
            p.onFix(f)
            store.trackFile(s.id).appendText(f.toTrackJson(p.travelBearing).toString() + "\n")
        }
        store.appendItem(s.id, p.itemMeta("p_1", "photo", 10L, Side.RIGHT).put("trigger", "auto"))
        store.photoFile(s.id, "p_1").writeBytes(byteArrayOf(1, 2, 3))
        store.appendItem(s.id, p.itemMeta("p_missing", "photo", 11L, Side.RIGHT))                  // no file: dropped
        store.appendItem(s.id, p.itemMeta("v_1", "video", 5L, Side.RIGHT).put("startMs", 5L))      // never finalized
        store.videoFile(s.id, "v_1").writeBytes(ByteArray(100))
        store.gnssFile(s.id).writeText("# Raw,utcTimeMillis\nRaw,1\n")
        store.imuFile(s.id).writeText("sensor,elapsedNanos,x,y,z,w\n")

        val zipFile = tmp.newFile("out.zip")
        val n = zipFile.outputStream().use { Exporter.writeZip(store, s.id, it, nowMs = 2000L) }
        assertEquals(6, n)
        ZipFile(zipFile).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("manifest.json", "track.geojson", "gnss_raw.csv", "imu.csv", "photos/p_1.jpg", "videos/v_1.mp4"), names)
            val m = JSONObject(z.getInputStream(z.getEntry("manifest.json")).reader().readText())
            assertEquals(SCHEMA, m.getString("schema"))
            assertEquals(3, m.getInt("trackPoints"))
            assertEquals("gnss_raw.csv", m.getString("gnssRawFile"))
            val items = m.getJSONArray("items")
            assertEquals(2, items.length())
            val video = items.getJSONObject(0)
            assertEquals("videos/v_1.mp4", video.getString("file"))
            assertTrue(video.getBoolean("incomplete"))
            assertEquals(100, video.getInt("bytes"))
            val photo = items.getJSONObject(1)
            assertEquals("photos/p_1.jpg", photo.getString("file"))
            assertEquals("right", photo.getString("sideOfWay"))
            val gj = JSONObject(z.getInputStream(z.getEntry("track.geojson")).reader().readText())
            val features = gj.getJSONArray("features")
            assertEquals(4, features.length())   // LineString + 3 points
            assertEquals(3, features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length())
        }
    }

    @Test fun autoRoadWaysLoadedDuringTheRideGoIntoTheManifestNotSessionJson() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = Session(id = "s_auto", createdAt = "2026-11-08T03:00:00Z", contributor = "t", note = "",
            vehicle = Vehicle.MOTORBIKE, mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings())
        store.create(s)
        store.appendWays(s.id, listOf(WAY))
        store.appendWays(s.id, listOf(WAY, Way(9, "Phố Khác", "", "residential", "", listOf(START, START))))   // overlap: deduplicated
        assertFalse(java.io.File(store.dir(s.id), "session.json").readText().contains("Phố Khác"))
        val road = Exporter.manifest(store, s.id).getJSONObject("session").getJSONObject("road")
        assertEquals("auto", road.getString("mode"))
        assertTrue(road.isNull("primaryWayId"))
        assertEquals(listOf(123456L, 9L), (0 until road.getJSONArray("ways").length()).map { road.getJSONArray("ways").getJSONObject(it).getLong("id") })
    }

    @Test fun photoRelocationIsStoredNextToTheGpsPositionAndCanBeUndone() {
        val store = SessionStore(tmp.newFolder("sessions"))
        store.create(session())
        store.appendItem("s_test", JSONObject().put("id", "p_1").put("kind", "photo").put("tMs", 1).put("lat", 21.0).put("lon", 105.0))
        store.placeAt("s_test", "p_1", LatLon(21.0001, 105.0002), nowMs = 5000L)
        var it = store.items("s_test").single()
        assertEquals(21.0, it.getDouble("lat"), 0.0)                 // GPS position kept
        assertEquals(21.0001, it.getDouble("placeLat"), 0.0)
        assertEquals(105.0002, it.getDouble("placeLon"), 0.0)
        assertEquals("1970-01-01T00:00:05Z", it.getString("placedAt"))
        store.placeAt("s_test", "p_1", null)                          // back to the estimate
        it = store.items("s_test").single()
        assertTrue(it.isNull("placeLat"))
        assertEquals(21.0, it.getDouble("lat"), 0.0)
    }

    @Test fun zipFileNameIsSafe() {
        assertEquals("poi-capture_Đường-Thử-Nghiệm_right_202611080301.zip", Exporter.fileName(session()))
        assertEquals("poi-capture_place_202611080301.zip", Exporter.fileName(session(kind = SessionKind.POI)))
        assertEquals("poi-capture_auto_right_202611080301.zip", Exporter.fileName(session(road = Road.auto())))
    }
}

class SessionSplitTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aRideOverTwoStreetsBecomesTwoSessionsWithTheirOwnPhotosAndLogs() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = Session(id = "s_ride", createdAt = "2026-11-19T02:29:00Z", startedAt = "2026-11-19T02:29:00Z",
            contributor = "tester", note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_AUTO,
            sideOfTravel = Side.RIGHT, road = Road.of(WAY, listOf(WAY)), settings = Settings(), name = "Trường Sa 2026-11-19 09h29")
        store.create(s)
        val t0 = java.time.Instant.parse("2026-11-19T02:29:00Z").toEpochMilli()   // 2026-11-19T02:29:00Z
        for (i in 0 until 40) {
            val t = t0 + i * 2000L
            store.appendItem("s_ride", JSONObject().put("id", "p_$i").put("kind", "photo").put("tMs", t).put("t", iso(t))
                .put("elapsedNanos", 5_000_000_000L + i * 2_000_000_000L).put("roadName", if (i < 15) "Nguyễn Văn Thủ" else "Trường Sa"))
            store.photoFile("s_ride", "p_$i").writeText("jpeg $i")
            store.trackFile("s_ride").appendText(JSONObject().put("t", t).put("lat", 10.0).put("lon", 106.0).toString() + "\n")
        }
        store.imuFile("s_ride").writeText("sensor,elapsedNanos,x,y,z,w\nacc,6000000000,0,0,0,\nacc,75000000000,0,0,0,\n")

        val ids = SessionSplit.split(store, "s_ride", "No street", java.time.ZoneId.of("Asia/Ho_Chi_Minh"))

        assertEquals(listOf("s_ride-1", "s_ride-2"), ids)
        assertFalse(store.dir("s_ride").exists())
        val a = store.load("s_ride-1")!!
        val b = store.load("s_ride-2")!!
        assertEquals("Nguyễn Văn Thủ 2026-11-19 09h29", a.name)
        assertEquals("Trường Sa 2026-11-19 09h29", b.name)
        assertEquals("s_ride", b.parentId); assertEquals(2, b.part); assertEquals(2, b.parts)
        assertEquals(15, store.items("s_ride-1").size)
        assertEquals(25, store.items("s_ride-2").size)
        assertEquals("jpeg 20", store.photoFile("s_ride-2", "p_20").readText())
        assertEquals(15, store.trackCount("s_ride-1"))
        assertEquals(25, store.trackCount("s_ride-2"))
        assertEquals(listOf("sensor,elapsedNanos,x,y,z,w", "acc,6000000000,0,0,0,"), store.imuFile("s_ride-1").readLines())
        assertEquals(listOf("sensor,elapsedNanos,x,y,z,w", "acc,75000000000,0,0,0,"), store.imuFile("s_ride-2").readLines())
        assertTrue(SessionSplit.split(store, "s_ride-2", "No street").isEmpty())   // a part is not cut again
    }
}
