package io.github.tewnut.poicapture.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.capture.PlanShot
import io.github.tewnut.poicapture.core.BoardFinder
import io.github.tewnut.poicapture.core.FloorPlanEntry
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.Levels
import io.github.tewnut.poicapture.core.PlanFit
import io.github.tewnut.poicapture.core.PlanToMap
import io.github.tewnut.poicapture.core.PlanUnit
import io.github.tewnut.poicapture.core.Xy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Floor plans on the phone (docs/indoor.md §7): the board's photo → the ML Kit document scanner (straightened page) or,
// when the scanner is not there / fails / is cancelled, the 4-corner editor → "Bạn đang ở đây" dot → placement on the
// capture map (fitted to the footprint, then two fingers, or 2 pinned point pairs) → drawn under the walk.

private const val TAG = "QuetPho"
const val PLAN_MAX_PX = 2048

/** Images of plans: upright decoding, straightening, saving. */
object PlanImages {
    /** The photo upright (EXIF applied), its long edge at least [minLong] when the photo has it (power-of-2 sampling). */
    fun decodeUpright(f: File, minLong: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= minLong) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = when (runCatching { ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    /** The board's corners on [bmp] (fractions, clockwise from top-left), or null: [BoardFinder] on a small gray copy. */
    fun findBoard(bmp: Bitmap): List<Xy>? = runCatching {
        val k = 320.0 / max(bmp.width, bmp.height)
        val w = max(16, (bmp.width * k).toInt()); val h = max(16, (bmp.height * k).toInt())
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val gray = IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        BoardFinder.find(gray, w, h)
    }.getOrNull()

    /** The quad [corners] (fractions of [src]) warped to a flat rectangle, long edge ≤ [PLAN_MAX_PX] (Matrix.setPolyToPoly). */
    fun straighten(src: Bitmap, corners: List<Xy>): Bitmap {
        val c = corners.map { Xy(it.x * src.width, it.y * src.height) }
        val (w, h) = PlanFit.straightSize(c, PLAN_MAX_PX)
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(c[0].x.toFloat(), c[0].y.toFloat(), c[1].x.toFloat(), c[1].y.toFloat(),
            c[2].x.toFloat(), c[2].y.toFloat(), c[3].x.toFloat(), c[3].y.toFloat()), 0,
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        return out
    }

    /** [bmp] scaled down to a long edge ≤ [PLAN_MAX_PX] and written as JPEG; returns (width, height). */
    fun save(bmp: Bitmap, f: File): Pair<Int, Int> {
        val k = min(1.0, PLAN_MAX_PX.toDouble() / max(bmp.width, bmp.height))
        val out = if (k < 1) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) else bmp
        f.parentFile?.mkdirs()
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val size = out.width to out.height
        if (out !== bmp) out.recycle()
        return size
    }

    /** The scanner's page (a content uri) saved as the plan. */
    fun saveScanned(ctx: Context, uri: android.net.Uri, f: File): Pair<Int, Int>? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= PLAN_MAX_PX) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        return try { save(bmp, f) } finally { bmp.recycle() }
    }

    fun decodeSmall(f: File, maxPx: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * From the board's photo to a straightened plan with its "you are here" dot: the scanner first, the corner editor when
 * it cannot be used. [onDone] gets the plan's index in the service ([CaptureService.addPlan]); [onCancel] leaves the
 * photo as a plain plan photo.
 */
@Composable
fun PlanCaptureFlow(svc: CaptureService, shot: PlanShot, onDone: (Int) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var stage by remember(shot) { mutableStateOf("scan") }     // scan | choose | corners | saving | dot
    var index by remember(shot) { mutableStateOf(-1) }
    fun saved(entry: FloorPlanEntry, proposed: List<Xy>?, detector: String?) {
        index = svc.addPlan(entry, proposed, detector)
        stage = "dot"
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val uri = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.firstOrNull()?.imageUri
        if (res.resultCode != Activity.RESULT_OK || uri == null) {
            Log.i(TAG, "plan scanner: ${if (res.resultCode == Activity.RESULT_CANCELED) "cancelled" else "no page"}")
            stage = "choose"; return@rememberLauncherForActivityResult
        }
        stage = "saving"
        val (name, file) = svc.newPlanFile(shot.level)
        val size = runCatching { PlanImages.saveScanned(ctx, uri, file) }.getOrNull()
        if (size == null) { stage = "choose"; return@rememberLauncherForActivityResult }
        saved(FloorPlanEntry(Levels.name(shot.level), shot.itemId, name, size.first, size.second, "mlkit-scanner", t = shot.tNanos), null, null)
    }
    LaunchedEffect(shot) {
        val act = ctx.activity()
        if (act == null) { stage = "choose"; return@LaunchedEffect }
        val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        runCatching {
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { e -> Log.i(TAG, "plan scanner unavailable: ${e.message}"); stage = "choose" }
        }.onFailure { e -> Log.i(TAG, "plan scanner unavailable: ${e.message}"); stage = "choose" }
    }
    when (stage) {
        "choose" -> AlertDialog(onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.plan_scan_failed_title)) },
            text = { Text(stringResource(R.string.plan_scan_failed_text)) },
            confirmButton = { Button(onClick = { stage = "corners" },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                Text(stringResource(R.string.plan_use_photo), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.plan_skip), color = Palette.Muted) } })
        "corners" -> CornerEditor(shot.file, onCancel = onCancel) { corners, proposed, detector, bmp ->
            val (name, file) = svc.newPlanFile(shot.level)
            val size = PlanImages.save(bmp, file)
            bmp.recycle()
            saved(FloorPlanEntry(Levels.name(shot.level), shot.itemId, name, size.first, size.second, "manual", corners, t = shot.tNanos),
                proposed, detector)
        }
        "saving", "scan" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (stage == "saving") CircularProgressIndicator() }
        "dot" -> {
            val entry = svc.indoorState.value.plans.getOrNull(index)
            if (entry == null) { onCancel(); return }
            YouAreHereEditor(svc.planFile(entry), entry.width, entry.height,
                onDone = { px -> svc.setYouAreHere(index, px); onDone(index) })
        }
    }
}

/**
 * The 4-corner editor (the scanner could not be used): the photo with the board's corners (detected, else the photo's
 * corners), each draggable; "Làm thẳng" warps the board to a rectangle. [onStraight] gets the corners kept, those
 * proposed, the detector ("contour" | "fallback") and the straightened bitmap (off the UI thread).
 */
@Composable
fun CornerEditor(photo: File, onCancel: () -> Unit, onStraight: (List<Xy>, List<Xy>, String, Bitmap) -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var proposed by remember { mutableStateOf<List<Xy>?>(null) }
    var detector by remember { mutableStateOf("fallback") }
    val corners = remember { mutableStateListOf<Xy>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(photo) {
        val (b, found) = withContext(Dispatchers.IO) {
            val b = PlanImages.decodeUpright(photo, 1600)
            b to b?.let { PlanImages.findBoard(it) }
        }
        bmp = b
        detector = if (found != null) "contour" else "fallback"
        val start = found ?: listOf(Xy(0.0, 0.0), Xy(1.0, 0.0), Xy(1.0, 1.0), Xy(0.0, 1.0))
        proposed = start
        corners.clear(); corners.addAll(start)
        Log.i(TAG, "plan corners: $detector")
    }
    var straighten by remember { mutableStateOf(false) }
    LaunchedEffect(straighten) {
        if (!straighten) return@LaunchedEffect
        busy = true
        val kept = corners.toList()
        val out = withContext(Dispatchers.Default) {
            val full = PlanImages.decodeUpright(photo, 2400) ?: return@withContext null
            try { PlanImages.straighten(full, kept) } finally { full.recycle() }
        }
        busy = false; straighten = false
        if (out != null) onStraight(kept, proposed ?: kept, detector, out)
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val b = bmp
            var box by remember { mutableStateOf(IntSize.Zero) }
            val density = LocalDensity.current
            if (b != null) {
                // where the photo sits in the box (fit)
                fun rect(): RectF {
                    if (box.width == 0) return RectF()
                    val s = min(box.width / b.width.toFloat(), box.height / b.height.toFloat())
                    val dw = b.width * s; val dh = b.height * s
                    val ox = (box.width - dw) / 2; val oy = (box.height - dh) / 2
                    return RectF(ox, oy, ox + dw, oy + dh)
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 64.dp, bottom = 96.dp, start = 12.dp, end = 12.dp)
                    .onSizeChanged { box = it }) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    var dragging by remember { mutableStateOf(-1) }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(b) {
                        detectDragGestures(onDragStart = { o ->
                            val r = rect()
                            val grab = with(density) { 48.dp.toPx() }
                            dragging = corners.indices.minByOrNull { k -> hypot(r.left + corners[k].x * r.width() - o.x, r.top + corners[k].y * r.height() - o.y) }
                                ?.takeIf { k -> hypot(r.left + corners[k].x * r.width() - o.x, r.top + corners[k].y * r.height() - o.y) <= grab } ?: -1
                        }, onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }) { ch, d ->
                            val k = dragging
                            if (k < 0) return@detectDragGestures
                            ch.consume()
                            val r = rect()
                            corners[k] = Xy((corners[k].x + d.x / r.width()).coerceIn(0.0, 1.0), (corners[k].y + d.y / r.height()).coerceIn(0.0, 1.0))
                        }
                    }) {
                        if (corners.size != 4) return@Canvas
                        val r = rect()
                        val pts = corners.map { Offset(r.left + it.x.toFloat() * r.width(), r.top + it.y.toFloat() * r.height()) }
                        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
                        drawPath(path, Palette.Lime.copy(alpha = 0.18f))
                        drawPath(path, Palette.Lime, style = Stroke(width = 3.dp.toPx()))
                        for (p in pts) {
                            drawCircle(Color.White, 15.dp.toPx(), p)
                            drawCircle(Palette.Black, 11.dp.toPx(), p)
                            drawCircle(Palette.Lime, 6.dp.toPx(), p)
                        }
                    }
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Lime) }
            Text(stringResource(R.string.plan_corners_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp))
            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.capture_here_cancel), color = Color.White) }
                OutlinedButton(onClick = { corners.clear(); corners.addAll(listOf(Xy(0.0, 0.0), Xy(1.0, 0.0), Xy(1.0, 1.0), Xy(0.0, 1.0))) },
                    modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.plan_whole_photo), color = Color.White) }
                Button(onClick = { straighten = true }, enabled = bmp != null && !busy, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Lime, contentColor = Palette.Black)) {
                    if (busy) CircularProgressIndicator(Modifier.height(20.dp), color = Palette.Black, strokeWidth = 2.dp)
                    else Text(stringResource(R.string.plan_straighten), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "Bạn đang ở đây": the straightened plan, one tap on the board's dot (or none). [onDone] gets plan px or null. */
@Composable
fun YouAreHereEditor(file: File, w: Int, h: Int, onDone: (Xy?) -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) { bmp = withContext(Dispatchers.IO) { PlanImages.decodeSmall(file, 1600) } }
    var dot by remember { mutableStateOf<Xy?>(null) }   // fractions
    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val b = bmp
            var box by remember { mutableStateOf(IntSize.Zero) }
            if (b != null) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 72.dp, bottom = 96.dp, start = 12.dp, end = 12.dp)
                .onSizeChanged { box = it }) {
                fun rect(): RectF {
                    if (box.width == 0) return RectF()
                    val s = min(box.width / b.width.toFloat(), box.height / b.height.toFloat())
                    val dw = b.width * s; val dh = b.height * s
                    return RectF((box.width - dw) / 2, (box.height - dh) / 2, (box.width + dw) / 2, (box.height + dh) / 2)
                }
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(b) {
                    detectTapGestures { o ->
                        val r = rect()
                        if (r.contains(o.x, o.y)) dot = Xy(((o.x - r.left) / r.width()).toDouble(), ((o.y - r.top) / r.height()).toDouble())
                    }
                }) {
                    val d = dot ?: return@Canvas
                    val r = rect()
                    val p = Offset(r.left + d.x.toFloat() * r.width(), r.top + d.y.toFloat() * r.height())
                    drawCircle(Color.White, 14.dp.toPx(), p)
                    drawCircle(Color(0xFFE5484D), 10.dp.toPx(), p)
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Lime) }
            Text(stringResource(R.string.plan_dot_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp))
            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.plan_no_dot), color = Color.White) }
                Button(onClick = { onDone(dot?.let { Xy(it.x * w, it.y * h) }) }, enabled = dot != null, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Lime, contentColor = Palette.Black)) {
                    Text(stringResource(R.string.plan_next), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "Đặt sơ đồ": one plan being placed on the capture map. */
class PlanPlacing(val index: Int, val entry: FloorPlanEntry, val shot: PlanShot?, val proposed: PlanToMap, proposedHow: String) {
    var toMap by mutableStateOf(proposed)
    var how by mutableStateOf(proposedHow)
    /** "Ghim 2 điểm": the next taps are plan point, map point, plan point, map point */
    var points by mutableStateOf(false)
    val pins = mutableStateListOf<Pair<Xy, LatLon?>>()
    val startedMs = System.currentTimeMillis()

    /** A tap on the map in points mode. */
    fun tap(p: LatLon) {
        val last = pins.lastOrNull()
        if (last == null || last.second != null) pins += toMap.toPlan(p) to null
        else pins[pins.size - 1] = last.first to p
        if (pins.size == 2 && pins.all { it.second != null }) {
            PlanToMap.fromTwoPoints(pins[0].first, pins[0].second!!, pins[1].first, pins[1].second!!)?.let { toMap = it; how = "points" }
            pins.clear(); points = false
        }
    }
}

/**
 * The plan of the current level on the osmdroid map: the bitmap through its `toMap`, semi-transparent, its "you are
 * here" dot, its unit numbers at close zoom (a tap on one = "Tôi ở đây" at that unit). While placing: one finger moves
 * it, two fingers move / turn / scale it (the map does not move meanwhile), or the taps pin point pairs.
 */
class PlanOverlay(private val ctx: Context) : Overlay() {
    private var file: File? = null
    private var bmp: Bitmap? = null
    var entry: FloorPlanEntry? = null
    var toMap: PlanToMap? = null
    var placing: PlanPlacing? = null
    var onUnit: ((PlanUnit, LatLon) -> Unit)? = null
    private val density = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * density; color = 0xFF0A0A0A.toInt(); isFakeBoldText = true }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF79009.toInt() }

    fun set(dir: File, e: FloorPlanEntry?, t: PlanToMap?, p: PlanPlacing?) {
        entry = e; toMap = t; placing = p
        val f = e?.let { File(dir, it.file) }
        if (f != file) {
            bmp?.recycle(); bmp = null; file = f
            if (f != null && f.exists()) bmp = PlanImages.decodeSmall(f, 1024)
        }
    }

    private fun screen(pj: Projection, t: PlanToMap, x: Double, y: Double): android.graphics.Point =
        t.toMap(x, y).let { pj.toPixels(GeoPoint(it.lat, it.lon), null) }

    override fun draw(c: Canvas, pj: Projection) {
        val e = entry ?: return
        val t = placing?.toMap ?: toMap ?: return
        val b = bmp ?: return
        val p0 = screen(pj, t, 0.0, 0.0); val p1 = screen(pj, t, e.width.toDouble(), 0.0); val p2 = screen(pj, t, 0.0, e.height.toDouble())
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(0f, 0f, b.width.toFloat(), 0f, 0f, b.height.toFloat()), 0,
            floatArrayOf(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat()), 0, 3)
        paint.alpha = if (placing != null) 170 else 140
        c.drawBitmap(b, m, paint)
        e.youAreHere?.let { y -> val s = screen(pj, t, y.x, y.y); c.drawCircle(s.x.toFloat(), s.y.toFloat(), 6 * density, dot) }
        // unit numbers when close enough to read them
        if (placing == null && (pj.zoomLevel >= UNIT_ZOOM)) for (u in e.units) {
            val s = screen(pj, t, u.x, u.y)
            val w = text.measureText(u.unit)
            c.drawRoundRect(s.x - w / 2 - 4 * density, s.y - 9 * density, s.x + w / 2 + 4 * density, s.y + 7 * density, 4 * density, 4 * density, label)
            c.drawText(u.unit, s.x - w / 2, s.y + 4 * density, text)
        }
        placing?.pins?.forEach { (px, at) ->
            val s = screen(pj, t, px.x, px.y); c.drawCircle(s.x.toFloat(), s.y.toFloat(), 7 * density, pin)
            at?.let { a -> val q = pj.toPixels(GeoPoint(a.lat, a.lon), null); c.drawCircle(q.x.toFloat(), q.y.toFloat(), 7 * density, dot) }
        }
    }

    // two-finger placement
    private var lastA: Pair<Float, Float>? = null
    private var lastB: Pair<Float, Float>? = null

    override fun onTouchEvent(e: MotionEvent, mv: MapView): Boolean {
        val pl = placing ?: return false
        if (pl.points) return false   // taps pin points; the map still pans
        val pj = mv.projection
        fun geo(x: Float, y: Float) = pj.fromPixels(x.toInt(), y.toInt()).let { LatLon(it.latitude, it.longitude) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // the fingers changed: start again from where they are (the lifted one left out)
                val up = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                val idx = (0 until e.pointerCount).filter { it != up }
                lastA = idx.getOrNull(0)?.let { e.getX(it) to e.getY(it) }
                lastB = idx.getOrNull(1)?.let { e.getX(it) to e.getY(it) }
            }
            MotionEvent.ACTION_MOVE -> {
                val a0 = lastA ?: return true
                val a1 = e.getX(0) to e.getY(0)
                val b0 = lastB
                if (e.pointerCount >= 2 && b0 != null) {
                    val b1 = e.getX(1) to e.getY(1)
                    val m0 = (a0.first + b0.first) / 2 to (a0.second + b0.second) / 2
                    val m1 = (a1.first + b1.first) / 2 to (a1.second + b1.second) / 2
                    val d0 = hypot(b0.first - a0.first, b0.second - a0.second); val d1 = hypot(b1.first - a1.first, b1.second - a1.second)
                    val r0 = atan2(b0.second - a0.second, b0.first - a0.first); val r1 = atan2(b1.second - a1.second, b1.first - a1.first)
                    val pivot = geo(m1.first, m1.second)
                    var t = pl.toMap.moved(geo(m0.first, m0.second), pivot)
                    // screen y points down: a growing angle turns clockwise, as the map's rotation
                    if (d0 > 10) t = t.adjusted(pivot, (d1 / d0).toDouble().coerceIn(0.5, 2.0), Math.toDegrees((r1 - r0).toDouble()))
                    pl.toMap = t
                    lastB = b1
                } else pl.toMap = pl.toMap.moved(geo(a0.first, a0.second), geo(a1.first, a1.second))
                lastA = a1
                pl.how = "drag"
                mv.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { lastA = null; lastB = null }
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mv: MapView): Boolean {
        val pj = mv.projection
        val at = pj.fromPixels(e.x.toInt(), e.y.toInt()).let { LatLon(it.latitude, it.longitude) }
        placing?.let { pl -> if (pl.points) { pl.tap(at); mv.invalidate(); return true }; return true }
        val en = entry ?: return false
        val t = toMap ?: return false
        val cb = onUnit ?: return false
        if (pj.zoomLevel < UNIT_ZOOM) return false
        val hit = en.units.minByOrNull { u -> screen(pj, t, u.x, u.y).let { hypot(it.x - e.x, it.y - e.y) } } ?: return false
        val s = screen(pj, t, hit.x, hit.y)
        if (hypot(s.x - e.x, s.y - e.y) > 28 * density) return false
        cb(hit, t.toMap(hit.x, hit.y))
        return true
    }

    override fun onDetach(mapView: MapView?) { bmp?.recycle(); bmp = null; file = null }

    companion object { const val UNIT_ZOOM = 19.5 }
}

/** The bar under the map while a plan is being placed. */
@Composable
fun PlacementBar(pl: PlanPlacing, footprint: Boolean, onDone: () -> Unit, onReset: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tip = when {
                pl.points && (pl.pins.isEmpty() || pl.pins.last().second != null) ->
                    stringResource(R.string.plan_points_plan, pl.pins.count { it.second != null } + 1)
                pl.points -> stringResource(R.string.plan_points_map)
                !footprint -> stringResource(R.string.plan_place_no_footprint)
                else -> stringResource(R.string.plan_place_hint)
            }
            Text(tip, color = Palette.Ink, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.capture_here_cancel), color = Palette.Muted) }
                OutlinedButton(onClick = { pl.points = !pl.points; pl.pins.clear() }) {
                    Text(stringResource(if (pl.points) R.string.plan_points_stop else R.string.plan_points), color = Palette.Ink, fontSize = 13.sp)
                }
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.plan_reset), color = Palette.Ink, fontSize = 13.sp) }
                Button(onClick = onDone, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                    Text(stringResource(R.string.plan_done), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "Chụp sơ đồ tầng": at the start and on a level without a plan; dismissible. */
@Composable
fun PlanPrompt(level: Int, onShoot: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.plan_prompt_title, Levels.name(level)), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
                Text(stringResource(R.string.plan_prompt_text), fontSize = 13.sp, color = Palette.Muted)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_later), color = Palette.Muted) }
            Button(onClick = onShoot, colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                Text(stringResource(R.string.plan_shoot), fontWeight = FontWeight.Bold)
            }
        }
    }
}
