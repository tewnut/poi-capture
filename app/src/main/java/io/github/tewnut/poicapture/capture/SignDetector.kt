package io.github.tewnut.poicapture.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import io.github.tewnut.poicapture.core.SignBox
import io.github.tewnut.poicapture.core.SignBoxes
import io.github.tewnut.poicapture.core.SignClass
import java.io.File
import java.nio.FloatBuffer

/**
 * On-device traffic-sign detection for a signs ride (YOLOX-Nano, ml/signs/README.md: trained on VNTS, CC BY-SA 4.0,
 * see NOTICE). A hint only: the boxes go into the photo's `signs` so the agent knows where to look, and the rider sees
 * a live count. The agent reads every sign from the photos and decides; about half the boxes are shop letters or logos.
 * ONNX Runtime (MIT) on the CPU, ~0.1–0.3 s per photo. Throws if the model cannot load: the caller skips the hint.
 */
class SignDetector private constructor(private val env: OrtEnvironment, private val session: OrtSession, val classes: List<SignClass>) : AutoCloseable {
    private val size = 640
    private val stride = 5 + classes.size
    private val input = FloatBuffer.allocate(3 * size * size)

    /** Blocking (call off the main thread). Boxes on the upright photo, best first, score ≥ [SignBoxes.KEEP]. */
    fun detect(file: File): List<SignBox> {
        val bmp = upright(file) ?: return emptyList()
        try { return detect(bmp) } finally { bmp.recycle() }
    }

    /** An upright bitmap (a photo decoded small, or a preview frame); the caller keeps it. Blocking. */
    @Synchronized
    fun detect(bmp: Bitmap): List<SignBox> {
        run {
            val scale = minOf(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
            val sq = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(sq).apply {
                drawColor(Color.rgb(114, 114, 114))
                drawBitmap(bmp, Matrix().apply { setScale(scale, scale) }, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(size * size).also { sq.getPixels(it, 0, size, 0, 0, size, size) }
            sq.recycle()
            input.clear()
            val plane = size * size
            val f = input.array()
            for (i in 0 until plane) {        // BGR planes, 0..255, no normalisation (as trained)
                val p = px[i]
                f[i] = (p and 0xff).toFloat()
                f[plane + i] = (p shr 8 and 0xff).toFloat()
                f[2 * plane + i] = (p shr 16 and 0xff).toFloat()
            }
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { r ->
                    val out = (r[0] as OnnxTensor).floatBuffer
                    val a = FloatArray(out.remaining()).also { out.get(it) }
                    return SignBoxes.decode(a, stride, scale, bmp.width, bmp.height)
                }
            }
        }
    }

    /** The photo turned upright (EXIF), decoded small: the model needs 640 px on the long side, not 12 Mpx. */
    private fun upright(file: File): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= size) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = when (runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true).also { if (it !== raw) raw.recycle() }
    }

    override fun close() { session.close() }

    companion object {
        const val MODEL = "signs/signs_nano_640.onnx"
        const val MODEL_NAME = "signs_nano_640 (YOLOX-Nano, VNTS)"

        fun open(context: Context): SignDetector {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }   // leave cores for the camera
            val classes = SignBoxes.classes(context.assets.open("signs/classes.tsv").use { it.readBytes().decodeToString() })
            return SignDetector(env, env.createSession(bytes, opts), classes)
        }
    }
}
