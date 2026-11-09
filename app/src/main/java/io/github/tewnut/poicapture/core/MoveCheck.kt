package io.github.tewnut.poicapture.core

import kotlin.math.sin

/** What may be wrong with a photo moved by hand (Photo locations → Move). Warnings only: the contributor decides. */
enum class MoveWarning { FAR, ON_ROAD, ACROSS }

/**
 * Checks a hand-moved position [to] against where the photo was taken ([gps], the camera) and the roads around:
 * - FAR: more than [maxM] from the camera position (GPS error plus a deep shop front fit well within it);
 * - ON_ROAD: closer to a road's centre line than half its usual width (by OSM type; paths and footways ignored);
 * - ACROSS: on the other side of the road nearest to the camera than the side the camera looked at ([cameraBearing];
 *   without it, e.g. on foot, the side the photo was taken from).
 */
object MoveCheck {
    const val MAX_M = 20.0

    fun warnings(gps: LatLon, cameraBearing: Double?, to: LatLon, ways: List<Way>, maxM: Double = MAX_M): Set<MoveWarning> {
        val out = mutableSetOf<MoveWarning>()
        if (Geo.distanceM(gps, to) > maxM) out += MoveWarning.FAR

        val onRoad = ways.any { w ->
            val half = halfWidthM(w.highway) ?: return@any false
            (Geo.nearestOnLine(w.geometry, to)?.distanceM ?: Double.MAX_VALUE) < half
        }
        if (onRoad) out += MoveWarning.ON_ROAD

        // the street the photo was taken on: the nearest drivable way to the camera, if it is close
        val street = ways.filter { halfWidthM(it.highway) != null }
            .mapNotNull { w -> Geo.nearestOnLine(w.geometry, gps)?.let { w to it } }
            .minByOrNull { it.second.distanceM }?.takeIf { it.second.distanceM <= 40 }?.first
        if (street != null) {
            val looked = cameraBearing?.let { Geo.destination(gps, it, 15.0) } ?: gps
            val expected = side(street, looked)
            val now = side(street, to)
            if (expected != 0 && now != 0 && expected != now) out += MoveWarning.ACROSS
        }
        return out
    }

    /** +1 right of the way's drawing direction, −1 left, 0 on it (within a metre). */
    private fun side(w: Way, p: LatLon): Int {
        val n = Geo.nearestOnLine(w.geometry, p) ?: return 0
        if (n.distanceM < 1.0) return 0
        val s = sin(Math.toRadians(Geo.bearing(n.point, p) - n.segmentBearing))
        return if (s > 0) 1 else if (s < 0) -1 else 0
    }

    /** Half the usual carriageway width by OSM `highway` type; null = not a road for cars (no "on the road" check). */
    fun halfWidthM(highway: String): Double? = when (highway.removeSuffix("_link")) {
        "motorway", "trunk" -> 9.0
        "primary" -> 7.0
        "secondary" -> 5.0
        "tertiary" -> 4.0
        "residential", "unclassified", "living_street", "road" -> 3.0
        "service" -> 2.0
        else -> null      // footway, path, pedestrian, steps, track, cycleway…
    }
}
