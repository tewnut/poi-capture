package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

class PolylineTest {
    @Test fun encodesGoogleVector() {
        val pts = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(pts))
        assertEquals("", Polyline.encode(emptyList()))
    }

    @Test fun simplifyDropsStraightLinePointsKeepsEndpointsAndCorners() {
        // 0..100 east along the equator, then north: the middle points of each leg go, the corner stays
        val east = (0..100).map { LatLon(0.0, it * 1e-5) }
        val north = (1..100).map { LatLon(it * 1e-5, 100e-5) }
        val s = Polyline.simplify(east + north, 5.0)
        assertEquals(listOf(LatLon(0.0, 0.0), LatLon(0.0, 100e-5), LatLon(100e-5, 100e-5)), s)
        assertEquals(listOf(LatLon(1.0, 1.0)), Polyline.simplify(listOf(LatLon(1.0, 1.0)), 5.0))
    }

    /** A long wiggly ride (≈ 1 fix / 10 m over ~100 km): too long at 5 m, so the tolerance grows until it fits. */
    @Test fun cappedRespectsTheLimitAndKeepsEndpoints() {
        val pts = (0 until 10_000).map { i -> LatLon(10.8 + i * 9e-5, 106.7 + 0.002 * sin(i / 7.0)) }
        assertTrue(Polyline.encode(Polyline.simplify(pts, 5.0)).length > 2000)
        val enc = Polyline.capped(pts)!!
        assertTrue(enc.length <= 2000)
        val small = Polyline.capped(pts, maxChars = 200)!!
        assertTrue(small.length <= 200)
        // endpoints kept: the encoding starts with the first point
        assertTrue(enc.startsWith(Polyline.encode(listOf(pts.first()))))
        assertNull(Polyline.capped(listOf(LatLon(1.0, 1.0))))
        assertNull(Polyline.capped(emptyList()))
    }

    @Test fun trackPointsUseTheAccuracyFilter() {
        val f = File.createTempFile("track", ".jsonl")
        try {
            f.writeText("""
                {"t":1000,"lat":10.0,"lon":106.0,"acc":5}
                {"t":2000,"lat":10.5,"lon":106.5,"acc":80}
                not json
                {"t":3000,"event":"pause"}
                {"t":4000,"lat":10.001,"lon":106.001,"acc":10}
            """.trimIndent())
            assertEquals(listOf(LatLon(10.0, 106.0), LatLon(10.001, 106.001)), Polyline.trackPoints(f))
        } finally { f.delete() }
        assertEquals(emptyList<LatLon>(), Polyline.trackPoints(File("does-not-exist.jsonl")))
    }

    private fun session() = Session(id = "s_t", createdAt = "2026-11-09T01:00:00Z", startedAt = "2026-11-09T01:00:05Z",
        endedAt = "2026-11-09T01:30:00Z", contributor = "lan", note = "", vehicle = Vehicle.UNSPECIFIED,
        mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), email = "lan@gmail.com")

    @Test fun uploadMetadataCarriesTheTrip() {
        val f = File.createTempFile("track", ".jsonl")
        try {
            f.writeText((0 until 50).joinToString("\n") { i -> """{"t":${1000 + i * 1000},"lat":${10.0 + i * 5e-5},"lon":106.0,"acc":5}""" })
            val m = sessionUploadMetadata(session(), "x.zip", 7, f)
            assertEquals("2026-11-09T01:00:05Z", m["startedAt"])
            assertEquals("2026-11-09T01:30:00Z", m["endedAt"])
            assertEquals("7", m["photos"])
            assertEquals("7", m["photoCount"])
            assertEquals("raw-session", m["type"])
            val d = m["distanceM"]!!.toLong()
            assertTrue("distance $d", d in 260..285)        // 49 × ~5.5 m
            assertEquals(Polyline.encode(listOf(LatLon(10.0, 106.0), LatLon(10.0 + 49 * 5e-5, 106.0))), m["track"])
            // nothing private, and it goes in the direct upload's JSON
            assertFalse(m.values.any { it?.contains("lan") == true })
            DirectUpload.createBody(10, m)

            f.writeText("""{"t":1000,"lat":10.0,"lon":106.0,"acc":5}""")
            assertNull(sessionUploadMetadata(session(), "x.zip", 0, f)["track"])
        } finally { f.delete() }
        val none = sessionUploadMetadata(session(), "x.zip", 0)
        assertNull(none["track"]); assertNull(none["distanceM"])
    }

    @Test fun clientHeader() {
        assertEquals("POI Capture/0.16.0 (Android 14; samsung SM-A546E)", Tus.clientHeader("0.16.0", "14", "samsung", "SM-A546E"))
        assertEquals("POI Capture/1 (Android 15; Pixel ?)", Tus.clientHeader("1", "15", null, "Pixel é"))
        val long = Tus.clientHeader("0.16.0", "14", "x".repeat(200), "y")
        assertEquals(120, long.length)
        assertTrue(long.startsWith("POI Capture/0.16.0 (Android 14; xxx"))
    }
}
