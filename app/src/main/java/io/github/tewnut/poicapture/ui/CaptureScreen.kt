package io.github.tewnut.poicapture.ui

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
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.capture.CaptureUi
import io.github.tewnut.poicapture.core.Mode
import io.github.tewnut.poicapture.core.PhotoRoles
import io.github.tewnut.poicapture.CaptureKeys
import io.github.tewnut.poicapture.core.PoiStatus
import io.github.tewnut.poicapture.core.SessionKind
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import io.github.tewnut.poicapture.core.Session
import io.github.tewnut.poicapture.core.Side

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val photoRoles = session?.photoRoles ?: PhotoRoles.DEFAULT
    var role by remember(session?.id) { mutableStateOf(photoRoles.roles.first().id) }   // POI mode: what the next photo shows
    var reviewing by remember { mutableStateOf(false) }          // POI mode: "Xong địa điểm" sheet open
    var draft by remember { mutableStateOf(PoiDraft()) }          // what was typed in the sheet, kept across "Chụp thêm"

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
        if (poiMode && ui.poiId != null) s.finishPoi(draft.pin, draft.moved, draft.fields(), draft.status)   // keep it with what was typed
        s.finishSession()
        if (discardIfEmpty && captured == 0 && id != null) {
            CaptureService.store(ctx).delete(id)
            io.github.tewnut.poicapture.capture.Uploads.forget(ctx, id)
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
        PoiSheet(ui.poiNumber, start, ui.poiRoles, photoRoles, draft, ui.poiRead, ui.reading > 0, onMore = { reviewing = false },
            onSave = { pin, moved, fields, status ->
                svc?.finishPoi(pin, moved, fields, status)
                reviewing = false
                draft = PoiDraft()
                role = photoRoles.roles.first().id
            })
        return
    }

    val manual = session?.mode == Mode.PHOTO_MANUAL
    // POI mode: shoot with the selected kind, then select the next kind without a photo (per the config)
    fun shoot() {
        val s = svc ?: return
        if (ui.camera != "on") return
        s.takePhoto("manual", role)
        role = photoRoles.next(role, ui.poiRoles + (role to ((ui.poiRoles[role] ?: 0) + 1)))
    }
    // volume keys / Bluetooth remote: shoot (POI, manual) or mark (riding), only while the camera screen is live
    val keyAction: (() -> Unit)? = when {
        svc == null || notStarted -> null
        poiMode -> { { shoot() } }
        manual -> { { if (ui.camera == "on") svc.takePhoto("manual") } }
        else -> { { if (ui.running) svc.addMark() } }
    }
    val latestKey = androidx.compose.runtime.rememberUpdatedState(keyAction)
    androidx.compose.runtime.SideEffect { CaptureKeys.handler = if (keyAction != null) ({ latestKey.value?.invoke() }) else null }
    DisposableEffect(Unit) { onDispose { CaptureKeys.handler = null } }
    // shutter feedback when the contributor shoots by hand (not for auto photos every few metres)
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(ui.photos) {
        if (ui.photos > 0 && (poiMode || manual)) { flash = true; kotlinx.coroutines.delay(120); flash = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (flash) Box(Modifier.fillMaxSize().background(Color(0x77FFFFFF)))

        // ---------- top: status (left), count + map + end (right) ----------
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(10.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (poiMode) "Địa điểm ${ui.poiNumber} · ${ui.photos} ảnh" else if (video) "${ui.videos} đoạn video" else "${ui.photos} ảnh",
                        size = 18.sp, bold = true, dot = if (ui.recording) Palette.Red else null)
                    if (!notStarted) {
                        DarkButton("Bản đồ") { showMap = true }
                        DarkButton("Kết thúc") { confirmEnd = true }
                    }
                }
                if (!poiMode && ui.marks > 0) Pill("★ ${ui.marks} đánh dấu", size = 13.sp)
                if (!ui.running && !notStarted && !poiMode) Pill("Đang tạm dừng", bg = Palette.AmberLight, fg = Palette.Amber, size = 13.sp)
            }
        }

        // ---------- right rail, under the thumb: the main actions ----------
        if (!notStarted) Column(Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 16.dp, top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                poiMode -> {
                    thumb?.let { Image(it, "Ảnh vừa chụp", Modifier.size(84.dp, 47.dp).border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                    Shutter("Chụp", enabled = svc != null && ui.camera == "on", onClick = { shoot() })
                    Button(onClick = { reviewing = true }, enabled = ui.poiId != null, modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text("Xong địa điểm", fontWeight = FontWeight.Bold) }
                }
                manual -> {
                    RoundButton(if (ui.running) "Dừng" else "Tiếp", if (ui.running) Palette.Red else Palette.Green, 56.dp, svc != null) {
                        if (ui.running) svc?.pause() else svc?.start()
                    }
                    Shutter("Chụp", enabled = ui.camera == "on", onClick = { svc?.takePhoto("manual") })
                    RoundButton("★", Color(0xCC000000), 56.dp, ui.running) { svc?.addMark() }
                }
                else -> {
                    // riding: "Đánh dấu" is the frequent touch (biggest); stopping is rare
                    RoundButton(if (ui.running) "Dừng" else "Tiếp", if (ui.running) Palette.Red else Palette.Green, 64.dp,
                        svc != null && ui.camera == "on") { if (ui.running) svc?.pause() else svc?.start() }
                    RoundButton("★\nĐánh dấu", Color(0xCC000000), 96.dp, ui.running) { svc?.addMark() }
                }
            }
        }

        // ---------- left: POI = kind of the next photo; riding = speed ----------
        if (poiMode && !notStarted) {
            Column(Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 10.dp, top = 56.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in photoRoles.roles) {
                    val n = ui.poiRoles[r.id] ?: 0
                    FilterChip(role == r.id, { role = r.id },
                        { Text((if (r.id == photoRoles.cover.id) "★ " else "") + r.label + (if (n > 0) "  ✓$n" else ""),
                            color = Color.White, fontWeight = if (role == r.id) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x99000000), selectedContainerColor = Palette.Green))
                }
            }
            val current = photoRoles.roles.firstOrNull { it.id == role }
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp).widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadLine(ui)
                if (current != null) Text(current.label + if (current.hint.isNotBlank()) ": ${current.hint}" else "",
                    color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!poiMode && !notStarted) Speed(ui)
            if (!poiMode) thumb?.let { Image(it, "Ảnh vừa chụp", Modifier.size(96.dp, 54.dp).border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
        }

        // ---------- riding: which side is filmed ----------
        session?.takeIf { !poiMode && (ui.mount == "right" || ui.mount == "left") }?.let {
            Text(if (it.sideOfTravel == Side.RIGHT) "GHI BÊN PHẢI →" else "← GHI BÊN TRÁI",
                color = Color.White, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp)
                    .background(Color(0xCC0B6B4C), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // ---------- get-ready overlay ----------
        if (notStarted && session != null) ReadyCard(ui, session, photoRoles, Modifier.align(Alignment.Center),
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
private fun ReadyCard(ui: CaptureUi, s: Session, roles: PhotoRoles, modifier: Modifier, onStart: () -> Unit, onCancel: () -> Unit) {

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
                Check(null, "Mỗi địa điểm: chụp ★ ${roles.cover.label.lowercase()}, rồi " +
                    roles.roles.filter { it.id != roles.cover.id }.joinToString(", ") { it.label.lowercase() } + " nếu có. App tự chọn loại ảnh tiếp theo.")
                Check(null, "Bấm “Xong địa điểm” để ghim lên đúng tòa nhà, rồi sang địa điểm tiếp.")
                Check(null, "Nút âm lượng cũng là nút chụp.")
            } else {
                Check(null, "Máy gắn chắc, nằm ngang, camera nhìn sang dãy nhà cần ghi. App tự nhận bên sau vài lần tăng tốc / phanh.")
                Check(null, if (s.mode == Mode.PHOTO_MANUAL) "Nút âm lượng (hoặc nút bấm Bluetooth trên tay lái) là nút chụp."
                    else "Nút âm lượng (hoặc nút bấm Bluetooth trên tay lái) = ★ Đánh dấu, không cần chạm màn hình.")
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

/** What the phone read on the current place's photos, or a request to shoot the sign again. */
@Composable
private fun ReadLine(ui: CaptureUi) {
    val r = ui.poiRead
    val (text, warn) = when {
        ui.readNote != null -> ui.readNote to true
        ui.reading > 0 -> "Đang đọc chữ trên ảnh…" to false
        r != null && (r.name != null || r.phones.isNotEmpty() || r.category != null) ->
            ("Đọc được: " + listOfNotNull(r.name, r.phones.firstOrNull(), r.category).joinToString(" · ")) to false
        else -> return
    }
    Text(text, color = if (warn) Palette.Amber else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(if (warn) Palette.AmberLight else Color(0xCC0B6B4C), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Big round shutter: white, high contrast on any camera picture. */
@Composable
private fun Shutter(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = Modifier.size(84.dp).border(4.dp, Color(0x99FFFFFF), CircleShape),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundButton(label: String, color: Color, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(size),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, fontSize = if (size >= 80.dp) 16.sp else 15.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
    }
}

/** Secondary action over the camera picture. */
@Composable
private fun DarkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(44.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000))) { Text(label, color = Color.White) }
}
