package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TusTest {
    @Test fun metadataIsBase64PairsAndRoundTrips() {
        val m = linkedMapOf<String, String?>("filename" to "poi-capture_place_202611180930.zip", "name" to "Nguyễn Thanh Sơn [261118]",
            "empty" to "", "skipped" to null, "type" to "raw-session")
        val h = Tus.encodeMetadata(m)
        // the reference value from the tus spec: "world" → d29ybGQ=
        assertEquals("hello d29ybGQ=", Tus.encodeMetadata(mapOf("hello" to "world")))
        assertFalse(h.contains("skipped"))
        assertTrue(h.split(",").contains("empty"))
        assertEquals(m.filterValues { it != null }, Tus.decodeMetadata(h))
        assertEquals(emptyMap<String, String>(), Tus.decodeMetadata(null))
    }

    @Test(expected = IllegalArgumentException::class) fun metadataKeysCannotHoldSpacesOrCommas() {
        Tus.encodeMetadata(mapOf("a b" to "x"))
    }

    @Test fun endpointNeedsHttpAndGetsOneTrailingSlash() {
        assertEquals("https://h.example/files/", Tus.endpoint(" https://h.example/files "))
        assertEquals("https://h.example/files/", Tus.endpoint("https://h.example/files//"))
        assertEquals("http://localhost:1080/", Tus.endpoint("http://localhost:1080"))
        assertNull(Tus.endpoint(""))
        assertNull(Tus.endpoint("ftp://h.example/files/"))
        assertNull(Tus.endpoint("h.example/files"))
        assertNull(Tus.endpoint("https:///files"))
    }

    @Test fun locationResolvesAgainstTheEndpoint() {
        val e = "https://h.example/files/"
        assertEquals("https://h.example/files/abc", Tus.resolve(e, "https://h.example/files/abc"))
        assertEquals("https://h.example/files/abc", Tus.resolve(e, "/files/abc"))
        assertEquals("https://h.example/files/abc", Tus.resolve(e, "abc"))
    }

    @Test fun tokenBecomesOneBearerHeader() {
        assertEquals("Bearer abc", Tus.authHeader(" abc "))
        assertEquals("Bearer abc", Tus.authHeader("Bearer abc"))
        assertNull(Tus.authHeader(""))
        assertNull(Tus.authHeader(null))
    }

    @Test fun chunksStopAtTheEnd() {
        assertEquals(Tus.CHUNK, Tus.chunkLength(0, 10 * Tus.CHUNK))
        assertEquals(5L, Tus.chunkLength(Tus.CHUNK, Tus.CHUNK + 5))
        assertEquals(0L, Tus.chunkLength(100, 100))
    }

    @Test fun resumesOnlyWhenTheBytesAreTheSame() {
        val saved = UploadRecord("s1", UploadStatus.UPLOADING, "https://h/files/u1", size = 1000, offset = 400)
        // nothing saved, or no URL yet: a new upload
        assertEquals(Tus.Start.Create, Tus.start(null, 1000, null))
        assertEquals(Tus.Start.Create, Tus.start(saved.copy(url = null), 1000, 400))
        // the server's offset wins over what the phone saved (it may have received more before the connection dropped)
        assertEquals(Tus.Start.Continue("https://h/files/u1", 650), Tus.start(saved, 1000, 650))
        assertEquals(Tus.Start.Continue("https://h/files/u1", 0), Tus.start(saved, 1000, 0))
        assertEquals(Tus.Start.Finished("https://h/files/u1"), Tus.start(saved, 1000, 1000))
        // zip gone or rebuilt with another size, or the server forgot the upload: start again
        assertEquals(Tus.Start.Create, Tus.start(saved, null, 400))
        assertEquals(Tus.Start.Create, Tus.start(saved, 999, 400))
        assertEquals(Tus.Start.Create, Tus.start(saved, 1000, null))
        assertEquals(Tus.Start.Create, Tus.start(saved, 1000, 2000))
    }

    @Test fun serverInfoFromOptionsHeaders() {
        val i = Tus.serverInfo("1.0.0,0.2.2", "creation, creation-with-upload,termination", "1073741824")
        assertTrue(i.supports100)
        assertTrue(i.canCreate)
        assertEquals(1073741824L, i.maxSize)
        val none = Tus.serverInfo(null, null, null)
        assertTrue(none.versions.isEmpty())
        assertFalse(none.canCreate)
        assertNull(none.maxSize)
    }

    @Test fun recordRoundTripsAndCountsPercent() {
        val r = UploadRecord("s1", UploadStatus.FAILED, "https://h/files/u1", 2000, 500, "HTTP 500", null)
        assertEquals(r, UploadRecord.fromJson(JSONObject(r.toJson().toString())))
        assertEquals(25, r.percent)
        assertEquals(0, UploadRecord("s2").percent)
        assertEquals(UploadStatus.NONE, UploadStatus.of("whatever"))
    }
}
