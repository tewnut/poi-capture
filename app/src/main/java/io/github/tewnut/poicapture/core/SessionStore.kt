package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.io.File

/**
 * Sessions on the phone, one directory each, written append-only so a crash or dead battery keeps everything
 * written so far:
 *   session.json      session (rewritten atomically on change)
 *   items.jsonl       one line per photo/video item; later lines {"_patch": id, ...} update an item
 *   track.jsonl       every position fix
 *   gnss_raw.csv      raw GNSS measurements (GnssLogger-style "Raw" rows)
 *   imu.csv           gyroscope / accelerometer / rotation vector samples
 *   photos/<id>.jpg, videos/<id>.mp4
 */
class SessionStore(val root: File) {
    fun dir(id: String) = File(root, id)
    fun photoFile(sessionId: String, itemId: String) = File(dir(sessionId), "photos/$itemId.jpg")
    fun videoFile(sessionId: String, itemId: String) = File(dir(sessionId), "videos/$itemId.mp4")
    fun trackFile(sessionId: String) = File(dir(sessionId), "track.jsonl")
    fun gnssFile(sessionId: String) = File(dir(sessionId), "gnss_raw.csv")
    fun imuFile(sessionId: String) = File(dir(sessionId), "imu.csv")
    fun waysFile(sessionId: String) = File(dir(sessionId), "ways.jsonl")   // auto road mode: ways loaded during the ride
    private fun itemsFile(sessionId: String) = File(dir(sessionId), "items.jsonl")
    private fun poisFile(sessionId: String) = File(dir(sessionId), "pois.jsonl")     // POI mode: one record per place
    private fun marksFile(sessionId: String) = File(dir(sessionId), "marks.jsonl")   // sweep mode: "Đánh dấu" taps
    fun coverageFile(sessionId: String) = File(dir(sessionId), "coverage.json")      // thinned track for the setup map

    /**
     * Version of a session's content (photos, places, marks, track), as the newest modification time: an edit (moved
     * photo, reviewed place) makes the cached coverage stale.
     */
    fun contentVersion(sessionId: String): Long =
        listOf(itemsFile(sessionId), poisFile(sessionId), marksFile(sessionId), trackFile(sessionId))
            .maxOf { if (it.exists()) it.lastModified() else 0L }

    fun create(s: Session) {
        File(dir(s.id), "photos").mkdirs()
        File(dir(s.id), "videos").mkdirs()
        save(s)
    }

    @Synchronized
    fun save(s: Session) {
        val d = dir(s.id).apply { mkdirs() }
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(s.toJson().toString(1))
        val dst = File(d, "session.json")
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
    }

    fun load(id: String): Session? = try {
        Session.fromJson(JSONObject(File(dir(id), "session.json").readText()))
    } catch (e: Exception) { null }

    /** All sessions, newest first. */
    fun list(): List<Session> = (root.listFiles { f -> f.isDirectory } ?: emptyArray())
        .mapNotNull { load(it.name) }
        .sortedByDescending { it.startedAt ?: it.createdAt }

    @Synchronized
    fun appendItem(sessionId: String, item: JSONObject) = appendLine(itemsFile(sessionId), item.toString())

    @Synchronized
    fun patchItem(sessionId: String, itemId: String, patch: JSONObject) =
        appendLine(itemsFile(sessionId), JSONObject(patch.toString()).put("_patch", itemId).toString())

    /** Items in creation order with patches applied. A torn last line (crash mid-write) is skipped. */
    @Synchronized
    fun items(sessionId: String): List<JSONObject> = records(itemsFile(sessionId))

    @Synchronized
    fun appendPoi(sessionId: String, poi: JSONObject) = appendLine(poisFile(sessionId), poi.toString())

    @Synchronized
    fun patchPoi(sessionId: String, poiId: String, patch: JSONObject) =
        appendLine(poisFile(sessionId), JSONObject(patch.toString()).put("_patch", poiId).toString())

    @Synchronized
    fun pois(sessionId: String): List<JSONObject> = records(poisFile(sessionId))

    @Synchronized
    fun appendMark(sessionId: String, mark: JSONObject) = appendLine(marksFile(sessionId), mark.toString())

    @Synchronized
    fun marks(sessionId: String): List<JSONObject> = records(marksFile(sessionId))

    /** Append-only records with patch lines {"_patch": id, ...} merged in. */
    private fun records(f: File): List<JSONObject> {
        if (!f.exists()) return emptyList()
        val byId = LinkedHashMap<String, JSONObject>()
        f.forEachLine { line ->
            val o = try { JSONObject(line) } catch (e: Exception) { return@forEachLine }
            val target = o.optString("_patch", "")
            if (target.isNotEmpty()) {
                val it = byId[target] ?: return@forEachLine
                for (k in o.keys()) if (k != "_patch") it.put(k, o.get(k))
            } else {
                byId[o.getString("id")] = o
            }
        }
        return byId.values.toList()
    }

    /** Auto road mode: append the ways of one loaded cell (kept out of session.json, which stays small). */
    @Synchronized
    fun appendWays(sessionId: String, ways: List<Way>) {
        if (ways.isEmpty()) return
        val sb = StringBuilder()
        for (w in ways) sb.append(w.toJson().toString()).append('\n')
        waysFile(sessionId).also { it.parentFile?.mkdirs() }.appendText(sb.toString())
    }

    /** All ways loaded during an auto-road ride, deduplicated, in load order. */
    @Synchronized
    fun loadedWays(sessionId: String): List<Way> {
        val f = waysFile(sessionId)
        if (!f.exists()) return emptyList()
        val byKey = LinkedHashMap<Pair<Long, Int>, Way>()   // (id, piece): pieces of one way are all kept
        f.forEachLine { line -> runCatching { Way.fromJson(JSONObject(line)) }.getOrNull()?.let { byKey[it.key] = it } }
        return byKey.values.toList()
    }

    /**
     * The contributor moved a photo on the map: keep the GPS position, store the corrected one next to it.
     * `null` puts the photo back on its GPS position.
     */
    fun relocate(sessionId: String, itemId: String, p: LatLon?, nowMs: Long = System.currentTimeMillis()) =
        patchItem(sessionId, itemId, JSONObject()
            .put("relocatedLat", p?.lat ?: JSONObject.NULL)
            .put("relocatedLon", p?.lon ?: JSONObject.NULL)
            .put("relocatedAt", if (p != null) iso(nowMs) else JSONObject.NULL))

    /** Track points (lat/lon) for drawing. */
    fun trackPoints(sessionId: String): List<LatLon> {
        val f = trackFile(sessionId)
        if (!f.exists()) return emptyList()
        val out = ArrayList<LatLon>()
        f.forEachLine { line ->
            runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.has("lat") }?.let { out += LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        }
        return out
    }

    fun trackCount(sessionId: String): Int =
        trackFile(sessionId).takeIf { it.exists() }?.useLines { lines -> lines.count { it.isNotBlank() } } ?: 0

    fun delete(sessionId: String) = dir(sessionId).deleteRecursively()

    private fun appendLine(f: File, line: String) {
        f.parentFile?.mkdirs()
        f.appendText(line + "\n")
    }
}
