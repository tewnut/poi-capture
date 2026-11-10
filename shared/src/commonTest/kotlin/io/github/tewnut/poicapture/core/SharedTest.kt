package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedTest {
    @Test fun isoTimeMatchesJavaInstant() {
        assertEquals("1970-01-01T00:00:00Z", IsoTime.format(0))
        assertEquals("2026-11-09T17:30:00Z", IsoTime.format(1_794_245_400_000))
        assertEquals("2024-02-29T23:59:59.250Z", IsoTime.format(1_709_251_199_250))
        assertEquals("1969-12-31T23:59:59Z", IsoTime.format(-1000))
    }

    @Test fun sessionIsNamedAfterItsMostCommonStreet() {
        assertEquals("Nguyễn Thanh Sơn [261110]",
            SessionNaming.name(listOf("Nguyễn Thanh Sơn", "Đường số 6", "Nguyễn Thanh Sơn", ""), "261110", "?"))
        assertEquals("No street [261110]", SessionNaming.name(listOf("null", " "), "261110", "No street"))
    }

    @Test fun gpsFreshnessIsFiveSeconds() {
        assertTrue(CaptureRules.gpsFresh(4_999))
        assertTrue(!CaptureRules.gpsFresh(5_001))
    }

    @Test fun placeManifestHasTheAndroidShape() {
        val ph = PlacePhoto("p_1", 1_794_245_400_000, "q_1", 10.78, 106.76, 6.0, 1_794_245_399_500,
            cameraBearing = 90.0, distanceM = 4.2, distanceSource = "lidar", widthPx = 3024, heightPx = 4032, hFovDeg = 52.0,
            roadName = "Nguyễn Thanh Sơn")
        val pl = PlaceRecord("q_1", 1_794_245_390_000, 10.78, 106.76, 6.0)
        val info = PlaceSessionInfo("s_1", 1_794_245_380_000, 1_794_245_380_000, 1_794_245_500_000, "lan", "lan@example.com",
            "Nguyễn Thanh Sơn [261110]", mapOf("platform" to "ios", "model" to "iPhone15,2"))
        val m = Json.parseToJsonElement(PlaceSession.manifest(info, listOf(ph), listOf(pl), 1_794_245_600_000)).jsonObject
        assertEquals(SCHEMA, m["schema"]!!.jsonPrimitive.content)
        assertEquals("poi", m["kind"]!!.jsonPrimitive.content)
        val item = m["items"]!!.jsonArray[0].jsonObject
        assertEquals("photos/p_1.jpg", item["file"]!!.jsonPrimitive.content)
        assertEquals("lidar", item["facadeSource"]!!.jsonPrimitive.content)
        assertTrue(item["facadeLon"]!!.jsonPrimitive.content.toDouble() > 106.76)   // east of the camera
        val poi = m["pois"]!!.jsonArray[0].jsonObject
        assertEquals("p_1", poi["cover"]!!.jsonPrimitive.content)
        assertEquals("gps", poi["position"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals("ios", m["session"]!!.jsonObject["device"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
    }
}
