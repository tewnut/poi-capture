package io.github.tewnut.poicapture.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How far the scene slid sideways between two consecutive low-resolution camera frames, as a fraction of the frame
 * width (the photo's horizontal). This is what decides photo overlap, measured directly: no GPS, no guess of how far
 * the shop fronts are.
 *
 * Method: the frame is cut into horizontal bands; each band becomes a 1-D profile (the change of its column means,
 * so a brightness change does not matter and shop borders, pillars and sign edges stand out); the profiles of the
 * two frames are matched by normalised cross-correlation over the allowed shifts. Each band that matches well votes;
 * the median vote wins, so one band filled by a motorbike riding alongside (no shift) or passing close (big shift)
 * does not decide.
 */
object FrameShift {
    const val BANDS = 5
    const val MAX_SHIFT = 0.35          // per frame: 35 % of the width (≈ 30 km/h at 10 frames/s on a narrow street)
    const val MIN_CORRELATION = 0.6

    class Profiles internal constructor(val width: Int, internal val bands: List<DoubleArray?>)

    data class Shift(val fraction: Double, val confidence: Double, val bandsAgreeing: Int)

    /**
     * Band profiles of a grayscale frame (values 0–255, row-major `w × h`), already turned so that the photo's
     * horizontal runs along x. A band with almost no texture (sky, a blank wall) gets no profile.
     */
    fun profiles(gray: IntArray, w: Int, h: Int, bands: Int = BANDS): Profiles {
        require(gray.size >= w * h && w >= 8 && h >= bands)
        val out = ArrayList<DoubleArray?>(bands)
        for (b in 0 until bands) {
            val y0 = h * b / bands; val y1 = h * (b + 1) / bands
            val mean = DoubleArray(w)
            for (y in y0 until y1) { val r = y * w; for (x in 0 until w) mean[x] += gray[r + x].toDouble() }
            val n = (y1 - y0).toDouble()
            val d = DoubleArray(w - 1) { x -> (mean[x + 1] - mean[x]) / n }
            var s2 = 0.0; for (v in d) s2 += v * v
            out += if (sqrt(s2 / d.size) < 0.3) null else d      // < 0.3 grey level per column on average: no texture
        }
        return Profiles(w, out)
    }

    /** Shift of [cur] relative to [prev] (positive: the scene moved towards +x), or null when nothing matched. */
    fun between(prev: Profiles, cur: Profiles, maxShift: Double = MAX_SHIFT): Shift? {
        if (prev.width != cur.width || prev.bands.size != cur.bands.size) return null
        val votes = ArrayList<Pair<Double, Double>>()
        for (i in prev.bands.indices) {
            val a = prev.bands[i] ?: continue
            val b = cur.bands[i] ?: continue
            match(a, b, (maxShift * prev.width).toInt())?.let { votes += it }
        }
        if (votes.isEmpty()) return null
        val sorted = votes.sortedBy { it.first }
        val mid = sorted[(sorted.size - 1) / 2]
        val agreeing = votes.count { abs(it.first - mid.first) <= 2.0 }
        return Shift(mid.first / (prev.width - 1), mid.second, agreeing)
    }

    /** Best shift s (in samples) with b[x + s] ≈ a[x], refined below one sample; null under [MIN_CORRELATION]. */
    private fun match(a: DoubleArray, b: DoubleArray, maxS: Int): Pair<Double, Double>? {
        val n = a.size
        val minOverlap = n - maxS
        if (minOverlap < 8) return null
        val scores = DoubleArray(2 * maxS + 1) { -1.0 }
        var best = -1; var bestScore = -1.0
        for (k in scores.indices) {
            val s = k - maxS
            val x0 = maxOf(0, -s); val x1 = minOf(n, n - s)
            if (x1 - x0 < minOverlap) continue
            var sa = 0.0; var sb = 0.0
            for (x in x0 until x1) { sa += a[x]; sb += b[x + s] }
            val m = (x1 - x0).toDouble(); val ma = sa / m; val mb = sb / m
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (x in x0 until x1) { val p = a[x] - ma; val q = b[x + s] - mb; ab += p * q; aa += p * p; bb += q * q }
            if (aa <= 0 || bb <= 0) continue
            val c = ab / sqrt(aa * bb)
            scores[k] = c
            if (c > bestScore) { bestScore = c; best = k }
        }
        if (best < 0 || bestScore < MIN_CORRELATION) return null
        var s = (best - maxS).toDouble()
        if (best in 1 until scores.size - 1 && scores[best - 1] > -1 && scores[best + 1] > -1) {
            val l = scores[best - 1]; val r = scores[best + 1]; val den = l - 2 * bestScore + r
            if (den < 0) s += 0.5 * (l - r) / den        // parabola through the peak
        }
        return s to bestScore
    }
}

/**
 * When to take the next street-sweep photo: once the scene has slid far enough since the last one that every part
 * of the facade is still on two photos, and, inside that window, at a steady moment (a sharp frame, little shake).
 *
 * - [earliest]…[latest]: the window, as a fraction of the frame width. Shooting by [latest] (45 %) keeps ≥ 55 %
 *   overlap, a little margin over the 50 % that "every point on two photos" needs, for the shutter delay.
 * - Slide comes from the frames ([addFrameShift]); where they cannot tell (dark, blurred, a bus in front) from the
 *   GPS speed over the time that passed ([addSpeed]), over the expected facade coverage of one photo.
 * - [behind]: the slide went past [tooFast] before a photo could be taken: the camera cannot keep up.
 */
class OverlapTrigger(val earliest: Double = 0.30, val latest: Double = 0.45, val tooFast: Double = 0.80) {
    private var measured = 0.0      // signed: a shaking phone goes back and forth and adds up to nothing
    private var measuredAbs = 0.0
    private var estimated = 0.0

    /** Slide since the last photo, as a fraction of the frame width. */
    val progress: Double get() = abs(measured) + estimated

    /** Share of [progress] measured on the frames (the rest from GPS speed). */
    val measuredShare: Double get() = if (measuredAbs + estimated <= 0) 0.0 else measuredAbs / (measuredAbs + estimated)

    val behind: Boolean get() = progress >= tooFast

    /** Recording starts: the first photo is due right away. */
    fun start() { measured = 0.0; measuredAbs = 0.0; estimated = latest }

    fun addFrameShift(fraction: Double) { measured += fraction; measuredAbs += abs(fraction) }

    fun addSpeed(speedMps: Double, dtS: Double, coverageM: Double) {
        if (speedMps > 0 && dtS > 0 && coverageM > 0) estimated += speedMps * dtS.coerceAtMost(2.0) / coverageM
    }

    fun due(steady: Boolean): Boolean = progress >= latest || (steady && progress >= earliest)

    /** A photo was taken: start counting again. */
    fun shot() { measured = 0.0; measuredAbs = 0.0; estimated = 0.0 }
}

/** Keeps the recent values of a signal and says whether the latest one is good compared to them (median based). */
class RecentMedian(private val size: Int = 30) {
    private val values = ArrayDeque<Double>()
    fun add(v: Double) { values.addLast(v); while (values.size > size) values.removeFirst() }
    val median: Double? get() = if (values.size < 5) null else values.sorted()[values.size / 2]
}
