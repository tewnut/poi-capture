package io.github.tewnut.poicapture.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where a contributor on foot is (Place capture), when the GPS cannot be trusted.
 *
 * Under an arcade or inside a building the GPS goes wrong while still claiming 3–8 m: at Vista Verde (2026-11-15)
 * photos of one shop, a few steps apart, landed 30–66 m apart. So a fix is trusted only when it also agrees with
 * how far the contributor can have walked since the last trusted one (steps from the accelerometer, direction from
 * the gyro set to north by the compass). In between, the position is carried forward by those steps: a bridge from
 * the last trusted fix, good for short stretches (an entrance, an arcade), not for long walks.
 *
 * GPS that keeps disagreeing in the same way (3 fixes within [AGREE_M] of each other) wins over the steps: then the
 * bridge was what drifted.
 */
class FootTracker(private var strideM: Double = 0.7) {

    enum class Source(val json: String) { GPS("gps"), BRIDGE("bridge"), NONE("none") }

    /** The best position now, how it was obtained and how sure it is. */
    data class Estimate(
        val ll: LatLon?, val source: Source, val uncertaintyM: Double,
        /** metres walked on steps since the last trusted fix */
        val bridgedM: Double,
        /** seconds since the last trusted fix */
        val sinceTrustedS: Double?,
    ) {
        /** Too unsure for a pin without a look by the contributor. */
        val unsure: Boolean get() = ll == null || uncertaintyM > UNSURE_M
    }

    private var anchor: LatLon? = null
    private var anchorNs = 0L
    private var anchorAccM = 0.0
    private var eastM = 0.0          // walked since the anchor
    private var northM = 0.0
    private var steps = 0
    private var headingDeg: Double? = null
    private val disagreeing = ArrayDeque<Fix>()

    // stride calibration: a trusted walk outdoors (straight enough) between two trusted fixes
    private var calStart: LatLon? = null
    private var calSteps = 0

    val stride: Double get() = strideM

    /** The walking direction now (degrees from north), from the gyro + compass; null while unknown. */
    @Synchronized fun onHeading(deg: Double?) { if (deg != null && !deg.isNaN()) headingDeg = deg }

    /** One step at [tNs]: moves the bridge along the current direction. */
    @Synchronized fun onStep(@Suppress("UNUSED_PARAMETER") tNs: Long) {
        val h = headingDeg ?: return
        val r = Math.toRadians(h)
        eastM += strideM * sin(r); northM += strideM * cos(r)
        steps++; calSteps++
    }

    /** A GPS fix; true when it is trusted (and becomes the new anchor). */
    @Synchronized fun onFix(f: Fix): Boolean {
        val ns = f.elapsedNanos ?: return false
        if (f.accuracyM > MAX_ACC_M) return false
        val a = anchor
        if (a == null) { trust(f, ns); return true }
        val here = position()!!
        val off = Geo.distanceM(here, f.ll)
        val walked = hypot(eastM, northM)
        val allowance = BASE_M + f.accuracyM + anchorAccM + DRIFT * steps * strideM + (if (steps == 0) walkAllowance(ns) else 0.0)
        if (off <= allowance) { calibrate(f); trust(f, ns); return true }
        // it disagrees with the steps: keep it aside; three that agree with one another win
        disagreeing.addLast(f)
        while (disagreeing.size > 3) disagreeing.removeFirst()
        val agree = disagreeing.size == 3 && disagreeing.all { x -> disagreeing.all { y -> Geo.distanceM(x.ll, y.ll) <= AGREE_M } } &&
            (disagreeing.last().elapsedNanos ?: 0) - (disagreeing.first().elapsedNanos ?: 0) <= 15_000_000_000L
        if (agree && walked < 1.0 && steps < 3) { trust(f, ns); return true }   // standing still: the GPS settled elsewhere
        if (agree) { trust(f, ns); return true }
        return false
    }

    /** The position now. */
    @Synchronized fun estimate(nowNs: Long): Estimate {
        val a = anchor ?: return Estimate(null, Source.NONE, Double.MAX_VALUE, 0.0, null)
        val walked = steps * strideM
        val since = (nowNs - anchorNs) / 1e9
        return if (steps == 0 && since <= FRESH_S) Estimate(a, Source.GPS, anchorAccM, 0.0, since)
        else Estimate(position(), Source.BRIDGE, anchorAccM + BASE_M + DRIFT * walked + 0.1 * since.coerceAtMost(600.0), walked, since)
    }

    private fun position(): LatLon? = anchor?.let { a ->
        val d = hypot(eastM, northM)
        if (d < 1e-6) a else Geo.destination(a, Math.toDegrees(kotlin.math.atan2(eastM, northM)), d)
    }

    private fun trust(f: Fix, ns: Long) {
        anchor = f.ll; anchorNs = ns; anchorAccM = f.accuracyM
        eastM = 0.0; northM = 0.0; steps = 0
        disagreeing.clear()
    }

    /** Standing still (no steps): the GPS may drift by itself a little over time. */
    private fun walkAllowance(ns: Long) = (((ns - anchorNs) / 1e9) * 0.3).coerceIn(0.0, 10.0)

    private fun calibrate(f: Fix) {
        val s = calStart
        if (s == null || f.accuracyM > CAL_ACC_M) { calStart = f.ll; calSteps = 0; return }
        if (calSteps >= CAL_STEPS) {
            val ratio = Geo.distanceM(s, f.ll) / calSteps
            if (ratio in 0.45..0.95) strideM = 0.8 * strideM + 0.2 * ratio
            calStart = f.ll; calSteps = 0
        }
    }

    companion object {
        const val MAX_ACC_M = 15.0      // a fix claiming worse than this is never trusted
        const val BASE_M = 4.0          // slack around the bridge
        const val DRIFT = 0.15          // bridge error per metre walked (heading and stride)
        const val AGREE_M = 8.0         // three fixes this close: the GPS is consistent, follow it
        const val UNSURE_M = 15.0       // more uncertain than this: ask the contributor to check the pin
        const val FRESH_S = 3.0
        const val CAL_ACC_M = 5.0
        const val CAL_STEPS = 20
    }
}

/** Indoor and position check for one place, from its photos' estimates. */
object PlacePosition {
    data class Check(val indoor: Boolean, val unsure: Boolean, val spreadM: Double)

    /** The position closest to all the others (sum of distances); null without positions. */
    fun medoid(points: List<LatLon>): LatLon? =
        points.minByOrNull { p -> points.sumOf { Geo.distanceM(p, it) } }

    /** [photos]: (position, source, uncertainty, GPS stale) of each photo of the place. */
    fun check(photos: List<FootTracker.Estimate>, staleFlags: List<Boolean>): Check {
        val pts = photos.mapNotNull { it.ll }
        var spread = 0.0
        for (i in pts.indices) for (j in i + 1 until pts.size) spread = maxOf(spread, Geo.distanceM(pts[i], pts[j]))
        val offGps = photos.indices.count { photos[it].source != FootTracker.Source.GPS || staleFlags.getOrElse(it) { false } }
        val indoor = photos.isNotEmpty() && offGps * 2 > photos.size
        val unsure = photos.any { it.unsure } || spread > FootTracker.UNSURE_M || indoor
        return Check(indoor, unsure, spread)
    }
}

/**
 * Steps from the accelerometer: |a| smoothed, a peak above [THRESHOLD] m/s² at least [MIN_GAP_NS] after the last
 * step (618 steps over the 10 minutes of the Vista Verde capture, about one a second while walking).
 */
class StepDetector {
    private var smooth = 9.81
    private var prev = 9.81
    private var prevT = 0L
    private var prev2 = 9.81
    private var lastStep = Long.MIN_VALUE / 2

    /** One accelerometer magnitude; returns the time of a step found at the previous sample, else null. */
    fun add(tNs: Long, magnitude: Double): Long? {
        smooth += K * (magnitude - smooth)
        var step: Long? = null
        if (prev > THRESHOLD && prev >= prev2 && prev >= smooth && prevT - lastStep > MIN_GAP_NS) { step = prevT; lastStep = prevT }
        prev2 = prev; prev = smooth; prevT = tNs
        return step
    }

    companion object {
        const val K = 0.25
        const val THRESHOLD = 10.6
        const val MIN_GAP_NS = 330_000_000L
    }
}
