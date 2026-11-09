package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a session went, small enough to draw many sessions on the setup map ("đã quét"): the track thinned to points
 * at least `minStepM` apart, split where GPS jumped, plus the places of a POI session. Cached as coverage.json.
 */
object Coverage {
    fun simplify(track: List<LatLon>, minStepM: Double = 10.0, maxGapM: Double = 200.0): List<List<LatLon>> {
        val lines = mutableListOf<MutableList<LatLon>>()
        var cur = mutableListOf<LatLon>()
        for (p in track) {
            val last = cur.lastOrNull()
            when {
                last == null -> cur.add(p)
                Geo.distanceM(last, p) > maxGapM -> { if (cur.size > 1) lines += cur; cur = mutableListOf(p) }
                Geo.distanceM(last, p) >= minStepM -> cur.add(p)
            }
        }
        if (cur.size > 1) lines += cur
        return lines
    }

    fun build(kind: SessionKind, track: List<LatLon>, places: List<LatLon>): JSONObject {
        val lines = simplify(track)
        val all = lines.flatten() + places
        val bbox = if (all.isEmpty()) JSONObject.NULL else JSONArray(listOf(
            all.minOf { it.lat }, all.minOf { it.lon }, all.maxOf { it.lat }, all.maxOf { it.lon }))
        return JSONObject().put("kind", kind.json).put("bbox", bbox)
            .put("lines", JSONArray(lines.map { l -> JSONArray(l.map { JSONArray(listOf(r6(it.lat), r6(it.lon))) }) }))
            .put("points", JSONArray(places.map { JSONArray(listOf(r6(it.lat), r6(it.lon))) }))
    }

    /** coverage.json of a session, computed (and cached) when missing or older than the session's content. */
    fun forSession(store: SessionStore, sessionId: String): JSONObject? {
        val f = store.coverageFile(sessionId)
        if (f.exists() && f.lastModified() >= store.contentVersion(sessionId)) {
            runCatching { return JSONObject(f.readText()) }
        }
        val s = store.load(sessionId) ?: return null
        val places = store.pois(sessionId).mapNotNull { p ->
            p.optJSONObject("position")?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        }
        val c = build(s.kind, store.trackPoints(sessionId), places)
        runCatching { f.writeText(c.toString()) }
        return c
    }

    fun lines(c: JSONObject): List<List<LatLon>> {
        val a = c.optJSONArray("lines") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { l -> (0 until l.length()).map { j -> l.getJSONArray(j).let { LatLon(it.getDouble(0), it.getDouble(1)) } } } }
    }

    fun points(c: JSONObject): List<LatLon> {
        val a = c.optJSONArray("points") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } }
    }

    /** bbox [s, w, n, e] of a coverage intersects the given box (map view). */
    fun intersects(c: JSONObject, s: Double, w: Double, n: Double, e: Double): Boolean {
        val b = c.optJSONArray("bbox") ?: return false
        return !(b.getDouble(2) < s || b.getDouble(0) > n || b.getDouble(3) < w || b.getDouble(1) > e)
    }

    private fun r6(d: Double) = Math.round(d * 1e6) / 1e6
}
