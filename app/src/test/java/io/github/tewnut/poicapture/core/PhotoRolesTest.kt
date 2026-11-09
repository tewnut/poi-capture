package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoRolesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val d = PhotoRoles.DEFAULT

    @Test fun advancesToTheNextKindWithoutAPhoto() {
        // after the front: the sign; after the sign: side (first without a photo)
        assertEquals("sign", d.next("facade", mapOf("facade" to 1)))
        assertEquals("side", d.next("sign", mapOf("facade" to 1, "sign" to 1)))
        // sign already shot before the front: skip it
        assertEquals("side", d.next("facade", mapOf("facade" to 1, "sign" to 2)))
        // kinds without "advance" keep the selection (several inside photos in a row)
        assertEquals("inside", d.next("inside", mapOf("inside" to 1)))
        // wraps round to a kind earlier in the list that still has no photo
        val r = PhotoRoles(listOf(PhotoRole("a", "A"), PhotoRole("b", "B", advance = true)))
        assertEquals("a", r.next("b", mapOf("b" to 1)))
        assertEquals("b", r.next("b", mapOf("a" to 1, "b" to 1)))
    }

    @Test fun readsAConfigAndRejectsBadOnes() {
        val ok = PhotoRoles.fromJson(JSONObject("""{"photoRoles":[{"id":"front","label":"Mặt tiền","cover":true},{"id":"menu","label":"Thực đơn","hint":"đọc được giá"}]}"""), "server")!!
        assertEquals(listOf("front", "menu"), ok.roles.map { it.id })
        assertEquals("front", ok.cover.id)
        assertEquals("Thực đơn", ok.label("menu"))
        assertEquals("Sign", ok.label("sign"))   // an id from the default list still has a label
        assertEquals("server", ok.source)
        // the cover defaults to the first kind
        assertEquals("x", PhotoRoles.fromJson(JSONArray("""[{"id":"x","label":"X"},{"id":"y","label":"Y"}]"""), "local")!!.cover.id)
        assertNull(PhotoRoles.fromJson(JSONObject("""{"photoRoles":[]}"""), "server"))
        assertNull(PhotoRoles.fromJson(JSONObject("""{"photoRoles":[{"id":"Mặt Tiền","label":"x"}]}"""), "server"))
        assertNull(PhotoRoles.fromJson(JSONObject("""{"photoRoles":[{"id":"a","label":"A"},{"id":"a","label":"B"}]}"""), "server"))
        assertNull(PhotoRoles.fromJson(JSONObject("""{"photoRoles":[{"id":"a","label":" "}]}"""), "server"))
        // round trip
        assertEquals(d.roles, PhotoRoles.fromJson(d.toJson(), "default")!!.roles)
    }

    @Test fun editsAndSlugs() {
        assertEquals("bai-do-xe", PhotoRoles.slug("Bãi đỗ xe", emptyList()))
        assertEquals("duong-vao-2", PhotoRoles.slug("Đường vào", listOf("duong-vao")))
        assertEquals("anh", PhotoRoles.slug("!!!", emptyList()))
        val moved = d.moved("sign", -1)
        assertEquals(listOf("sign", "facade"), moved.roles.take(2).map { it.id })
        assertEquals("local", moved.source)
        assertEquals(5, d.without("context").roles.size)
    }

    @Test fun withoutKindsTheCoverIsTheFirstPhoto() {
        val store = SessionStore(tmp.newFolder("plain"))
        val s = Session(id = "s_n", createdAt = "2026-11-09T01:00:00Z", contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_MANUAL, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), kind = SessionKind.POI)
        store.create(s)
        assertNull(store.load("s_n")!!.photoRoles)
        store.appendPoi("s_n", Pois.new("poi_1", 1L, null))
        val photos = listOf("p_1", "p_2").map { JSONObject().put("id", it).put("kind", "photo").put("poiId", "poi_1") }
        val poi = Exporter.poisWithPhotos(store, "s_n", photos).single()
        assertEquals("p_1", poi.getString("cover"))
        assertEquals(2, poi.getJSONArray("photos").length())
    }

    @Test fun builtInKindsAreTranslatedUnlessRenamedOrFromTheServer() {
        val facade = PhotoRoles.DEFAULT.roles.first()
        assertTrue(PhotoRoles.isBuiltIn(facade, "default"))
        assertTrue(PhotoRoles.isBuiltIn(facade, "local"))                                  // only reordered / trimmed
        assertTrue(PhotoRoles.isBuiltIn(PhotoRole("facade", "Mặt tiền"), "local"))  // saved before the translation
        assertFalse(PhotoRoles.isBuiltIn(PhotoRole("facade", "Shopfront"), "local"))       // renamed by the contributor
        assertFalse(PhotoRoles.isBuiltIn(facade, "server"))                                // the team's own wording
        assertFalse(PhotoRoles.isBuiltIn(PhotoRole("menu", "Menu"), "default"))
    }

    @Test fun coverFollowsTheSessionConfig() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val roles = PhotoRoles(listOf(PhotoRole("sign", "Biển hiệu"), PhotoRole("menu", "Thực đơn", cover = true)), "local")
        val s = Session(id = "s_r", createdAt = "2026-11-09T01:00:00Z", contributor = "t", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_MANUAL, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), kind = SessionKind.POI,
            photoRoles = roles)
        store.create(s)
        assertEquals(listOf("sign", "menu"), store.load("s_r")!!.photoRoles!!.roles.map { it.id })
        store.appendPoi("s_r", Pois.new("poi_1", 1L, null))
        val photos = listOf("p_1" to "sign", "p_2" to "menu").map { (id, r) ->
            JSONObject().put("id", id).put("kind", "photo").put("poiId", "poi_1").put("role", r)
        }
        assertEquals("p_2", Exporter.poisWithPhotos(store, "s_r", photos).single().getString("cover"))
    }
}
