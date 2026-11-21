package io.github.tewnut.poicapture.core

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * A ride as a Google encoded polyline (precision 5), small enough to go in tus Upload-Metadata (server/README.md,
 * "Trip metadata"): the track simplified with Douglas–Peucker, the tolerance doubled until the text fits.
 */
object Polyline {
    /** Google's encoded polyline algorithm format: lat/lon deltas at 10^-[precision] degrees, zig-zag, 5-bit chunks. */
    fun encode(points: List<LatLon>, precision: Int = 5): String {
        var factor = 1.0
        repeat(precision) { factor *= 10 }
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - pLat, sb)
            encodeValue(lon - pLon, sb)
            pLat = lat
            pLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x.toInt() + 63).toChar())
    }

    /**
     * Douglas–Peucker: the points that keep the line within [toleranceM] metres of every dropped point. Always keeps
     * the first and the last. Distances on a local flat projection (fine for a ride of a few tens of km); iterative,
     * so a long track does not overflow the stack.
     */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val kx = 111_320.0 * cos(Math.toRadians(points.map { it.lat }.average()))
        val ky = 110_540.0
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = points[a].lon * kx; val ay = points[a].lat * ky
            val dx = points[b].lon * kx - ax; val dy = points[b].lat * ky - ay
            val len2 = dx * dx + dy * dy
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val px = points[i].lon * kx - ax; val py = points[i].lat * ky - ay
                val d = if (len2 == 0.0) hypot(px, py) else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    hypot(px - t * dx, py - t * dy)
                }
                if (d > worst) { worst = d; at = i }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(a to at)
                stack.addLast(at to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The track encoded in at most [maxChars] characters: simplified at [startToleranceM], the tolerance doubled until
     * it fits. null with fewer than 2 points.
     */
    fun capped(points: List<LatLon>, maxChars: Int = 2000, startToleranceM: Double = 5.0): String? {
        if (points.size < 2) return null
        var tol = startToleranceM
        repeat(40) {
            val s = encode(simplify(points, tol))
            if (s.length <= maxChars) return s
            tol *= 2
        }
        return encode(listOf(points.first(), points.last())).takeIf { it.length <= maxChars }
    }

    /** The usable fixes of a track.jsonl, in order: the same accuracy filter as [TrackStats.of]. */
    fun trackPoints(track: File, maxAccuracyM: Double = 30.0): List<LatLon> {
        if (!track.exists()) return emptyList()
        val out = ArrayList<LatLon>()
        track.forEachLine { line ->
            val o = try { org.json.JSONObject(line) } catch (e: Exception) { return@forEachLine }
            if (!o.has("lat") || !o.has("lon")) return@forEachLine
            if (o.optDouble("acc", 999.0) > maxAccuracyM) return@forEachLine
            val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
            if (lat.isNaN() || lon.isNaN() || abs(lat) > 90 || abs(lon) > 180) return@forEachLine
            out.add(LatLon(lat, lon))
        }
        return out
    }
}
