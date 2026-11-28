package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudTest {
    @Test fun setupLinkSplitsIntoAddressTokenAndName() {
        val l = Cloud.parseSetup("https://poi-uploader.4hum.ai/files/#token=pct_abcDEF-_123&name=S%C6%A1n%20%E2%80%93%20phone")
        assertTrue(l.ok)
        assertEquals("https://poi-uploader.4hum.ai/files/", l.endpoint)
        assertEquals("pct_abcDEF-_123", l.token)
        assertEquals("Sơn – phone", l.name)
        assertFalse(l.toString().contains("pct_abc"))
        val p = Cloud.parseSetup("https://h.example/files/#token=pct_1&token=pct_2&name=A+B")
        assertEquals("pct_1", p.token); assertEquals("A B", p.name)
        assertEquals("http://192.168.1.5:1080/files/", Cloud.parseSetup("  http://192.168.1.5:1080/files#name=&token=pct_x ").endpoint)
    }

    @Test fun badSetupLinksSayWhy() {
        for (t in listOf(null, "", "hello world", "ftp://h.example/files/#token=x", "#token=x", "WIFI:S:office;T:WPA;P:secret;;"))
            assertEquals("notHttp", Cloud.parseSetup(t).problem, "$t")
        for (t in listOf("https://h.example/files/", "https://h.example/files/#name=x", "https://h.example/files/#token=",
                "https://h.example/files/#token=pct%20x", "https://h.example/files/#token=%ZZ", "https://h.example/files/#token=%4"))
            assertEquals("noToken", Cloud.parseSetup(t).problem, t)
    }

    @Test fun endpointsAndHeaders() {
        assertEquals("https://h.example/files/", Cloud.endpoint(" https://h.example/files// "))
        assertNull(Cloud.endpoint("not a url"))
        assertNull(Cloud.endpoint("https:///files"))
        assertEquals("https://h.example:8443/me", Cloud.meUrl("https://h.example:8443/files/"))
        assertEquals("http://localhost:1080/direct/abc/urls", Cloud.directUrl("http://localhost:1080/a/files", "/abc/urls"))
        assertEquals("Bearer pct_x", Cloud.authHeader(" Bearer pct_x "))
        assertNull(Cloud.authHeader("  "))
        assertEquals("POI Capture/0.23.0 (iOS 18.1; Apple iPhone15,2)", Cloud.clientHeader("0.23.0", "18.1", "iPhone15,2"))
        assertFalse(Cloud.configured(Cloud.DEFAULT_URL, ""))
        assertTrue(Cloud.configured(Cloud.DEFAULT_URL, "pct_x"))
        assertTrue(Cloud.configured("http://localhost:1080/files/", ""))
        assertEquals("filename YS56aXA=,type cmF3LXNlc3Npb24=", Cloud.tusMetadata(linkedMapOf("filename" to "a.zip", "type" to "raw-session")))
        assertEquals("U8ahbg==", Cloud.base64("Sơn".encodeToByteArray()))
    }

    @Test fun meReadsScopesOrRole() {
        val me = Cloud.parseMe("""{"schema":"poi-cloud-me/1","id":"t1","name":"Lan","scopes":["upload","read"],"admin":false,"role":"custom"}""")!!
        assertEquals(listOf("upload", "read"), me.scopes); assertTrue(me.canUpload); assertFalse(me.admin)
        assertEquals(listOf("upload"), Cloud.parseMe("""{"schema":"poi-cloud-me/1","id":"t2","name":"","role":"uploader"}""")!!.scopes)
        assertEquals("t2", Cloud.parseMe("""{"schema":"poi-cloud-me/1","id":"t2","name":"","role":"uploader"}""")!!.name)
        assertNull(Cloud.parseMe("<html>"))
        assertNull(Cloud.parseMe("""{"schema":"poi-cloud-me/1","name":"x"}"""))
    }

    @Test fun directUploadBookkeeping() {
        val d = Direct.parseCreate("""{"schema":"poi-cloud-direct/1","id":"u1","location":"https://h/files/u1","size":20000000,
            "partSize":8388608,"parts":3,"urls":[{"part":1,"url":"https://r2/1"},{"part":2,"url":"https://r2/2"}]}""")!!
        assertEquals(listOf(1, 2, 3), d.pending)
        assertEquals(8388608L, d.start(2)); assertEquals(20000000L - 2 * 8388608L, d.length(3))
        assertEquals(listOf(1, 2), Direct.parseUrls("""{"urls":[{"part":1,"url":"a"},{"part":2,"url":"b"}]}""").map { it.part })
        val e = d.withEtag(1, "\"e1\"").withEtag(3, "\"e3\"")
        assertEquals(listOf(2), e.pending); assertFalse(e.done)
        val back = Direct.state(e.toJson())!!
        assertEquals(listOf(2), back.pending); assertEquals(e.sent, back.sent)
        val done = back.withEtag(2, "e2")
        assertTrue(done.done)
        val parts = Json.parseToJsonElement(done.completeBody()).jsonObject["parts"]!!.jsonArray
        assertEquals(listOf(1, 2, 3), parts.map { it.jsonObject["part"]!!.jsonPrimitive.int })
        assertNull(Direct.parseCreate("""{"id":"u","location":"l","size":100,"partSize":10,"parts":3}"""))   // parts do not add up
        assertEquals("""{"parts":[4,5]}""", Direct.urlsBody(listOf(4, 5)))
        assertEquals("""{"size":5,"metadata":{"a":"b"}}""", Direct.createBody(5, mapOf("a" to "b")))
    }
}

class SweepRideTest {
    // a straight street drawn west → east at 10.78 N, then a cross street
    private val overpass = """{"elements":[
        {"type":"way","id":11,"tags":{"highway":"secondary","name":"Nguyễn Thị Minh Khai"},
         "geometry":[{"lat":10.78,"lon":106.690},{"lat":10.78,"lon":106.700}]},
        {"type":"way","id":12,"tags":{"highway":"residential"},
         "geometry":[{"lat":10.775,"lon":106.695},{"lat":10.785,"lon":106.695}]},
        {"type":"node","id":1}]}"""

    private fun ride(target: String = "shops", hand: Boolean = false): SweepRide {
        val r = SweepRide(target, hand, 30.0, 60.0)
        r.onFix(1_000_000L, 10.78, 106.6900, 5.0, Double.NaN, Double.NaN, Double.NaN, 1_000_000_000L)
        val cells = r.cellsToLoad()
        assertTrue(cells.isNotEmpty())
        assertTrue(r.cellsToLoad().isEmpty())   // asked once
        assertEquals(2, r.cellLoaded(cells[0], overpass, "overpass"))
        for (c in cells.drop(1)) r.cellLoaded(c, """{"elements":[]}""", "overpass")
        // riding east at ~5 m/s along the street
        for (i in 1..5) r.onFix(1_000_000L + i * 1000, 10.78, 106.6900 + i * 0.00005, 4.0, 12.0, 5.0, 90.0, 1_000_000_000L + i * 1_000_000_000L)
        return r
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun cellsAndQueriesAreTheAndroidOnes() {
        assertEquals("21338,2156", RideRoads.cellOf(10.78, 106.69))
        assertEquals("0.010000", RideRoads.fixed6(0.01)); assertEquals("-0.005000", RideRoads.fixed6(-0.005))
        assertEquals("[out:json][timeout:25];way(10.780000,106.690000,10.785000,106.695000)[highway][highway!~\"footway|path|steps|" +
            "cycleway|pedestrian|bridleway|corridor|elevator|platform|proposed|construction|service|track|bus_guideway|raceway|" +
            "abandoned|razed\"];out tags geom;", RideRoads.cellQuery("21338,2156"))
        assertNull(RideRoads.parseWays("<html>busy</html>"))
        assertNull(RideRoads.parseWays("""{"remark":"runtime error: timeout","elements":[]}"""))
        assertEquals(2, RideRoads.parseWays(overpass)!!.size)
    }

    @Test fun aPhotoOnAMatchedStreetCarriesItsRoadAndFacade() {
        val r = ride()
        assertEquals("Nguyễn Thị Minh Khai", r.roadName)
        assertEquals("forward", r.travel)
        assertEquals(6.0, r.facadeNow)                 // secondary road
        val line = json(r.onFix(1_010_000L, 10.78, 106.6903, 4.0, Double.NaN, 5.0, 90.0, 1_010_000_000L))
        assertEquals(JsonNull, line["alt"]); assertEquals(1_010_000_000L, line["en"]!!.jsonPrimitive.long)
        val item = json(r.photoItem("p_1", 1_010_500L, 1_010_500_000L, "auto", true,
            """{"cameraAzimuthDeg":181.0,"cameraElevationDeg":-3.0}""", """{"slide":0.31}""", 90))
        assertEquals("photo", item["kind"]!!.jsonPrimitive.content)
        assertEquals(11L, item["wayId"]!!.jsonPrimitive.long)
        assertEquals("Nguyễn Thị Minh Khai", item["roadName"]!!.jsonPrimitive.content)
        assertEquals("assumed", item["cameraBearingSource"]!!.jsonPrimitive.content)
        assertEquals(180.0, item["cameraBearing"]!!.jsonPrimitive.double)
        assertEquals("right", item["sideOfWay"]!!.jsonPrimitive.content)
        assertEquals("roadType", item["facadeSource"]!!.jsonPrimitive.content)
        assertEquals(0.31, item["overlap"]!!.jsonObject["slide"]!!.jsonPrimitive.double)
        assertEquals("landscape", item["orientation"]!!.jsonPrimitive.content)
        assertNull(item["gpsStale"])
        val done = r.completePhoto(item.toString(), 3840, 2160, 1, 0.002, 200, 6.86, 1_500_000L, 120.0, false)
        val o = json(done.json)
        assertEquals(3840, o["width"]!!.jsonPrimitive.int)
        assertFalse(o["blur"]!!.jsonObject["blurry"]!!.jsonPrimitive.boolean)
        assertFalse(done.unusable)
    }

    @Test fun aMountedPhoneLearnsItsYawFromTheCompass() {
        val r = ride()
        var t = 1_100_000L
        // the camera looks 60° right of travel (east): compass 150°
        repeat(20) { k ->
            t += 1000
            r.onFix(t, 10.78, 106.6903 + k * 0.00005, 4.0, Double.NaN, 5.0, 90.0, t * 1000)
            r.photoItem("p_$k", t, t * 1000, "auto", true, """{"cameraAzimuthDeg":150.0}""", "", 90)
        }
        assertNotNull(r.mountJson)
        assertEquals("compassMount", json(r.mountJson!!)["source"]!!.jsonPrimitive.content)
        val item = json(r.photoItem("p_x", t, t * 1000, "auto", true, """{"cameraAzimuthDeg":150.0}""", "", 90))
        assertEquals("compassMount", item["cameraBearingSource"]!!.jsonPrimitive.content)
        assertEquals(60.0, item["cameraYawToTravelDeg"]!!.jsonPrimitive.double)
        // an item taken before the estimate is corrected
        val early = """{"id":"p_0","cameraBearingSource":"assumed","travelBearing":90.0,"travel":"forward","lat":10.78,"lon":106.69}"""
        val fixed = json(r.remeasure(early)!!)
        assertEquals(150.0, fixed["cameraBearing"]!!.jsonPrimitive.double)
        assertNull(r.remeasure(item.toString()))
    }

    @Test fun signsLookAheadAndHandsAimEachPhoto() {
        val s = ride("signs")
        val si = json(s.photoItem("p_s", 1_010_000L, 1_010_000_000L, "auto", true, "", "", 90))
        assertEquals("forward", si["cameraBearingSource"]!!.jsonPrimitive.content)
        assertEquals(0.0, si["cameraYawToTravelDeg"]!!.jsonPrimitive.double)
        assertEquals(JsonNull, si["sideOfWay"]); assertNull(si["facadeLat"])
        assertTrue("\"trigger\":\"distance\"" in s.planJson())

        val h = ride("shops", hand = true)
        val left = json(h.photoItem("p_h", 1_010_000L, 1_010_000_000L, "auto", false, """{"cameraAzimuthDeg":0.0}""", "", 90))
        assertEquals("compass", left["cameraBearingSource"]!!.jsonPrimitive.content)
        assertEquals("left", left["sideOfTravel"]!!.jsonPrimitive.content)
        assertEquals(true, left["gpsStale"]!!.jsonPrimitive.boolean)
        val ahead = json(h.photoItem("p_a", 1_010_000L, 1_010_000_000L, "auto", true, """{"cameraAzimuthDeg":95.0}""", "", 90))
        assertEquals("front", ahead["sideOfTravel"]!!.jsonPrimitive.content)
    }

    @Test fun warningsAreCodes() {
        val r = ride("addresses")
        r.onFix(2_000_000L, 10.78, 106.6930, 45.0, Double.NaN, 9.0, 90.0, 2_000_000_000L)   // 32 km/h, weak GPS
        r.portrait = true
        val w = r.warnings()
        assertTrue("fast" in w); assertTrue("weakGps" in w); assertTrue("portrait" in w)
    }
}

class SweepShotTimerTest {
    @Test fun firstPhotoAtOnceThenBySlide() {
        val t = SweepShotTimer("shops", false)
        t.start(0)
        assertTrue(t.onFrame(100_000_000, Double.NaN, 50.0, Double.NaN, 0.0, 5.0, true, false, 6.0, 1.0, 1000))
        assertTrue(t.shot().contains("\"slide\""))
        // 10 frames of 2 % each: 20 % < 30 %, no photo
        var due = false
        for (k in 1..10) due = due || t.onFrame(100_000_000L + k * 100_000_000L, 0.02, 50.0, Double.NaN, 0.0, 5.0, true, false, 6.0, 1.0, 1000)
        assertFalse(due)
        assertEquals("frame", t.used)
        for (k in 11..20) due = due || t.onFrame(100_000_000L + k * 100_000_000L, 0.02, 50.0, Double.NaN, 0.0, 5.0, true, false, 6.0, 1.0, 1000)
        assertTrue(due)
    }

    @Test fun waitingAimingLowAndBusyHoldThePhoto() {
        val t = SweepShotTimer("shops", false)
        t.start(0)
        t.shot()
        assertFalse(t.onFrame(100_000_000, 0.5, 50.0, Double.NaN, 0.0, 0.2, true, false, 6.0, 1.0, 1000))   // standing still
        assertEquals("wait", t.used)
        assertFalse(t.onFrame(200_000_000, 0.5, 50.0, Double.NaN, -70.0, 5.0, true, false, 6.0, 1.0, 1000))
        assertTrue(t.aimLow)
        assertFalse(t.onFrame(300_000_000, 0.5, 50.0, Double.NaN, 0.0, 5.0, true, true, 6.0, 1.0, 1000))   // busy
        assertTrue(t.missedRecently(5000))
        assertFalse(t.missedRecently(20_000))
    }

    @Test fun signsGoByDistance() {
        val t = SweepShotTimer("signs", false)
        t.start(0)
        val plan = CapturePlan.of(4.0, null, 2560, SweepTarget.SIGNS)
        assertTrue(t.onFrame(100_000_000, Double.NaN, 50.0, Double.NaN, 0.0, 5.0, true, false, plan.coverageM, 1.0, 0))
        t.shot()
        // 5 m/s: 8 m after 1.6 s
        var at = -1
        for (k in 1..30) if (at < 0 && t.onFrame(100_000_000L + k * 100_000_000L, Double.NaN, 50.0, Double.NaN, 0.0, 5.0, true, false, plan.coverageM, 1.0, 0)) at = k
        assertEquals("distance", t.used)
        assertTrue(at in 10..12, "photo after $at frames")   // 30 % of 8 m / 45 % at a steady frame, as on Android
    }

    @Test fun framesLogLine() {
        val t = SweepShotTimer("shops", false)
        val line = t.frameLine(5, false, FrameReading(0.0123456, 12.34, 5, 4, 3, 0.9), 101.6, Double.NaN, 1920, 1080)
        assertEquals("5,0,0.01235,5,4,3,0.900,12.3,102,,,0.000,0,1920,1080", line)
        assertEquals(15, SweepShotTimer.FRAMES_HEADER.split(',').size)
    }
}

class SweepExportTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun sessionManifestAndMetadata() {
        var s = SweepExport.sessionDoc("s_1", 1_795_000_000_000L, "T01", "hand", "addresses", 4000, """{"platform":"ios"}""")
        s = SweepExport.withTime(s, "startedAt", 1_795_000_060_000L)
        s = SweepExport.withText(s, "name", "Nguyễn Thị Minh Khai 2026-11-19 09h29")
        s = SweepExport.withJson(s, "camera", """{"hFovDeg":63.4}""")
        assertEquals("hand", json(s)["holding"]!!.jsonPrimitive.content)
        assertEquals("addresses", json(s)["target"]!!.jsonPrimitive.content)
        assertEquals(63.4, json(s)["camera"]!!.jsonObject["hFovDeg"]!!.jsonPrimitive.double)
        assertEquals("Nguyễn Thị Minh Khai 2026-11-19 09h29", SweepExport.readText(s, "name"))
        val way = RideWay(11, "Nguyễn Thị Minh Khai", "", "secondary", "", listOf(LatLon(10.78, 106.69), LatLon(10.78, 106.70)))
        val items = listOf(
            """{"id":"p_2","kind":"photo","tMs":2,"roadName":"Nguyễn Thị Minh Khai","lat":10.78,"lon":106.695}""",
            """{"id":"p_1","kind":"photo","tMs":1,"roadName":"Nguyễn Thị Minh Khai","lat":10.78,"lon":106.694}""")
        val track = listOf(
            """{"t":1795000060000,"lat":10.78,"lon":106.690,"acc":4.0,"alt":null,"spd":5.0,"hdg":90.0,"en":1}""",
            """{"t":1795000061000,"lat":10.78,"lon":106.69005,"acc":4.0,"alt":null,"spd":5.0,"hdg":90.0,"en":2}""")
        val m = json(SweepExport.manifest(s, items, listOf(way.toJson(), way.toJson()), 2, true, false, 1_795_000_100_000L))
        assertEquals("poi-capture/3", m["schema"]!!.jsonPrimitive.content)
        assertEquals("sweep", m["kind"]!!.jsonPrimitive.content)
        assertEquals(listOf("p_1", "p_2"), m["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("photos/p_1.jpg", m["items"]!!.jsonArray[0].jsonObject["file"]!!.jsonPrimitive.content)
        assertEquals(1, m["session"]!!.jsonObject["road"]!!.jsonObject["ways"]!!.jsonArray.size)
        assertEquals("imu.csv", m["imuFile"]!!.jsonPrimitive.content); assertEquals(JsonNull, m["framesFile"])
        assertEquals("track.geojson", m["trackFile"]!!.jsonPrimitive.content)
        val name = SweepExport.fileName(s)
        assertEquals("poi-capture_auto_right_202611181107.zip", name)
        val meta = SweepExport.uploadMetadata(m.toString(), name, track)
        assertEquals("raw-session", meta["type"]); assertEquals("addresses", meta["target"]); assertEquals("2", meta["photoCount"])
        assertEquals("Nguyễn Thị Minh Khai", meta["street"]); assertEquals("5", meta["distanceM"]); assertNotNull(meta["track"])
        assertEquals("s_1", meta["sessionId"])
        val g = json(SweepExport.trackGeoJson(track))
        assertEquals(3, g["features"]!!.jsonArray.size)
        assertEquals(1L, g["features"]!!.jsonArray[1].jsonObject["properties"]!!.jsonObject["elapsedNanos"]!!.jsonPrimitive.long)
    }

    @Test fun aRideWithoutNamedStreetsTakesTheNearestBigRoad() {
        val big = RideWay(7, "Mai Chí Thọ", "", "primary", "", listOf(LatLon(10.780, 106.69), LatLon(10.780, 106.70)))
        val items = listOf("""{"kind":"photo","roadName":"Unnamed road (service)","lat":10.781,"lon":106.695}""")
        assertEquals("Mai Chí Thọ 2026-11-19 09h29", SweepExport.sessionName(items, listOf(big.toJson()), "2026-11-19 09h29", "?"))
        assertEquals("? 2026-11-19 09h29", SweepExport.sessionName(items, emptyList(), "2026-11-19 09h29", "?"))
    }

    @Test fun polylineIsGoogles() {
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@",
            TrackLine.encode(listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453)), 5))
        assertNull(TrackLine.capped(listOf(LatLon(1.0, 2.0)), 2000, 5.0))
    }
}
