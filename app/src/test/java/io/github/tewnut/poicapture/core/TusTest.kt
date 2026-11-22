package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TusTest {
    @Test fun endpointNeedsHttpAndGetsOneTrailingSlash() {
        assertEquals("https://h.example/files/", Tus.endpoint(" https://h.example/files "))
        assertEquals("https://h.example/files/", Tus.endpoint("https://h.example/files//"))
        assertEquals("http://localhost:1080/", Tus.endpoint("http://localhost:1080"))
        assertNull(Tus.endpoint(""))
        assertNull(Tus.endpoint("ftp://h.example/files/"))
        assertNull(Tus.endpoint("h.example/files"))
        assertNull(Tus.endpoint("https:///files"))
    }

    @Test fun tokenBecomesOneBearerHeader() {
        assertEquals("Bearer abc", Tus.authHeader(" abc "))
        assertEquals("Bearer abc", Tus.authHeader("Bearer abc"))
        assertNull(Tus.authHeader(""))
        assertNull(Tus.authHeader(null))
    }

    private val MiB = 1024L * 1024

    @Test fun recordRoundTripsAndCountsPercent() {
        val r = UploadRecord("s1", UploadStatus.FAILED, "https://h/files/u1", 2000, 500, "HTTP 500", null)
        assertEquals(r, UploadRecord.fromJson(JSONObject(r.toJson().toString())))
        assertEquals(25, r.percent)
        assertEquals(0, UploadRecord("s2").percent)
        assertEquals(UploadStatus.NONE, UploadStatus.of("whatever"))
    }

    @Test fun recordsFromBeforePartsStillRead() {
        val old = JSONObject("""{"sessionId":"s1","status":"uploading","url":"https://h/files/u1","size":2000,"offset":500,"error":null,"uploadedAt":null}""")
        val r = UploadRecord.fromJson(old)
        assertEquals(UploadRecord("s1", UploadStatus.UPLOADING, "https://h/files/u1", 2000, 500), r)
        assertNull(r.direct)
    }
}
