package io.github.tewnut.roadcapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export one session as a zip for the POI agent (schema road-capture/2, see docs/handoff.md):
 * manifest.json, track.geojson, gnss_raw.csv, imu.csv, photos/, videos/. Everything is streamed from disk,
 * so multi-GB videos never sit in memory.
 */
object Exporter {

    /**
     * Items worth exporting. Photos need their file. A video needs a non-empty file; one that never got its end
     * (app killed mid-recording) is marked `incomplete` and gets its end time from the file's last write.
     */
    fun usableItems(store: SessionStore, sessionId: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (it in store.items(sessionId)) {
            when (it.optString("kind")) {
                "photo" -> if (store.photoFile(sessionId, it.getString("id")).length() > 0) out += it
                "video" -> {
                    val f = store.videoFile(sessionId, it.getString("id"))
                    if (f.length() == 0L) continue
                    val v = JSONObject(it.toString()).put("bytes", f.length())
                    if (!v.has("endMs") || v.isNull("endMs")) {
                        val start = v.optLong("startMs", v.optLong("tMs"))
                        val end = maxOf(start, f.lastModified())
                        v.put("incomplete", true).put("endMs", end).put("endT", iso(end)).put("durationMs", end - start)
                    }
                    out += v
                }
            }
        }
        return out.sortedBy { it.optLong("tMs") }
    }

    fun manifest(store: SessionStore, sessionId: String, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val session = store.load(sessionId) ?: error("Không tìm thấy phiên $sessionId")
        // auto road mode: the ways loaded during the ride live in ways.jsonl; the manifest carries them in road.ways
        if (session.road.auto) session.road = Road.auto((session.road.ways + store.loadedWays(sessionId)).distinctBy { it.key }, session.road.sources)
        val items = usableItems(store, sessionId).map { o ->
            val id = o.getString("id")
            JSONObject(o.toString()).put("file", if (o.getString("kind") == "photo") "photos/$id.jpg" else "videos/$id.mp4")
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("kind", session.kind.json)
            .put("exportedAt", iso(nowMs))
            .put("session", session.toJson())
            .put("items", JSONArray(items))
            .put("pois", JSONArray(poisWithPhotos(store, sessionId, items)))
            .put("marks", JSONArray(marksWithPhotos(store, sessionId, items)))
            // nothing is blurred on the phone: faces and licence plates must be blurred before anything is published
            .put("privacy", JSONObject().put("blurred", false).put("required", JSONArray(listOf("faces", "plates"))))
            .put("trackFile", "track.geojson")
            .put("trackPoints", store.trackCount(sessionId))
            .put("gnssRawFile", if (store.gnssFile(sessionId).length() > 0) "gnss_raw.csv" else JSONObject.NULL)
            .put("imuFile", if (store.imuFile(sessionId).length() > 0) "imu.csv" else JSONObject.NULL)
    }

    /**
     * POI mode: each place with its photo ids (creation order) and its cover = the first front photo, else the first
     * sign photo, else the first photo. Places without any photo are kept, flagged `noPhotos`.
     */
    fun poisWithPhotos(store: SessionStore, sessionId: String, items: List<JSONObject>): List<JSONObject> =
        store.pois(sessionId).map { poi ->
            val id = poi.getString("id")
            val photos = items.filter { it.optString("poiId") == id && it.optString("kind") == "photo" }
            val cover = photos.firstOrNull { it.optString("role") == PhotoRole.FACADE.json }
                ?: photos.firstOrNull { it.optString("role") == PhotoRole.SIGN.json } ?: photos.firstOrNull()
            JSONObject(poi.toString())
                .put("photos", JSONArray(photos.map { it.getString("id") }))
                .put("cover", cover?.getString("id") ?: JSONObject.NULL)
                .also { if (photos.isEmpty()) it.put("noPhotos", true) }
        }

    /** Sweep mode: each "Đánh dấu" with the photos taken within ±5 s of it (the place the contributor pointed out). */
    fun marksWithPhotos(store: SessionStore, sessionId: String, items: List<JSONObject>): List<JSONObject> =
        store.marks(sessionId).map { m ->
            val t = m.optLong("tMs")
            val near = items.filter { it.optString("kind") == "photo" && kotlin.math.abs(it.optLong("tMs") - t) <= 5000 }
            JSONObject(m.toString()).put("photoIds", JSONArray(near.map { it.getString("id") }))
        }

    fun fileName(s: Session): String {
        val label = (if (s.kind == SessionKind.POI) "dia-diem" else s.road.label).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifEmpty { "road" }
        val start = (s.startedAt ?: s.createdAt).let {
            try { DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(Instant.parse(it)) } catch (e: Exception) { "start" }
        }
        val side = if (s.kind == SessionKind.POI) "" else "_${s.sideOfTravel.json}"
        return "road-capture_${label}${side}_$start.zip"
    }

    /** Write the zip to `out`. Returns the number of entries. */
    fun writeZip(store: SessionStore, sessionId: String, out: OutputStream, nowMs: Long = System.currentTimeMillis()): Int {
        val manifest = manifest(store, sessionId, nowMs)
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, level: Int, body: (OutputStream) -> Unit) {
                zip.setLevel(level)
                zip.putNextEntry(ZipEntry(name))
                body(zip)
                zip.closeEntry()
                n++
            }
            entry("manifest.json", Deflater.DEFAULT_COMPRESSION) { it.write(manifest.toString(1).toByteArray()) }
            entry("track.geojson", Deflater.DEFAULT_COMPRESSION) { o ->
                val w = o.writer(Charsets.UTF_8)
                writeTrackGeoJson(store.trackFile(sessionId), w)
                w.flush()
            }
            for ((file, name) in listOf(store.gnssFile(sessionId) to "gnss_raw.csv", store.imuFile(sessionId) to "imu.csv")) {
                if (file.length() > 0) entry(name, Deflater.DEFAULT_COMPRESSION) { o -> file.inputStream().use { it.copyTo(o) } }
            }
            val items = manifest.getJSONArray("items")
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val src = if (it.getString("kind") == "photo") store.photoFile(sessionId, it.getString("id"))
                else store.videoFile(sessionId, it.getString("id"))
                // JPEG / MP4 are already compressed: store them (fast, no CPU burn on the phone)
                entry(it.getString("file"), Deflater.NO_COMPRESSION) { o -> src.inputStream().use { s -> s.copyTo(o) } }
            }
        }
        return n
    }

    /** track.jsonl → GeoJSON: one LineString of the whole track + one Point per fix, streamed. */
    fun writeTrackGeoJson(track: File, w: Writer) {
        w.write("{\"type\":\"FeatureCollection\",\"features\":[")
        w.write("{\"type\":\"Feature\",\"properties\":{\"kind\":\"track\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        var first = true
        forEachFix(track) { p ->
            if (!first) w.write(",")
            first = false
            w.write("[${p.getDouble("lon")},${p.getDouble("lat")}]")
        }
        w.write("]}}")
        forEachFix(track) { p ->
            val props = JSONObject()
                .put("t", p.getLong("t")).put("iso", iso(p.getLong("t")))
                .put("accuracyM", p.opt("acc")).put("speedMps", p.opt("spd")).put("headingDeg", p.opt("hdg"))
                .put("altitudeM", p.opt("alt")).put("elapsedNanos", p.opt("en"))
            val geom = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.getDouble("lon")).put(p.getDouble("lat")))
            w.write(",")
            w.write(JSONObject().put("type", "Feature").put("properties", props).put("geometry", geom).toString())
        }
        w.write("]}")
    }

    private fun forEachFix(track: File, f: (JSONObject) -> Unit) {
        if (!track.exists()) return
        track.forEachLine { line ->
            val o = try { JSONObject(line) } catch (e: Exception) { return@forEachLine }
            if (o.has("lat") && o.has("lon")) f(o)
        }
    }
}
