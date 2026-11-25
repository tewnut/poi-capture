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
import java.util.zip.ZipFile

/** Indoor mode (docs/indoor.md): the building from Overpass, the session fields, the export, no split per street. */
class IndoorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(lat: Double, lon: Double) = JSONObject().put("lat", lat).put("lon", lon)
    private fun geom(vararg p: Pair<Double, Double>) = JSONArray().apply { p.forEach { put(node(it.first, it.second)) } }

    // a 100 m × 100 m mall at 10.8 / 106.74, and a small house 300 m east
    private val mall = geom(10.8 to 106.74, 10.8 to 106.741, 10.801 to 106.741, 10.801 to 106.74, 10.8 to 106.74)
    private val house = geom(10.8 to 106.743, 10.8 to 106.7431, 10.8001 to 106.7431, 10.8001 to 106.743, 10.8 to 106.743)

    private fun answer(vararg els: JSONObject) = JSONObject().put("elements", JSONArray(els.toList())).toString()

    private val buildings = answer(
        JSONObject().put("type", "way").put("id", 1).put("geometry", mall).put("tags", JSONObject()
            .put("building", "retail").put("name", "Vincom Mega Mall").put("building:levels", "4")
            .put("building:levels:underground", "2").put("addr:street", "Xa lộ Hà Nội").put("addr:housenumber", "161")),
        JSONObject().put("type", "way").put("id", 2).put("geometry", house).put("tags", JSONObject().put("building", "house")),
        JSONObject().put("type", "way").put("id", 3).put("geometry", house).put("tags", JSONObject().put("highway", "service")),
    )

    @Test fun theBuildingContainingThePointElseTheNearest() {
        val all = BuildingQuery.parseBuildings(buildings)
        assertEquals(listOf("way/1", "way/2"), all.map { it.osm })
        val b = BuildingQuery.pick(all, LatLon(10.8005, 106.7405))!!
        assertEquals("Vincom Mega Mall", b.name)
        assertEquals(4, b.levels); assertEquals(2, b.underground)
        assertEquals("Xa lộ Hà Nội", b.addr["addr:street"])
        // just outside the mall, nearer to it than to the house
        assertEquals("way/1", BuildingQuery.pick(all, LatLon(10.8005, 106.7412))?.osm)
        // nothing within 150 m
        assertNull(BuildingQuery.pick(all, LatLon(10.81, 106.74)))
        assertTrue(BuildingQuery.buildingsQuery(LatLon(10.8, 106.74)).contains("is_in(10.800000,106.740000)"))
    }

    @Test fun multipolygonRingsAreAssembledWithHoles() {
        val rel = JSONObject().put("type", "relation").put("id", 9).put("tags", JSONObject().put("building", "yes").put("type", "multipolygon"))
            .put("members", JSONArray()
                .put(JSONObject().put("type", "way").put("role", "outer").put("geometry", geom(10.8 to 106.74, 10.8 to 106.741, 10.801 to 106.741)))
                .put(JSONObject().put("type", "way").put("role", "outer").put("geometry", geom(10.8 to 106.74, 10.801 to 106.74, 10.801 to 106.741)))
                .put(JSONObject().put("type", "way").put("role", "inner").put("geometry",
                    geom(10.8004 to 106.7404, 10.8004 to 106.7406, 10.8006 to 106.7406, 10.8006 to 106.7404, 10.8004 to 106.7404))))
        val b = BuildingQuery.parseBuildings(answer(rel)).single()
        assertEquals("relation/9", b.osm)
        assertEquals(1, b.outer.size); assertEquals(4, b.outer[0].size)
        assertEquals(1, b.holes.size)
        assertTrue(b.contains(LatLon(10.8002, 106.7402)))
        assertFalse(b.contains(LatLon(10.8005, 106.7405)))   // the courtyard
    }

    @Test fun shopsInsideTheFootprintWithTheirLevel() {
        val b = BuildingQuery.parseBuildings(buildings).first()
        val features = BuildingQuery.parseFeatures(answer(
            JSONObject().put("type", "node").put("id", 5).put("lat", 10.8005).put("lon", 106.7405).put("tags", JSONObject()
                .put("shop", "clothes").put("name", "Uniqlo").put("level", "1").put("addr:unit", "L2-15")),
            JSONObject().put("type", "node").put("id", 6).put("lat", 10.81).put("lon", 106.7405).put("tags", JSONObject().put("shop", "bakery")),
            JSONObject().put("type", "way").put("id", 7).put("geometry", geom(10.8001 to 106.7401, 10.8001 to 106.7402, 10.8002 to 106.7402, 10.8001 to 106.7401))
                .put("tags", JSONObject().put("indoor", "room").put("level", "-1")),
        ), b)
        assertEquals(listOf("node/5", "way/7"), features.map { it.osm })
        assertEquals("L2-15", features[0].unit)
        assertTrue(features[0].onLevel(1)); assertFalse(features[0].onLevel(0))
        assertEquals("room", features[1].kind); assertTrue(features[1].onLevel(-1))
        assertTrue(b.copy(features = features).indoorMapped)
    }

    private fun indoorSession(b: Building?) = Session(
        id = "s_in", createdAt = "2026-11-25T03:00:00Z", startedAt = "2026-11-25T03:01:00Z", contributor = "t", note = "",
        vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(),
        kind = SessionKind.INDOOR, holding = Holding.HAND, building = b?.copy(levelsSeen = listOf("B1", "1", "2")),
        indoor = JSONObject().put("stepLengthM", 0.71).put("anchors", 7),
    )

    @Test fun sessionJsonCarriesTheBuildingAsTheDocSays() {
        val b = BuildingQuery.parseBuildings(buildings).first()
        val o = indoorSession(b).toJson()
        assertEquals("indoor", o.getString("kind"))
        assertEquals("shops", o.getString("target"))
        val bj = o.getJSONObject("building")
        assertEquals("way/1", bj.getString("osm"))
        assertEquals(4, bj.getInt("levels")); assertEquals(2, bj.getInt("underground"))
        assertEquals(10.8, bj.getJSONArray("footprint").getJSONArray(0).getDouble(0), 1e-9)   // [lat, lon]
        assertEquals(1, bj.getJSONArray("outer").length())
        assertEquals(0, bj.getJSONArray("holes").length())
        assertEquals(listOf("B1", "1", "2"), bj.getJSONArray("levels_seen").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertFalse(bj.getBoolean("indoorMapped"))
        assertEquals(0.71, o.getJSONObject("indoor").getDouble("stepLengthM"), 1e-9)
        val back = Session.fromJson(o)
        assertEquals(SessionKind.INDOOR, back.kind)
        assertEquals(b.footprint, back.building!!.footprint)
        assertEquals(7, back.indoor!!.getInt("anchors"))
        // no building: walked without one
        assertTrue(indoorSession(null).toJson().isNull("building"))
        // other kinds do not carry the fields
        assertFalse(Session.fromJson(o.put("kind", "sweep")).toJson().has("building"))
    }

    @Test fun exportHasIndoorLogAndPdrTrack() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = indoorSession(BuildingQuery.parseBuildings(buildings).first())
        store.create(s)
        store.indoorFile(s.id).writeText("{\"t\":1,\"type\":\"step\"}\n{\"t\":2,\"type\":\"yaw\",\"deg\":183.2}\n")
        store.trackFile(s.id).writeText(JSONObject().put("t", 1000L).put("lat", 10.8005).put("lon", 106.7405).put("acc", 3.0)
            .put("source", "pdr").put("level", "2").put("sinceAnchorM", 12.5).toString() + "\n")
        store.appendItem(s.id, JSONObject().put("id", "p_1").put("kind", "photo").put("tMs", 1).put("lat", 10.8005).put("lon", 106.7405)
            .put("level", "2").put("positionSource", "pdr").put("sinceAnchorM", 12.5).put("cameraBearing", 90.0)
            .put("cameraBearingSource", "pdr-yaw").put("trigger", "directory"))
        store.photoFile(s.id, "p_1").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val zip = tmp.newFile("x.zip")
        zip.outputStream().use { Exporter.writeZip(store, s.id, it) }
        ZipFile(zip).use { z ->
            assertNotNull(z.getEntry("indoor.jsonl"))
            val m = JSONObject(z.getInputStream(z.getEntry("manifest.json")).reader().readText())
            assertEquals("indoor", m.getString("kind"))
            assertEquals("indoor.jsonl", m.getString("indoorFile"))
            assertEquals("way/1", m.getJSONObject("session").getJSONObject("building").getString("osm"))
            val item = m.getJSONArray("items").getJSONObject(0)
            assertEquals("directory", item.getString("trigger")); assertEquals("2", item.getString("level"))
            val track = JSONObject(z.getInputStream(z.getEntry("track.geojson")).reader().readText())
            val point = track.getJSONArray("features").getJSONObject(1).getJSONObject("properties")
            assertEquals("pdr", point.getString("source")); assertEquals("2", point.getString("level"))
            assertEquals(12.5, point.getDouble("sinceAnchorM"), 1e-9)
        }
        assertTrue(Exporter.fileName(store.load(s.id)!!).startsWith("poi-capture_indoor-Vincom-Mega-Mall_"))
        assertFalse(Exporter.fileName(store.load(s.id)!!).contains("_right"))
    }

    @Test fun anIndoorWalkIsNeverSplitPerStreet() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = indoorSession(null)
        store.create(s)
        // photos that a sweep would cut in two (two streets)
        for (i in 0 until 60) store.appendItem(s.id, JSONObject().put("id", "p_$i").put("kind", "photo").put("tMs", i * 2000L)
            .put("roadName", if (i < 30) "Trường Sa" else "Hoàng Sa"))
        assertFalse(SessionSplit.splittable(s))
        assertEquals(emptyList<String>(), SessionSplit.split(store, s.id, "?"))
        assertEquals("shops", sessionUploadMetadata(s, "x.zip", 60)["target"])
    }
}
