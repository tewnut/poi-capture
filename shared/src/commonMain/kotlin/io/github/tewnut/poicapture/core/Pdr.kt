package io.github.tewnut.poicapture.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of a walk's local frame: metres east (x) and north (y) of the frame's origin. */
data class Xy(val x: Double, val y: Double) {
    operator fun plus(o: Xy) = Xy(x + o.x, y + o.y)
    operator fun minus(o: Xy) = Xy(x - o.x, y - o.y)
    operator fun times(k: Double) = Xy(x * k, y * k)
    val length: Double get() = sqrt(x * x + y * y)
    /** Degrees clockwise from north, 0-360. */
    val bearing: Double get() = Geo.norm360(atan2(x, y) * 180 / PI)
}

/** Flat metres around an origin (equirectangular): exact enough over one building. */
class LocalFrame(val origin: LatLon) {
    private val mLat = PI / 180 * 6371008.8
    private val mLon = mLat * cos(origin.lat * PI / 180)
    fun xy(p: LatLon) = Xy((p.lon - origin.lon) * mLon, (p.lat - origin.lat) * mLat)
    fun ll(p: Xy) = LatLon(origin.lat + p.y / mLat, origin.lon + p.x / mLon)
}

/**
 * Pedestrian dead reckoning for indoor mode (docs/indoor.md §2-3): each step moves the position one step length along
 * the walking direction (the phone's yaw plus an offset that aligns it to the map). Anchors ("I am here", a good GPS
 * fix outdoors) put the position back where it really is, and the stretch between the last two anchors re-estimates
 * the step length (walked distance / steps) and the heading offset (the turn between where the steps went and where
 * the walker really went). Inside the building's footprint the position slides along the wall rather than leave it.
 *
 * Pure logic: the app feeds it steps, yaw and anchors from the sensors and the map.
 */
class Pdr(
    val frame: LocalFrame,
    stepLengthM: Double = DEFAULT_STEP_M,
    /** the building's outer rings (lat/lon); empty = no footprint constraint */
    footprint: List<List<LatLon>> = emptyList(),
    /** false when the start position is only a guess (setup location, building centre): wider uncertainty */
    anchored: Boolean = true,
) {
    private val rings: List<List<Xy>> = footprint.filter { it.size >= 3 }.map { r -> r.map { frame.xy(it) } }

    var stepLengthM: Double = stepLengthM; private set
    /** aligned heading = raw yaw + offset (degrees) */
    var offsetDeg: Double = 0.0; private set
    var rawYawDeg: Double? = null; private set
    var position: Xy = Xy(0.0, 0.0); private set
    var steps: Int = 0; private set
    var walkedM: Double = 0.0; private set
    var anchors: Int = 0; private set
    var anchored: Boolean = anchored; private set
    /** metres walked since the last anchor (path length) */
    var sinceAnchorM: Double = 0.0; private set
    private var stepsSinceAnchor = 0
    private var unitSum = Xy(0.0, 0.0)        // sum of the steps' unit vectors since the last anchor (unclamped)
    private var lastAnchor: Xy? = if (anchored) Xy(0.0, 0.0) else null
    private var pushedOutM = 0.0              // metres the steps kept pushing through the wall

    val latLon: LatLon get() = frame.ll(position)
    val headingDeg: Double? get() = rawYawDeg?.let { Geo.norm360(it + offsetDeg) }
    /** ≈ 5 % of the distance since the last anchor, at least [MIN_UNCERTAINTY_M]; wide before any anchor. */
    val uncertaintyM: Double get() = max(if (anchored) MIN_UNCERTAINTY_M else UNANCHORED_M, DRIFT * sinceAnchorM)
    val hasFootprint: Boolean get() = rings.isNotEmpty()

    /** The phone's yaw (degrees, arbitrary zero: game rotation vector). */
    fun onYaw(rawDeg: Double) { if (!rawDeg.isNaN()) rawYawDeg = Geo.norm360(rawDeg) }

    /** Align the yaw so that the current one ([raw], default the last) points to [trueDeg]; returns the new offset. */
    fun alignTo(trueDeg: Double, raw: Double? = rawYawDeg): Double? {
        val r = raw ?: return null
        offsetDeg = norm180(trueDeg - r)
        return offsetDeg
    }

    /** One step: returns false when the direction is not known yet (the step is counted, the position stays). */
    fun onStep(): Boolean {
        steps++
        val h = headingDeg ?: return false
        val rad = h * PI / 180
        val u = Xy(sin(rad), cos(rad))
        unitSum += u
        stepsSinceAnchor++
        sinceAnchorM += stepLengthM
        walkedM += stepLengthM
        position = constrain(position, position + u * stepLengthM)
        return true
    }

    /** What an anchor changed: the step length and heading re-estimated from the last two anchors (null = kept). */
    data class AnchorResult(val stepLengthM: Double?, val rotationDeg: Double?, val jumpM: Double)

    /**
     * The walker is at [at] (tap on the map, GPS outdoors). With [headingDeg] (a drag from the anchor) the yaw is
     * re-aligned to it as well.
     */
    fun anchor(at: LatLon, headingDeg: Double? = null): AnchorResult {
        val p = frame.xy(at)
        val jump = (p - position).length
        var newStep: Double? = null
        var rot: Double? = null
        val prev = lastAnchor
        val dr = unitSum.length   // in steps
        if (prev != null && stepsSinceAnchor >= MIN_STEPS && dr / stepsSinceAnchor >= MIN_STRAIGHTNESS) {
            val truth = p - prev
            if (truth.length >= MIN_STRETCH_M) {
                // trust grows with the stretch: tapping the map is a few metres off, which matters on 10 m, not on 60
                val w = min(1.0, truth.length / FULL_TRUST_M)
                val s = truth.length / dr
                if (s in MIN_STEP_M..MAX_STEP_M) {
                    stepLengthM = (stepLengthM + w * (s - stepLengthM)).coerceIn(MIN_STEP_M, MAX_STEP_M)
                    newStep = stepLengthM
                }
                val r = norm180(truth.bearing - unitSum.bearing)
                if (abs(r) <= MAX_ROTATION_DEG) {
                    offsetDeg = norm180(offsetDeg + w * r)
                    rot = w * r
                }
            }
        }
        position = p
        lastAnchor = p
        unitSum = Xy(0.0, 0.0)
        stepsSinceAnchor = 0
        sinceAnchorM = 0.0
        pushedOutM = 0.0
        anchored = true
        anchors++
        if (headingDeg != null) alignTo(headingDeg)
        return AnchorResult(newStep, rot, jump)
    }

    fun isInside(p: Xy): Boolean = rings.isNotEmpty() && inRings(p, rings)
    fun isInside(p: LatLon): Boolean = isInside(frame.xy(p))

    /**
     * Keep a step inside the footprint: a step that would leave it slides along the wall (the nearest point of the
     * outline, a little inside). Outside the building nothing is constrained; steps that keep pushing out for
     * [RELEASE_M] are let through (the walker left by a door).
     */
    private fun constrain(from: Xy, to: Xy): Xy {
        if (rings.isEmpty()) return to
        if (inRings(to, rings)) { pushedOutM = 0.0; return to }
        if (!inRings(from, rings)) return to
        pushedOutM += (to - from).length
        if (pushedOutM > RELEASE_M) { pushedOutM = 0.0; return to }
        val q = nearestOnRings(to, rings) ?: return from
        // from the point outside to the wall is the inward normal there: a little further, along the wall
        val inward = q - to
        if (inward.length < 1e-9) return from
        val inside = q + inward * (WALL_GAP_M / inward.length)
        return if (inRings(inside, rings)) inside else from
    }

    companion object {
        const val DEFAULT_STEP_M = 0.7
        const val MIN_STEP_M = 0.35
        const val MAX_STEP_M = 1.1
        const val DRIFT = 0.05
        const val MIN_UNCERTAINTY_M = 3.0
        const val UNANCHORED_M = 25.0
        /** an anchor re-estimates step length / heading only after this many steps, on a stretch at least this long */
        const val MIN_STEPS = 10
        const val MIN_STRETCH_M = 15.0
        const val FULL_TRUST_M = 40.0
        /** straight-line steps / steps: a walk that came back on itself says nothing about the heading */
        const val MIN_STRAIGHTNESS = 0.5
        /** larger turns are a wrong anchor, not drift */
        const val MAX_ROTATION_DEG = 45.0
        const val RELEASE_M = 15.0
        const val WALL_GAP_M = 0.3
        /** "đã đi N m từ lần định vị cuối" turns amber above this */
        const val REPOSITION_M = 60.0

        fun norm180(d: Double): Double = ((d % 360) + 540) % 360 - 180

        fun inRings(p: Xy, rings: List<List<Xy>>): Boolean {
            var inside = false
            for (ring in rings) {
                var j = ring.size - 1
                for (i in ring.indices) {
                    val a = ring[i]; val b = ring[j]
                    if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
                    j = i
                }
            }
            return inside
        }

        fun nearestOnRings(p: Xy, rings: List<List<Xy>>): Xy? {
            var best: Xy? = null
            var bestD = Double.MAX_VALUE
            for (ring in rings) for (i in ring.indices) {
                val a = ring[i]; val b = ring[(i + 1) % ring.size]
                val d = b - a
                val len2 = d.x * d.x + d.y * d.y
                val t = if (len2 > 0) (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / len2).coerceIn(0.0, 1.0) else 0.0
                val q = a + d * t
                val dist = (p - q).length
                if (dist < bestD) { bestD = dist; best = q }
            }
            return best
        }
    }
}

/**
 * Floor changes from the barometer (docs/indoor.md §2): the height relative to the current level's pressure
 * (~0.12 hPa per metre). A change of at least [minChangeM] that has stopped changing for [holdNs] (off the escalator,
 * out of the lift) is suggested once as so many levels up or down; the walker confirms (or not), and either way the
 * current pressure becomes the new reference.
 */
class FloorDetector(
    private val minChangeM: Double = 2.5,
    private val holdNs: Long = 5_000_000_000L,
    private val levelHeightM: Double = 4.5,
    /** the height may still wander this much while "held" (sensor noise, people breathing on the phone…) */
    private val steadyM: Double = 0.75,
) {
    private val recent = ArrayDeque<Double>()               // last raw readings, for a median
    private val history = ArrayDeque<Pair<Long, Double>>()  // (t, height m) of the last holdNs
    private var base: Double? = null
    private var smoothed: Double? = null
    private var pending = false

    /** Height (m) above the current level's reference, null before the first reading. */
    val heightM: Double? get() = base?.let { b -> smoothed?.let { (b - it) / HPA_PER_M } }

    /** One pressure reading; returns a suggested change of level (±1, ±2…) once per change, else null. */
    fun add(tNs: Long, hPa: Double): Int? {
        if (hPa.isNaN() || hPa <= 0) return null
        recent.addLast(hPa); while (recent.size > 5) recent.removeFirst()
        val m = recent.sorted()[recent.size / 2]
        smoothed = m
        if (base == null || recent.size < 5) base = m   // the reference from a full median, not one odd reading
        val h = heightM ?: return null
        history.addLast(tNs to h)
        while (history.size > 1 && tNs - history[1].first >= holdNs) history.removeFirst()
        if (pending || abs(h) < minChangeM) return null
        val held = tNs - history.first().first >= holdNs && history.all { abs(it.second - h) <= steadyM && abs(it.second) >= minChangeM }
        if (!held) return null
        pending = true
        val n = max(1, (abs(h) / levelHeightM).roundToInt())
        return if (h > 0) n else -n
    }

    /** The walker answered (or picked a level): the pressure now is the new level's reference. */
    fun rebase() {
        base = smoothed
        history.clear()
        pending = false
    }

    companion object { const val HPA_PER_M = 0.12 }
}

/**
 * Level names (docs/indoor.md): as in Vietnam, the ground floor is "1" (OSM `level=0`), the floors below are "B1",
 * "B2"… (OSM -1, -2). Internally a level is its OSM number.
 */
object Levels {
    fun name(osm: Int): String = if (osm >= 0) "${osm + 1}" else "B${-osm}"

    /** "B1" → -1, "1" / "G" / "L1" → 0, "2" / "L2" → 1; null when it is not a level name. */
    fun parse(name: String): Int? {
        val s = name.trim().uppercase()
        if (s == "G" || s == "GF") return 0
        if (s.startsWith("B")) return s.drop(1).toIntOrNull()?.takeIf { it > 0 }?.let { -it }
        val n = s.removePrefix("L").toIntOrNull() ?: return null
        return if (n >= 1) n - 1 else null
    }

    /** OSM `level` values ("0", "-1", "0;1", "1-3") → the OSM numbers they cover. */
    fun osmLevels(v: String?): List<Int> {
        if (v.isNullOrBlank()) return emptyList()
        return v.split(';').flatMap { part ->
            val p = part.trim()
            val range = Regex("^(-?\\d+)-(-?\\d+)$").find(p)
            if (range != null) {
                val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
                (min(a, b)..max(a, b)).toList()
            } else listOfNotNull(p.toDoubleOrNull()?.let { kotlin.math.floor(it).toInt() })
        }.distinct()
    }

    /** The levels to offer: from `building:levels` / `:underground` (ground only when unknown), always with [current]. */
    fun range(levels: Int?, underground: Int?, current: Int = 0): IntRange {
        val lo = min(-(underground ?: 0), current)
        val hi = max((levels ?: 1) - 1, current)
        return lo..hi
    }
}
