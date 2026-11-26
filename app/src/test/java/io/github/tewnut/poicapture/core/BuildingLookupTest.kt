package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** Indoor 0.22: the building lookup's queries and parsing, the cache, floor plans in the session and the export. */
class BuildingLookupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(lat: Double, lon: Double) = JSONObject().put("lat", lat).put("lon", lon)
    private fun geom(vararg p: Pair<Double, Double>) = JSONArray().apply { p.forEach { put(node(it.first, it.second)) } }
    private fun answer(vararg els: JSONObject) = JSONObject().put("elements", JSONArray(els.toList())).toString()
    private val mallArea = geom(10.8 to 106.74, 10.8 to 106.741, 10.801 to 106.741, 10.801 to 106.74, 10.8 to 106.74)

    @Test fun buildingsQueryAlsoAsksForMallAndMarketAreas() {
        val q = BuildingQuery.buildingsQuery(LatLon(10.8, 106.74))
        for (f in listOf("[building]", "[shop=mall]", "[amenity=marketplace]")) {
            assertTrue("pivot $f", q.contains("way(pivot.a)$f;"))
            assertTrue("around $f", q.contains("way(around:150,10.800000,106.740000)$f;"))
        }
        assertTrue(q.startsWith("[out:json][timeout:25];is_in(10.800000,106.740000)->.a;("))
        assertTrue(q.endsWith(");out tags geom;"))
    }

    @Test fun aMallMappedOnlyAsShopMallIsTheBuilding() {
        val body = answer(
            JSONObject().put("type", "way").put("id", 7).put("geometry", mallArea)
                .put("tags", JSONObject().put("shop", "mall").put("name", "Vạn Hạnh Mall")),
            JSONObject().put("type", "way").put("id", 8).put("geometry", mallArea)
                .put("tags", JSONObject().put("amenity", "marketplace").put("name", "Chợ Bến Thành")),
            JSONObject().put("type", "way").put("id", 9).put("geometry", mallArea).put("tags", JSONObject().put("shop", "clothes")),
        )
        val all = BuildingQuery.parseBuildings(body)
        assertEquals(listOf("way/7", "way/8"), all.map { it.osm })
        val b = BuildingQuery.pick(all, LatLon(10.8005, 106.7405))
        assertEquals("Vạn Hạnh Mall", b!!.name)
    }

    @Test fun nameRegexToleratesAccentsAndCase() {
        assertEquals("van hanh", BuildingQuery.fold("Vạn  Hạnh".replace("  ", " ")))
        val re = BuildingQuery.nameRegex("van hanh")
        assertTrue(re.contains("(a|à|á|ả|ã|ạ|ă|ằ|ắ|ẳ|ẵ|ặ|â|ầ|ấ|ẩ|ẫ|ậ|A|À|"))
        // the server's regex is POSIX-like; Java's agrees on these simple alternations
        val rx = Regex(re, RegexOption.IGNORE_CASE)
        for (name in listOf("Vạn Hạnh Mall", "VAN HANH", "Trung tâm Van  Hanh")) assertTrue(name, rx.containsMatchIn(name))
        assertFalse(Regex(BuildingQuery.nameRegex("vincom"), RegexOption.IGNORE_CASE).containsMatchIn("Vạn Hạnh Mall"))
        assertTrue(Regex(BuildingQuery.nameRegex("Đồng Khởi"), RegexOption.IGNORE_CASE).containsMatchIn("Vincom Center Dong Khoi"))
        assertTrue(Regex(BuildingQuery.nameRegex("AEON-Mall"), RegexOption.IGNORE_CASE).containsMatchIn("Aeon Mall Tân Phú"))
    }

    @Test fun nameSearchQuery() {
        val q = BuildingQuery.nameSearchQuery("Vincom", LatLon(10.8, 106.7))
        assertTrue(q, q.contains("way[building][name~\""))
        assertTrue(q.contains("node[shop=mall][name~\""))
        assertTrue(q.contains("\",i]("))
        // a box of ±30 km
        assertTrue(q, q.contains("(10.5305"))
        assertTrue(q.endsWith(");out tags geom;"))
        assertFalse("no quote inside the regex", BuildingQuery.nameRegex("a\"b").contains('"'))
    }

    @Test fun nameSearchAnswerNearestFirstWithPoints() {
        val from = LatLon(10.79, 106.74)
        val body = answer(
            JSONObject().put("type", "way").put("id", 7).put("geometry", mallArea)
                .put("tags", JSONObject().put("shop", "mall").put("name", "Vincom Thảo Điền")),
            JSONObject().put("type", "node").put("id", 5).put("lat", 10.85).put("lon", 106.77)
                .put("tags", JSONObject().put("shop", "mall").put("name", "Vincom Plaza")),
            JSONObject().put("type", "node").put("id", 6).put("lat", 10.85).put("lon", 106.77).put("tags", JSONObject().put("shop", "mall")),
        )
        val hits = BuildingQuery.parseSearch(body, from)
        assertEquals(listOf("way/7", "node/5"), hits.map { it.osm })
        assertNotNull(hits[0].building)
        assertNull(hits[1].building)
        assertEquals("mall", hits[1].kind)
        assertTrue(hits[0].distanceM in 900.0..1200.0)
    }

    @Test fun cacheKeepsTheLastBuildings() {
        val cache = BuildingCache(File(tmp.root, "b.json"), max = 2)
        val b = BuildingQuery.parseBuildings(answer(JSONObject().put("type", "way").put("id", 7).put("geometry", mallArea)
            .put("tags", JSONObject().put("building", "retail").put("name", "Mall")))).single()
        assertNull(cache.near(LatLon(10.8005, 106.7405)))
        cache.put(b)
        assertEquals("way/7", cache.near(LatLon(10.8005, 106.7405))?.osm)
        assertNull(cache.near(LatLon(10.9, 106.9)))
        cache.put(b.copy(osm = "way/8")); cache.put(b.copy(osm = "way/9")); cache.put(b.copy(osm = "way/9"))
        assertEquals(listOf("way/9", "way/8"), cache.all().map { it.osm })
    }

    private val plan = FloorPlanEntry("2", "p_1", "plans/plan_2_1.jpg", 2048, 1365, "manual",
        listOf(Xy(0.12, 0.08), Xy(0.91, 0.10), Xy(0.93, 0.88), Xy(0.10, 0.86)),
        PlanToMap(LatLon(10.80213, 106.74122), 0.061, 33.5), "footprint", Xy(812.0, 640.0), 123456789L,
        listOf(PlanUnit("L2-15", 812.0, 640.0, 18.0)))

    @Test fun planJsonAsInTheDoc() {
        val o = plan.toJson()
        assertEquals("[2048,1365]", o.getJSONArray("size").toString())
        assertEquals("[0.12,0.08]", o.getJSONArray("corners").getJSONArray(0).toString())
        assertEquals("[10.80213,106.74122]", o.getJSONObject("toMap").getJSONArray("origin").toString())
        assertEquals(0.061, o.getJSONObject("toMap").getDouble("scaleMPerPx"), 1e-12)
        assertEquals("[812,640]", o.getJSONArray("youAreHere").toString())
        assertEquals("manual", o.getString("capture"))
        assertEquals("L2-15", o.getJSONArray("units").getJSONObject(0).getString("unit"))
        assertEquals(plan, FloorPlanEntry.fromJson(JSONObject(o.toString())))
        // scanned: no corners
        val scanned = plan.copy(capture = "mlkit-scanner", corners = null, toMap = null)
        val j = scanned.toJson()
        assertTrue(j.isNull("corners")); assertTrue(j.isNull("toMap"))
        assertEquals(scanned, FloorPlanEntry.fromJson(j))
        assertEquals("plans/plan_2_2.jpg", FloorPlanEntry.fileName("2", listOf(plan)))
        assertEquals("plans/plan_B1_1.jpg", FloorPlanEntry.fileName("B1", listOf(plan)))
    }

    @Test fun plansInTheSessionAndTheExport() {
        val store = SessionStore(tmp.root)
        val b = Building("way/1", "Mall", 4, null, listOf(listOf(LatLon(10.8, 106.74), LatLon(10.8, 106.741), LatLon(10.801, 106.741))),
            plans = listOf(plan))
        val s = Session(id = "s_1", createdAt = "2026-11-26T00:00:00Z", contributor = "", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(),
            kind = SessionKind.INDOOR, holding = Holding.HAND, building = b, indoor = JSONObject())
        store.create(s)
        assertEquals(listOf(plan), store.load("s_1")!!.building!!.plans)
        File(store.plansDir("s_1"), "plan_2_1.jpg").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(10) { 1 })
        File(store.plansDir("s_1"), "plans.json").writeText(FloorPlanEntry.listJson(listOf(plan)).toString())
        val zip = File(tmp.root, "x.zip")
        zip.outputStream().use { Exporter.writeZip(store, "s_1", it) }
        ZipFile(zip).use { z ->
            assertNotNull(z.getEntry("plans/plan_2_1.jpg"))
            assertNotNull(z.getEntry("plans/plans.json"))
            val m = JSONObject(z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals("plans/plans.json", m.getString("plansFile"))
            assertEquals("plans/plan_2_1.jpg", m.getJSONObject("session").getJSONObject("building").getJSONArray("plans")
                .getJSONObject(0).getString("file"))
        }
    }
}
