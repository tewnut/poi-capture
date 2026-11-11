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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.tewnut.poicapture.R
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
    val previewView = remember { PreviewView(ctx) }

    // portrait or landscape, as the phone is held or mounted (the rest of the app is portrait); screen kept on,
    // service started while visible
    val view = LocalView.current
    DisposableEffect(sessionId) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
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
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    // preview only while the screen is visible; capture itself keeps running in the service
    val owner = LocalLifecycleOwner.current
    // (map mode: no preview either; capture keeps running)
    DisposableEffect(owner, service) {
        val s = service
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) s?.attachPreview(previewView.surfaceProvider)
            if (e == Lifecycle.Event.ON_STOP) s?.attachPreview(null)
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) s?.attachPreview(previewView.surfaceProvider)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val svc = service
    val ui: CaptureUi = svc?.state?.collectAsState()?.value ?: CaptureUi()
    val session: Session? = svc?.takeIf { CaptureService.activeSession.value != null }?.session
    val video = session?.mode == Mode.VIDEO
    val captured = ui.photos + ui.videos
    // New session only lets Start through with a fresh GPS fix: recording begins as soon as the camera is open
    var autoStarted by rememberSaveable(sessionId) { mutableStateOf(false) }
    val notStarted = !autoStarted && !ui.running && captured == 0 && !ui.recording
    // street sweep also waits for the light level: too dark for a fast shutter = no start (Place capture instead)
    val lightOk = session?.kind == SessionKind.POI || (ui.exposureReady && !ui.tooDark)
    LaunchedEffect(svc, ui.camera, ui.gpsFresh, lightOk) {
        if (!autoStarted && svc != null && ui.camera == "on" && ui.gpsFresh && lightOk) { svc.start(); autoStarted = true }
    }
    val poiMode = session?.kind == SessionKind.POI
    val askRole = session?.photoRoles != null          // POI: the contributor picks the kind of each photo (off by default)
    val photoRoles = session?.photoRoles ?: PhotoRoles.DEFAULT
    var role by remember(session?.id) { mutableStateOf(photoRoles.roles.first().id) }   // POI mode: what the next photo shows
    var reviewing by remember { mutableStateOf(false) }          // Place capture: "Pin & status" sheet open
    var draft by remember { mutableStateOf(PoiDraft()) }          // what was typed in the sheet, kept across "Chụp thêm"
    var saved by remember { mutableStateOf<String?>(null) }       // "Đã lưu địa điểm N" for a few seconds
    LaunchedEffect(saved) { if (saved != null) { kotlinx.coroutines.delay(3000); saved = null } }

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
            onCancel()
        } else onDone()
    }
    // nothing recorded yet: leaving just drops the empty session (no "save?" question)
    BackHandler { if (captured == 0) end(discardIfEmpty = true) else confirmEnd = true }

    if (reviewing && session != null) {
        val start = remember(ui.poiId) { svc?.poiStartPosition() }
        PoiSheet(ui.poiNumber, start, ui.poiRoles, photoRoles, draft, ui.poiRead, ui.reading > 0, onMore = { reviewing = false },
            onSave = { pin, moved, fields, status ->
                svc?.finishPoi(pin, moved, fields, status)
                reviewing = false
                saved = ctx.getString(R.string.capture_place_saved, ui.poiNumber)
                draft = PoiDraft()
                role = photoRoles.roles.first().id
                thumb = null
            })
        return
    }

    val manual = session?.mode == Mode.PHOTO_MANUAL
    // POI mode: shoot with the selected kind, then select the next kind without a photo (per the config)
    fun shoot() {
        val s = svc ?: return
        if (ui.camera != "on") return
        if (!askRole) { s.takePhoto("manual"); return }
        s.takePhoto("manual", role)
        role = photoRoles.next(role, ui.poiRoles + (role to ((ui.poiRoles[role] ?: 0) + 1)))
    }
    // volume keys / Bluetooth remote: shoot (POI, manual), only while the camera screen is live; auto sweep: nothing
    val keyAction: (() -> Unit)? = when {
        svc == null || notStarted -> null
        poiMode -> { { shoot() } }
        manual -> { { if (ui.camera == "on") svc.takePhoto("manual") } }
        else -> null
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

        // ---------- top: status as plain text (left), "Kết thúc" (right) ----------
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(10.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusText(ui, session, poiMode)
                val alert = when {
                    ui.camera == "lost" -> stringResource(R.string.capture_camera_busy)
                    ui.error != null -> ui.error.text()
                    else -> ui.warnings.firstOrNull()?.text()
                }
                if (alert != null) {
                    Text(alert, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.widthIn(max = 520.dp)
                            .background(if (ui.camera == "lost" || ui.error != null) Palette.Red else Palette.Amber, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            DarkButton(stringResource(R.string.capture_end)) { if (captured == 0) end(discardIfEmpty = true) else confirmEnd = true }
        }

        // ---------- the main actions, under the thumb: right rail in landscape, bottom bar in portrait ----------
        val upright = ui.portrait
        val nextPlace = {
            svc?.finishPoi(draft.pin, draft.moved, draft.fields(), draft.status)
            saved = ctx.getString(R.string.capture_place_saved, ui.poiNumber)
            draft = PoiDraft()
            role = photoRoles.roles.first().id
            thumb = null
        }
        @Composable fun lastThumb() {
            if (ui.poiId != null) thumb?.let { Image(it, stringResource(R.string.capture_last_photo), Modifier.size(84.dp, 47.dp).border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                ?: Spacer(Modifier.size(84.dp, 47.dp))
        }
        @Composable fun placeButtons() {
            Button(onClick = nextPlace, enabled = ui.poiId != null, modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text(stringResource(R.string.capture_next_place), fontWeight = FontWeight.Bold) }
            // optional, when needed: pin on the right building, closed / moved, a note
            if (ui.poiId != null) DarkButton(stringResource(R.string.capture_pin_status)) { reviewing = true }
        }
        @Composable fun pauseButton(size: androidx.compose.ui.unit.Dp) {
            RoundButton(stringResource(if (ui.running) R.string.capture_pause else R.string.capture_resume),
                if (ui.running) Palette.Red else Palette.Green, size, svc != null && ui.camera == "on") {
                if (ui.running) svc?.pause() else svc?.start()
            }
        }
        if (!notStarted && upright) Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                poiMode -> {
                    lastThumb()
                    Shutter(stringResource(R.string.capture_shoot), enabled = svc != null && ui.camera == "on", onClick = { shoot() })
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { placeButtons() }
                }
                manual -> {
                    pauseButton(56.dp)
                    Shutter(stringResource(R.string.capture_shoot), enabled = ui.camera == "on", onClick = { svc?.takePhoto("manual") })
                }
                else -> pauseButton(64.dp)
            }
        }
        if (!notStarted && !upright) Column(Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 16.dp, top = if (poiMode) 48.dp else 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                poiMode -> {
                    if (ui.poiId != null) thumb?.let { Image(it, stringResource(R.string.capture_last_photo), Modifier.size(84.dp, 47.dp).border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                    Shutter(stringResource(R.string.capture_shoot), enabled = svc != null && ui.camera == "on", onClick = { shoot() })
                    placeButtons()
                }
                manual -> {
                    pauseButton(56.dp)
                    Shutter(stringResource(R.string.capture_shoot), enabled = ui.camera == "on", onClick = { svc?.takePhoto("manual") })
                }
                else -> pauseButton(64.dp)
            }
        }
        // in portrait the bar takes the bottom: what sits there in landscape moves up above it
        val aboveBar = if (upright && !notStarted) 128.dp else 0.dp

        // ---------- left: POI = kind of the next photo; riding = speed ----------
        if (poiMode && !notStarted) {
            if (askRole) Column(Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 10.dp, top = 56.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in photoRoles.roles) {
                    val n = ui.poiRoles[r.id] ?: 0
                    FilterChip(role == r.id, { role = r.id },
                        { Text((if (r.id == photoRoles.cover.id) "★ " else "") + roleLabel(photoRoles, r) + (if (n > 0) "  ✓$n" else ""),
                            color = Color.White, fontWeight = if (role == r.id) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x99000000), selectedContainerColor = Palette.Green))
                }
            }
            val current = photoRoles.roles.firstOrNull { it.id == role }
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp + aboveBar).widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                saved?.let {
                    Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Palette.Green, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                if (saved == null) ReadLine(ui)
                if (askRole && current != null) Text(roleLabel(photoRoles, current) +
                    roleHint(photoRoles, current).let { h -> if (h.isNotBlank()) ": $h" else "" },
                    color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + aboveBar), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!poiMode && !notStarted) {
                Speed(ui)
                ShadowText(stringResource(if (ui.running) R.string.capture_recording else R.string.capture_paused), color = if (ui.running) Color(0xFFFF6B6B) else Palette.AmberLight,
                    size = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }

        // ---------- riding: which side is filmed ----------
        session?.takeIf { !poiMode && (ui.mount == "right" || ui.mount == "left") }?.let {
            Text(stringResource(if (it.sideOfTravel == Side.RIGHT) R.string.capture_side_right else R.string.capture_side_left),
                color = Color.White, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp + aboveBar)
                    .background(Color(0xCC0B6B4C), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // ---------- GPS lost: nothing is captured until it is back ----------
        if (session != null && !ui.gpsFresh && (ui.camera == "on" || !notStarted)) GpsLostCard(ui, Modifier.align(Alignment.Center),
            onEnd = { if (captured == 0) end(discardIfEmpty = true) else confirmEnd = true })

        // ---------- street sweep in the dark: not started, say why and what to do ----------
        if (notStarted && session != null && !poiMode && ui.camera == "on" && ui.tooDark) TooDarkCard(Modifier.align(Alignment.Center),
            onEnd = { end(discardIfEmpty = true) })

        // ---------- starting: the camera is opening (a second or two) ----------
        if (notStarted && (session == null || ui.camera != "on")) Text(
            stringResource(if (ui.camera == "error") R.string.capture_check_camera_error else R.string.capture_check_camera_opening),
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }

    if (confirmEnd) EndDialog(ui, video, { confirmEnd = false }, { confirmEnd = false; end(discardIfEmpty = false) })
}

@Composable
private fun EndDialog(ui: CaptureUi, video: Boolean, onDismiss: () -> Unit, onEnd: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_end_title)) },
        text = {
            val clips = ui.videos + if (ui.recording) 1 else 0
            val count = if (video) pluralStringResource(R.plurals.capture_end_videos, clips, clips)
                else pluralStringResource(R.plurals.capture_end_photos, ui.photos, ui.photos)
            Text(count + " " + stringResource(R.string.capture_end_body))
        },
        confirmButton = { TextButton(onClick = onEnd) { Text(stringResource(R.string.capture_end_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_keep_going)) } },
    )
}

/** Text over the camera picture: white with a dark shadow, no background (information, not a button). */
@Composable
private fun ShadowText(text: String, color: Color = Color.White, size: androidx.compose.ui.unit.TextUnit = 15.sp,
                       modifier: Modifier = Modifier, bold: Boolean = true) {
    Text(text, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, 2f), 6f)))
}

/** GPS, road and (riding) the camera direction on one or two lines of plain text. */
@Composable
private fun StatusText(ui: CaptureUi, session: Session?, poiMode: Boolean) {
    val gps = when (ui.gps) {
        "good" -> stringResource(R.string.capture_gps_good, ui.accuracyM?.toString() ?: "?")
        "weak" -> stringResource(R.string.capture_gps_weak, ui.accuracyM?.toString() ?: "?")
        "error" -> stringResource(R.string.capture_gps_error)
        else -> stringResource(R.string.capture_gps_waiting)
    }
    val dot = when (ui.gps) { "good" -> Color(0xFF47CD89); "error" -> Palette.Red; else -> Color(0xFFFEC84B) }
    val road = when {
        ui.roadName != null -> ui.roadName
        session?.road?.auto == true && ui.roadData == "loading" -> null
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShadowText(gps.take(1), color = dot)
        ShadowText(gps.drop(1) + (road?.let { " · $it" } ?: "") + (if (ui.simulated) " · " + stringResource(R.string.capture_simulated) else ""))
    }
    if (!poiMode) when (ui.mount) {
        "right", "left" -> {}
        "front", "back" -> ShadowText(stringResource(if (ui.mount == "front") R.string.capture_mount_front else R.string.capture_mount_back),
            color = Color(0xFFFF6B6B))
        else -> ShadowText(stringResource(R.string.capture_mount_measuring), color = Palette.AmberLight, size = 13.sp, bold = false)
    }
}

@Composable
private fun Speed(ui: CaptureUi) {
    val v = ui.speedKmh
    val over = v != null && v > ui.maxSpeedKmh
    Column(Modifier.background(if (over) Palette.Red else Color(0xB3000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v?.toString() ?: "–", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.capture_speed_max, ui.maxSpeedKmh), color = Color.White, fontSize = 11.sp)
    }
}

/**
 * In front of the controls while the GPS is stale (no fix for 5 s): photos, marks and video are on hold, so the
 * contributor fixes the GPS first. Goes away by itself when a fix comes back.
 */
@Composable
private fun GpsLostCard(ui: CaptureUi, modifier: Modifier, onEnd: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {})
    Surface(modifier.widthIn(max = 440.dp).padding(16.dp), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 12.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.capture_gps_lost_title), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Red)
            Text(stringResource(R.string.capture_gps_lost_text), color = Palette.Ink)
            Check(null, stringResource(R.string.capture_gps_lost_sky))
            Check(null, stringResource(R.string.capture_gps_lost_location))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEnd) { Text(stringResource(R.string.capture_end)) }
                Button(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.capture_gps_lost_settings), fontWeight = FontWeight.Bold)
                }
            }
            Hint(stringResource(R.string.capture_gps_lost_resume), color = Palette.Muted)
        }
    }
}

/** Street sweep could not start: too dark for a shutter fast enough to read signs while riding. */
@Composable
private fun TooDarkCard(modifier: Modifier, onEnd: () -> Unit) {
    Surface(modifier.widthIn(max = 440.dp).padding(16.dp), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 12.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.capture_too_dark_title), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Amber)
            Text(stringResource(R.string.capture_too_dark_text), color = Palette.Ink)
            Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.capture_too_dark_back), fontWeight = FontWeight.Bold)
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
        Text(text, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What the phone read on the current place's photos, or a request to shoot the sign again. */
@Composable
private fun ReadLine(ui: CaptureUi) {
    val r = ui.poiRead
    val ctx = LocalContext.current
    val (text, warn) = when {
        ui.readNote != null -> ui.readNote.get(ctx) to true
        ui.reading > 0 -> ctx.getString(R.string.capture_reading) to false
        r != null && (r.name != null || r.phones.isNotEmpty() || r.category != null) ->
            ctx.getString(R.string.capture_read, listOfNotNull(r.name, r.phones.firstOrNull(), r.category?.let { categoryLabel(ctx, it) })
                .joinToString(" · ")) to false
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
