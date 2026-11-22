package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUploadTest {
    private val MiB = 1024L * 1024
    private val created = """{"schema":"poi-cloud-direct/1","id":"abc","location":"https://s.test/files/abc","size":${20 * MiB + 5},
        "partSize":${8 * MiB},"parts":3,"urls":[{"part":1,"url":"https://r2/1"},{"part":2,"url":"https://r2/2"},{"part":3,"url":"https://r2/3"}],"expiresAt":"x"}"""

    @Test fun parsesTheServersAnswerAndSplitsTheFile() {
        val (d, urls) = DirectUpload.parseCreate(created)
        assertEquals(3, d.parts)
        assertEquals(mapOf(1 to "https://r2/1", 2 to "https://r2/2", 3 to "https://r2/3"), urls)
        assertEquals(0L, d.start(1)); assertEquals(8 * MiB, d.length(1))
        assertEquals(16 * MiB, d.start(3)); assertEquals(4 * MiB + 5, d.length(3))
        assertEquals(listOf(1, 2, 3), d.pending)
    }

    @Test fun keepsEtagsAcrossRestartsAndCompletesInOrder() {
        var d = DirectUpload.parseCreate(created).first.with(3, "\"e3\"").with(1, "\"e1\"")
        assertEquals(listOf(2), d.pending)
        assertEquals(12 * MiB + 5, d.sent)
        assertFalse(d.done)
        d = DirectUpload.fromJson(JSONObject(d.toJson().toString()))!!.with(2, "\"e2\"")
        assertTrue(d.done)
        val parts = JSONObject(d.completeBody()).getJSONArray("parts")
        assertEquals(listOf(1, 2, 3), (0 until 3).map { parts.getJSONObject(it).getInt("part") })
        assertEquals("\"e2\"", parts.getJSONObject(1).getString("etag"))
        val rec = UploadRecord("s", direct = d)
        assertEquals(d, UploadRecord.fromJson(JSONObject(rec.toJson().toString())).direct)
        assertNull(UploadRecord.fromJson(JSONObject(UploadRecord("s").toJson().toString())).direct)
    }

    @Test fun refusesPartsThatDoNotAddUp() {
        val bad = created.replace("\"parts\":3", "\"parts\":2")
        assertTrue(runCatching { DirectUpload.parseCreate(bad) }.isFailure)
    }

    @Test fun directUrlIsOnTheSameServer() {
        assertEquals("https://poi-uploader.4hum.ai/direct", DirectUpload.url("https://poi-uploader.4hum.ai/files/"))
        assertEquals("http://10.0.2.2:8787/direct/abc/urls", DirectUpload.url("http://10.0.2.2:8787/files/", "/abc/urls"))
        val body = JSONObject(DirectUpload.createBody(10, linkedMapOf("type" to "raw-session", "name" to null)))
        assertEquals(10, body.getInt("size"))
        assertEquals("""{"type":"raw-session"}""", body.getJSONObject("metadata").toString())
    }
}
