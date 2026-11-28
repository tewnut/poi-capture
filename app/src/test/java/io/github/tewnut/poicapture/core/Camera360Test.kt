package io.github.tewnut.poicapture.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class Camera360Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun syncTextIsPrefixSessionAndEpochMs() {
        assertEquals("QPSYNC1 s_mia3k2x0abcde 1764300000123", Camera360.syncText("s_mia3k2x0abcde", 1764300000123L))
        assertEquals("s_x" to 42L, Camera360.parseSyncText(Camera360.syncText("s_x", 42L)))
        assertNull(Camera360.parseSyncText("QPSYNC2 s_x 42"))
        assertNull(Camera360.parseSyncText("QPSYNC1 s_x"))
        assertNull(Camera360.parseSyncText("QPSYNC1 s_x notatime"))
    }

    @Test fun syncsAreAppendedInOrder() {
        var c: JSONObject? = null
        c = Camera360.withSync(c, Camera360.Sync(1000, 4000, Camera360.When.START, gpsMinusPhoneMs = -120))
        c = Camera360.withSync(c, Camera360.Sync(9000, 8000, Camera360.When.MANUAL))   // ends before it starts: closed at its start
        c = Camera360.withSync(c, Camera360.Sync(20000, 23000, Camera360.When.END))
        val a = c.getJSONArray("syncs")
        assertEquals(3, a.length())
        val first = a.getJSONObject(0)
        assertEquals(1000L, first.getLong("fromMs"))
        assertEquals(4000L, first.getLong("toMs"))
        assertEquals("start", first.getString("when"))
        assertEquals(-120L, first.getLong("gpsMinusPhoneMs"))
        assertEquals(9000L, a.getJSONObject(1).getLong("toMs"))
        assertFalse(a.getJSONObject(1).has("gpsMinusPhoneMs"))
        assertEquals(listOf("start", "manual", "end"), Camera360.syncs(c).map { it.`when`.json })
        // through text and back
        assertEquals(Camera360.syncs(c), Camera360.syncs(JSONObject(c.toString())))
    }

    @Test fun aRideWithATrackAndNoPhotosIsKept() {
        assertTrue(Camera360.worthKeeping(SessionKind.CAMERA360, 0, 12))
        assertFalse(Camera360.worthKeeping(SessionKind.CAMERA360, 0, 0))
        assertFalse(Camera360.worthKeeping(SessionKind.SWEEP, 0, 12))
        assertTrue(Camera360.worthKeeping(SessionKind.SWEEP, 3, 12))
    }

    @Test fun theQrCodeReadsBackWithItsQuietZone() {
        val text = Camera360.syncText("s_mia3k2x0abcde", 1764300000123L)
        val m = QrModules.encode(text)
        // quiet zone: the outer 4 modules are white all around
        for (i in 0 until m.size) for (k in 0 until QrModules.QUIET) {
            assertFalse(m[i, k]); assertFalse(m[k, i]); assertFalse(m[i, m.size - 1 - k]); assertFalse(m[m.size - 1 - k, i])
        }
        // the top-left finder starts right after it
        assertTrue(m[QrModules.QUIET, QrModules.QUIET])
        // scaled up 4x (as on screen), a reader gets the text back
        val k = 4
        val n = m.size * k
        val px = m.pixels()
        val big = IntArray(n * n) { px[(it / n / k) * m.size + (it % n) / k] }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(n, n, big))))
        assertEquals(text, read.text)
        assertEquals("M", read.resultMetadata[com.google.zxing.ResultMetadataType.ERROR_CORRECTION_LEVEL])
        // the same size every time (13-digit epochs): the code does not jump in size while it is redrawn
        assertEquals(m.size, QrModules.encode(Camera360.syncText("s_mia3k2x0abcde", 1764300099999L)).size)
    }

    private fun session() = Session(
        id = "s_c360", createdAt = "2026-11-28T03:00:00Z", startedAt = "2026-11-28T03:01:00Z",
        contributor = "", note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_MANUAL,
        sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings(), kind = SessionKind.CAMERA360,
        camera360 = Camera360.empty(),
    )

    @Test fun aCamera360RideReachesTheManifestAndTheZipWithoutPhotos() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = session()
        store.create(s)
        s.camera360 = Camera360.withSync(s.camera360, Camera360.Sync(1764298860500, 1764298863500, Camera360.When.START))
        store.save(s)
        val back = store.load("s_c360")!!
        assertEquals(SessionKind.CAMERA360, back.kind)
        assertEquals(1, Camera360.syncs(back.camera360).size)
        for (i in 0..2) store.trackFile("s_c360").appendText(JSONObject().put("t", 1764298861000L + i * 1000)
            .put("lat", 10.77 + i * 1e-4).put("lon", 106.70).put("acc", 4.0).toString() + "\n")

        val m = Exporter.manifest(store, "s_c360")
        assertEquals("camera360", m.getString("kind"))
        assertEquals("camera360", m.getJSONObject("session").getString("kind"))
        val syncs = m.getJSONObject("session").getJSONObject("camera360").getJSONArray("syncs")
        assertEquals("start", syncs.getJSONObject(0).getString("when"))
        assertEquals(0, m.getJSONArray("items").length())
        assertEquals(3, m.getInt("trackPoints"))
        assertTrue(Exporter.fileName(back).startsWith("poi-capture_camera360_"))

        val out = ByteArrayOutputStream()
        Exporter.writeZip(store, "s_c360", out)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("manifest.json" in names)
        assertTrue("track.geojson" in names)

        val meta = sessionUploadMetadata(back, Exporter.fileName(back), 0, store.trackFile("s_c360"))
        assertEquals("camera360", meta["kind"])
        assertEquals("0", meta["photos"])
    }

    @Test fun otherKindsCarryNoCamera360Block() {
        val s = Session(id = "s_sw", createdAt = "2026-11-28T03:00:00Z", contributor = "", note = "", vehicle = Vehicle.UNSPECIFIED,
            mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings())
        assertFalse(s.toJson().has("camera360"))
        // an older camera360 session.json without the block still gets an empty one
        val c = session().apply { camera360 = null }
        assertEquals(0, c.toJson().getJSONObject("camera360").getJSONArray("syncs").length())
    }
}
