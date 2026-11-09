package io.github.tewnut.poicapture.core

import org.json.JSONObject
import kotlin.math.roundToInt

/** One position fix from the platform (GPS provider). */
data class Fix(
    val t: Long,                 // UTC ms of the fix
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val elapsedNanos: Long? = null, // SystemClock.elapsedRealtimeNanos of the fix: common time base with IMU / GNSS / camera
) {
    val ll get() = LatLon(lat, lon)

    fun toTrackJson(heading: Double?): JSONObject = JSONObject()
        .put("t", t).put("lat", lat).put("lon", lon).put("acc", accuracyM)
        .put("alt", altitudeM.orNull()).put("spd", speedMps.orNull()).put("hdg", heading.orNull())
        .put("en", elapsedNanos.orNull())
}

/** An on-screen warning while capturing; the UI turns it into text in the user's language. */
sealed interface CaptureWarning {
    data class TooFast(val kmh: Int, val maxKmh: Int) : CaptureWarning
    data class WeakGps(val accuracyM: Int) : CaptureWarning
    data class OffRoad(val distanceM: Int) : CaptureWarning
    /** Turned back on the picked road: the photographed side is now the other row of buildings. */
    data object UTurn : CaptureWarning
}

/**
 * Turns raw fixes into what the capture needs: travel bearing, speed, the matched road segment (heading-aware),
 * the travel direction along the road, U-turn detection and on-screen warnings. Pure logic, unit-tested on the JVM.
 *
 * Two road modes:
 * - picked road (`fixedRoad = true`): the contributor films one road; leaving it or turning back is warned about.
 * - auto (`fixedRoad = false`): any road; road data arrives while riding (or never, when offline). Nothing is warned
 *   about the road: turning into another street or back is normal, and the side stays "left/right of travel".
 */
class FixProcessor(
    ways: List<Way>,
    side: Side,
    initialTravel: Travel?,
    maxSpeedKmh: Int,
    private val maxAccuracyM: Double,
    private val offRoadWarnM: Double = 60.0,
    private val mountOffsetDeg: Double = 0.0,
    private val fixedRoad: Boolean = true,
    /** expected camera -> shop front distance: each item gets an estimated facade point on the building side */
    var facadeDistanceM: Double? = null,
) {
    /** side of travel the camera films (assumed, then measured) */
    var side: Side = side
        set(v) { field = v; cameraBearing = camBearing(travelBearing) }
    var maxSpeedKmh: Int = maxSpeedKmh
    /** measured camera yaw relative to travel (clockwise, right = +90); null = not measured, use side + mount offset */
    var cameraYawDeg: Double? = null
        set(v) { field = v; cameraBearing = camBearing(travelBearing) }

    private fun camBearing(tb: Double?): Double? {
        val yaw = cameraYawDeg
        return if (yaw != null) tb?.let { Geo.norm360(it + yaw) } else cameraBearing(tb, side, mountOffsetDeg)
    }

    /** Yaw used for the current items: measured, else the assumed perpendicular + manual mount offset. */
    val effectiveYawDeg: Double get() = cameraYawDeg ?: if (side == Side.RIGHT) 90 - mountOffsetDeg else -90 + mountOffsetDeg

    private var ways: List<Way> = ways
    private var byId: Map<Long, Way> = ways.associateBy { it.id }

    var fix: Fix? = null; private set
    var speedMps: Double? = null; private set
    var travelBearing: Double? = null; private set
    var cameraBearing: Double? = null; private set
    var match: RoadMatch? = null; private set
    var travel: Travel? = initialTravel; private set
    /** false after an odd number of U-turns (the filmed row of buildings is now the other one). */
    var travelOk: Boolean? = null; private set
    private var prev: Fix? = null
    private var refBearing: Double? = null

    val speedKmh: Double? get() = speedMps?.let { it * 3.6 }
    val gpsGood: Boolean get() = fix?.let { it.accuracyM <= maxAccuracyM } ?: false
    val hasRoadData: Boolean get() = ways.isNotEmpty()

    /** Road data changed (auto mode: more cells loaded). */
    fun setWays(newWays: List<Way>) {
        ways = newWays
        byId = newWays.associateBy { it.id }
        fix?.let { match = matchRoad(ways, it.ll, travelBearing) }
    }

    /** The matched road's name when the rider is on it (≤ 30 m), else null. */
    fun roadName(): String? = roadWay()?.let { roadLabel(it) }

    /** The matched way when the rider is on it (≤ 30 m), else null. */
    fun roadWay(): Way? {
        val m = match ?: return null
        if (m.distanceM > 30) return null
        return byId[m.wayId]
    }

    /** Feed one fix. Returns true when the session's travel direction was decided on this fix (save the session). */
    fun onFix(f: Fix): Boolean {
        val p = f.ll
        val pr = prev
        val moved = pr != null && Geo.distanceM(pr.ll, p) >= 5
        // speed from positions, used when the receiver reports none or reports ~0 while the phone clearly moved
        // (seen on the emulator; some receivers do the same)
        val derived = if (pr != null && f.t > pr.t) Geo.distanceM(pr.ll, p) / ((f.t - pr.t) / 1000.0) else null
        val spd: Double? = when {
            f.speedMps != null && f.speedMps > 0.5 -> f.speedMps
            moved && derived != null -> derived
            else -> f.speedMps ?: derived
        }
        var tb: Double? = if (f.bearingDeg != null && (f.speedMps ?: 0.0) > 1) f.bearingDeg else null
        if (tb == null && moved) tb = Geo.bearing(pr!!.ll, p)
        val freshBearing = tb != null
        if (tb == null) tb = travelBearing
        if (pr == null || moved) prev = f

        fix = f
        speedMps = spd
        travelBearing = tb
        cameraBearing = camBearing(tb)
        match = matchRoad(ways, p, tb)

        // U-turn = the travel bearing reverses (≥ 150°) between moving fixes; bends and corners (< 150°) just
        // move the reference along. Independent of how the ways are drawn, so split roads give no false alarms.
        if (freshBearing && (spd ?: 0.0) > 1) {
            val ref = refBearing
            if (ref == null) {
                refBearing = tb
                travelOk = true
            } else {
                val d = Geo.angleDiff(tb!!, ref)
                if (d >= 150) travelOk = !(travelOk ?: true)
                refBearing = tb
            }
        }

        var decided = false
        val m = match
        if (travel == null && m != null && tb != null && (spd ?: 0.0) > 1 && m.distanceM <= offRoadWarnM) {
            travel = directionOn(tb, m.segmentBearing)
            decided = true
        }
        return decided
    }

    /** Direction along the matched way right now, falling back to the session's direction. */
    fun travelNow(): Travel? {
        val m = match
        val tb = travelBearing
        return if (m != null && tb != null) directionOn(tb, m.segmentBearing) else travel
    }

    fun warnings(): List<CaptureWarning> {
        val w = mutableListOf<CaptureWarning>()
        speedKmh?.let { if (it > maxSpeedKmh) w += CaptureWarning.TooFast(it.roundToInt(), maxSpeedKmh) }
        fix?.let { if (it.accuracyM > maxAccuracyM) w += CaptureWarning.WeakGps(it.accuracyM.roundToInt()) }
        if (fixedRoad) {
            match?.let { if (it.distanceM > offRoadWarnM) w += CaptureWarning.OffRoad(it.distanceM.roundToInt()) }
            if (travelOk == false) w += CaptureWarning.UTurn
        }
        return w
    }

    /** Position / direction / side fields shared by photo and video items (manifest contract). */
    fun itemMeta(id: String, kind: String, nowMs: Long, sideOfTravel: Side, elapsedNanos: Long? = null): JSONObject {
        val f = fix
        val m = match?.takeIf { it.distanceM <= offRoadWarnM }   // too far from any known way: no road for this item
        val tn = if (m != null) travelNow() else null
        return JSONObject()
            .put("id", id).put("kind", kind)
            .put("tMs", nowMs).put("t", iso(nowMs)).put("elapsedNanos", elapsedNanos.orNull())
            .put("fixT", f?.t.orNull())
            .put("lat", f?.lat.orNull()).put("lon", f?.lon.orNull())
            .put("accuracyM", f?.accuracyM.orNull()).put("altitudeM", f?.altitudeM.orNull())
            .put("speedMps", speedMps.orNull())
            .put("travelBearing", travelBearing?.round1().orNull())
            .put("cameraBearing", cameraBearing?.round1().orNull())
            .put("cameraYawToTravelDeg", effectiveYawDeg.round1())
            .put("cameraBearingSource", if (cameraYawDeg != null) "measured" else "assumed")
            .put("wayId", m?.wayId.orNull())
            .put("roadName", m?.let { byId[it.wayId]?.let { w -> roadLabel(w) } }.orNull())
            .put("distanceToRoadM", m?.distanceM?.round1().orNull())
            .put("segmentBearing", m?.segmentBearing?.roundToInt().orNull())
            .put("travel", tn?.json.orNull())
            .put("sideOfTravel", sideOfTravel.json)
            .put("sideOfWay", tn?.let { sideOfWay(sideOfTravel, it).json }.orNull())
            .also { o -> facadeFields(f?.ll, cameraBearing, facadeDistanceM)?.let { fj -> for (k in fj.keys()) o.put(k, fj.get(k)) } }
    }
}

/** Estimated shop-front point: from the camera position along the camera bearing, at the expected distance. */
fun facadeFields(at: LatLon?, cameraBearing: Double?, distanceM: Double?): JSONObject? {
    if (at == null || cameraBearing == null || distanceM == null) return null
    val p = Geo.destination(at, cameraBearing, distanceM)
    return JSONObject().put("facadeLat", p.lat).put("facadeLon", p.lon).put("facadeDistanceM", distanceM).put("facadeSource", "profile")
}

/**
 * Once the camera direction is measured, items recorded before that (on the "right side" assumption) are corrected:
 * camera bearing, side, side of way and facade point. Returns the patch, or null when nothing changes.
 */
object Remeasure {
    fun patch(item: JSONObject, est: MountEstimate, facadeDistanceM: Double?): JSONObject? {
        if (item.optString("cameraBearingSource") == "measured") return null
        // photos of a place (POI mode) are aimed by hand: their compass bearing is kept as is
        if (item.optString("cameraBearingSource") == "compass" || (item.has("poiId") && !item.isNull("poiId"))) return null
        val side = when (est.side) { CameraSide.RIGHT -> Side.RIGHT; CameraSide.LEFT -> Side.LEFT; else -> return null }
        val tb = if (item.isNull("travelBearing") || !item.has("travelBearing")) null else item.getDouble("travelBearing")
        val cb = tb?.let { Geo.norm360(it + est.yawDeg) }
        val travel = Travel.of(item.optStr("travel"))
        val p = JSONObject()
            .put("cameraBearing", cb?.round1().orNull())
            .put("cameraYawToTravelDeg", est.yawDeg.round1())
            .put("cameraBearingSource", "measured")
            .put("sideOfTravel", side.json)
            .put("sideOfWay", travel?.let { sideOfWay(side, it).json }.orNull())
        val at = if (item.isNull("lat") || !item.has("lat")) null else LatLon(item.getDouble("lat"), item.getDouble("lon"))
        facadeFields(at, cb, facadeDistanceM)?.let { fj -> for (k in fj.keys()) p.put(k, fj.get(k)) }
        return p
    }
}

/** Auto photo trigger: one photo every `everyM` metres, only on fixes with usable accuracy. */
class AutoShutter(var everyM: Double, private val maxAccuracyM: Double) {
    private var last: LatLon? = null

    fun reset() { last = null }

    fun due(f: Fix): Boolean {
        if (f.accuracyM > maxAccuracyM) return false
        val l = last ?: return true
        return Geo.distanceM(l, f.ll) >= everyM
    }

    fun mark(p: LatLon) { last = p }
}

internal fun Double.round1() = (this * 10).roundToInt() / 10.0
