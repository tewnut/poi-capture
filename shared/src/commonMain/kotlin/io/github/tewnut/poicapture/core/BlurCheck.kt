package io.github.tewnut.poicapture.core

import kotlin.math.PI
import kotlin.math.tan

/**
 * Is a photo sharp enough to read the signs? Two independent views:
 * - [motionPx]: how far the shop fronts slid across the sensor during the exposure, from the facts (speed from the
 *   GPS, exposure time from the photo, focal length in pixels from the field of view, distance to the fronts);
 * - [sharpness]: variance of the Laplacian of the image (high = crisp edges); catches what the facts miss, mostly
 *   a vibrating mount. It depends on the scene, so it is judged against the session's own recent photos.
 */
object BlurCheck {
    /** Smear allowed at 2560 px across, scaled to the photo's width (same budget as CapturePlan.MAX_BLUR_PX). */
    fun maxMotionPx(widthPx: Int) = CapturePlan.MAX_BLUR_PX * widthPx / 2560.0

    fun motionPx(speedMps: Double, exposureS: Double, hFovDeg: Double, widthPx: Int, distanceM: Double): Double {
        if (distanceM <= 0 || widthPx <= 0 || hFovDeg <= 0) return 0.0
        val focalPx = (widthPx / 2.0) / tan(hFovDeg / 2 * PI / 180.0)
        return speedMps * exposureS * focalPx / distanceM
    }

    /** Variance of the 4-neighbour Laplacian over the middle of a grayscale image (values 0–255). */
    fun sharpness(gray: IntArray, w: Int, h: Int): Double {
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in maxOf(1, h / 6) until minOf(h - 1, h * 5 / 6)) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += l; sum2 += l * l; n++
            }
        }
        if (n == 0) return 0.0
        val m = sum / n
        return sum2 / n - m * m
    }

    /**
     * Unusable: almost no detail compared to the session's recent photos (under [UNUSABLE_SHARE] of their median), e.g.
     * a motorbike passing a metre from the lens, or a fully smeared frame. Plain scenes (a blank wall) keep far more.
     */
    fun unusable(sharpness: Double?, recent: List<Double>): Boolean {
        if (sharpness == null || recent.size < 5) return false
        val median = recent.sorted()[recent.size / 2]
        return sharpness < UNUSABLE_SHARE * median
    }
    const val UNUSABLE_SHARE = 0.08

    /** Blurry: too much motion, or far less detail than the session's recent photos (median of [recent]). */
    fun blurry(motionPx: Double?, widthPx: Int, sharpness: Double?, recent: List<Double>): Boolean {
        if (motionPx != null && motionPx > maxMotionPx(widthPx)) return true
        if (sharpness != null && recent.size >= 5) {
            val median = recent.sorted()[recent.size / 2]
            if (sharpness < 0.35 * median) return true
        }
        return false
    }
}
