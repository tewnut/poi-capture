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
                        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), ways: List<Way> = emptyList()): String {
            val day = runCatching { java.time.format.DateTimeFormatter.ofPattern("yyMMdd").withZone(zone).format(java.time.Instant.parse(startedIso)) }
                .getOrNull()
            val streets = items.map { it.optString("roadName") }
            val nearby = if (streets.none { SessionNaming.isNamed(it) }) nearestBigRoad(items, ways) else null
            return SessionNaming.name(streets, day, fallback, nearby)
        }

        /**
         * The named road that best describes where the photos are when none of them was taken on a named street: the
         * nearest one to their centre, bigger roads counting as nearer (a primary road 150 m away beats a named lane
         * 60 m away), within 500 m.
         */
        fun nearestBigRoad(items: List<JSONObject>, ways: List<Way>): String? {
            val pts = items.mapNotNull { i -> i.optDouble("lat").takeIf { !it.isNaN() }?.let { LatLon(it, i.optDouble("lon")) } }
                .filter { !it.lon.isNaN() }
            if (pts.isEmpty()) return null
            val c = LatLon(pts.map { it.lat }.average(), pts.map { it.lon }.average())
            return ways.mapNotNull { w ->
                val name = roadNameOf(w) ?: return@mapNotNull null
                val d = distanceToWayM(c, w)
                if (d > 500) null else name to d / roadWeight(w.highway)
            }.minByOrNull { it.second }?.first
        }

        private fun roadWeight(highway: String): Double = when (highway.removeSuffix("_link")) {
            "motorway", "trunk" -> 3.0
            "primary" -> 2.5
            "secondary" -> 2.0
            "tertiary" -> 1.5
            else -> 1.0
        }

        /** Distance from a point to a way's polyline (local flat approximation, fine within a few km). */
        private fun distanceToWayM(p: LatLon, w: Way): Double {
            val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)); val ky = 110_540.0
            var best = Double.MAX_VALUE
            for (k in 0 until w.geometry.size) {
                val a = w.geometry[k]
                val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
                if (k == w.geometry.size - 1) { best = minOf(best, kotlin.math.hypot(ax, ay)); break }
                val b = w.geometry[k + 1]
                val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, kotlin.math.hypot(ax + t * dx, ay + t * dy))
            }
            return best
        }

        fun mainRoads(items: List<JSONObject>, max: Int = 2, minShare: Double = 0.2): List<String> =
            items.mapNotNull { it.optString("roadName").takeIf { n -> n.isNotBlank() && n != "null" } }
                .groupingBy { it }.eachCount().entries
                .filter { it.value >= minShare * items.size }
                .sortedByDescending { it.value }.take(max).map { it.key }
    }
}
