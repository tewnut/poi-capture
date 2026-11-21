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

    private val MiB = 1024L * 1024

    @Test fun smallFilesGoInOnePart() {
        assertEquals(listOf(UploadPart(0, 0)), Tus.splitRanges(0))
        assertEquals(listOf(UploadPart(0, 5)), Tus.splitRanges(5))
        assertEquals(listOf(UploadPart(0, 16 * MiB)), Tus.splitRanges(16 * MiB))
    }

    @Test fun bigFilesSplitInAtMostFourAlignedContiguousParts() {
        for (size in listOf(16 * MiB + 1, 17 * MiB, 24 * MiB, 24 * MiB + 1, 32 * MiB, 33 * MiB, 100 * MiB, 703_000_000L, 5_000 * MiB)) {
            val parts = Tus.splitRanges(size)
            assertTrue("$size: ${parts.size} parts", parts.size in 2..4)
            assertTrue(Tus.partsFit(parts, size))
            parts.forEach { assertEquals("$size: cut at ${it.start}", 0L, it.start % (8 * MiB)); assertTrue(it.length > 0) }
            assertEquals(size, parts.sumOf { it.length })
        }
        // 703 MB (the measured ride): four parts of 168 MiB, the last one shorter
        val big = Tus.splitRanges(703_000_000L)
        assertEquals(4, big.size)
        assertEquals(listOf(0L, 168 * MiB, 336 * MiB, 504 * MiB), big.map { it.start })
        // just over 16 MiB: 8 + 8 + the rest
        assertEquals(listOf(UploadPart(0, 8 * MiB), UploadPart(8 * MiB, 8 * MiB), UploadPart(16 * MiB, 1)), Tus.splitRanges(16 * MiB + 1))
        assertEquals(4, Tus.splitRanges(24 * MiB + 1).size)
        assertEquals(listOf(UploadPart(0, 8 * MiB), UploadPart(8 * MiB, 8 * MiB), UploadPart(16 * MiB, 8 * MiB)), Tus.splitRanges(24 * MiB))
    }

    @Test fun savedPartsMustCoverTheZipExactly() {
        val parts = Tus.splitRanges(100 * MiB)
        assertTrue(Tus.partsFit(parts, 100 * MiB))
        assertFalse(Tus.partsFit(parts, 100 * MiB + 1))
        assertFalse(Tus.partsFit(emptyList(), 0))
        assertFalse(Tus.partsFit(parts.drop(1), 100 * MiB))
        assertFalse(Tus.partsFit(listOf(parts[0].copy(offset = parts[0].length + 1)) + parts.drop(1), 100 * MiB))
    }

    @Test fun finalConcatListsThePartialsInOrder() {
        assertEquals("final;https://h/files/a https://h/files/b", Tus.finalConcat(listOf("https://h/files/a", "https://h/files/b")))
    }

    @Test fun aSingleUploadIsKeptOnceHalfIsSent() {
        assertTrue(Tus.keepSingle(500, 1000))
        assertTrue(Tus.keepSingle(1000, 1000))
        assertFalse(Tus.keepSingle(499, 1000))
        assertFalse(Tus.keepSingle(0, 1000))
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
        assertFalse(i.canConcat)
        assertTrue(Tus.serverInfo("1.0.0", "creation,termination,concatenation", null).canConcat)
    }

    @Test fun recordRoundTripsAndCountsPercent() {
        val r = UploadRecord("s1", UploadStatus.FAILED, "https://h/files/u1", 2000, 500, "HTTP 500", null)
        assertEquals(r, UploadRecord.fromJson(JSONObject(r.toJson().toString())))
        assertEquals(25, r.percent)
        assertEquals(0, UploadRecord("s2").percent)
        assertEquals(UploadStatus.NONE, UploadStatus.of("whatever"))
    }

    @Test fun recordWithPartsRoundTrips() {
        val parts = listOf(UploadPart(0, 8 * MiB, "https://h/files/p1", 8 * MiB), UploadPart(8 * MiB, 8 * MiB, "https://h/files/p2", 3),
            UploadPart(16 * MiB, 10, null, 0))
        val r = UploadRecord("s1", UploadStatus.UPLOADING, null, 16 * MiB + 10).withParts(parts)
        assertEquals(8 * MiB + 3, r.offset)
        val back = UploadRecord.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r, back)
        assertNull(back.parts[2].url)
        assertTrue(back.parts[0].done)
        assertFalse(back.parts[1].done)
        // a single upload writes no "parts"
        assertFalse(UploadRecord("s2", url = "https://h/files/u").toJson().has("parts"))
    }

    @Test fun recordsFromBeforePartsStillRead() {
        val old = JSONObject("""{"sessionId":"s1","status":"uploading","url":"https://h/files/u1","size":2000,"offset":500,"error":null,"uploadedAt":null}""")
        val r = UploadRecord.fromJson(old)
        assertEquals(UploadRecord("s1", UploadStatus.UPLOADING, "https://h/files/u1", 2000, 500), r)
        assertTrue(r.parts.isEmpty())
    }
}
