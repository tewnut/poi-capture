package io.github.tewnut.poicapture.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.tan

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
        const val MAX_SHOTS_PER_SEC = 1.5     // sustained full-resolution shots on a mid-range phone
        const val SPEED_CAP_KMH = 30          // never suggest more on city streets
        const val OVERLAP_LATEST = 0.45       // = OverlapTrigger.latest

        fun of(facadeDistanceM: Double, hFovDeg: Double? = null, imageWidthPx: Int = 2560): CapturePlan {
            val d = facadeDistanceM.coerceIn(2.0, 30.0)
            val fov = (hFovDeg ?: DEFAULT_HFOV).coerceIn(30.0, 120.0)
            val half = fov / 2 * PI / 180.0
            val coverage = 2 * d * tan(half)
            // the photos are triggered by how far the scene slid in the frames (OverlapTrigger), at the latest after
            // 45 % of a frame: the widest spacing to expect, used for the speed the camera can keep up with
            val every = (coverage * OVERLAP_LATEST).coerceIn(1.0, 10.0)
            val focalPx = (imageWidthPx / 2.0) / tan(half)
            val blurMps = MAX_BLUR_PX * d / (EXPOSURE_S * focalPx)
            val rateMps = every * MAX_SHOTS_PER_SEC
            val blurKmh = (blurMps * 3.6).roundToInt()
            val rateKmh = (rateMps * 3.6).roundToInt()
            return CapturePlan(d, fov, coverage, every, minOf(blurKmh, rateKmh, SPEED_CAP_KMH), blurKmh, rateKmh)
        }
    }
}
