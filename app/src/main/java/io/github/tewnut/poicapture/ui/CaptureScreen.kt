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
import androidx.compose.foundation.layout.fillMaxHeight
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
    // Camera 360 ride: its own screen, no camera at all (docs/camera360.md)
    val kind = remember(sessionId) { CaptureService.store(ctx).load(sessionId)?.kind }
    if (kind == SessionKind.CAMERA360) { Camera360Screen(sessionId, onDone, onCancel); return }
    val activity = ctx as Activity
    var service by remember { mutableStateOf<CaptureService?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var endAfterCheck by remember { mutableStateOf(false) }   // End was pressed on an unsure place: check its pin first
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
    val indoorMode = session?.kind == SessionKind.INDOOR
    // street sweep also waits for the light level: too dark for a fast shutter = no start (Place capture instead)
    val lightOk = session?.kind == SessionKind.POI || indoorMode || (ui.exposureReady && !ui.tooDark)
    // indoors there is often no GPS at all: the walk starts from the building / the setup position
    LaunchedEffect(svc, ui.camera, ui.gpsFresh, lightOk, indoorMode) {
        if (!autoStarted && svc != null && ui.camera == "on" && (ui.gpsFresh || indoorMode) && lightOk) { svc.start(); autoStarted = true }
    }
    // indoor: the walk's estimate, the map panel and "Tôi ở đây"
    val iu: io.github.tewnut.poicapture.capture.IndoorUi = svc?.indoorState?.collectAsState()?.value ?: io.github.tewnut.poicapture.capture.IndoorUi()
    val walked = remember(iu.trackVersion, iu.level, indoorMode) { if (indoorMode) svc?.indoorTrackOf(iu.level) ?: emptyList() else emptyList() }
    var mapBig by rememberSaveable(sessionId) { mutableStateOf(false) }
    var placing by remember { mutableStateOf(false) }                                   // ⌖: the next tap is "I am here"
    var hereAt by remember { mutableStateOf<io.github.tewnut.poicapture.core.LatLon?>(null) }     // waiting for its confirmation
    var directionFrom by remember { mutableStateOf<io.github.tewnut.poicapture.core.LatLon?>(null) } // next tap = walking direction
    // floor plans (docs/indoor.md §7): the board just photographed, the plan being placed, a unit label tapped
    var planShot by remember { mutableStateOf<io.github.tewnut.poicapture.capture.PlanShot?>(null) }
    var placingPlan by remember { mutableStateOf<PlanPlacing?>(null) }
    var proposedHow by remember { mutableStateOf("footprint") }
    var hereUnit by remember { mutableStateOf<String?>(null) }
    val levelName = io.github.tewnut.poicapture.core.Levels.name(iu.level)
    val levelPlan = iu.plans.lastOrNull { it.level == levelName && it.toMap != null }
    fun startPlacing(index: Int, shot: io.github.tewnut.poicapture.capture.PlanShot?) {
        val s = svc ?: return
        val e = s.indoorState.value.plans.getOrNull(index) ?: return
        val (prop, how) = e.toMap?.let { it to (e.placedBy ?: "drag") } ?: s.proposePlacement(index, shot) ?: return
        proposedHow = how
        placingPlan = PlanPlacing(index, e, shot, prop, how)
        mapBig = true
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

    /** Throw the ride away (the end dialog's Discard, confirmed): ended without sending, then deleted from the phone. */
    fun discard() {
        val s = svc ?: run { onCancel(); return }
        val id = runCatching { s.session.id }.getOrNull()
        s.finishSession(discard = true)
        if (id != null) {
            CaptureService.store(ctx).delete(id)
            io.github.tewnut.poicapture.capture.Uploads.forget(ctx, id)
        }
        onCancel()
    }

    fun end(discardIfEmpty: Boolean) {
        val s = svc ?: run { onDone(); return }
        // the last place's position is doubtful and its pin was not checked: the pin check comes first, then the end
        if (poiMode && ui.poiId != null && ui.poiUnsure && !draft.moved && !discardIfEmpty) {
            endAfterCheck = true; reviewing = true; return
        }
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
            unsure = ui.poiUnsure, indoor = ui.poiIndoor,
            onSave = { pin, moved, fields, status ->
                svc?.finishPoi(pin, moved, fields, status)
                if (endAfterCheck) { endAfterCheck = false; reviewing = false; end(discardIfEmpty = false); return@PoiSheet }
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
        manual || indoorMode -> { { if (ui.camera == "on") svc.takePhoto("manual") } }
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
                if (indoorMode) IndoorStatus(ui, iu, session) else StatusText(ui, session, poiMode)
                val alert = when {
                    ui.camera == "lost" -> stringResource(R.string.capture_camera_busy)
                    ui.error != null -> ui.error.text()
                    !ui.gpsFresh && !notStarted && !indoorMode -> stringResource(R.string.capture_gps_lost_banner)
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
        val nextPlace = nextPlace@{
            // GPS doubtful for this place (photos far apart, taken inside, or carried by steps for long): check the pin
            if (ui.poiUnsure && !draft.moved) { reviewing = true; return@nextPlace }
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
                indoorMode -> {
                    pauseButton(64.dp)
                    DirectoryButton(ui.camera == "on") { svc?.takePhoto("directory") }
                    PlanButton(ui.camera == "on" && planShot == null) { svc?.takePlanPhoto { planShot = it } }
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
                indoorMode -> {
                    pauseButton(64.dp)
                    DirectoryButton(ui.camera == "on") { svc?.takePhoto("directory") }
                    PlanButton(ui.camera == "on" && planShot == null) { svc?.takePlanPhoto { planShot = it } }
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
            if (!poiMode && !indoorMode && !notStarted) {
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

        // ---------- indoor: the map of the walk, the level, "Tôi ở đây" ----------
        if (indoorMode && !notStarted && session != null) {
            val mapModifier = if (mapBig) Modifier.fillMaxSize().safeDrawingPadding().padding(top = 96.dp, bottom = aboveBar)
                else if (upright) Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 10.dp, bottom = 12.dp + aboveBar)
                    .fillMaxWidth(0.58f).fillMaxHeight(0.3f)
                else Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 10.dp, bottom = 12.dp)
                    .fillMaxWidth(0.36f).fillMaxHeight(0.55f)
            MapFrame(mapBig, mapModifier) {
                IndoorMap(session.settings.tileUrl, session.building, iu, walked, hereAt,
                    planDir = svc?.sessionDir, plan = levelPlan, placing = placingPlan,
                    onUnit = { u, p -> placing = false; directionFrom = null; hereUnit = u.unit; hereAt = p },
                    onLongPress = { p -> if (placingPlan == null) { placing = false; directionFrom = null; hereUnit = null; hereAt = p } },
                    onTap = { p ->
                        val from = directionFrom
                        when {
                            placing -> { placing = false; hereAt = p }
                            from != null -> { svc?.setWalkHeading(io.github.tewnut.poicapture.core.Geo.bearing(from, p)); directionFrom = null }
                        }
                    }, modifier = Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapIcon(if (mapBig) "✕" else "⤢") { mapBig = !mapBig }
                    MapIcon("⌖", active = placing) { placing = !placing; directionFrom = null }
                    // "Đặt sơ đồ": move the level's plan again
                    val idx = iu.plans.indexOfLast { it.level == levelName }
                    if (idx >= 0 && placingPlan == null) MapIcon("🗺") { startPlacing(idx, null) }
                }
                val tip = when {
                    placing -> stringResource(R.string.capture_here_pick)
                    directionFrom != null -> stringResource(R.string.capture_here_direction)
                    else -> null
                }
                if (tip != null) Row(Modifier.align(Alignment.BottomCenter).padding(6.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tip, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                    if (directionFrom != null) TextButton(onClick = { directionFrom = null }) { Text(stringResource(R.string.capture_here_skip), color = Palette.Lime) }
                }
            }
            LevelPicker(iu, onPick = { svc?.pickLevel(it) },
                // landscape: left of the right-hand rail (pause, directory)
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 64.dp, end = if (upright) 10.dp else 112.dp))
            iu.floorPrompt?.let { f ->
                FloorPrompt(iu.level, f, onAnswer = { yes -> svc?.answerFloor(yes) },
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 110.dp, start = 12.dp, end = 76.dp))
            }
            // "Chụp sơ đồ tầng": at the start and on each level without a plan
            if (iu.floorPrompt == null && iu.planPrompt && planShot == null && placingPlan == null)
                PlanPrompt(iu.level, onShoot = { svc?.takePlanPhoto { planShot = it } }, onDismiss = { svc?.dismissPlanPrompt() },
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 110.dp, start = 12.dp, end = 76.dp))
            placingPlan?.let { pl ->
                PlacementBar(pl, footprint = svc?.footprint != null,
                    onDone = {
                        svc?.placePlan(pl.index, pl.toMap, pl.how, pl.proposed, System.currentTimeMillis() - pl.startedMs, pl.shot)
                        placingPlan = null
                    },
                    onReset = { pl.toMap = pl.proposed; pl.how = proposedHow; pl.pins.clear() },
                    onCancel = { placingPlan = null },
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 10.dp, end = 10.dp, bottom = 12.dp + aboveBar))
            }
        }
        hereAt?.let { p ->
            HereSheet(iu.level, iu.levels, onConfirm = { lvl, dir ->
                svc?.anchorHere(p, lvl, unit = hereUnit)
                hereAt = null; hereUnit = null
                if (dir) directionFrom = p
            }, onCancel = { hereAt = null; hereUnit = null }, unit = hereUnit)
        }
        // the board just photographed: scanner (or corners), "you are here", then its placement on the map
        val shot = planShot
        if (shot != null && svc != null) PlanCaptureFlow(svc, shot,
            onDone = { idx -> planShot = null; startPlacing(idx, shot) }, onCancel = { planShot = null })

        // ---------- no GPS yet: the session cannot start (once started, GPS lost is only an alert at the top) ----------
        if (session != null && !ui.gpsFresh && notStarted && ui.camera == "on" && !indoorMode) GpsLostCard(ui, Modifier.align(Alignment.Center),
            onEnd = { end(discardIfEmpty = true) })

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

    if (confirmEnd) EndDialog(ui, video, session, { confirmEnd = false }, { confirmEnd = false; end(discardIfEmpty = false) },
        onDiscard = { confirmEnd = false; confirmDiscard = true })
    if (confirmDiscard) DiscardDialog(ui, video, { confirmDiscard = false }, { confirmDiscard = false; discard() })
}

@Composable
private fun EndDialog(ui: CaptureUi, video: Boolean, session: Session?, onDismiss: () -> Unit, onEnd: () -> Unit, onDiscard: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // what this ride did, read off the track once (distance, time) while the dialog opens
    val stats by androidx.compose.runtime.produceState<io.github.tewnut.poicapture.core.TrackStats?>(null, session?.id) {
        val s = session ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { io.github.tewnut.poicapture.core.TrackStats.of(CaptureService.store(ctx).trackFile(s.id), s.settings.maxAccuracyM) }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_end_title)) },
        text = {
            val clips = ui.videos + if (ui.recording) 1 else 0
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // the ride in numbers, VMAP lime on black
                Row(Modifier.fillMaxWidth().background(Palette.Black, RoundedCornerShape(16.dp)).padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    @Composable fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, color = Palette.Lime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                    if (video) Stat("$clips", stringResource(R.string.capture_end_stat_videos))
                    else Stat("${ui.photos}", stringResource(R.string.capture_end_stat_photos))
                    stats?.takeIf { it.distanceM > 0 }?.let { st ->
                        Stat(if (st.distanceM < 1000) "${st.distanceM.toInt()} m" else String.format(java.util.Locale.ROOT, "%.1f km", st.distanceM / 1000),
                            stringResource(R.string.capture_end_stat_distance))
                    }
                    when {
                        session?.kind == io.github.tewnut.poicapture.core.SessionKind.POI ->
                            Stat("${ui.poiNumber}", stringResource(R.string.capture_end_stat_places))
                        session?.target == io.github.tewnut.poicapture.core.SweepTarget.SIGNS ->
                            Stat("${ui.signsSeen}", stringResource(R.string.capture_end_stat_signs))
                        session?.target == io.github.tewnut.poicapture.core.SweepTarget.ADDRESSES ->
                            Stat("${ui.platesSeen}", stringResource(R.string.capture_end_stat_numbers))
                        else -> stats?.takeIf { it.durationMs > 0 }?.let { st -> Stat("${(st.durationMs / 60_000).coerceAtLeast(1)}′",
                            stringResource(R.string.capture_end_stat_minutes)) }
                    }
                }
                Text(stringResource(R.string.capture_end_body))
            }
        },
        confirmButton = { TextButton(onClick = onEnd) { Text(stringResource(R.string.capture_end_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.capture_discard), color = Palette.Red) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_keep_going)) }
            }
        },
    )
}

/** Second step of Discard: says what is lost, then the ride is deleted from the phone and never sent. */
@Composable
private fun DiscardDialog(ui: CaptureUi, video: Boolean, onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_discard_title)) },
        text = {
            val clips = ui.videos + if (ui.recording) 1 else 0
            val count = if (video) pluralStringResource(R.plurals.capture_end_videos, clips, clips)
                else pluralStringResource(R.plurals.capture_end_photos, ui.photos, ui.photos)
            Text(count + " " + stringResource(R.string.capture_discard_body))
        },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.capture_discard_confirm), color = Palette.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_back)) } },
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

/** Indoor: building and level, recording or paused, and how far the walker went since the last "Tôi ở đây". */
@Composable
private fun IndoorStatus(ui: CaptureUi, iu: io.github.tewnut.poicapture.capture.IndoorUi, session: Session?) {
    val name = session?.building?.name ?: stringResource(R.string.setup_indoor_short)
    ShadowText(name + " · " + stringResource(R.string.capture_indoor_level_name, io.github.tewnut.poicapture.core.Levels.name(iu.level)) +
        " · " + stringResource(if (ui.running) R.string.capture_recording else R.string.capture_paused))
    SinceAnchorLine(iu)
    if (ui.running && ui.aimLow) ShadowText(stringResource(R.string.capture_aim_low_indoor), color = Color(0xFFFF6B6B), size = 13.sp)
    else if (ui.running && ui.swinging) ShadowText(stringResource(R.string.capture_hand_swinging), color = Palette.AmberLight, size = 13.sp)
    if (!iu.barometer) ShadowText(stringResource(R.string.capture_indoor_no_barometer), color = Palette.AmberLight, size = 12.sp, bold = false)
}

/** "Bảng chỉ dẫn": a photo of the mall's directory board (trigger "directory"), read later as a cross-check. */
@Composable
private fun DirectoryButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
        Text("🗂 " + stringResource(R.string.capture_directory), fontWeight = FontWeight.Bold)
    }
}

/** "Sơ đồ": photograph the floor's plan board (docs/indoor.md §7). */
@Composable
private fun PlanButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
        Text("🗺 " + stringResource(R.string.capture_plan), fontWeight = FontWeight.Bold)
    }
}

/** A small round button on the map panel. */
@Composable
private fun MapIcon(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).background(if (active) Palette.Lime else Color(0xE6FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(label, fontSize = 20.sp, color = Palette.Ink, fontWeight = FontWeight.Bold) }
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
    // the auto photos are waiting: say why
    if (!poiMode && ui.running && ui.aimLow) ShadowText(stringResource(R.string.capture_aim_low), color = Color(0xFFFF6B6B))
    else if (!poiMode && ui.running && ui.swinging) ShadowText(stringResource(R.string.capture_hand_swinging), color = Palette.AmberLight)
    val signs = session?.target == io.github.tewnut.poicapture.core.SweepTarget.SIGNS
    // house-number ride: how many photos the phone found a number on, and the last one (the rider sees it works)
    if (!poiMode && session?.target == io.github.tewnut.poicapture.core.SweepTarget.ADDRESSES && ui.photos > 0)
        ShadowText(pluralStringResource(R.plurals.capture_plates_seen, ui.platesSeen, ui.platesSeen) +
            (ui.lastPlate?.let { " · $it" } ?: ""), size = 13.sp, bold = false)
    // traffic-signs ride: how many photos the phone's detector found a sign on, and what it took the last for (a hint)
    if (!poiMode && signs && ui.photos > 0)
        ShadowText(pluralStringResource(R.plurals.capture_signs_seen, ui.signsSeen, ui.signsSeen) +
            (ui.lastSign?.let { " · $it" } ?: ""), size = 13.sp, bold = false)
    // a sign in view: photos come denser until it is passed (the rider sees why the shutter speeds up)
    if (!poiMode && signs && ui.signBoost) ShadowText(stringResource(R.string.capture_sign_boost), color = Palette.Lime, size = 13.sp)
    // traffic-signs ride: the camera must look ahead, so forward is right and sideways is the mistake
    if (!poiMode && signs) when (ui.mount) {
        "right", "left" -> ShadowText(stringResource(R.string.service_mount_side_signs), color = Color(0xFFFF6B6B))
        "back" -> ShadowText(stringResource(R.string.capture_mount_back), color = Color(0xFFFF6B6B))
        "hand" -> ShadowText(stringResource(R.string.capture_hand_hint), color = Palette.AmberLight, size = 13.sp, bold = false)
        else -> {}
    } else if (!poiMode) when (ui.mount) {
        "right", "left" -> {}
        "hand" -> ShadowText(stringResource(R.string.capture_hand_hint), color = Palette.AmberLight, size = 13.sp, bold = false)
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
 * Before the start, while there is no GPS fix: the session cannot start without one. Goes away by itself (and the
 * session starts) when a fix comes in. Once started, GPS lost is only an alert line: photos keep the last fix.
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
