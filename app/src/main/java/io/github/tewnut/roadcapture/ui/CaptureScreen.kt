package io.github.tewnut.roadcapture.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tewnut.roadcapture.capture.CaptureService
import io.github.tewnut.roadcapture.capture.CaptureUi
import io.github.tewnut.roadcapture.core.Mode
import io.github.tewnut.roadcapture.core.PhotoRole
import io.github.tewnut.roadcapture.core.PoiStatus
import io.github.tewnut.roadcapture.core.SessionKind
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import io.github.tewnut.roadcapture.core.Session
import io.github.tewnut.roadcapture.core.Side

@Composable
fun CaptureScreen(sessionId: String, onDone: () -> Unit, onCancel: () -> Unit = onDone) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var service by remember { mutableStateOf<CaptureService?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(ctx) }

    // landscape (wider horizontal field of view for shop fronts), screen kept on, service started while visible
    val view = LocalView.current
    DisposableEffect(sessionId) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        view.keepScreenOn = true
        if (CaptureService.activeSession.value == null) CaptureService.start(ctx, sessionId)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as CaptureService.LocalBinder).service
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        ctx.bindService(Intent(ctx, CaptureService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose {
            service?.attachPreview(null)
            runCatching { ctx.unbindService(conn) }
            view.keepScreenOn = false
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // preview only while the screen is visible; capture itself keeps running in the service
    val owner = LocalLifecycleOwner.current
    // (map mode: no preview either; capture keeps running)
    DisposableEffect(owner, service, showMap) {
        val s = service
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && !showMap) s?.attachPreview(previewView.surfaceProvider)
            if (e == Lifecycle.Event.ON_STOP) s?.attachPreview(null)
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) s?.attachPreview(if (showMap) null else previewView.surfaceProvider)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler { confirmEnd = true }

    val svc = service
    val ui: CaptureUi = svc?.state?.collectAsState()?.value ?: CaptureUi()
    val session: Session? = svc?.takeIf { CaptureService.activeSession.value != null }?.session
    val video = session?.mode == Mode.VIDEO
    val captured = ui.photos + ui.videos
    val notStarted = !ui.running && captured == 0 && !ui.recording
    val poiMode = session?.kind == SessionKind.POI
    var role by remember { mutableStateOf(PhotoRole.FACADE) }   // POI mode: what the next photo shows
    var reviewing by remember { mutableStateOf(false) }          // POI mode: "Xong địa điểm" sheet open

    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ui.lastPhoto) {
        val path = ui.lastPhoto ?: return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 16 })?.asImageBitmap()
        }
    }

    fun end(discardIfEmpty: Boolean) {
        val s = svc ?: run { onDone(); return }
        val id = runCatching { s.session.id }.getOrNull()
        if (poiMode && ui.poiId != null) s.finishPoi(null, false, emptyMap(), PoiStatus.NEW)   // keep it, at its GPS position
        s.finishSession()
        if (discardIfEmpty && captured == 0 && id != null) {
            CaptureService.store(ctx).delete(id)
            io.github.tewnut.roadcapture.capture.Uploads.forget(ctx, id)
            onCancel()
        } else onDone()
    }

    if (showMap && session != null) {
        // live map: track + a dot per photo where it was taken; tap a dot to see it and correct its position
        Box(Modifier.fillMaxSize().background(Color.White)) {
            SessionMap(session.id, refresh = ui.photos to ui.trackPoints / 5, live = true, modifier = Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { showMap = false }) { Text("← Camera") }
                GpsPill(ui)
                RoadPill(ui, session)
                Spacer(Modifier.weight(1f))
                Pill(if (video) "${ui.videos} đoạn video" else "${ui.photos} ảnh", size = 18.sp, bold = true,
                    dot = if (ui.running) Palette.Red else null)
            }
        }
        if (confirmEnd) EndDialog(ui, video, { confirmEnd = false }, { confirmEnd = false; end(discardIfEmpty = false) })
        return
    }

    if (reviewing && session != null) {
        val start = remember(ui.poiId) { svc?.poiStartPosition() }
        PoiSheet(ui.poiNumber, start, ui.poiRoles, onMore = { reviewing = false },
            onSave = { pin, moved, fields, status ->
                svc?.finishPoi(pin, moved, fields, status)
                reviewing = false
                role = PhotoRole.FACADE
            })
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // ---------- top: status + count ----------
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GpsPill(ui)
                    RoadPill(ui, session)
                    if (!poiMode) MountPill(ui)
                    if (ui.simulated) Pill("Vị trí giả lập", bg = Palette.AmberLight, fg = Palette.Amber, size = 12.sp)
                }
                val alert = when {
                    ui.camera == "lost" -> "Camera đang bị app khác dùng (cuộc gọi…). Sẽ tự ghi tiếp khi camera trở lại."
                    ui.error.isNotEmpty() -> ui.error
                    else -> ui.warnings.firstOrNull()
                }
                if (alert != null) {
                    Text(alert, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.widthIn(max = 520.dp)
                            .background(if (ui.camera == "lost" || ui.error.isNotEmpty()) Palette.Red else Palette.Amber, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (poiMode) "${ui.poiNumber} địa điểm · ${ui.photos} ảnh" else if (video) "${ui.videos} đoạn video" else "${ui.photos} ảnh",
                    size = 20.sp, bold = true, dot = if (ui.recording) Palette.Red else null)
                if (!poiMode && ui.marks > 0) Pill("★ ${ui.marks} đánh dấu", size = 13.sp)
                if (!ui.running && !notStarted) Pill("Đang tạm dừng", bg = Palette.AmberLight, fg = Palette.Amber, size = 12.sp)
                if (!notStarted) OutlinedButton(onClick = { showMap = true },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000))) { Text("Bản đồ", color = Color.White) }
            }
        }

        // ---------- side marker ----------
        session?.takeIf { !poiMode && (ui.mount == "right" || ui.mount == "left") }?.let {
            Text(if (it.sideOfTravel == Side.RIGHT) "GHI BÊN PHẢI →" else "← GHI BÊN TRÁI",
                color = Color.White, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(if (it.sideOfTravel == Side.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                    .safeDrawingPadding().padding(12.dp)
                    .background(Color(0xCC0B6B4C), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // ---------- bottom: speed, controls ----------
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                thumb?.let { Image(it, "Ảnh vừa chụp", Modifier.size(96.dp, 54.dp).border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                if (!poiMode) Speed(ui)
            }
            if (!notStarted && poiMode) {
                // POI mode: shoot with the selected role, then "Xong địa điểm" to pin the place on its building
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { svc?.takePhoto("manual", role); if (role == PhotoRole.FACADE) role = PhotoRole.SIGN },
                        enabled = svc != null && ui.camera == "on",
                        shape = CircleShape, modifier = Modifier.size(88.dp),
                    ) { Text("Chụp", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Button(onClick = { reviewing = true }, enabled = ui.poiId != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text("Xong địa điểm", fontWeight = FontWeight.Bold) }
                }
            } else if (!notStarted) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (ui.running) svc?.pause() else svc?.start() },
                        enabled = svc != null && ui.camera == "on",
                        shape = CircleShape, modifier = Modifier.size(88.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (ui.running) Palette.Red else Palette.Green),
                    ) { Text(if (ui.running) "Dừng" else "Tiếp", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    if (session != null && !video) {
                        OutlinedButton(onClick = { svc?.takePhoto("manual") }, enabled = ui.camera == "on",
                            shape = CircleShape, modifier = Modifier.size(64.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x66000000))) { Text("📷", fontSize = 22.sp) }
                    }
                    // sweep: point out a place while riding; the photos around this moment go with the mark
                    OutlinedButton(onClick = { svc?.addMark() }, enabled = ui.running,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000))) { Text("★ Đánh dấu", color = Color.White) }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (!notStarted) {
                    OutlinedButton(onClick = { confirmEnd = true },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000))) { Text("Kết thúc", color = Color.White) }
                }
            }
        }

        // ---------- POI mode: role of the next photo ----------
        if (poiMode && !notStarted) {
            // one row just above the controls (a column of 6 chips does not fit a landscape screen's height)
            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 110.dp)
                .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in PhotoRole.entries) {
                    val n = ui.poiRoles[r.json] ?: 0
                    FilterChip(role == r, { role = r },
                        { Text((if (r == PhotoRole.FACADE) "★ " else "") + r.label + (if (n > 0) " · $n" else ""), color = Color.White) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x99000000), selectedContainerColor = Palette.Green))
                }
            }
        }

        // ---------- get-ready overlay ----------
        if (notStarted && session != null) ReadyCard(ui, session, Modifier.align(Alignment.Center),
            onStart = { svc?.start() }, onCancel = { end(discardIfEmpty = true) })
    }

    if (confirmEnd) EndDialog(ui, video, { confirmEnd = false }, { confirmEnd = false; end(discardIfEmpty = false) })
}

@Composable
private fun EndDialog(ui: CaptureUi, video: Boolean, onDismiss: () -> Unit, onEnd: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kết thúc phiên ghi?") },
        text = { Text("Đã ghi ${if (video) "${ui.videos + if (ui.recording) 1 else 0} đoạn video" else "${ui.photos} ảnh"}. " +
            "Phiên được lưu trong máy, bạn xem lại vị trí ảnh và gửi ở mục “Đã ghi”.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("Kết thúc và lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ghi tiếp") } },
    )
}

@Composable
private fun GpsPill(ui: CaptureUi) = when (ui.gps) {
    "good" -> Pill("GPS ±${ui.accuracyM ?: "?"} m", dot = Color(0xFF47CD89))
    "weak" -> Pill("GPS yếu ±${ui.accuracyM ?: "?"} m", dot = Color(0xFFFEC84B))
    "error" -> Pill("Lỗi GPS", dot = Palette.Red)
    else -> Pill("Đang chờ GPS…", dot = Color(0xFFFEC84B))
}

@Composable
private fun RoadPill(ui: CaptureUi, session: Session?) {
    val text = when {
        ui.roadName != null -> ui.roadName
        session?.road?.auto == true && ui.roadData == "loading" -> "Đang tải dữ liệu đường…"
        session?.road?.auto == true && ui.roadData == "offline" -> "Chưa có dữ liệu đường (vẫn ghi)"
        else -> "Chưa nhận ra đường"
    }
    Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 300.dp).background(Color(0xB3000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun MountPill(ui: CaptureUi) = when (ui.mount) {
    "right", "left" -> Pill("Camera " + (if (ui.mount == "right") "→ PHẢI" else "← TRÁI") + " ✓" +
        (ui.mountOffsetFrontDeg?.takeIf { kotlin.math.abs(it) >= 3 }?.let { " (chếch ${kotlin.math.abs(it)}° về ${if (it > 0) "trước" else "sau"})" } ?: ""),
        dot = Color(0xFF47CD89), size = 13.sp)
    "front", "back" -> Pill("Camera nhìn về phía ${if (ui.mount == "front") "TRƯỚC" else "SAU"}!", dot = Palette.Red, size = 13.sp)
    else -> Pill("Đang nhận hướng camera…", dot = Color(0xFFFEC84B), size = 13.sp)
}

@Composable
private fun Speed(ui: CaptureUi) {
    val v = ui.speedKmh
    val over = v != null && v > ui.maxSpeedKmh
    Column(Modifier.background(if (over) Palette.Red else Color(0xB3000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v?.toString() ?: "–", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("km/h · tối đa ${ui.maxSpeedKmh}", color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun ReadyCard(ui: CaptureUi, s: Session, modifier: Modifier, onStart: () -> Unit, onCancel: () -> Unit) {

    // the checklist scrolls inside the card; the Start row is always visible (small phones, large system fonts)
    Surface(modifier.width(440.dp).padding(vertical = 12.dp), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 12.dp) {
      Column(Modifier.padding(20.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val poi = s.kind == SessionKind.POI
            Text(if (poi) "Sẵn sàng chụp địa điểm?" else "Sẵn sàng ghi?", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Ink)
            Check(ui.gps == "good", if (ui.gps == "good") "GPS tốt (±${ui.accuracyM} m)" else if (ui.gps == "weak") "GPS còn yếu, đợi thêm chút" else "Đang chờ GPS…")
            Check(ui.camera == "on", if (ui.camera == "on") "Camera sẵn sàng" else if (ui.camera == "error") "Không mở được camera" else "Đang mở camera…")
            when {
                !s.road.auto -> Check(true, "Đường: ${s.road.label}")
                ui.roadName != null -> Check(true, "Đang ở: ${ui.roadName}")
                else -> Check(null, "Chưa có dữ liệu đường: vẫn ghi được, đường được nhận sau")
            }
            if (poi) {
                Check(null, "Mỗi địa điểm: chụp ★ mặt tiền trực diện, biển hiệu, rồi bên hông / bên trong / thông tin nếu có.")
                Check(null, "Bấm “Xong địa điểm” để ghim lên đúng tòa nhà, rồi sang địa điểm tiếp.")
            } else {
                Check(null, "Máy gắn chắc, nằm ngang, camera nhìn sang dãy nhà cần ghi. App tự nhận bên sau vài lần tăng tốc / phanh.")
                Check(null, "Mặt tiền cách ~${s.settings.facadeDistanceM.toInt()} m: " +
                    (if (s.mode == Mode.PHOTO_AUTO) "1 ảnh mỗi ${String.format(java.util.Locale.ROOT, "%.1f", ui.photoEveryM)} m, " else "") +
                    "chạy tối đa ${ui.maxSpeedKmh} km/h")
            }
            if (ui.simulated) Hint("Máy ảo: vị trí đang là vị trí giả lập.", color = Palette.Amber)
        }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Huỷ") }
                Button(onClick = onStart, enabled = ui.camera == "on", modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (s.kind == SessionKind.POI) "Bắt đầu chụp" else if (s.mode == Mode.VIDEO) "Bắt đầu quay" else "Bắt đầu ghi",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
      }
    }
}

/** ok = true ✓ green, false ✗/⏳ amber, null = info. */
@Composable
private fun Check(ok: Boolean?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when (ok) { true -> "✓"; false -> "…"; null -> "•" }, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = when (ok) { true -> Palette.Green; false -> Palette.Amber; null -> Palette.Muted }, modifier = Modifier.width(18.dp))
        Text(text, color = Palette.Ink)
    }
}
