package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The iPhone app's street sweep and upload use the shared code (shared/ SweepRide, SweepExport, Cloud, Direct,
 * TrackLine); the Android app keeps its own (core FixProcessor, Session, Exporter, Tus, SetupLink, Polyline). These
 * tests hold the two to the same answers, so a ride from either phone reads the same in the PC pipeline.
 */
class IosParityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun setupLinksParseTheSame() {
        val inputs = listOf(
            "https://poi-capture-cloud.4hum-ai.workers.dev/files/#token=pct_abcDEF-_123&name=S%C6%A1n%20%E2%80%93%20phone",
            "  http://192.168.1.5:1080/files#name=&token=pct_x  ", "https://h.example/files/#token=pct_y",
            "https://h.example/files/#token=pct_1&token=pct_2&name=A+B&extra=1", null, "", "hello world",
            "WIFI:S:office;T:WPA;P:secret;;", "ftp://h.example/files/#token=pct_x", "#token=pct_x", "https://h.example/files/",
            "https://h.example/files/#name=phone", "https://h.example/files/#token=", "https://h.example/files/#token=pct%20x",
            "https://h.example/files/#token=%ZZ", "https://h.example/files/#token=%4", "http://localhost:1080/files/#token=a&name=%E1%BB%87",
        )
        for (t in inputs) {
            val shared = Cloud.parseSetup(t)
            when (val a = SetupLink.parse(t)) {
                is SetupLink.Parsed.Ok -> {
                    assertEquals("$t", null, shared.problem)
                    assertEquals("$t", a.link.endpoint, shared.endpoint)
                    assertEquals("$t", a.link.token, shared.token)
                    assertEquals("$t", a.link.name, shared.name)
                }
                is SetupLink.Parsed.Bad -> assertEquals("$t", if (a.problem == SetupLink.Problem.NOT_HTTP) "notHttp" else "noToken", shared.problem)
            }
        }
    }

    @Test fun endpointsAndUrlsAreTheSame() {
        for (t in listOf("https://h.example/files/", " https://h.example/files ", "http://localhost:1080/a/b/files", "HTTPS://H.example/x",
                "https://h.example:8443/files//", "not a url", "", "ftp://x/y", "https:///files", "https://h_x.example/files/",
                "http://[::1]:1080/files/", "https://user@h.example/files/", "mailto:a@b.c")) {
            assertEquals(t, Tus.endpoint(t), Cloud.endpoint(t))
            assertEquals(t, CloudMe.url(t), Cloud.meUrl(t))
            assertEquals(t, DirectUpload.url(t, "/u1/urls"), Cloud.directUrl(t, "/u1/urls"))
        }
        for (t in listOf("pct_x", " Bearer pct_x", "bearer pct_y", "", null)) assertEquals(Tus.authHeader(t), Cloud.authHeader(t))
    }

    @Test fun directUploadsKeepTheSameBooks() {
        val body = """{"id":"u1","location":"https://h/files/u1","size":20000000,"partSize":8388608,"parts":3,"urls":[{"part":1,"url":"https://r2/1"}]}"""
        val (a, urls) = DirectUpload.parseCreate(body)
        val s = Direct.parseCreate(body)!!
        assertEquals(a.pending, s.pending)
        for (n in 1..3) { assertEquals(a.start(n), s.start(n)); assertEquals(a.length(n), s.length(n)) }
        assertEquals(urls.keys.toList(), Direct.parseUrls(body).map { it.part })
        val a2 = a.with(1, "e1").with(2, "e2").with(3, "e3")
        val s2 = s.withEtag(1, "e1").withEtag(2, "e2").withEtag(3, "e3")
        assertEquals(JSONObject(a2.completeBody()).toString(), JSONObject(s2.completeBody()).toString())
        assertEquals(JSONObject(DirectUpload.createBody(5, mapOf("a" to "b", "c" to null))).toString(),
            JSONObject(Direct.createBody(5, mapOf("a" to "b"))).toString())
    }

    @Test fun roadCellsQueriesAndMatchesAreTheSame() {
        val rnd = Random(7)
        repeat(200) {
            val lat = 8.0 + rnd.nextDouble() * 15 - (if (it % 10 == 0) 30 else 0)
            val lon = 102.0 + rnd.nextDouble() * 8
            val cell = RoadCells.cellOf(LatLon(lat, lon))
            assertEquals("${cell.x},${cell.y}", RideRoads.cellOf(lat, lon))
            assertEquals(Overpass.cellQuery(cell), RideRoads.cellQuery(RideRoads.cellOf(lat, lon)))
            assertEquals(RoadCells.around(LatLon(lat, lon)).map { "${it.x},${it.y}" }, RideRoads.cellsAround(lat, lon, 200.0))
        }
        val ways = List(12) { k ->
            val la = 10.78 + rnd.nextDouble() * 0.01; val lo = 106.69 + rnd.nextDouble() * 0.01
            Way(100L + k, "W$k", "", "residential", "", List(4) { j -> LatLon(la + j * (rnd.nextDouble() - 0.5) * 0.002, lo + j * (rnd.nextDouble() - 0.5) * 0.002) })
        }
        val rides = ways.map { RideWay(it.id, it.name, it.ref, it.highway, it.oneway, it.geometry) }
        repeat(300) {
            val p = LatLon(10.78 + rnd.nextDouble() * 0.01, 106.69 + rnd.nextDouble() * 0.01)
            val tb = if (it % 3 == 0) null else rnd.nextDouble() * 360
            assertEquals(matchRoad(ways, p, tb)?.wayId, RideRoads.match(rides, p, tb)?.way?.id)
        }
        val overpass = JSONObject().put("elements", org.json.JSONArray().put(JSONObject().put("type", "way").put("id", 5)
            .put("tags", JSONObject().put("name", "Lê Lợi").put("highway", "primary").put("ref", "QL1"))
            .put("geometry", org.json.JSONArray().put(JSONObject().put("lat", 1.0).put("lon", 2.0)).put(JSONObject().put("lat", 1.1).put("lon", 2.1))))).toString()
        val w = Overpass.parseWays(overpass).single()
        val r = RideRoads.parseWays(overpass)!!.single()
        assertEquals(roadLabel(w), r.label)
        assertEquals(JSONObject(w.toJson().toString()).toString(), JSONObject(r.toJson()).toString())
    }

    /** The same fixes and the same road give the same photo fields as the Android FixProcessor (mount, auto road). */
    @Test fun photoItemsCarryTheAndroidFields() {
        val way = Way(11, "Nguyễn Thị Minh Khai", "", "secondary", "", listOf(LatLon(10.78, 106.690), LatLon(10.78, 106.700)))
        val fp = FixProcessor(listOf(way), Side.RIGHT, null, 30, 30.0, 60.0, 0.0, fixedRoad = false, facadeDistanceM = 4.0)
        val ride = SweepRide("shops", false, 30.0, 60.0)
        ride.onFix(1_000_000L, 10.78, 106.69, 5.0, Double.NaN, Double.NaN, Double.NaN, 1_000_000_000L)
        for (c in ride.cellsToLoad()) ride.cellLoaded(c, """{"elements":[${JSONObject(way.toJson().toString()).put("type", "way")
            .put("tags", JSONObject().put("name", way.name).put("highway", way.highway)).toString()}]}""", "overpass")
        fp.onFix(Fix(1_000_000L, 10.78, 106.69, 5.0, null, null, null, 1_000_000_000L))
        for (i in 1..6) {
            val t = 1_000_000L + i * 1000
            val f = Fix(t, 10.78, 106.69 + i * 0.00005, 4.0, 12.0, 5.0, 90.0, 1_000_000_000L + i * 1_000_000_000L)
            fp.onFix(f)
            val line = JSONObject(ride.onFix(t, f.lat, f.lon, f.accuracyM, 12.0, 5.0, 90.0, f.elapsedNanos!!))
            val android = f.toTrackJson(fp.travelBearing)
            for (k in android.keys()) assertEquals(k, android.get(k).toString(), line.get(k).toString())
        }
        fp.facadeDistanceM = ride.facadeNow   // Android follows the road type the same way (CaptureService.onLocation)
        val a = fp.itemMeta("p_1", "photo", 1_007_000L, Side.RIGHT, 1_007_000_000L)
        val s = JSONObject(ride.photoItem("p_1", 1_007_000L, 1_007_000_000L, "auto", true, "", "", 90))
        for (k in a.keys()) {
            assertTrue("missing $k", s.has(k))
            assertEquals(k, a.get(k).toString(), s.get(k).toString())
        }
        for (k in listOf("trigger", "orientation", "facadeRoadType", "targetRotationDeg", "hFovDeg")) assertTrue(k, s.has(k))
    }

    @Test fun sessionJsonHasTheAndroidKeys() {
        val android = Session(id = "s", createdAt = "2026-11-22T03:00:00Z", contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), target = SweepTarget.SIGNS,
            holding = Holding.HAND).toJson()
        val ios = JSONObject(SweepExport.sessionDoc("s", 1_795_000_000_000L, "t", "hand", "signs", 2560, "{}"))
        for (k in android.keys()) assertTrue("session.$k", ios.has(k))
        for (k in android.getJSONObject("settings").keys()) assertTrue("settings.$k", ios.getJSONObject("settings").has(k))
        for (k in android.getJSONObject("road").keys()) assertTrue("road.$k", ios.getJSONObject("road").has(k))
        for (k in listOf("kind", "mode", "vehicle", "holding", "target", "sideOfTravel", "schema"))
            assertEquals(k, android.get(k).toString(), ios.get(k).toString())
        assertEquals(android.getJSONObject("road").getString("mode"), ios.getJSONObject("road").getString("mode"))
        assertEquals(android.getJSONObject("road").getString("label"), ios.getJSONObject("road").getString("label"))
    }

    @Test fun uploadMetadataAndTrackAreTheSame() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = Session(id = "s_m", createdAt = "2026-11-22T03:00:00Z", startedAt = "2026-11-22T03:01:00Z", endedAt = "2026-11-22T03:20:00Z",
            contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT,
            road = Road.auto(), settings = Settings(), target = SweepTarget.ADDRESSES, name = "Lê Lợi 2026-11-22 10h01")
        store.create(s)
        val rnd = Random(3)
        val lines = ArrayList<String>()
        var lat = 10.78; var lon = 106.69
        for (i in 0 until 400) {
            lat += (rnd.nextDouble() - 0.3) * 0.0002; lon += (rnd.nextDouble() - 0.2) * 0.0002
            val l = JSONObject().put("t", 1_795_000_000_000L + i * 1000).put("lat", lat).put("lon", lon).put("acc", if (i % 17 == 0) 45.0 else 5.0).toString()
            lines += l
            store.trackFile("s_m").appendText(l + "\n")
        }
        val items = (0 until 9).map { i ->
            JSONObject().put("id", "p_$i").put("kind", "photo").put("tMs", i.toLong()).put("roadName", if (i < 6) "Lê Lợi" else "Pasteur")
        }
        items.forEach { store.appendItem("s_m", it) }
        val android = sessionUploadMetadata(s, "x.zip", 9, store.trackFile("s_m"), items).filterValues { it != null }
        val manifest = SweepExport.manifest(s.toJson().toString(), items.map { it.toString() }, emptyList(), lines.size, false, false, 1L)
        val ios = SweepExport.uploadMetadata(manifest, "x.zip", lines)
        assertEquals(android, ios)
        assertEquals(Polyline.capped(Polyline.trackPoints(store.trackFile("s_m"))), TrackLine.capped(TrackLine.points(lines, 30.0), 2000, 5.0))
        assertEquals(Exporter.fileName(s), SweepExport.fileName(s.toJson().toString()))
        // the export's track.geojson
        val w = java.io.StringWriter()
        Exporter.writeTrackGeoJson(store.trackFile("s_m"), w)
        assertEquals(JSONObject(w.toString()).toString(), JSONObject(SweepExport.trackGeoJson(lines)).toString())
    }
}
