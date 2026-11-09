package io.github.tewnut.roadcapture.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Small geodesy helpers (WGS84, spherical approximations are fine at street scale). */
object Geo {
    private const val R = 6371008.8
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h))
    }

    /** Initial bearing a → b, degrees clockwise from north, 0–360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return norm360(deg(atan2(y, x)))
    }

    /** Smallest absolute difference between two bearings, 0–180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 360) % 360)
        return if (d > 180) 360 - d else d
    }

    fun norm360(b: Double): Double = ((b % 360) + 360) % 360

    data class Nearest(val index: Int, val point: LatLon, val distanceM: Double, val segmentBearing: Double)

    /** Nearest point on a polyline to p (segment start index, point, distance, bearing of that segment). */
    fun nearestOnLine(line: List<LatLon>, p: LatLon): Nearest? {
        var best: Nearest? = null
        val kx = cos(rad(p.lat)) // local equirectangular projection around p
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = a.lat - p.lat
            val bx = (b.lon - p.lon) * kx
            val by = b.lat - p.lat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0) -(ax * dx + ay * dy) / len2 else 0.0
            t = max(0.0, min(1.0, t))
            val q = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
            val d = distanceM(p, q)
            if (best == null || d < best.distanceM) best = Nearest(i, q, d, bearing(a, b))
        }
        return best
    }

    /** Point `distanceM` from p along `bearingDeg` (great circle; exact enough at street scale). */
    fun destination(p: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / R
        val b = rad(bearingDeg)
        val lat1 = rad(p.lat)
        val lon1 = rad(p.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(deg(lat2), ((deg(lon2) + 540) % 360) - 180)
    }

    fun lineLengthM(line: List<LatLon>): Double {
        var s = 0.0
        for (i in 0 until line.size - 1) s += distanceM(line[i], line[i + 1])
        return s
    }
}

enum class Side(val json: String) {
    LEFT("left"), RIGHT("right");

    fun flipped() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s }
    }
}

enum class Travel(val json: String) {
    FORWARD("forward"), BACKWARD("backward");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s }
    }
}

/**
 * Side model. A road (OSM way) has a drawing direction (first node → last node).
 * - travel: FORWARD if the contributor drives along the drawing direction, BACKWARD otherwise.
 * - sideOfTravel: left/right of the contributor's travel direction (what the user picks).
 * - sideOfWay: the same side relative to the way's drawing direction (stable for the agent).
 * Vietnam drives on the right: the right side is the near side, the left side is across traffic.
 */
fun sideOfWay(sideOfTravel: Side, travel: Travel): Side =
    if (travel == Travel.FORWARD) sideOfTravel else sideOfTravel.flipped()

/** Direction of travel along a segment: within 90° of its drawing direction = forward. */
fun directionOn(travelBearing: Double, segmentBearing: Double): Travel =
    if (Geo.angleDiff(travelBearing, segmentBearing) <= 90) Travel.FORWARD else Travel.BACKWARD

/**
 * Camera bearing for a capture: travel bearing ± 90° toward the chosen side, turned by the mount offset
 * (degrees, positive = camera turned toward the front of the vehicle, calibrated once per mount).
 */
fun cameraBearing(travelBearing: Double?, side: Side, mountOffsetDeg: Double = 0.0): Double? {
    if (travelBearing == null) return null
    val perpendicular = if (side == Side.RIGHT) 90.0 - mountOffsetDeg else -90.0 + mountOffsetDeg
    return Geo.norm360(travelBearing + perpendicular)
}
