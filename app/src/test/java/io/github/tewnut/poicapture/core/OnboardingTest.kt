package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test fun emailFormatOnly() {
        assertTrue(Onboarding.isEmail("ten@gmail.com"))
        assertTrue(Onboarding.isEmail("  a.b+c@sub.example.vn "))
        assertFalse(Onboarding.isEmail(""))
        assertFalse(Onboarding.isEmail("ten@gmail"))
        assertFalse(Onboarding.isEmail("ten gmail.com"))
        assertFalse(Onboarding.isEmail("ten@@gmail.com"))
        assertFalse(Onboarding.isEmail("ten@gmail.c"))
    }

    @Test fun displayNameFallsBackToEmail() {
        assertEquals("Lan", Onboarding.displayName(" Lan ", "x@y.vn"))
        assertEquals("nguyen.lan", Onboarding.displayName("", "nguyen.lan@gmail.com"))
    }

    private data class P(val id: String, val b: Onboarding.Box)

    @Test fun suggestsTheSmallestRegionCoveringThePosition() {
        val hanoi = P("ha-noi", Onboarding.Box(20.53, 105.28, 21.39, 106.02))
        val centre = P("ha-noi-noi-thanh", Onboarding.Box(20.95, 105.75, 21.1, 105.9))
        val danang = P("da-nang", Onboarding.Box(15.95, 108.1, 16.15, 108.3))
        val all = listOf(hanoi, centre, danang)
        assertEquals("ha-noi-noi-thanh", Onboarding.suggest(all, 21.028, 105.852) { it.b }?.id)   // Hoàn Kiếm
        assertEquals("ha-noi", Onboarding.suggest(all, 21.2, 105.4) { it.b }?.id)                // outskirts
        assertNull(Onboarding.suggest(all, 10.77, 106.7) { it.b })                               // no pack there
    }

    @Test fun locatesTheProvinceByItsOutlineNotItsBox() {
        // two neighbours whose boxes overlap; the point is in B's outline but also inside A's (bigger) box
        data class Prov(val id: String, val rings: List<List<LatLon>>, val b: Onboarding.Box)
        val a = Prov("a", listOf(listOf(LatLon(0.0, 0.0), LatLon(0.0, 2.0), LatLon(1.0, 2.0), LatLon(2.0, 0.0))),
            Onboarding.Box(0.0, 0.0, 2.0, 2.0))
        val b = Prov("b", listOf(listOf(LatLon(1.2, 1.6), LatLon(1.2, 3.0), LatLon(3.0, 3.0), LatLon(3.0, 1.6))),
            Onboarding.Box(1.2, 1.6, 3.0, 3.0))
        val island = Prov("i", listOf(listOf(LatLon(0.0, 5.0), LatLon(0.0, 6.0), LatLon(1.0, 6.0), LatLon(1.0, 5.0)),
            listOf(LatLon(4.0, 5.0), LatLon(4.0, 5.5), LatLon(4.5, 5.5), LatLon(4.5, 5.0))), Onboarding.Box(0.0, 5.0, 4.5, 6.0))
        val all = listOf(a, b, island)
        fun at(lat: Double, lon: Double) = Onboarding.locate(all, lat, lon, { it.rings }) { it.b }?.id
        assertEquals("b", at(1.5, 1.8))      // A's box covers it too, its outline does not (the diagonal edge)
        assertEquals("a", at(0.5, 0.5))
        assertEquals("i", at(4.2, 5.2))      // second ring (an island)
        assertNull(at(10.0, 10.0))
        assertTrue(Onboarding.inRings(0.5, 0.5, a.rings))
        assertFalse(Onboarding.inRings(1.9, 1.9, a.rings))
    }

    @Test fun sessionCarriesTheEmail() {
        val s = Session(id = "s_e", createdAt = "2026-11-09T01:00:00Z", contributor = "lan", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), email = "lan@gmail.com")
        val o = s.toJson()
        assertEquals("poi-capture/3", o.getString("schema"))
        assertEquals("lan@gmail.com", o.getString("email"))
        assertEquals("lan@gmail.com", Session.fromJson(JSONObject(o.toString())).email)
        // sessions from before 0.8 have no email
        o.remove("email")
        assertEquals("", Session.fromJson(o).email)
    }
}
