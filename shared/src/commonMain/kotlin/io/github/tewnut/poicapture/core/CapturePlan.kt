package io.github.tewnut.poicapture.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * What a street sweep is for (docs/sweep-targets.md). It sets how the phone captures; what is read from the photos
 * is decided by the agent (a shops ride may still be read for house numbers when its photos allow).
 * - SHOPS: the camera sideways at the fronts, a photo every 30-45 % of a frame of slide.
 * - ADDRESSES: shops and every house-number plate: plates are small, so about twice as dense. Their blur budget is a
 *   smear on the wall ([maxSmearMm], speed × exposure), not pixels: a plate's digits are the same few centimetres on
 *   any street, so how far the wall is does not change how readable they are (only how many pixels they get).
 *   Speed: [ADDRESSES_SPEED_CAP_KMH] at most (rides of 24 Nov 2026: numbers still readable at 27 km/h; at 20+ km/h a
 *   plate is often on one photo only); the camera's shot rate is not held against it, one sharp photo is enough.
 * - SIGNS: traffic signs, the camera looking **ahead**: no sideways slide to measure, a photo every [SIGN_EVERY_M].
 */
enum class SweepTarget(val json: String, val earliest: Double, val latest: Double, val maxBlurPx: Double,
                       val maxSmearMm: Double? = null) {
    SHOPS("shops", 0.30, 0.45, 8.0),
    // 16 mm of smear (≈ 29 km/h at 1/500 s). Rides of 24 Nov 2026, all at 1/500 s: every house number read at up to
    // 13 mm (24 km/h, Nguyễn Thanh Sơn, which the old 8 px budget flagged 32 photos of 38); blue enamel plates still
    // read at 15-17 mm (27-30 km/h), small print (the street line, engraved / dark plates) lost from about 14 mm.
    ADDRESSES("addresses", 0.18, 0.25, 8.0, maxSmearMm = 16.0),
    SIGNS("signs", 0.30, 0.45, 8.0);

    /** Photos are triggered by the distance ridden (GPS), not by the slide of the camera frames. */
    val byDistance: Boolean get() = this == SIGNS

    companion object {
        /** Missing (older apps, Place capture): shops. */
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: SHOPS
        /** Signs ride: a photo every this many metres (a sign is on 3+ photos as it is approached). */
        const val SIGN_EVERY_M = 8.0
        /** House numbers: the largest photo the camera gives (long edge). */
        const val ADDRESSES_LONG_EDGE = 4000
        /** House numbers: never suggest more (see the class comment). */
        const val ADDRESSES_SPEED_CAP_KMH = 20
        /** Small print on a plate (its street / ward line) is readable up to about this smear (≈ 22 km/h at 1/500 s). */
        const val FINE_PRINT_SMEAR_MM = 12.0
    }
}

/**
 * How densely to photograph and how fast to ride, from the distance between the camera and the shop fronts
 * (not from the vehicle):
 * - one landscape frame covers `coverageM = 2 · d · tan(hFov/2)` of facade;
 * - every point of the facade should be on at least 2 photos (to triangulate signs): a photo at the latest every
 *   45 % of the coverage (the photos themselves are triggered by the slide measured on the camera frames);
 * - motion blur in pixels = v · exposure · focalPx / d  → speed limit for a blur budget;
 * - the camera cannot shoot faster than `maxShotsPerSec`, so the speed must also let it keep the spacing.
 */
data class CapturePlan(
    val facadeDistanceM: Double,
    val hFovDeg: Double,
    val coverageM: Double,
    val photoEveryM: Double,
    val maxSpeedKmh: Int,
    val blurLimitKmh: Int,
    val rateLimitKmh: Int,
    val target: SweepTarget = SweepTarget.SHOPS,
) {
    companion object {
        const val DEFAULT_HFOV = 70.0

        /**
         * Expected camera → shop-front distance from the type of road being ridden (OSM `highway`), on the near side
         * of what is usual so that photos overlap even where the fronts are close: the distance changes from block to
         * block, so it is not asked. Unknown road: the nearest case. Where a shop really is comes from several photos
         * (triangulation by the agent), not from this number.
         */
        fun facadeForRoad(highway: String?): Double = when (highway?.removeSuffix("_link")) {
            "motorway", "trunk", "primary" -> 8.0
            "secondary" -> 6.0
            "tertiary" -> 5.0
            else -> UNKNOWN_ROAD_FACADE_M      // residential, service, alleys, living streets, unknown
        }
        const val UNKNOWN_ROAD_FACADE_M = 4.0
        const val DEFAULT_HFOV_PORTRAIT = 43.0   // the 16:9 frame's short side, same lens as DEFAULT_HFOV
        const val EXPOSURE_S = 1.0 / 250      // typical phone daylight exposure on auto (the real one is in each photo's EXIF)
        const val MAX_BLUR_PX = 8.0           // at 2560 px width: small sign text still readable
        const val SWEEP_SHUTTER_S = 1.0 / 500 // what ShutterGuard holds on a street sweep (phones with manual control)
        const val MAX_SHOTS_PER_SEC = 1.5     // sustained full-resolution shots on a mid-range phone
        const val SPEED_CAP_KMH = 30          // never suggest more on city streets
        const val OVERLAP_LATEST = 0.45       // = OverlapTrigger.latest

        fun of(facadeDistanceM: Double, hFovDeg: Double? = null, imageWidthPx: Int = 2560,
               target: SweepTarget = SweepTarget.SHOPS): CapturePlan {
            if (target.byDistance) {
                // looking ahead, the frame moves toward the signs, not across them: blur is small, the camera's
                // shot rate is the limit
                val every = SweepTarget.SIGN_EVERY_M
                val rateKmh = (every * MAX_SHOTS_PER_SEC * 3.6).roundToInt()
                return CapturePlan(facadeDistanceM, hFovDeg ?: DEFAULT_HFOV, every / target.latest, every,
                    minOf(rateKmh, SPEED_CAP_KMH), SPEED_CAP_KMH, rateKmh, target)
            }
            val d = facadeDistanceM.coerceIn(2.0, 30.0)
            val fov = (hFovDeg ?: DEFAULT_HFOV).coerceIn(30.0, 120.0)
            val half = fov / 2 * PI / 180.0
            val coverage = 2 * d * tan(half)
            // the photos are triggered by how far the scene slid in the frames (OverlapTrigger), at the latest after
            // 45 % of a frame: the widest spacing to expect, used for the speed the camera can keep up with
            val every = (coverage * target.latest).coerceIn(1.0, 10.0)
            val focalPx = (imageWidthPx / 2.0) / tan(half)
            // house numbers: a smear on the wall at the sweep's shutter, whatever the distance (see SweepTarget)
            val blurMps = target.maxSmearMm?.let { it / 1000 / SWEEP_SHUTTER_S }
                ?: (target.maxBlurPx * d / (EXPOSURE_S * focalPx))
            val rateMps = every * MAX_SHOTS_PER_SEC
            val blurKmh = (blurMps * 3.6).roundToInt()
            val rateKmh = (rateMps * 3.6).roundToInt()
            val max = if (target == SweepTarget.ADDRESSES) minOf(blurKmh, SweepTarget.ADDRESSES_SPEED_CAP_KMH)
                else minOf(blurKmh, rateKmh, SPEED_CAP_KMH)
            return CapturePlan(d, fov, coverage, every, max, blurKmh, rateKmh, target)
        }
    }
}
