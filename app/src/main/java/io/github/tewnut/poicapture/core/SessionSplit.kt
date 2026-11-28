package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Cuts a street sweep into one session per street (and after a long stop, [RideSplit]) when it is uploaded, so each
 * street is a session of its own on the server and can be reviewed by its own reviewer.
 *
 * Part k is the session "<ride id>-k" (parentId, part, parts in session.json), named like any session ("Trường Sa
 * 2026-11-19 09h32"). Its photos are moved into it (no copy: a ride is hundreds of MB); the track, the GNSS, IMU and
 * camera-frame logs and the marks are cut at the middle of the time between two parts; the loaded ways are copied.
 * The ride is deleted once every part is written. Interrupted, it is simply done again: the cut is the same, and a
 * photo already moved is left where it is.
 */
object SessionSplit {
    /** A session that may be cut: a photo street sweep, not already a part. */
    fun splittable(s: Session): Boolean = s.kind == SessionKind.SWEEP && s.mode != Mode.VIDEO && s.parentId == null

    /**
     * Split [sessionId] in [store] if it covers more than one street. Returns the parts' ids, or an empty list when
     * the session stays as it is (one street, not a sweep, already a part).
     */
    fun split(store: SessionStore, sessionId: String, noStreet: String, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val s = store.load(sessionId) ?: return emptyList()
        if (!splittable(s)) return emptyList()
        val photos = store.items(sessionId).filter { it.optString("kind") == "photo" }
        val rides = photos.map { RideSplit.Photo(it.optStr("roadName"), it.optLong("tMs", 0L).takeIf { t -> t > 0 }) }
        val ranges = RideSplit.parts(rides)
        if (ranges.size < 2) return emptyList()
        val ways = runCatching { s.road.ways + store.loadedWays(sessionId) }.getOrDefault(s.road.ways)

        // the cut between part k-1 and part k: the middle of the time between their photos, in both clocks
        fun mid(a: JSONObject, b: JSONObject, key: String): Long? {
            val x = a.optLong(key, 0L); val y = b.optLong(key, 0L)
            return if (x > 0 && y > 0) x + (y - x) / 2 else null
        }
        val wallCuts = (1 until ranges.size).map { k -> mid(photos[ranges[k - 1].last], photos[ranges[k].first], "tMs") }
        val nanoCuts = (1 until ranges.size).map { k -> mid(photos[ranges[k - 1].last], photos[ranges[k].first], "elapsedNanos") }

        val ids = ranges.indices.map { "$sessionId-${it + 1}" }
        val src = store.dir(sessionId)
        for ((k, range) in ranges.withIndex()) {
            val id = ids[k]
            val out = store.dir(id).apply { File(this, "photos").mkdirs(); File(this, "videos").mkdirs() }
            val wallLo = if (k == 0) Long.MIN_VALUE else wallCuts[k - 1] ?: Long.MIN_VALUE
            val wallHi = if (k == ranges.size - 1) Long.MAX_VALUE else wallCuts[k] ?: Long.MAX_VALUE
            val nanoLo = if (k == 0) Long.MIN_VALUE else nanoCuts[k - 1] ?: Long.MIN_VALUE
            val nanoHi = if (k == ranges.size - 1) Long.MAX_VALUE else nanoCuts[k] ?: Long.MAX_VALUE
            val mine = photos.slice(range)

            File(out, "items.jsonl").writeText(mine.joinToString("") { "$it\n" })
            val marks = store.marks(sessionId).filter { m -> m.optLong("tMs", 0L).let { it == 0L && k == 0 || it in wallLo until wallHi } }
            File(out, "marks.jsonl").writeText(marks.joinToString("") { "$it\n" })
            val track = cutLines(store.trackFile(sessionId), File(out, "track.jsonl")) { line ->
                runCatching { JSONObject(line).optLong("t", 0L) }.getOrNull()?.takeIf { it > 0 }?.let { it in wallLo until wallHi }
            }
            cutCsv(store.gnssFile(sessionId), File(out, "gnss_raw.csv"), 1, wallLo, wallHi)   // Raw,utcTimeMillis,...
            cutCsv(store.imuFile(sessionId), File(out, "imu.csv"), 1, nanoLo, nanoHi)         // sensor,elapsedNanos,...
            cutCsv(store.framesFile(sessionId), File(out, "frames.csv"), 0, nanoLo, nanoHi)   // elapsedNanos,...
            store.waysFile(sessionId).takeIf { it.exists() }?.copyTo(store.waysFile(id), overwrite = true)
            for (p in mine) {
                val pid = p.getString("id")
                val from = store.photoFile(sessionId, pid)
                val to = store.photoFile(id, pid)
                if (from.exists() && !to.exists() && !from.renameTo(to)) { from.copyTo(to); from.delete() }
            }

            val started = mine.first().optStr("t") ?: s.startedAt
            val street = RideSplit.street(rides, range)
            val stamp = runCatching { DateTimeFormatter.ofPattern(SessionNaming.STAMP).withZone(zone).format(Instant.parse(started)) }.getOrNull()
            val name = if (street != null) SessionNaming.name(listOf(street), stamp, noStreet)
                else TrackStats.sessionName(mine, started, noStreet, zone, ways)
            val o = s.toJson().put("id", id).put("name", name)
                .put("startedAt", started).put("endedAt", mine.last().optStr("t") ?: s.endedAt)
                .put("exportedAt", JSONObject.NULL)
                .put("counts", JSONObject().put("photos", mine.size).put("videos", 0).put("trackPoints", track))
                .put("parentId", sessionId).put("part", k + 1).put("parts", ranges.size)
            store.save(Session.fromJson(o))   // last: a part is listed only once it is complete
        }
        src.deleteRecursively()
        return ids
    }

    /** Copy the lines of [from] that [keep] (null: not a record, e.g. a header: kept); returns the records kept. */
    private fun cutLines(from: File, to: File, keep: (String) -> Boolean?): Int {
        if (!from.exists()) return 0
        var n = 0
        to.bufferedWriter().use { w ->
            from.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                when (keep(line)) {
                    null -> w.write(line + "\n")
                    true -> { w.write(line + "\n"); n++ }
                    false -> {}
                }
            }
        }
        return n
    }

    /** A CSV log cut by the time in column [col]; lines without a number there (headers, comments) are kept. */
    private fun cutCsv(from: File, to: File, col: Int, lo: Long, hi: Long) {
        cutLines(from, to) { line -> line.split(',').getOrNull(col)?.trim()?.toLongOrNull()?.let { it in lo until hi } }
    }
}
