package io.github.tewnut.roadcapture.core

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Where the camera looks relative to the direction of travel. */
enum class CameraSide(val json: String) {
    RIGHT("right"), LEFT("left"), FRONT("front"), BACK("back");

    companion object {
        fun ofYaw(yawDeg: Double): CameraSide = when {
            yawDeg in 45.0..135.0 -> RIGHT
            yawDeg in -135.0..-45.0 -> LEFT
            abs(yawDeg) < 45 -> FRONT
            else -> BACK
        }
    }
}

/**
 * yawDeg: camera direction relative to travel, clockwise from straight ahead (right = +90, left = −90).
 * consistency: 0–1, how well the acceleration events agree (1 = all point the same way).
 */
data class MountEstimate(val yawDeg: Double, val side: CameraSide, val consistency: Double, val events: Int) {
    /** Same convention as Settings.mountOffsetDeg: degrees the camera is turned toward the front. */
    val offsetTowardFrontDeg: Double get() = when (side) {
        CameraSide.RIGHT -> 90 - yawDeg
        CameraSide.LEFT -> yawDeg + 90
        else -> 0.0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("yawToTravelDeg", (yawDeg * 10).let { Math.round(it) / 10.0 })
        .put("side", side.json).put("consistency", (consistency * 100).let { Math.round(it) / 100.0 })
        .put("events", events).put("source", "measured")
}

/**
 * Finds where a rigidly mounted phone's rear camera looks relative to the direction of travel, without the compass
 * (a motorbike disturbs it): when the vehicle speeds up or brakes while going straight, the horizontal linear
 * acceleration in the phone's frame points along the direction of travel (forward when speeding up, backward when
 * braking — the sign comes from the GPS Doppler speed). The rear camera looks along the phone's −Z axis; the angle
 * between the two, measured around "up" (from the gravity sensor), is the camera's yaw relative to travel.
 *
 * Conventions (Android): TYPE_GRAVITY points up (+9.81 on Z when lying screen-up), TYPE_LINEAR_ACCELERATION is the
 * true acceleration (points forward when speeding up), gyroscope in rad/s around the device axes.
 * Thread-safe: sensor samples and fixes come from different threads.
 */
class MountEstimator(
    private val minEvents: Int = 6,
    private val minConsistency: Double = 0.8,
    private val minAccelMps2: Double = 0.4,
    private val minSpeedMps: Double = 2.0,
    private val maxBearingRateDegPerS: Double = 10.0,
    private val maxYawRateRadPerS: Double = 0.12,
) {
    // current window (between two fixes)
    private val lin = DoubleArray(3)
    private var nLin = 0
    private val grav = DoubleArray(3)
    private var nGrav = 0
    private val gyro = DoubleArray(3)
    private var nGyro = 0
    // all windows
    private val gravAll = DoubleArray(3)
    private val forward = DoubleArray(3)
    private var weight = 0.0
    private var events = 0
    private var prevT: Long? = null
    private var prevSpeed: Double? = null
    private var prevBearing: Double? = null

    @Synchronized fun addLinear(x: Float, y: Float, z: Float) { lin[0] += x; lin[1] += y; lin[2] += z; nLin++ }
    @Synchronized fun addGravity(x: Float, y: Float, z: Float) { grav[0] += x; grav[1] += y; grav[2] += z; nGrav++ }
    @Synchronized fun addGyro(x: Float, y: Float, z: Float) { gyro[0] += x; gyro[1] += y; gyro[2] += z; nGyro++ }

    /**
     * A position fix closes the current window. `speedMps` must be the receiver's Doppler speed (null when the fix
     * has none): differences of position-derived speeds are far too noisy.
     */
    @Synchronized
    fun onFix(elapsedNanos: Long, speedMps: Double?, bearingDeg: Double?) {
        val pt = prevT
        val pv = prevSpeed
        try {
            if (pt == null || pv == null || speedMps == null || nLin < 5 || nGrav < 5) return
            val dt = (elapsedNanos - pt) / 1e9
            if (dt < 0.5 || dt > 3.0) return
            if (minOf(pv, speedMps) < minSpeedMps) return
            val up = norm(scaled(grav, 1.0 / nGrav)) ?: return
            for (i in 0..2) gravAll[i] += up[i]
            // going straight: GPS bearing steady and no rotation around "up" (a turn's centripetal acceleration
            // is sideways and would be taken for forward)
            val pb = prevBearing
            if (pb != null && bearingDeg != null && Geo.angleDiff(pb, bearingDeg) / dt > maxBearingRateDegPerS) return
            if (nGyro > 0 && abs(dot(scaled(gyro, 1.0 / nGyro), up)) > maxYawRateRadPerS) return
            val aGps = (speedMps - pv) / dt
            if (abs(aGps) < minAccelMps2) return
            val a = scaled(lin, 1.0 / nLin)
            val aH = horizontal(a, up)
            val mag = length(aH)
            if (mag < 0.5 * abs(aGps)) return          // the phone did not feel it: not the same event
            val sign = if (aGps > 0) 1.0 else -1.0
            val w = abs(aGps)
            for (i in 0..2) forward[i] += sign * w * aH[i] / mag
            weight += w
            events++
        } finally {
            prevT = elapsedNanos
            prevSpeed = speedMps
            prevBearing = bearingDeg
            lin.fill(0.0); grav.fill(0.0); gyro.fill(0.0)
            nLin = 0; nGrav = 0; nGyro = 0
        }
    }

    /** Null until enough consistent acceleration/braking events were seen (or when the phone is not mounted rigidly). */
    @Synchronized
    fun estimate(): MountEstimate? {
        if (events < minEvents || weight <= 0) return null
        val consistency = length(forward) / weight
        if (consistency < minConsistency) return null
        val up = norm(gravAll) ?: return null
        val f = norm(horizontal(forward, up)) ?: return null
        val c = norm(horizontal(doubleArrayOf(0.0, 0.0, -1.0), up)) ?: return null   // camera looks up/down: no yaw
        // counter-clockwise angle from forward to camera around "up"; yaw is clockwise
        val ccw = Math.toDegrees(atan2(dot(up, cross(f, c)), dot(f, c)))
        return MountEstimate(-ccw, CameraSide.ofYaw(-ccw), consistency, events)
    }

    private fun scaled(v: DoubleArray, k: Double) = DoubleArray(3) { v[it] * k }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun length(v: DoubleArray) = sqrt(dot(v, v))
    private fun norm(v: DoubleArray): DoubleArray? = length(v).takeIf { it > 1e-9 }?.let { l -> scaled(v, 1 / l) }
    private fun horizontal(v: DoubleArray, up: DoubleArray): DoubleArray { val k = dot(v, up); return DoubleArray(3) { v[it] - k * up[it] } }
}
