package io.github.tewnut.poicapture.core

import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * How densely to photograph and how fast to ride, from the distance between the camera and the shop fronts
 * (not from the vehicle):
 * - one landscape frame covers `coverageM = 2 · d · tan(hFov/2)` of facade;
 * - every point of the facade should be on at least 2 photos (to triangulate signs), so photos every coverage/2;
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
    fun toJson(): JSONObject = JSONObject()
        .put("facadeDistanceM", facadeDistanceM).put("hFovDeg", hFovDeg).put("coverageM", coverageM.round1())
        .put("photoEveryM", photoEveryM.round1()).put("maxSpeedKmh", maxSpeedKmh)
        .put("blurLimitKmh", blurLimitKmh).put("rateLimitKmh", rateLimitKmh)

    companion object {
        const val DEFAULT_HFOV = 70.0
        const val EXPOSURE_S = 1.0 / 250      // typical phone daylight exposure on auto (the real one is in each photo's EXIF)
        const val MAX_BLUR_PX = 8.0           // at 2560 px width: small sign text still readable
        const val MAX_SHOTS_PER_SEC = 1.5     // sustained full-resolution shots on a mid-range phone
        const val SPEED_CAP_KMH = 30          // never suggest more on city streets

        fun of(facadeDistanceM: Double, hFovDeg: Double? = null, imageWidthPx: Int = 2560): CapturePlan {
            val d = facadeDistanceM.coerceIn(2.0, 30.0)
            val fov = (hFovDeg ?: DEFAULT_HFOV).coerceIn(30.0, 120.0)
            val half = Math.toRadians(fov / 2)
            val coverage = 2 * d * tan(half)
            val every = (coverage / 2).coerceIn(3.0, 10.0)
            val focalPx = (imageWidthPx / 2.0) / tan(half)
            val blurMps = MAX_BLUR_PX * d / (EXPOSURE_S * focalPx)
            val rateMps = every * MAX_SHOTS_PER_SEC
            val blurKmh = (blurMps * 3.6).roundToInt()
            val rateKmh = (rateMps * 3.6).roundToInt()
            return CapturePlan(d, fov, coverage, every, minOf(blurKmh, rateKmh, SPEED_CAP_KMH), blurKmh, rateKmh)
        }
    }
}
