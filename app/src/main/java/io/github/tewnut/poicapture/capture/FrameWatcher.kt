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
import io.github.tewnut.poicapture.core.SlideTracker
import java.util.concurrent.Executors

/**
 * Street sweep: a small 16:9 stream next to the preview (same field of view as the photos), read on its own thread:
 * - how far the scene slid since the previous frame, along the photo's horizontal, measured against a reference
 *   frame ([SlideTracker], [FrameShift]): what triggers the photos (see OverlapTrigger);
 * - how sharp the frame is (Laplacian variance): with the gyroscope, says when the camera is steady;
 * - mean brightness: steers the ISO of [ShutterGuard] when the shutter is held at 1/500 s.
 *
 * Results arrive on the main thread.
 */
class FrameWatcher(private val onFrame: (Frame) -> Unit, private val onBrightness: (Double) -> Unit) {
    /** [shift]: fraction of the frame width, null when the frames could not tell (dark, blank, blurred, first frame). */
    data class Frame(val tNs: Long, val shift: Double?, val sharpness: Double, val brightness: Double,
                     /** how the frame matched its reference (for frames.csv); null = it became the first reference */
                     val match: io.github.tewnut.poicapture.core.FrameShift.Match? = null, val portrait: Boolean = false,
                     /** the camera buffer the frame was read from (for frames.csv) */
                     val bufferW: Int = 0, val bufferH: Int = 0)

    private val thread = Executors.newSingleThreadExecutor()

    /**
     * Signs ride: called with an upright colour copy of a frame when [wantColor] says yes (the scan is idle and due).
     * The receiver owns the bitmap. Costs a YUV -> RGB conversion, so only a few frames a second.
     */
    @Volatile var colorFrame: ((android.graphics.Bitmap, Long) -> Unit)? = null
    @Volatile var wantColor: () -> Boolean = { false }
    private var useCase: ImageAnalysis? = null

    /** The phone turned: frames are still sensor-landscape, only their rotation (for [colorFrame]) follows. */
    fun setRotation(r: Int) { useCase?.targetRotation = r }
    private val main = Handler(Looper.getMainLooper())

    /** The phone is held upright: the photo's horizontal is the sensor's short side. */
    @Volatile var portrait = false
    private val tracker = SlideTracker()
    private var prevPortrait = false
    private var gray = IntArray(0)
    private var row = ByteArray(0)
    private var acc = IntArray(0)

    fun analysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build())
        .build().also { a -> useCase = a; a.setAnalyzer(thread) { img -> try { analyze(img); color(img) } finally { img.close() } } }

    private fun color(img: ImageProxy) {
        val cb = colorFrame ?: return
        if (!wantColor()) return
        val t = SystemClock.elapsedRealtimeNanos()
        val raw = runCatching { img.toBitmap() }.getOrNull() ?: return
        val deg = img.imageInfo.rotationDegrees
        val up = if (deg == 0) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
            android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
        cb(up, t)
    }

    fun release() { thread.shutdown() }

    private fun analyze(img: ImageProxy) {
        val t = SystemClock.elapsedRealtimeNanos()
        val y = img.planes[0]
        val buf = y.buffer
        val stride = y.rowStride
        // ~320 px along the photo's horizontal: landscape = the sensor's long side (halved), portrait = its short side
        val up = portrait
        val step = if (up) maxOf(1, img.height / 360) else maxOf(1, img.width / 320)
        val gw = img.width / step; val gh = img.height / step
        if (gray.size != gw * gh) gray = IntArray(gw * gh)
        // grayscale at ~320 px, turned so that the photo's horizontal runs along x (sensor buffers are landscape).
        // Each pixel is the mean of its step x step block, not one pixel picked out of it: picking aliases, so fine
        // texture (tiles, grilles, lettering) flickered from frame to frame as the scene slid by a fraction of a
        // block, and two frames 33 ms apart matched in only 15-28 % of cases (15 Nov rides, frames.csv).
        val w = if (up) gh else gw
        val h = if (up) gw else gh
        if (row.size < img.width) row = ByteArray(img.width)
        if (acc.size != gw) acc = IntArray(gw)
        val area = step * step
        var sum = 0L; var n = 0
        for (r in 0 until gh) {
            java.util.Arrays.fill(acc, 0)
            for (k in 0 until step) {
                buf.position((r * step + k) * stride)
                buf.get(row, 0, minOf(img.width, buf.remaining()))
                for (c in 0 until gw) { var s = 0; val x0 = c * step; for (j in 0 until step) s += row[x0 + j].toInt() and 0xFF; acc[c] += s }
            }
            for (c in 0 until gw) {
                val v = acc[c] / area
                if (up) gray[c * w + r] = v else gray[r * w + c] = v
                if (r in gh / 4 until gh * 3 / 4 && c in gw / 4 until gw * 3 / 4) { sum += v; n++ }
            }
        }
        buf.rewind()
        val brightness = if (n > 0) sum.toDouble() / n else 0.0
        val profiles = FrameShift.profiles(gray, w, h)
        if (prevPortrait != up) tracker.reset()
        prevPortrait = up
        val shift = tracker.next(profiles)
        val match = tracker.last
        val sharp = BlurCheck.sharpness(gray, w, h)
        main.post { onBrightness(brightness); onFrame(Frame(t, shift, sharp, brightness, match, up, img.width, img.height)) }
    }
}
