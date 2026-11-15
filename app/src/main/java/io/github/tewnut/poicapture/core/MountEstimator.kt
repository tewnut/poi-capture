package io.github.tewnut.poicapture.core

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
data class MountEstimate(val yawDeg: Double, val side: CameraSide, val consistency: Double, val events: Int,
                         /** "measured" (acceleration, [MountEstimator]) or "compassMount" ([CompassYaw]) */
                         val source: String = "measured") {
    /** Same convention as Settings.mountOffsetDeg: degrees the camera is turned toward the front. */
    /**
     * The side of the road whose shop fronts the camera sees, also when it is turned diagonally (20-45 deg off the
     * travel direction, [side] = FRONT): 15 Nov, Nguyen Co Thach, the phone looked 38 deg right of travel and the
     * estimate was dropped as "front", so every photo kept the assumed 90 deg. Null only for a camera looking
     * (nearly) straight ahead or back.
     */
    val viewSide: CameraSide? get() = when {
        yawDeg in 20.0..160.0 -> CameraSide.RIGHT
        yawDeg in -160.0..-20.0 -> CameraSide.LEFT
        else -> null
    }

    val offsetTowardFrontDeg: Double get() = when (side) {
        CameraSide.RIGHT -> 90 - yawDeg
        CameraSide.LEFT -> yawDeg + 90
        else -> 0.0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("yawToTravelDeg", (yawDeg * 10).let { Math.round(it) / 10.0 })
        .put("side", side.json).put("consistency", (consistency * 100).let { Math.round(it) / 100.0 })
        .put("events", events).put("source", source)
}

/**
 * The camera's direction photo by photo when the phone is held by hand (a passenger aiming at the shops). The compass
 * alone is off by 20–40° on a motorbike (engine, steel, passing trucks); the gyroscope follows every turn of the hand
 * to a degree or two but has no north and drifts a few degrees a minute. Together: the gyro direction ("game rotation
 * vector", gyroscope + gravity only) plus the median of (compass − gyro) over the last [windowNs]. The difference is the
 * same whichever way the phone points, so its median cancels the compass's disturbances while the window follows the
 * gyro's drift.
 */
class HeadingFusion(private val windowNs: Long = 120_000_000_000L, private val minSamples: Int = 8,
                    private val maxSpreadDeg: Double = 40.0) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** One pair of camera azimuths (degrees) at [tNs]: from the compass-based and the gyro-only rotation. */
    @Synchronized fun add(tNs: Long, compassDeg: Double, gyroDeg: Double) {
        samples.addLast(tNs to ((compassDeg - gyroDeg + 540) % 360) - 180)
        while (samples.isNotEmpty() && tNs - samples.first().first > windowNs) samples.removeFirst()
        while (samples.size > 2000) samples.removeFirst()
    }

    /** Median (compass − gyro) and the spread of its middle half, or null with too few or too scattered readings. */
    @Synchronized fun offset(): Offset? {
        if (samples.size < minSamples) return null
        val v = samples.map { it.second }
        val sx = v.sumOf { kotlin.math.sin(Math.toRadians(it)) }
        val cx = v.sumOf { kotlin.math.cos(Math.toRadians(it)) }
        val mean = Math.toDegrees(atan2(sx, cx))
        val rel = v.map { ((it - mean + 540) % 360) - 180 }.sorted()
        val spread = rel[rel.size * 3 / 4] - rel[rel.size / 4]
        if (spread > maxSpreadDeg) return null
        return Offset(((mean + rel[rel.size / 2] + 540) % 360) - 180, spread, rel.size)
    }

    /** True camera azimuth for a gyro azimuth, or null while the offset is not known. */
    fun bearing(gyroDeg: Double): Double? = offset()?.let { (gyroDeg + it.deg + 720) % 360 }

    data class Offset(val deg: Double, val spreadDeg: Double, val samples: Int)
}

/**
 * The camera's yaw relative to travel from the compass, when the acceleration method ([MountEstimator]) has not
 * settled: on each sweep photo, the camera's compass azimuth minus the GPS travel bearing, at speed. One reading is
 * unreliable on a motorbike (engine, steel buildings: ±20–40°), but the median of many is a good estimate of how
 * the phone is mounted, and it is far better than assuming 90°. First ride on Mai Chí Thọ: the camera looked 32°
 * forward of perpendicular (middle half of 68 photos within 28–36°); assuming 90° put every place 20–40 m behind
 * the shop, the compass yaw halved the error.
 */
class CompassYaw(private val minSamples: Int = 15, private val maxSpreadDeg: Double = 25.0, private val minSpeedMps: Double = 2.0) {
    private val samples = ArrayList<Double>()

    /** A photo's camera azimuth and the travel bearing at that moment (degrees), and the speed (m/s). */
    @Synchronized fun add(cameraAzimuthDeg: Double?, travelBearingDeg: Double?, speedMps: Double?) {
        if (cameraAzimuthDeg == null || travelBearingDeg == null || speedMps == null || speedMps < minSpeedMps) return
        samples += ((cameraAzimuthDeg - travelBearingDeg + 540) % 360) - 180      // -180..180, right = +90
        if (samples.size > 400) samples.removeAt(0)
    }

    /** Median yaw once there are [minSamples] readings whose middle half spans at most [maxSpreadDeg]; else null. */
    @Synchronized fun estimate(): MountEstimate? {
        if (samples.size < minSamples) return null
        // around the circular mean, so that readings near ±180 do not split
        val sx = samples.sumOf { kotlin.math.sin(Math.toRadians(it)) }
        val cx = samples.sumOf { kotlin.math.cos(Math.toRadians(it)) }
        val mean = Math.toDegrees(atan2(sx, cx))
        val rel = samples.map { ((it - mean + 540) % 360) - 180 }.sorted()
        val q1 = rel[rel.size / 4]; val q3 = rel[rel.size * 3 / 4]
        if (q3 - q1 > maxSpreadDeg) return null
        val yaw = ((mean + rel[rel.size / 2] + 540) % 360) - 180
        return MountEstimate(yaw, CameraSide.ofYaw(yaw), (1 - (q3 - q1) / 90).coerceIn(0.0, 1.0), samples.size, "compassMount")
    }
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
