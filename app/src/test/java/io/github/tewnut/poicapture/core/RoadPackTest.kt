package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CatalogueErrorTest {
    private val gh = "https://github.com/o/r/releases/download/roadpacks/index.json"
    private val other = "https://example.org/packs/index.json"

    @Test fun githubMissingReleaseIsNotATimeout() {
        assertTrue(CatalogueError.forHttp(504, gh).contains("chưa phát hành"))
        assertTrue(CatalogueError.forHttp(404, other).contains("chưa phát hành"))
    }

    @Test fun otherServersKeepTheGatewayMeaning() {
        assertTrue(CatalogueError.forHttp(504, other).contains("thử lại sau"))
        assertTrue(CatalogueError.forHttp(403, gh).contains("không cho truy cập"))
    }
}

/** Offline road packs: the fixture is built by tools/roadpack/build_roadpacks.py from tools/roadpack/test/fixture.osm. */
class RoadPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): File = tmp.newFile("fixture.zip").also { f ->
        javaClass.classLoader!!.getResourceAsStream("roadpack_fixture.zip")!!.use { s -> f.outputStream().use { s.copyTo(it) } }
    }

    private fun cellAt(lat: Double, lon: Double) = RoadCells.cellOf(LatLon(lat, lon))

    @Test fun readsThePythonBuiltPack() {
        RoadPack(fixture()).use { p ->
            assertEquals("fixture", p.id)
            assertTrue(p.source.startsWith("pack:fixture@"))
            assertTrue(p.attribution.contains("OpenStreetMap"))
            val west = p.ways(cellAt(21.0285, 105.8040))
            assertEquals(listOf(100L to 0), west.map { it.key })
            assertEquals("Phố Thử Nghiệm", west[0].name)
            assertEquals(105.8040, west[0].geometry.first().lon, 1e-9)
            val east = p.ways(cellAt(21.0285, 105.8060))
            assertEquals(listOf(100L to 1), east.map { it.key })
            assertTrue(p.ways(cellAt(21.0350, 105.8350)).isEmpty())           // inside the region, no road
            // footway and service were filtered out by the builder
            assertTrue((0..6).flatMap { dx -> p.ways(RoadCells.Cell(21160 + dx, 4205)) + p.ways(RoadCells.Cell(21160 + dx, 4206)) }
                .none { it.id == 300L || it.id == 400L })
        }
    }

    @Test fun aStreetCrossingACellBoundaryMatchesOnBothSidesAndKeepsAllPieces() {
        val packs = RoadPacks(tmp.newFolder("packs")).apply { install(fixture()) }
        val west = cellAt(21.0285, 105.8049)
        val near = (-1..1).flatMap { dx -> (-1..1).map { dy -> RoadCells.Cell(west.x + dx, west.y + dy) } }
            .flatMap { packs.forCell(it)?.ways(it) ?: emptyList() }.distinctBy { it.key }
        assertEquals(2, near.count { it.id == 100L })
        val p = FixProcessor(near, Side.RIGHT, null, 20, 30.0, fixedRoad = false)
        for ((i, lon) in listOf(105.80490, 105.80495, 105.80505, 105.80520).withIndex()) {
            p.onFix(Fix(i * 1000L, 21.02852, lon, 5.0, speedMps = 4.0, bearingDeg = 90.0))
            assertEquals("at $lon", "Phố Thử Nghiệm", p.roadName())
            assertEquals(100L, p.itemMeta("p", "photo", 1L, Side.RIGHT).getLong("wayId"))
        }
        // both pieces reach the manifest (merged by (id, piece), not by id)
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = Session(id = "s_p", createdAt = "2026-11-09T01:00:00Z", contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(sources = listOf("pack:fixture@x")), settings = Settings())
        store.create(s)
        store.appendWays(s.id, near)
        store.appendWays(s.id, near)                                           // loaded twice: still one copy each
        val road = Exporter.manifest(store, s.id).getJSONObject("session").getJSONObject("road")
        val keys = (0 until road.getJSONArray("ways").length()).map { road.getJSONArray("ways").getJSONObject(it).let { w -> w.getLong("id") to w.optInt("piece") } }
        assertEquals(setOf(100L to 0, 100L to 1), keys.filter { it.first == 100L }.toSet())
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("pack:fixture@x", road.getJSONArray("sources").getString(0))
    }

    @Test fun aLongStraightSegmentIsFoundFromTheMiddleOfIt() {
        val packs = RoadPacks(tmp.newFolder("packs")).apply { install(fixture()) }
        val mid = cellAt(21.0300, 105.8200)                                     // 1 km from both ends of way 200
        val near = (-1..1).flatMap { dx -> (-1..1).map { dy -> RoadCells.Cell(mid.x + dx, mid.y + dy) } }
            .flatMap { packs.forCell(it)?.ways(it) ?: emptyList() }
        val m = matchRoad(near, LatLon(21.03003, 105.8200), 90.0)
        assertNotNull(m)
        assertEquals(200L, m!!.wayId)
        assertTrue(m.distanceM < 5)
    }

    @Test fun lookupOrderIsPackThenCacheAndAnEmptyCellInAPackIsFinal() {
        val packs = RoadPacks(tmp.newFolder("packs")).apply { install(fixture()) }
        val cache = CellCache(tmp.newFolder("cache"))
        val emptyInPack = cellAt(21.0350, 105.8350)
        val hit = RoadData.local(emptyInPack, packs, cache)
        assertNotNull(hit)                                                      // not null: do not ask Overpass
        assertTrue(hit!!.ways.isEmpty())
        assertTrue(hit.source.startsWith("pack:fixture"))
        val outside = cellAt(10.77, 106.70)
        assertNull(RoadData.local(outside, packs, cache))                       // no pack, nothing cached: Overpass
        cache.put(outside, listOf(WAY))
        val cached = RoadData.local(outside, packs, cache)!!
        assertEquals("cache", cached.source)
        assertEquals(WAY.geometry.size, cached.ways.single().geometry.size)
    }

    @Test fun cacheExpiresAndStaysBounded() {
        val cache = CellCache(tmp.newFolder("cache"), maxBytes = 3_000)
        val c = cellAt(21.0285, 105.8040)
        cache.put(c, listOf(WAY))
        assertNotNull(cache.get(c))
        assertNull(cache.get(c, nowMs = System.currentTimeMillis() + 31L * 24 * 3600_000))   // older than 30 days
        for (i in 0..20) cache.put(RoadCells.Cell(c.x + i, c.y), listOf(WAY))
        assertTrue(cache.dir.listFiles()!!.sumOf { it.length() } <= 3_000)
    }

    @Test fun packsBuiltWithAnotherFilterOrFormatAreRefused() {
        fun pack(header: JSONObject): File = tmp.newFile().also { f ->
            ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("pack.json")); z.write(header.toString().toByteArray()); z.closeEntry() }
        }
        val ok = JSONObject().put("magic", RoadPack.MAGIC).put("format", RoadPack.FORMAT).put("id", "x").put("cellSize", RoadCells.SIZE)
            .put("skip", Overpass.SKIP_AREA).put("bbox", org.json.JSONArray(listOf(21.0, 105.0, 21.1, 105.1)))
        RoadPack(pack(ok)).close()
        for (bad in listOf(JSONObject(ok.toString()).put("skip", "footway"), JSONObject(ok.toString()).put("format", 99),
                JSONObject(ok.toString()).put("cellSize", 0.01))) {
            try { RoadPack(pack(bad)).close(); fail("accepted $bad") } catch (e: IOException) { /* expected */ }
        }
        val packs = RoadPacks(tmp.newFolder("packs"))
        try { packs.install(pack(JSONObject(ok.toString()).put("skip", "footway"))); fail() } catch (e: IOException) { }
        assertTrue(packs.installed().isEmpty())
    }

    @Test fun encodeDecodeRoundTrip() {
        val w = Way(7, "Phố A", "", "residential", "yes", listOf(LatLon(21.0285, 105.804), LatLon(21.02851, 105.80412)), piece = 3)
        assertEquals(w, RoadPack.decode(RoadPack.encode(w)))
    }
}
