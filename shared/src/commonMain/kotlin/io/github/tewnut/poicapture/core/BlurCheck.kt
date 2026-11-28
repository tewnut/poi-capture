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

    /**
     * Smear allowed on this photo, in its pixels at the wall [distanceM] away (the distance [motionPx] used).
     * - house numbers ([SweepTarget.maxSmearMm]): a smear on the wall, so the budget grows with the photo's focal
     *   length in pixels and shrinks with the distance exactly as the smear does: the photo is blurry when
     *   speed × exposure passes the budget (16 mm), whether held portrait or landscape and however far the wall is;
     * - shops and signs: [SweepTarget.maxBlurPx] at 2560 px across, scaled to the photo's width (as before).
     */
    fun maxMotionPx(widthPx: Int, hFovDeg: Double, distanceM: Double, target: SweepTarget): Double {
        val smear = target.maxSmearMm
        if (smear == null || distanceM <= 0 || widthPx <= 0 || hFovDeg <= 0) return target.maxBlurPx * widthPx / 2560.0
        return smear / 1000 * focalPx(widthPx, hFovDeg) / distanceM
    }

    fun focalPx(widthPx: Int, hFovDeg: Double) = (widthPx / 2.0) / tan(hFovDeg / 2 * PI / 180.0)

    /** How far the wall moved while the shutter was open, in millimetres (speed × exposure): what a plate's digits suffer. */
    fun smearMm(speedMps: Double, exposureS: Double) = speedMps * exposureS * 1000

    fun motionPx(speedMps: Double, exposureS: Double, hFovDeg: Double, widthPx: Int, distanceM: Double): Double {
        if (distanceM <= 0 || widthPx <= 0 || hFovDeg <= 0) return 0.0
        return speedMps * exposureS * focalPx(widthPx, hFovDeg) / distanceM
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
    fun blurry(motionPx: Double?, widthPx: Int, sharpness: Double?, recent: List<Double>): Boolean =
        judge(motionPx, maxMotionPx(widthPx), sharpness, recent).blurry

    /** Why a photo is blurry: [motion] = the ride was too fast for the shutter; [soft] = far less detail than usual. */
    data class Verdict(val motion: Boolean, val soft: Boolean) {
        val blurry get() = motion || soft
    }

    fun judge(motionPx: Double?, maxMotionPx: Double, sharpness: Double?, recent: List<Double>): Verdict {
        val motion = motionPx != null && motionPx > maxMotionPx
        var soft = false
        if (sharpness != null && recent.size >= 5) {
            val median = recent.sorted()[recent.size / 2]
            soft = sharpness < SOFT_SHARE * median
        }
        return Verdict(motion, soft)
    }
    const val SOFT_SHARE = 0.35

    /**
     * Tell the rider "blurry: slow down" (street sweep) from the last few verdicts, oldest first: 2 of the last 3
     * too fast for the shutter, or the last [SOFT_RUN] all soft (a vibrating mount, a dirty lens). One or two soft
     * photos in a row are a truck or a motorbike passing close: not the rider's doing, and the next photos are fine
     * (rides of 24 Nov 2026: the only flags on the slow rides were passing traffic).
     */
    fun warnRider(recent: List<Verdict>): Boolean {
        val last3 = recent.takeLast(3)
        if (last3.count { it.motion } >= 2) return true
        return recent.size >= SOFT_RUN && recent.takeLast(SOFT_RUN).all { it.soft }
    }
    const val SOFT_RUN = 3
}
