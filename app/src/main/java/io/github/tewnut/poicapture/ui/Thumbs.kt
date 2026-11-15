package io.github.tewnut.poicapture.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import io.github.tewnut.poicapture.capture.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small, upright previews of session photos for lists and strips. Decoded off the main thread at about [maxPx] on the
 * long side (a 12 MP photo at full size is ~48 MB; a 360 px preview is ~0.4 MB), turned by the EXIF orientation
 * (CameraX stores portrait shots as landscape pixels plus a "rotate 90" tag), and kept in a small memory cache so
 * scrolling back does not decode again.
 */
object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(f: File, maxPx: Int): ImageBitmap? = cache.get(key(f, maxPx))

    private fun key(f: File, maxPx: Int) = "${f.path}@$maxPx"

    /** Decode (blocking: call on a background thread); null when the file is missing or unreadable. */
    fun load(f: File, maxPx: Int): ImageBitmap? {
        val k = key(f, maxPx)
        cache.get(k)?.let { return it }
        if (!f.isFile) return null
        val b = runCatching { decode(f, maxPx) }.getOrNull() ?: return null
        return b.asImageBitmap().also { cache.put(k, it) }
    }

    private fun decode(f: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= maxPx) sample *= 2
        val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = runCatching { ExifInterface(f.path).rotationDegrees }.getOrDefault(0)
        if (deg == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            .also { if (it !== raw) raw.recycle() }
    }
}

/** A session photo as an upright preview about [maxPx] on its long side; null while loading or when missing. */
@Composable
fun rememberThumb(sessionId: String, itemId: String, maxPx: Int = 360): ImageBitmap? {
    val ctx = LocalContext.current
    val f = remember(sessionId, itemId) { CaptureService.store(ctx).photoFile(sessionId, itemId) }
    var b by remember(f, maxPx) { mutableStateOf(Thumbs.cached(f, maxPx)) }
    LaunchedEffect(f, maxPx) { if (b == null) b = withContext(Dispatchers.IO) { Thumbs.load(f, maxPx) } }
    return b
}
