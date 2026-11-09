package io.github.tewnut.poicapture.capture

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import java.util.concurrent.Executor
import kotlin.math.pow

/**
 * Street sweep: keeps the shutter at [MAX_EXPOSURE_NS] (1/500 s) or faster, so photos taken while riding are not
 * smeared. While the camera's own auto-exposure already picks a fast enough shutter (daylight) it is left alone; when
 * it wants a slower one, the exposure goes manual: shutter fixed at 1/500 s, sensitivity (ISO) raised instead and
 * kept right from the brightness of a small analysis stream. Grainy is readable, smeared is not. When even the
 * highest ISO is not enough, [State.tooDark].
 *
 * Phones without manual sensor control keep auto-exposure ([State.supported] = false); the blur check on each photo
 * still tells.
 */
@ExperimentalCamera2Interop
class ShutterGuard(private val onState: (State) -> Unit) {
    data class State(val supported: Boolean = true, val ready: Boolean = false, val manual: Boolean = false,
                     val tooDark: Boolean = false, val exposureNs: Long? = null, val iso: Int? = null)

    private val main = Handler(Looper.getMainLooper())
    private var control: Camera2CameraControl? = null
    private var isoRange: Range<Int>? = null
    @Volatile private var manual = false
    @Volatile private var iso = 0
    private var lastAdjustMs = 0L
    private var state = State()

    /** On the preview: read what auto-exposure chose (exposure time, ISO) on every frame. */
    fun watch(preview: Preview.Builder, executor: Executor) {
        Camera2Interop.Extender(preview).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                val i = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                main.post { onAuto(t, i) }
            }
        })
    }

    /** Brightness of each analysis frame (centre of the luma plane, sparsely sampled): steers the ISO when manual. */
    fun analysis(executor: Executor): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build().also { a ->
            a.setAnalyzer(executor) { img ->
                try {
                    val y = img.planes[0]
                    val buf = y.buffer
                    val w = img.width; val h = img.height; val stride = y.rowStride
                    var sum = 0L; var n = 0
                    var row = h / 4
                    while (row < h * 3 / 4) {
                        var col = w / 4
                        while (col < w * 3 / 4) { sum += (buf.get(row * stride + col).toInt() and 0xFF); n++; col += 8 }
                        row += 8
                    }
                    if (n > 0) { val mean = sum.toDouble() / n; main.post { onBrightness(mean) } }
                } finally { img.close() }
            }
        }

    @SuppressLint("UnsafeOptInUsageError")
    fun attach(cam: Camera) {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val ok = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps && isoRange != null
        control = if (ok) Camera2CameraControl.from(cam.cameraControl) else null
        set(state.copy(supported = ok, ready = !ok))   // without manual control there is nothing to wait for
    }

    private fun onAuto(t: Long, i: Int) {
        if (manual) { set(state.copy(exposureNs = t, iso = i)); return }
        if (control == null) { set(state.copy(ready = true, exposureNs = t, iso = i)); return }
        if (t > MAX_EXPOSURE_NS * 1.05) goManual(t, i)          // auto wants a slower shutter: take over
        else set(state.copy(ready = true, manual = false, tooDark = false, exposureNs = t, iso = i))
    }

    private fun goManual(t: Long, i: Int) {
        val r = isoRange ?: return
        manual = true
        iso = (i * t.toDouble() / MAX_EXPOSURE_NS).toInt().coerceIn(r.lower, r.upper)   // same light: t × ISO kept
        apply()
        set(state.copy(ready = true, manual = true, tooDark = iso >= r.upper && i * t.toDouble() / MAX_EXPOSURE_NS > r.upper * 1.2,
            exposureNs = MAX_EXPOSURE_NS, iso = iso))
    }

    private fun onBrightness(mean: Double) {
        if (!manual) return
        val r = isoRange ?: return
        val now = System.currentTimeMillis()
        if (now - lastAdjustMs < 400) return
        lastAdjustMs = now
        // bright again (ISO at its lowest and still too bright): auto-exposure is fast enough on its own
        if (iso <= r.lower && mean > TARGET * 1.4) { backToAuto(); return }
        val f = (TARGET / mean.coerceAtLeast(1.0)).pow(0.6)        // damped: no pumping
        if (f in 0.9..1.1) { set(state.copy(tooDark = iso >= r.upper && mean < TARGET * 0.6)); return }
        iso = (iso * f).toInt().coerceIn(r.lower, r.upper)
        apply()
        set(state.copy(manual = true, tooDark = iso >= r.upper && mean < TARGET * 0.6, exposureNs = MAX_EXPOSURE_NS, iso = iso))
    }

    private fun apply() {
        control?.setCaptureRequestOptions(CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, MAX_EXPOSURE_NS)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
            .build())
    }

    private fun backToAuto() {
        manual = false
        control?.clearCaptureRequestOptions()
        set(state.copy(manual = false, tooDark = false))
    }

    fun release() { if (manual) control?.clearCaptureRequestOptions(); manual = false }

    private fun set(s: State) { if (s != state) { state = s; onState(s) } }

    companion object {
        const val MAX_EXPOSURE_NS = 2_000_000L      // 1/500 s: ≈ 3 px of smear at 20 km/h, 6 m, 2560 px wide
        private const val TARGET = 115.0            // mean luma of a well exposed street scene
    }
}
