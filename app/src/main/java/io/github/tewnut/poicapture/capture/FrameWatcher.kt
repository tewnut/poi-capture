package io.github.tewnut.poicapture.capture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import io.github.tewnut.poicapture.core.BlurCheck
import io.github.tewnut.poicapture.core.FrameShift
import java.util.concurrent.Executors

/**
 * Street sweep: a small 16:9 stream next to the preview (same field of view as the photos), read on its own thread:
 * - how far the scene slid since the previous frame, along the photo's horizontal ([FrameShift]): what triggers
 *   the photos (see OverlapTrigger);
 * - how sharp the frame is (Laplacian variance): with the gyroscope, says when the camera is steady;
 * - mean brightness: steers the ISO of [ShutterGuard] when the shutter is held at 1/500 s.
 *
 * Results arrive on the main thread.
 */
class FrameWatcher(private val onFrame: (Frame) -> Unit, private val onBrightness: (Double) -> Unit) {
    /** [shift]: fraction of the frame width, null when the frames could not tell (dark, blank, blurred, first frame). */
    data class Frame(val tNs: Long, val shift: Double?, val sharpness: Double, val brightness: Double)

    private val thread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The phone is held upright: the photo's horizontal is the sensor's short side. */
    @Volatile var portrait = false
    private var prev: FrameShift.Profiles? = null
    private var prevPortrait = false
    private var gray = IntArray(0)

    fun analysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build())
        .build().also { a -> a.setAnalyzer(thread) { img -> try { analyze(img) } finally { img.close() } } }

    fun release() { thread.shutdown() }

    private fun analyze(img: ImageProxy) {
        val t = SystemClock.elapsedRealtimeNanos()
        val y = img.planes[0]
        val buf = y.buffer
        val stride = y.rowStride
        val step = maxOf(1, img.width / 320)
        val gw = img.width / step; val gh = img.height / step
        val up = portrait
        if (gray.size != gw * gh) gray = IntArray(gw * gh)
        // grayscale at ~320 px, turned so that the photo's horizontal runs along x (sensor buffers are landscape)
        val w = if (up) gh else gw
        val h = if (up) gw else gh
        var sum = 0L; var n = 0
        for (r in 0 until gh) {
            val row = r * step * stride
            for (c in 0 until gw) {
                val v = buf.get(row + c * step).toInt() and 0xFF
                if (up) gray[c * w + r] = v else gray[r * w + c] = v
                if (r in gh / 4 until gh * 3 / 4 && c in gw / 4 until gw * 3 / 4) { sum += v; n++ }
            }
        }
        val brightness = if (n > 0) sum.toDouble() / n else 0.0
        val profiles = FrameShift.profiles(gray, w, h)
        val p = prev
        val shift = if (p != null && prevPortrait == up) FrameShift.between(p, profiles)?.fraction else null
        prev = profiles; prevPortrait = up
        val sharp = BlurCheck.sharpness(gray, w, h)
        main.post { onBrightness(brightness); onFrame(Frame(t, shift, sharp, brightness)) }
    }
}
