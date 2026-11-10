package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.io.File

/** Distance and duration of a ride from track.jsonl, ignoring poor fixes and GPS jumps. */
data class TrackStats(val distanceM: Double, val durationMs: Long, val points: Int) {
    companion object {
        fun of(track: File, maxAccuracyM: Double = 30.0): TrackStats {
            if (!track.exists()) return TrackStats(0.0, 0, 0)
            var dist = 0.0
            var first: Long? = null
            var last: Long? = null
            var prev: Pair<LatLon, Long>? = null
            var n = 0
            track.forEachLine { line ->
                val o = try { JSONObject(line) } catch (e: Exception) { return@forEachLine }
                if (!o.has("lat")) return@forEachLine
                n++
                val t = o.getLong("t")
                if (first == null) first = t
                last = t
                if (o.optDouble("acc", 999.0) > maxAccuracyM) return@forEachLine
                val p = LatLon(o.getDouble("lat"), o.getDouble("lon"))
                prev?.let { (pp, pt) ->
                    val d = Geo.distanceM(pp, p)
                    val dt = (t - pt) / 1000.0
                    if (dt > 0 && d / dt < 40) dist += d       // > 144 km/h between fixes = a GPS jump
                }
                prev = p to t
            }
            return TrackStats(dist, (last ?: 0L) - (first ?: 0L), n)
        }

        /**
         * The most photographed roads of a session (auto mode), most frequent first; a road counts only when at
         * least `minShare` of all items were taken on it (one stray match does not name a session).
         */
        /**
         * A session's name, "{street} [yymmdd]": the street most of its photos were taken on (any street when none
         * dominates, [fallback] when none is known) and the local day it started.
         */
        fun sessionName(items: List<JSONObject>, startedIso: String?, fallback: String,
                        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
            val day = runCatching { java.time.format.DateTimeFormatter.ofPattern("yyMMdd").withZone(zone).format(java.time.Instant.parse(startedIso)) }
                .getOrNull()
            return SessionNaming.name(items.map { it.optString("roadName") }, day, fallback)
        }

        fun mainRoads(items: List<JSONObject>, max: Int = 2, minShare: Double = 0.2): List<String> =
            items.mapNotNull { it.optString("roadName").takeIf { n -> n.isNotBlank() && n != "null" } }
                .groupingBy { it }.eachCount().entries
                .filter { it.value >= minShare * items.size }
                .sortedByDescending { it.value }.take(max).map { it.key }
    }
}
