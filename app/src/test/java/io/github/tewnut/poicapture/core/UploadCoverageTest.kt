package io.github.tewnut.poicapture.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/** In-process tus 1.0 server: create, HEAD, PATCH (or POST + X-HTTP-Method-Override), with injectable faults. */
class FakeTus : AutoCloseable {
    private val server = MockWebServer()
    val data = HashMap<String, ByteArrayOutputStream>()
    val lengths = HashMap<String, Long>()
    val meta = HashMap<String, Map<String, String>>()
    var creates = 0
    var acceptedBytes = 0L
    var dropAfterPatches = -1      // drop the connection without answering after this many accepted PATCHes
    var conflictOnce = false        // answer the next PATCH with 409 without taking its data
    private var patches = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeTus) { handle(request) }
        }
        server.start()
    }

    val endpoint: String get() = server.url("/files/").toString()

    private fun handle(r: RecordedRequest): MockResponse {
        val m = r.getHeader("X-HTTP-Method-Override") ?: r.method
        val id = r.requestUrl!!.encodedPath.removePrefix("/files/").trim('/')
        return when {
            m == "POST" && id.isEmpty() -> {
                val uid = "u${++creates}"
                lengths[uid] = r.getHeader("Upload-Length")!!.toLong()
                data[uid] = ByteArrayOutputStream()
                meta[uid] = r.getHeader("Upload-Metadata")!!.split(",").associate { kv ->
                    val (k, v) = kv.split(" "); k to String(Base64.getDecoder().decode(v))
                }
                MockResponse().setResponseCode(201).addHeader("Location", "/files/$uid")
            }
            m == "HEAD" -> {
                val d = data[id] ?: return MockResponse().setResponseCode(404)
                MockResponse().setResponseCode(200).addHeader("Upload-Offset", d.size().toString())
                    .addHeader("Upload-Length", lengths[id].toString())
            }
            m == "PATCH" -> {
                val d = data[id] ?: return MockResponse().setResponseCode(404)
                val body = r.body.readByteArray()
                if (conflictOnce) { conflictOnce = false; return MockResponse().setResponseCode(409) }
                if (r.getHeader("Upload-Offset")!!.toLong() != d.size().toLong()) return MockResponse().setResponseCode(409)
                if (dropAfterPatches >= 0 && patches >= dropAfterPatches) {
                    dropAfterPatches = -1
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                d.write(body); acceptedBytes += body.size; patches++
                MockResponse().setResponseCode(204).addHeader("Upload-Offset", d.size().toString())
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    override fun close() = server.shutdown()
}

class UploadCoverageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tus = FakeTus()
    @After fun stop() = tus.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun sessionWithPhotos(store: SessionStore): Session {
        val s = Session(id = "s_up", createdAt = "2026-11-09T01:00:00Z", startedAt = "2026-11-09T01:00:00Z", contributor = "t",
            note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings())
        store.create(s)
        for (i in 0..2) {
            store.appendItem(s.id, JSONObject().put("id", "p_$i").put("kind", "photo").put("tMs", i.toLong()).put("lat", START.lat).put("lon", START.lon))
            store.photoFile(s.id, "p_$i").writeBytes(Random(i).nextBytes(100_000))   // incompressible: zip ≈ 300 KB
        }
        return s
    }

    private fun client() = TusClient(tus.endpoint, token = "secret", chunkBytes = 64 * 1024)
    private fun far() = System.currentTimeMillis() + 60_000

    @Test fun uploadsInChunksAndTheServerGetsExactlyTheZip() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        val up = tmp.newFolder("uploads")
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))
        val st = store.uploadState(s.id)
        assertEquals("uploaded", st.state)
        val got = tus.data["u1"]!!.toByteArray()
        assertEquals(st.zipBytes, got.size.toLong())
        assertEquals(st.sha256, sha(got))                                  // byte-identical
        assertEquals(st.sha256, tus.meta["u1"]!!["sha256"])
        assertEquals("s_up", tus.meta["u1"]!!["sessionId"])
        assertEquals("poi-capture/3", tus.meta["u1"]!!["schema"])
        assertFalse(java.io.File(up, "s_up.zip").exists())                 // removed once uploaded
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))   // nothing to do
        assertEquals(1, tus.creates)
    }

    @Test fun aDroppedConnectionResumesFromTheServerOffsetWithoutResending() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        val up = tmp.newFolder("uploads")
        tus.dropAfterPatches = 2
        assertEquals(UploadJob.Result.RETRY, UploadJob.run(store, s.id, up, client(), far()))
        val mid = store.uploadState(s.id)
        assertEquals("failed", mid.state)
        assertEquals(2L * 64 * 1024, mid.offset)
        assertNotNull(mid.url)
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))
        assertEquals(1, tus.creates)                                        // same upload, resumed
        val st = store.uploadState(s.id)
        assertEquals(st.zipBytes, tus.acceptedBytes)                         // no byte accepted twice
        assertEquals(st.sha256, sha(tus.data["u1"]!!.toByteArray()))
    }

    @Test fun conflictReReadsTheOffsetAndAnExpiredUploadStartsOver() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        val up = tmp.newFolder("uploads")
        // a 409 first (re-read the offset and go on), then the connection drops after 2 chunks: stops half-way on any
        // machine (a time budget made this depend on the speed of the CI runner)
        tus.conflictOnce = true
        tus.dropAfterPatches = 2
        assertEquals(UploadJob.Result.RETRY, UploadJob.run(store, s.id, up, client(), far()))
        assertEquals(2L * 64 * 1024, store.uploadState(s.id).offset)          // the 409 cost nothing
        tus.data.clear()                                                     // server forgot the upload (expired)
        val r = UploadJob.run(store, s.id, up, client(), far())
        assertEquals(UploadJob.Result.DONE, r)
        assertEquals(2, tus.creates)
        assertEquals(store.uploadState(s.id).sha256, sha(tus.data["u2"]!!.toByteArray()))
    }

    @Test fun aServerOffsetBeyondTheFileIsNeverTakenAsComplete() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        val up = tmp.newFolder("uploads")
        tus.dropAfterPatches = 1
        assertEquals(UploadJob.Result.RETRY, UploadJob.run(store, s.id, up, client(), far()))
        tus.data["u1"]!!.write(ByteArray(2_000_000))                         // someone else wrote into "our" upload
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))
        assertEquals(2, tus.creates)                                         // started a clean upload
        assertEquals(store.uploadState(s.id).sha256, sha(tus.data["u2"]!!.toByteArray()))
        assertEquals(store.uploadState(s.id).zipBytes, store.uploadState(s.id).offset)
    }

    @Test fun anEditAfterUploadingMeansUploadingAgain() {
        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        val up = tmp.newFolder("uploads")
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))
        store.relocate(s.id, "p_1", LatLon(START.lat - 0.0001, START.lon))   // moved a photo on the map
        val items = java.io.File(store.dir(s.id), "items.jsonl")
        items.setLastModified(System.currentTimeMillis() + 5000)
        assertTrue(store.contentVersion(s.id) > store.uploadState(s.id).uploadedVersion)
        assertEquals(UploadJob.Result.DONE, UploadJob.run(store, s.id, up, client(), far()))
        assertEquals(2, tus.creates)
        val zip2 = tus.data["u2"]!!.toByteArray()
        java.util.zip.ZipInputStream(zip2.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null && e.name != "manifest.json") e = z.nextEntry
            assertTrue(String(z.readBytes()).contains("relocatedLat"))
        }
    }

    @Test fun coverageIsThinnedSplitAtJumpsAndCached() {
        val track = (0..100).map { LatLon(START.lat, START.lon + it * 0.00001) } +            // ~1 m steps over 100 m
            (0..20).map { LatLon(START.lat + 0.01, START.lon + it * 0.0001) }                    // 1.1 km away: new line
        val lines = Coverage.simplify(track)
        assertEquals(2, lines.size)
        assertTrue(lines[0].size in 10..12)                                                     // ~10 m apart
        val c = Coverage.build(SessionKind.SWEEP, track, listOf(START))
        assertTrue(Coverage.intersects(c, START.lat - 0.001, START.lon - 0.001, START.lat + 0.001, START.lon + 0.001))
        assertFalse(Coverage.intersects(c, 10.0, 100.0, 10.1, 100.1))
        assertEquals(lines.size, Coverage.lines(c).size)
        assertEquals(1, Coverage.points(c).size)
        val (gl, gp) = Coverage.fromGeoJson("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[105.80,21.02],[105.81,21.02]]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[105.805,21.021]}}]}""")
        assertEquals(1, gl.size); assertEquals(21.02, gl[0][0].lat, 0.0); assertEquals(1, gp.size)

        val store = SessionStore(tmp.newFolder("sessions"))
        val s = sessionWithPhotos(store)
        for ((i, p) in track.withIndex()) store.trackFile(s.id).appendText(Fix(i * 1000L, p.lat, p.lon, 5.0).toTrackJson(90.0).toString() + "\n")
        val cov = Coverage.forSession(store, s.id)!!
        assertEquals(2, Coverage.lines(cov).size)
        assertTrue(store.coverageFile(s.id).exists())
    }
}
