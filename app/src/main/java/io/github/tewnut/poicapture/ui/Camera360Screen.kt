package io.github.tewnut.poicapture.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.capture.CaptureUi
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.core.Camera360
import io.github.tewnut.poicapture.core.QrModules
import io.github.tewnut.poicapture.core.ThetaRide
import io.github.tewnut.poicapture.core.ThetaShooter
import io.github.tewnut.poicapture.core.TrackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

/**
 * Camera 360 ride (docs/camera360.md §1–2): no camera preview, the phone records GPS only. The sync QR code comes up
 * right after the start, once more when the ride is ended (before the end dialog), and on "Đồng bộ lại"; each showing
 * is kept in `session.camera360.syncs`. The screen is not kept on: the phone may ride in a pocket, screen off.
 * With a Ricoh Theta the phone controls (docs/camera360.md §4) the camera's clock is set at connect: no code at the
 * start or the end ("Đồng bộ lại" stays as a fallback); the panel shows the camera, photos taken and skipped.
 */
@Composable
fun Camera360Screen(sessionId: String, onDone: (io.github.tewnut.poicapture.core.RideEnd?) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var service by remember { mutableStateOf<CaptureService?>(null) }
    DisposableEffect(sessionId) {
        if (CaptureService.activeSession.value == null) CaptureService.start(ctx, sessionId)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as CaptureService.LocalBinder).service
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        ctx.bindService(Intent(ctx, CaptureService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose { runCatching { ctx.unbindService(conn) } }
    }
    val svc = service
    val ui: CaptureUi = svc?.state?.collectAsState()?.value ?: CaptureUi()
    val active = svc?.takeIf { CaptureService.activeSession.value != null }
    val startedAt = active?.session?.startedAt
    val thetaRide = svc?.let { s -> runCatching { ThetaRide.isTheta(s.session.camera360) }.getOrNull() } == true
    val thetaStats = svc?.thetaStats?.collectAsState()?.value ?: ThetaShooter.Stats()

    // the sync screen being shown (Camera360.When json) and since when
    var syncing by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var syncFrom by rememberSaveable(sessionId) { mutableLongStateOf(0L) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun showSync(w: Camera360.When) { syncFrom = System.currentTimeMillis(); syncing = w.json }

    // the first fresh fix starts the ride, then the sync code; reopened mid-ride (paused by nothing): just go on
    LaunchedEffect(active, ui.gpsFresh, ui.running) {
        val s = active ?: return@LaunchedEffect
        if (ui.running || !ui.gpsFresh) return@LaunchedEffect
        val first = s.session.startedAt == null
        s.start()
        if (first && s.state.value.running && !thetaRide) showSync(Camera360.When.START)
    }

    fun discard() {
        val s = svc ?: run { onCancel(); return }
        val id = runCatching { s.session.id }.getOrNull()
        s.finishSession(discard = true)
        if (id != null) {
            CaptureService.store(ctx).delete(id)
            Uploads.forget(ctx, id)
        }
        onCancel()
    }
    fun end() {
        val s = svc ?: run { onDone(null); return }
        val id = runCatching { s.session.id }.getOrNull()
        // the summary: a 360 ride has no photos; with a Theta, the shots taken and skipped (kept nowhere else)
        val ended = id?.let { io.github.tewnut.poicapture.core.RideEnd(it, cam360 = true, photos = 0, videos = 0,
            thetaShots = if (thetaRide) thetaStats.shots else null, thetaSkipped = if (thetaRide) thetaStats.skipped else null) }
        s.finishSession()
        onDone(ended)
    }
    // not started (no GPS yet): leaving drops the empty ride; started: the end sync code, then the end dialog
    fun askEnd() { if (startedAt == null) discard() else if (thetaRide) confirmEnd = true else showSync(Camera360.When.END) }

    val sync = syncing
    if (sync != null) {
        SyncQrScreen(sessionId) {
            val w = Camera360.When.of(sync)
            svc?.addSync(syncFrom, System.currentTimeMillis(), w)
            syncing = null
            if (w == Camera360.When.END) confirmEnd = true
        }
        return
    }
    BackHandler { askEnd() }

    // distance from the track (every few seconds) and the ride's time (every second)
    var distanceM by remember { mutableStateOf(0.0) }
    LaunchedEffect(active, startedAt) {
        val s = active ?: return@LaunchedEffect
        while (true) {
            distanceM = withContext(Dispatchers.IO) {
                runCatching { TrackStats.of(CaptureService.store(ctx).trackFile(s.session.id), s.session.settings.maxAccuracyM).distanceM }.getOrNull()
            } ?: distanceM
            delay(5_000)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val elapsedMs = startedAt?.let { runCatching { now - Instant.parse(it).toEpochMilli() }.getOrNull() }?.coerceAtLeast(0) ?: 0L

    val gpsHint = rememberGpsWaitHint(startedAt == null && !ui.gpsFresh)
    Box(Modifier.fillMaxSize().background(Palette.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📷 " + stringResource(R.string.setup_camera360_short), color = Palette.Lime, fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { askEnd() }, modifier = Modifier.height(44.dp)) {
                    Text(stringResource(R.string.capture_end), color = Color.White)
                }
            }
            val gps = when {
                ui.gps == "error" -> stringResource(R.string.capture_gps_error)
                ui.gps == "good" -> stringResource(R.string.capture_gps_good, ui.accuracyM?.toString() ?: "?")
                ui.gps == "weak" -> stringResource(R.string.capture_gps_weak, ui.accuracyM?.toString() ?: "?")
                else -> stringResource(R.string.capture_gps_waiting)
            }
            Text(gps, color = when (ui.gps) { "good" -> Color(0xFF47CD89); "error" -> Palette.Red; else -> Color(0xFFFEC84B) },
                fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val alert = ui.error?.text() ?: if (startedAt != null && !ui.gpsFresh) stringResource(R.string.capture_gps_lost_banner) else null
            if (alert != null) Text(alert, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.background(if (ui.error != null) Palette.Red else Palette.Amber, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (startedAt == null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.c360_gps_wait), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    // still no fix after 20 s: where to stand
                    if (gpsHint) GpsSkyHint(color = Color(0xFFFEC84B))
                }
                else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    C360Stat(ui.speedKmh?.toString() ?: "–", stringResource(R.string.c360_speed), big = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        C360Stat(distanceText(distanceM), stringResource(R.string.capture_end_stat_distance))
                        C360Stat(clockText(elapsedMs), "")
                    }
                    Text(stringResource(if (ui.running) R.string.capture_recording else R.string.capture_paused),
                        color = if (ui.running) Color(0xFFFF6B6B) else Palette.AmberLight, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.c360_screen_off), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    if (thetaRide) ThetaRidePanel(thetaStats)
                }
            }
            if (startedAt != null) Button(onClick = { showSync(Camera360.When.MANUAL) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Lime, contentColor = Palette.Black)) {
                Text("▣ " + stringResource(R.string.c360_resync), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text(stringResource(R.string.capture_end_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().background(Palette.Black, RoundedCornerShape(16.dp)).padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    C360Stat(distanceText(distanceM), stringResource(R.string.capture_end_stat_distance), small = true)
                    C360Stat("${(elapsedMs / 60_000).coerceAtLeast(1)}′", stringResource(R.string.capture_end_stat_minutes), small = true)
                }
                Text(stringResource(R.string.capture_end_body))
            }
        },
        confirmButton = { TextButton(onClick = { confirmEnd = false; end() }) { Text(stringResource(R.string.capture_end_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmEnd = false; confirmDiscard = true }) { Text(stringResource(R.string.capture_discard), color = Palette.Red) }
                TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.capture_keep_going)) }
            }
        },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.capture_discard_title)) },
        text = { Text(stringResource(R.string.c360_discard_body)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; discard() }) { Text(stringResource(R.string.capture_discard_confirm), color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
private fun C360Stat(value: String, label: String, big: Boolean = false, small: Boolean = false) =
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Palette.Lime, fontSize = if (big) 72.sp else if (small) 26.sp else 34.sp, fontWeight = FontWeight.ExtraBold)
        if (label.isNotEmpty()) Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = if (big) 16.sp else 12.sp)
    }

private fun distanceText(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)

private fun clockText(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/**
 * The full-screen sync QR code (docs/camera360.md §2): `QPSYNC1 <sessionId> <epochMs>`, drawn again every
 * [Camera360.REDRAW_MS] with the phone's wall clock; black on white with its quiet zone, error correction M, screen
 * at full brightness and kept on while shown. Nothing else but one line of instruction above it and Done.
 */
@Composable
fun SyncQrScreen(sessionId: String, onDone: () -> Unit) {
    val activity = LocalContext.current as Activity
    val view = LocalView.current
    DisposableEffect(Unit) {
        val w = activity.window
        val before = w.attributes.screenBrightness
        w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        view.keepScreenOn = true
        onDispose {
            w.attributes = w.attributes.apply { screenBrightness = before }
            view.keepScreenOn = false
        }
    }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(sessionId) {
        while (true) {
            val t = System.currentTimeMillis()
            val m = QrModules.encode(Camera360.syncText(sessionId, t))
            qr = Bitmap.createBitmap(m.pixels(), m.size, m.size, Bitmap.Config.ARGB_8888).asImageBitmap()
            delay((Camera360.REDRAW_MS - (System.currentTimeMillis() - t)).coerceIn(1, Camera360.REDRAW_MS))
        }
    }
    BackHandler(onBack = onDone)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        // the code as large as fits: the full width, less the one line above it and Done
        val side = minOf(maxWidth, maxHeight - 150.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Text(stringResource(R.string.c360_sync_hint), color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 16.dp))
            // the bitmap has one pixel per module (quiet zone included): scaled up with no filtering, sharp edges
            qr?.let { Image(it, null, Modifier.size(side), filterQuality = FilterQuality.None) } ?: Box(Modifier.size(side))
            Button(onClick = onDone, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
                Text(stringResource(R.string.c360_sync_done), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
