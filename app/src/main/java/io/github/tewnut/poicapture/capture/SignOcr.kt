package io.github.tewnut.poicapture.capture

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.tewnut.poicapture.core.SignReading
import io.github.tewnut.poicapture.core.SignText
import io.github.tewnut.poicapture.core.TextLine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * On-device reading of a photo (ML Kit text recognition, Latin script, which covers Vietnamese; model provided by
 * Google Play services, offline once downloaded). Throws when the model is not available (no Play services, not
 * downloaded yet): the caller skips the reading and the server reads the photo. Only text: what the text means is
 * guessed by [SignText] as a hint and decided by the server's agent.
 */
object SignOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Blocking (call off the main thread). Full-resolution still, not preview frames: small text needs the pixels. */
    fun read(file: File): SignReading {
        val rotation = when (runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
        val sample = if (maxOf(bounds.outWidth, bounds.outHeight) > 3200) 2 else 1
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return SignText.read(emptyList())
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, rotation)), 30, TimeUnit.SECONDS)
            val lines = text.textBlocks.flatMap { it.lines }.map { l ->
                val b = l.boundingBox
                TextLine(l.text, (b?.height() ?: 0) * sample, b?.let { intArrayOf(it.left * sample, it.top * sample, it.right * sample, it.bottom * sample) })
            }
            return SignText.read(lines)
        } finally {
            bmp.recycle()
        }
    }
}
