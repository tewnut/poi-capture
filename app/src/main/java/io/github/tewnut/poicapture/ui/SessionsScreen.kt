package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.size
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.Context
import io.github.tewnut.poicapture.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.Exporter
import io.github.tewnut.poicapture.core.Mode
import io.github.tewnut.poicapture.core.Session
import io.github.tewnut.poicapture.core.SessionKind
import io.github.tewnut.poicapture.core.Side
import io.github.tewnut.poicapture.core.TrackStats
import io.github.tewnut.poicapture.core.iso
import androidx.compose.material3.LinearProgressIndicator
import androidx.work.NetworkType
import androidx.work.WorkInfo
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.core.UploadRecord
import io.github.tewnut.poicapture.core.UploadStatus
import java.io.File
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class SessionRow(val s: Session, val title: String, val photos: Int, val videos: Int, val stats: TrackStats, val bytes: Long,
                              val relocated: Int, val pois: Int = 0, val marks: Int = 0, val previews: List<Preview> = emptyList(),
                              val route: List<io.github.tewnut.poicapture.core.LatLon> = emptyList())

@Composable
fun SessionsScreen(onNew: () -> Unit, onOpenMap: (id: String, title: String) -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { CaptureService.store(ctx) }
    val scope = rememberCoroutineScope()
    val active by CaptureService.activeSession.collectAsState()
    var rows by remember { mutableStateOf<List<SessionRow>?>(null) }
    var msg by remember { mutableStateOf("") }
    var msgError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf<Session?>(null) }
    var saving by remember { mutableStateOf<Session?>(null) }
    var setupFor by remember { mutableStateOf<Session?>(null) }   // Send to server tapped with no access key yet
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload, active) {
        rows = withContext(Dispatchers.IO) {
            store.list().map { s ->
                val items = store.items(s.id)
                val pois = if (s.kind == SessionKind.POI) store.pois(s.id) else emptyList()
                val names = pois.mapNotNull { it.optJSONObject("fields")?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                // saved sessions carry their name; older ones get the same "{street} {stamp}" from their photos
                // (sessions saved as "Unnamed road …" before v0.14, or with the older "{street} [yymmdd]", are named again)
                val title = s.name?.takeIf { !it.startsWith(io.github.tewnut.poicapture.core.SessionNaming.UNNAMED_PREFIX) &&
                    !io.github.tewnut.poicapture.core.SessionNaming.isOldStyle(it) }
                    ?: TrackStats.sessionName(items, s.startedAt ?: s.createdAt,
                        if (s.kind == SessionKind.INDOOR) s.building?.name ?: ctx.getString(R.string.setup_indoor_short)
                        else if (s.kind == SessionKind.CAMERA360) ctx.getString(R.string.setup_camera360_short)
                        else if (!s.road.auto && s.kind != SessionKind.POI) roadText(ctx, s.road) else ctx.getString(R.string.session_no_street),
                        ways = s.road.ways)
                SessionRow(s, title,
                    items.count { it.optString("kind") == "photo" },
                    items.count { it.optString("kind") == "video" && store.videoFile(s.id, it.getString("id")).length() > 0 },
                    TrackStats.of(store.trackFile(s.id), s.settings.maxAccuracyM),
                    store.dir(s.id).walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    items.count { (it.has("placeLat") && !it.isNull("placeLat")) || (it.has("relocatedLat") && !it.isNull("relocatedLat")) },
                    pois.size, if (s.kind == SessionKind.SWEEP) store.marks(s.id).size else 0,
                    previews(s.kind, items, pois),
                    store.trackPoints(s.id).let { pts -> if (pts.size <= 160) pts else pts.filterIndexed { i, _ -> i % (pts.size / 160 + 1) == 0 } })
            }
        }
    }

    fun markExported(s: Session) {
        store.load(s.id)?.let { fresh -> fresh.exportedAt = iso(System.currentTimeMillis()); store.save(fresh) }
        reload++
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val s = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = s.id
        msg = ctx.getString(R.string.sessions_packing); msgError = false
        scope.launch {
            msg = try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { Exporter.writeZip(store, s.id, it) } }
                markExported(s)
                ctx.getString(R.string.sessions_saved, Exporter.fileName(s))
            } catch (e: Exception) { msgError = true; ctx.getString(R.string.sessions_save_failed, errorText(ctx, e)) }
            busy = null
        }
    }

    fun send(s: Session) {
        busy = s.id
        msg = ctx.getString(R.string.sessions_packing); msgError = false
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    File(dir, Exporter.fileName(s)).also { f -> f.outputStream().use { Exporter.writeZip(store, s.id, it) } }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.sessions_send_chooser, f.name)))
                // not marked sent: the chooser says nothing of whether the file went anywhere (a cancel looks the same),
                // and the delete dialog trusts "sent". Only a saved file or a server upload counts.
                msg = ctx.getString(R.string.sessions_packed, mb(f.length()))
            } catch (e: Exception) { msgError = true; msg = ctx.getString(R.string.sessions_pack_failed, errorText(ctx, e)) }
            busy = null
        }
    }

    // the upload server is set (Settings / onboarding): each card shows its upload
    val uploads = remember { io.github.tewnut.poicapture.capture.UploadServer.configured(ctx) }
    // an upload finishing marks the session sent: read the list again
    val uploadFinished by io.github.tewnut.poicapture.capture.Uploads.finished.collectAsState()
    LaunchedEffect(uploadFinished) { if (uploadFinished > 0) reload++ }
    val removed by io.github.tewnut.poicapture.capture.Uploads.removed.collectAsState()
    LaunchedEffect(removed) {
        if (removed.isNotEmpty()) {
            msgError = false
            msg = ctx.resources.getQuantityString(R.plurals.sessions_removed_after_upload, removed.size, removed.size, mb(removed.sumOf { it.second }))
        }
    }
    val list = rows
    Box(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
    Column(Modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.sessions_title)) {
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings), tint = Palette.Ink) }
    }
    if (list != null && list.isEmpty()) {
        EmptySessions(onNew, Modifier.weight(1f))
        if (msg.isNotEmpty()) Hint(msg, Modifier.padding(16.dp), color = if (msgError) Palette.Red else Palette.Green)
        return@Column
    }
    // room at the bottom so the floating "New session" button never hides the last card
    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 104.dp)) {
        if (msg.isNotEmpty()) item { Hint(msg, color = if (msgError) Palette.Red else Palette.Green) }
        if (list == null) item {
            Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Hint(stringResource(R.string.sessions_loading))
            }
        }
        items(list ?: emptyList(), key = { it.s.id }) { r ->
            SessionCard(r, active == r.s.id, busy == r.s.id, uploads,
                onSend = { if (uploads) io.github.tewnut.poicapture.capture.Uploads.enqueue(ctx, r.s.id, manual = true) else setupFor = r.s },
                onShare = { send(r.s) },
                onMap = { onOpenMap(r.s.id, r.title) },
                onSave = { saving = r.s; saveLauncher.launch(Exporter.fileName(r.s)) },
                onSettings = onOpenSettings,
                onDelete = { confirmDel = r.s })
        }
    }
    }
    if (list != null && list.isNotEmpty()) androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onNew, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).height(60.dp),
        containerColor = Palette.Green, contentColor = Color.White, shape = RoundedCornerShape(50),
        icon = { Icon(Icons.Filled.Add, null) },
        text = { Text(stringResource(R.string.home_new_session), fontSize = 18.sp, fontWeight = FontWeight.Bold) })
    }

    setupFor?.let { s ->
        AlertDialog(
            onDismissRequest = { setupFor = null },
            title = { Text(stringResource(R.string.sessions_setup_title)) },
            text = { Text(stringResource(R.string.sessions_setup_text)) },
            confirmButton = { TextButton(onClick = { setupFor = null; onOpenSettings() }) { Text(stringResource(R.string.sessions_setup_open)) } },
            dismissButton = { TextButton(onClick = { setupFor = null; send(s) }) { Text(stringResource(R.string.sessions_share_file)) } },
        )
    }

    confirmDel?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                Text(stringResource(when {
                    s.exportedAt != null -> R.string.sessions_delete_sent
                    else -> R.string.sessions_delete_unsent
                }))
            },
            confirmButton = { TextButton(onClick = {
                confirmDel = null
                msgError = false; msg = ctx.getString(R.string.sessions_deleting)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // deleted unsent: the field tasks its Place captures did are to do again (sent: the server has them)
                        val sent = store.load(s.id)?.exportedAt != null || Uploads.record(ctx, s.id).status == UploadStatus.DONE
                        Uploads.forget(ctx, s.id); store.delete(s.id)
                        if (!sent) runCatching { CaptureService.tasks(ctx).reopenBy(s.id) }
                    }
                    msg = ctx.getString(R.string.sessions_deleted)
                    reload++
                }
            }) { Text(stringResource(R.string.sessions_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text(stringResource(R.string.sessions_keep)) } },
        )
    }
}

/**
 * One session: its own photos on top (Place capture: one per place, with the name; street sweep: a strip from along the
 * ride), then the name, what was recorded (with its size) and one send control. Tapping the card opens the map; share
 * as a file, save the zip and delete are in the ⋮ menu.
 */
@Composable
private fun SessionCard(r: SessionRow, recording: Boolean, busy: Boolean, uploads: Boolean,
                        onSend: () -> Unit, onShare: () -> Unit, onMap: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit,
                        onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s = r.s
    // a Camera 360 ride never has photos: its track is what it recorded
    val cam360 = s.kind == SessionKind.CAMERA360
    val empty = !io.github.tewnut.poicapture.core.Camera360.worthKeeping(s.kind, r.photos + r.videos, r.stats.points)
    val up = rememberUpload(s.id, uploads)
    var menu by remember { mutableStateOf(false) }
    val open = r.photos > 0 && !recording
    Card(onClick = onMap, enabled = open, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White, disabledContainerColor = Color.White),
        border = BorderStroke(1.dp, Palette.Line), elevation = CardDefaults.cardElevation(0.dp)) {
        Hero(r, recording, empty, up)
        Column(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.2).sp, color = Palette.Ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, enabled = !recording && !busy) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.sessions_more), tint = Palette.Muted)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        if (!empty) DropdownMenuItem({ Text(stringResource(R.string.sessions_share_file)) }, { menu = false; onShare() })
                        if (!empty) DropdownMenuItem({ Text(stringResource(R.string.sessions_save_zip)) }, { menu = false; onSave() })
                        DropdownMenuItem({ Text(stringResource(R.string.sessions_delete_session), color = Palette.Red) }, { menu = false; onDelete() })
                    }
                }
            }
            // what was recorded, the numbers that matter first, and how much there is to send
            Text(listOfNotNull(
                if (s.kind == SessionKind.POI) ctx.plural(R.plurals.sessions_places, r.pois) else null,
                if (cam360) null else if (s.mode == Mode.VIDEO) ctx.plural(R.plurals.sessions_videos, r.videos) else ctx.plural(R.plurals.sessions_photos, r.photos),
                km(r.stats.distanceM).takeIf { r.stats.distanceM > 0 },
                if (r.marks > 0) ctx.plural(R.plurals.sessions_marks, r.marks) else null,
                mb(r.bytes).takeIf { !empty },
            ).joinToString("  ·  "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink.copy(alpha = 0.8f))
            Hint(listOfNotNull(
                fmt(s.startedAt ?: s.createdAt),
                duration(ctx, r.stats.durationMs).takeIf { r.stats.durationMs > 0 },
                if (s.kind != SessionKind.SWEEP) null else ctx.getString(if (s.sideOfTravel == Side.RIGHT) R.string.sessions_side_right else R.string.sessions_side_left)
                    .let { if (s.mount != null) ctx.getString(R.string.sessions_side_measured, it) else it },
                if (s.kind == SessionKind.POI || cam360) null else ctx.getString(when (s.mode) {
                    Mode.PHOTO_AUTO -> R.string.sessions_mode_auto; Mode.PHOTO_MANUAL -> R.string.sessions_mode_manual; Mode.VIDEO -> R.string.sessions_mode_video }),
                if (r.relocated > 0) ctx.plural(R.plurals.sessions_relocated, r.relocated) else null,
            ).joinToString(" · "))
            if (s.endedAt == null && !recording) Hint(ctx.getString(R.string.sessions_interrupted), color = Palette.Amber)
            if (!empty && !recording) Box(Modifier.padding(top = 8.dp, end = 12.dp)) { SendControl(up, busy, onSend, onSettings) }
        }
    }
}

/** Where a session's upload is: the record kept across restarts, and the background work if any. */
private data class UploadState(val rec: UploadRecord, val running: Boolean, val queued: Boolean, val wifiWait: Boolean,
                               val done: Long, val total: Long) {
    val pct get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
    val sent get() = rec.status == UploadStatus.DONE
}

@Composable
private fun rememberUpload(sessionId: String, uploads: Boolean): UploadState? {
    if (!uploads) return null
    val ctx = LocalContext.current
    val changed by Uploads.changed.collectAsState()
    val rec = remember(sessionId, changed) { Uploads.record(ctx, sessionId) }
    val infos by remember(sessionId) { Uploads.flow(ctx, sessionId) }.collectAsState(initial = emptyList())
    val work = infos.firstOrNull { !it.state.isFinished }
    return UploadState(rec, work?.state == WorkInfo.State.RUNNING, work != null,
        work != null && work.state != WorkInfo.State.RUNNING && work.constraints.requiredNetworkType == NetworkType.UNMETERED,
        work?.progress?.getLong("done", -1L)?.takeIf { it >= 0 } ?: rec.offset,
        work?.progress?.getLong("total", 0L)?.takeIf { it > 0 } ?: rec.size)
}

/**
 * The card's one action. Not sent: "Send to server" (no access key yet: a dialog to set it up, or share the file).
 * Sending: a progress bar with the MB sent; waiting (Wi-Fi, retry): why, and Send now; failed: why, and Try again;
 * on the server: when. Access key refused: say so, and open Settings (trying again would only fail again).
 * Saved as a file (no server): Sent, and Send to server stays.
 */
@Composable
private fun SendControl(up: UploadState?, busy: Boolean, onSend: () -> Unit, onSettings: () -> Unit) {
    val status: @Composable (String, Color, Int?) -> Unit = { text, color, action ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f), maxLines = 2)
            if (action != null) TextButton(onClick = if (action == R.string.sessions_setup_open) onSettings else onSend,
                modifier = Modifier.height(36.dp)) { Text(stringResource(action)) }
        }
    }
    when {
        up == null || (!up.queued && up.rec.status != UploadStatus.DONE && up.rec.status != UploadStatus.FAILED) ->
            Button(onClick = onSend, enabled = !busy, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                SendLabel(stringResource(if (busy) R.string.sessions_packing else R.string.sessions_send_server))
            }
        up.running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status(if (up.total > 0) stringResource(R.string.sessions_sending, up.pct, mb(up.done), mb(up.total))
                else stringResource(R.string.sessions_preparing), Palette.Blue, null)
            if (up.total > 0) LinearProgressIndicator(progress = { up.pct / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        up.queued && up.rec.error != null -> status(stringResource(R.string.upload_status_retrying, up.rec.error), Palette.Amber, R.string.sessions_send_now)
        up.wifiWait -> status(stringResource(R.string.upload_status_wifi), Palette.Muted, R.string.sessions_send_now)
        up.queued -> status(stringResource(R.string.upload_status_waiting), Palette.Muted, null)
        up.sent -> status("✓ " + stringResource(R.string.sessions_on_server, fmt(up.rec.uploadedAt)), Palette.Green, null)
        up.rec.authFailed -> status(stringResource(R.string.upload_auth_failed), Palette.Red, R.string.sessions_setup_open)
        up.rec.noSpace -> status(stringResource(R.string.upload_no_space), Palette.Red, R.string.upload_retry)
        else -> status(stringResource(R.string.upload_status_failed, up.rec.error ?: ""), Palette.Red, R.string.upload_retry)
    }
}

@Composable
private fun SendLabel(text: String) {
    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
    Box(Modifier.size(8.dp))
    Text(text, maxLines = 1)
}

/**
 * The top of a session card. Place capture: up to three places side by side, each its first photo with its name, "+N"
 * on the last when there are more. Street sweep: four photos spread over the ride. Video or no photo: the kind's
 * symbol. The kind and the sent state sit on top.
 */
@Composable
private fun Hero(r: SessionRow, recording: Boolean, empty: Boolean, up: UploadState?) {
    val poi = r.s.kind == SessionKind.POI
    val shown = r.previews.take(if (poi) 3 else 4)
    Box(Modifier.fillMaxWidth().height(if (shown.isEmpty()) 96.dp else 152.dp).background(Palette.House)) {
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.GreenLight, Palette.Canvas))),
                contentAlignment = Alignment.Center) {
                Text(if (r.s.kind == SessionKind.CAMERA360) "📷" else if (r.s.mode == Mode.VIDEO && !poi) "🎬" else if (poi) "📍"
                    else if (r.s.kind == SessionKind.INDOOR) "🏬" else "🛵", fontSize = 36.sp)
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { i, p ->
                Tile(r.s.id, p, Modifier.weight(1f).fillMaxHeight(), label = poi,
                    more = if (poi && i == shown.lastIndex) r.previews.size - shown.size else 0)
            }
        }
        if (r.route.size >= 2) RouteSketch(r.route, Modifier.align(Alignment.BottomEnd).padding(10.dp))
        if (shown.isNotEmpty()) Box(Modifier.fillMaxWidth().height(52.dp)
            .background(Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (poi) "📍 " + stringResource(R.string.sessions_kind_poi)
                else if (r.s.kind == SessionKind.INDOOR) "🏬 " + stringResource(R.string.setup_indoor_short) +
                    (r.s.building?.name?.let { " · $it" } ?: "")
                else if (r.s.kind == SessionKind.CAMERA360) "📷 360"
                else "🛵 " + stringResource(R.string.sessions_kind_sweep) + " · " +
                    when (r.s.target) {
                        io.github.tewnut.poicapture.core.SweepTarget.ADDRESSES -> "🔢 " + stringResource(R.string.setup_target_addresses_short)
                        io.github.tewnut.poicapture.core.SweepTarget.SIGNS -> "🚸 " + stringResource(R.string.setup_target_signs_short)
                        else -> "🏪 " + stringResource(R.string.setup_target_shops_short)
                    },
                bg = Color(0xF2FFFFFF), fg = Palette.Ink, size = 12.sp, bold = true)
            Box(Modifier.weight(1f))
            when {
                recording -> Pill(stringResource(R.string.sessions_badge_recording), dot = Palette.Red, bg = Color(0xF2FFFFFF), fg = Palette.Blue, size = 12.sp, bold = true)
                empty -> Pill(stringResource(R.string.sessions_badge_empty), bg = Color(0xF2FFFFFF), fg = Palette.Muted, size = 12.sp, bold = true)
                up?.running == true -> Pill(stringResource(R.string.sessions_badge_sending, up.pct), bg = Color(0xF2FFFFFF), fg = Palette.Blue, size = 12.sp, bold = true)
                up?.sent == true || r.s.exportedAt != null -> Pill("✓ " + stringResource(R.string.sessions_badge_sent), bg = Palette.GreenLight, fg = Palette.Green, size = 12.sp, bold = true)
                else -> Pill(stringResource(R.string.sessions_badge_not_sent), dot = Color(0xFFF79009), bg = Palette.AmberLight, fg = Palette.Amber, size = 12.sp, bold = true)
            }
        }
    }
}

@Composable
private fun Tile(sessionId: String, p: Preview, modifier: Modifier, label: Boolean, more: Int) {
    val img = rememberThumb(sessionId, p.itemId)
    Box(modifier.background(Color(0xFFD5DBD8))) {
        androidx.compose.animation.Crossfade(img, label = "thumb") { b ->
            if (b != null) Image(b, p.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (label) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000))))
            .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 7.dp)) {
            Text(p.label ?: stringResource(R.string.sessionmap_place_no_name), color = Color.White.copy(alpha = if (p.label == null) 0.7f else 1f),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (more > 0) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Text("+$more", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The ride's route as a small lime line on black (VMAP colours), north up, kept to its shape. */
@Composable
private fun RouteSketch(route: List<io.github.tewnut.poicapture.core.LatLon>, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(76.dp, 56.dp).background(Palette.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))) {
        val lat0 = route.minOf { it.lat }; val lat1 = route.maxOf { it.lat }
        val lon0 = route.minOf { it.lon }; val lon1 = route.maxOf { it.lon }
        val k = Math.cos(Math.toRadians((lat0 + lat1) / 2))          // a degree of longitude is shorter
        val w = ((lon1 - lon0) * k).coerceAtLeast(1e-6); val h = (lat1 - lat0).coerceAtLeast(1e-6)
        val pad = 9.dp.toPx()
        val sc = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - (w * sc).toFloat()) / 2; val oy = (size.height - (h * sc).toFloat()) / 2
        fun at(p: io.github.tewnut.poicapture.core.LatLon) = androidx.compose.ui.geometry.Offset(
            ox + ((p.lon - lon0) * k * sc).toFloat(), oy + ((lat1 - p.lat) * sc).toFloat())
        val path = androidx.compose.ui.graphics.Path().apply {
            at(route[0]).let { moveTo(it.x, it.y) }
            for (p in route.drop(1)) at(p).let { lineTo(it.x, it.y) }
        }
        drawPath(path, Palette.Lime, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(Color.White, 3.dp.toPx(), at(route.last()))
    }
}

/** A photo to show on a session card, and the place's name for Place capture. */
private data class Preview(val itemId: String, val label: String? = null)

/**
 * Place capture: each place's first photo with its name (typed, or read from the sign). Street sweep: four sharp photos
 * spread over the ride, leaving out the first and last tenth (often taken while mounting the phone).
 */
private fun previews(kind: SessionKind, items: List<JSONObject>, pois: List<JSONObject>): List<Preview> {
    val photos = items.filter { it.optString("kind") == "photo" && it.optString("id").isNotBlank() }
    if (kind == SessionKind.POI) {
        val byPoi = photos.groupBy { it.optString("poiId") }
        return pois.mapNotNull { p ->
            val first = byPoi[p.optString("id")]?.firstOrNull() ?: return@mapNotNull null
            val name = p.optJSONObject("fields")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: p.optJSONObject("read")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            Preview(first.getString("id"), name)
        }
    }
    val sharp = photos.filter { it.optJSONObject("blur")?.let { b -> b.optBoolean("blurry") || b.optBoolean("unusable") } != true }
        .ifEmpty { photos }
    val n = 4
    if (sharp.size <= n) return sharp.map { Preview(it.getString("id")) }
    val lo = sharp.size / 10
    val hi = sharp.size - 1 - sharp.size / 10
    return (0 until n).map { k -> Preview(sharp[lo + (hi - lo) * k / (n - 1)].getString("id")) }
}

private fun mb(b: Long) = if (b >= 1L shl 30) String.format(Locale.ROOT, "%.1f GB", b / 1073741824.0)
    else String.format(Locale.ROOT, "%.0f MB", b / 1048576.0)
private fun km(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)
private fun duration(ctx: Context, ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min < 1) ctx.getString(R.string.sessions_duration_s, (ms / 1000).toInt())
    else if (min < 60) ctx.getString(R.string.sessions_duration_min, min)
    else ctx.getString(R.string.sessions_duration_h, min / 60, min % 60)
}
/** A quantity string: [n] picks the plural form; the format arguments default to [n] alone. */
private fun Context.plural(id: Int, n: Int, vararg args: Any): String =
    resources.getQuantityString(id, n, *(if (args.isEmpty()) arrayOf<Any>(n) else args))
private fun fmt(isoTime: String?): String = isoTime?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: "–"

/**
 * A street, a rider, and the viewfinder of the app icon: a black line drawing with VMAP lime, drawn in code so it
 * stays sharp and follows the palette. The street has what the app's rides take: shops with signs, houses with their
 * number plates and a traffic sign. [animated]: the rider rides in and stays in the middle while the street goes by
 * (the view follows the rider); the phone on the handlebar takes each sign, plate and traffic sign as it passes (a
 * flash, then lime), on and on. Still when the phone's animations are off (Accessibility → remove animations).
 */
@Composable
fun StreetIllustration(modifier: Modifier, animated: Boolean = false) {
    val ctx = LocalContext.current
    val motion = remember {
        animated && runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
    val soft = Modifier.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    // soft edges: the street fades out at both sides instead of being cut
    fun androidx.compose.ui.graphics.drawscope.DrawScope.edges() = drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
        0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    if (!motion) {
        androidx.compose.foundation.Canvas(modifier.then(soft)) { drawRide(null, 1f); edges() }
        return
    }
    // the opening, once: the rider rides in to the middle
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    // then the street goes by, one period per loop: the loop's end is its start, no seam
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "street")
    val offset by loop.animateFloat(0f, StreetScroll.PERIOD, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(StreetScroll.LOOP_MS, easing = androidx.compose.animation.core.LinearEasing)), label = "ride")
    androidx.compose.foundation.Canvas(modifier.then(soft)) { drawRide(offset, enter.value); edges() }
}

/**
 * The moving street (pure: StreetSceneTest), in drawing units (the picture is 150 high, the ground at 104). One
 * [PERIOD] of the street repeats: [ITEMS] at their x in it. Each item has one thing the phone takes (a shop's sign, a
 * house's number plate, a traffic sign: [Item.target]); it is taken once its centre has passed the phone.
 */
internal object StreetScroll {
    const val LOOP_MS = 6400
    /** Eight lane-mark gaps: the marks are back in place after one period too. */
    const val GAP = 56f
    const val PERIOD = 8 * GAP
    /** Where the phone is, from the scooter's centre (units): it is the phone that passes the targets. */
    const val CAMERA = 19f

    enum class Kind { SHOP, HOUSE, TUBE, POLE }

    /** An item of the street: its left edge in the period, width, height above the ground. */
    data class Item(val kind: Kind, val x: Float, val w: Float, val h: Float) {
        /** What the phone takes, relative to the item's left edge and the ground (x, y up from it, w, h). */
        val target: FloatArray get() = when (kind) {
            Kind.SHOP -> floatArrayOf(10f, h - 28f, w - 20f, 18f)          // the sign across the front
            Kind.HOUSE -> floatArrayOf(w - 22f, 26f, 13f, 9f)              // the number plate by the door
            Kind.TUBE -> floatArrayOf(w - 20f, 26f, 13f, 9f)
            Kind.POLE -> floatArrayOf(-1f, 46f, 20f, 20f)                  // the round sign on its pole
        }
        val targetCentre: Float get() = x + target[0] + target[2] / 2
    }

    val ITEMS = listOf(
        Item(Kind.SHOP, 0f, 92f, 70f),
        Item(Kind.HOUSE, 112f, 72f, 66f),
        Item(Kind.POLE, 204f, 18f, 66f),
        Item(Kind.SHOP, 242f, 92f, 88f),
        Item(Kind.TUBE, 354f, 62f, 92f),
    )

    /** An item in view: its period [n], its left edge in the picture (units), and the item. */
    data class Placed(val n: Int, val left: Float, val item: Item) {
        val targetCentre: Float get() = left + item.target[0] + item.target[2] / 2
    }

    /** The items in view of a picture [width] units wide, at [offset]. */
    fun visible(width: Float, offset: Float): List<Placed> {
        val first = kotlin.math.floor((offset - 120f) / PERIOD).toInt()
        val last = kotlin.math.ceil((offset + width) / PERIOD).toInt()
        return (first..last).flatMap { n -> ITEMS.map { Placed(n, n * PERIOD + it.x - offset, it) } }
            .filter { it.left + it.item.w > -20f && it.left < width + 20f }
    }

    /** How far the phone ([camX]) is past an item's target (units; < 0 = still ahead). */
    fun passed(camX: Float, p: Placed): Float = camX - p.targetCentre

    /** The flash right after a target is taken: 1 at the moment of passing, 0 after 14 units. */
    fun flash(p: Float): Float = if (p in 0f..14f) 1f - p / 14f else 0f

    /** The viewfinder stays on the target just taken for 50 units. */
    fun framed(p: Float): Boolean = p in 0f..50f

    /** The still picture: the street placed so the tall shop's sign was just taken ([width] = the picture's). */
    fun stillOffset(width: Float): Float = ITEMS[3].targetCentre + 18f - (width / 2)

    /** The body's bob over the road (units): small bumps, a whole number per period. */
    fun bob(offset: Float): Float = 0.7f * kotlin.math.abs(kotlin.math.sin(offset / PERIOD * 10 * Math.PI.toFloat()))

    /** The lane marks move with the street (units, 0 until [GAP]). */
    fun laneShift(offset: Float): Float = ((offset % GAP) + GAP) % GAP
}

/**
 * One item of the street at [x] (its left edge, px), kept plain: a white block with a door and the one thing the
 * phone takes (a grey sign, a blue number plate) that turns lime when [taken]; a traffic sign is a disc on a pole with
 * a lime ring once taken. [flash]: the photo just taken, [framed]: the viewfinder on it.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawItem(it: StreetScroll.Item, x: Float, taken: Float,
                                                                      flash: Float, framed: Boolean, u: Float) {
    val ink = Palette.Black
    val line = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val round = androidx.compose.ui.graphics.StrokeCap.Round
    val ground = 104 * u
    fun o(dx: Float, up: Float) = androidx.compose.ui.geometry.Offset(x + dx * u, ground - up * u)
    fun sz(w: Float, h: Float) = androidx.compose.ui.geometry.Size(w * u, h * u)
    fun cr(r: Float) = androidx.compose.ui.geometry.CornerRadius(r * u)
    val t = it.target
    val tl = o(t[0], t[1] + t[3])   // the target's top-left
    if (flash > 0f) drawRoundRect(Palette.Lime.copy(alpha = 0.6f * flash), o(t[0] - 6, t[1] + t[3] + 6), sz(t[2] + 12, t[3] + 12), cr(6f))
    if (it.kind == StreetScroll.Kind.POLE) {
        val c = o(t[0] + t[2] / 2, t[1] + t[3] / 2)
        drawLine(ink, o(t[0] + t[2] / 2, 0f), o(t[0] + t[2] / 2, t[1]), 2.5f * u, cap = round)
        if (taken > 0f) drawCircle(Palette.Lime.copy(alpha = taken), 13 * u, c)
        drawCircle(Palette.Red, 9 * u, c)
        drawCircle(Color.White, 5.5f * u, c)
        drawCircle(ink, 9 * u, c, style = line)
    } else {
        drawRoundRect(Color.White, o(0f, it.h), sz(it.w, it.h), cr(6f))
        drawRoundRect(ink, o(0f, it.h), sz(it.w, it.h), cr(6f), style = line)
        val door = if (it.kind == StreetScroll.Kind.SHOP) it.w / 2 - 12 else 12f
        drawRoundRect(ink, o(door, 30f), sz(24f, 30f), cr(3f), style = line)
        val plain = if (it.kind == StreetScroll.Kind.SHOP) Palette.House else Palette.Blue
        drawRoundRect(androidx.compose.ui.graphics.lerp(plain, Palette.Lime, taken), tl, sz(t[2], t[3]), cr(3f))
        drawRoundRect(ink, tl, sz(t[2], t[3]), cr(3f), style = line)
    }
    if (framed) {
        val l = tl.x - 5 * u
        val tp = tl.y - 5 * u
        val r = tl.x + (t[2] + 5) * u
        val b = tl.y + (t[3] + 5) * u
        val c = 7 * u
        fun q(px: Float, py: Float) = androidx.compose.ui.geometry.Offset(px, py)
        for (k in listOf(listOf(q(l, tp + c), q(l, tp), q(l + c, tp)), listOf(q(r - c, tp), q(r, tp), q(r, tp + c)),
                         listOf(q(r, b - c), q(r, b), q(r - c, b)), listOf(q(l + c, b), q(l, b), q(l, b - c)))) {
            drawLine(ink, k[0], k[1], 3.2f * u, cap = round)
            drawLine(ink, k[1], k[2], 3.2f * u, cap = round)
        }
    }
}

/**
 * The rider, kept plain: two wheels, the scooter's deck and front, the rider as one dark stroke under a lime helmet,
 * and the phone on the handlebar with its scan beam up to the street (brighter while a photo is taken: [beam]).
 * [bx] = the scooter's centre; [bob] lifts the body (units) as on a real road.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRider(bx: Float, u: Float, alpha: Float = 1f,
                                                                       bob: Float = 0f, beam: Float = 0f) {
    val ink = Palette.Black.copy(alpha = alpha)
    val white = Color.White.copy(alpha = alpha)
    val line = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val round = androidx.compose.ui.graphics.StrokeCap.Round
    val by = 128 * u
    fun o(dx: Float, dy: Float) = androidx.compose.ui.geometry.Offset(bx + dx * u, by + dy * u)
    fun b(dx: Float, dy: Float) = o(dx, dy - bob)
    // the scan beam: from the phone up to the street
    if (alpha > 0f) {
        val beamA = (0.14f + 0.4f * beam) * alpha
        val tip = b(19f, -36f)
        val beamPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(tip.x, tip.y); val l = b(-12f, -82f); lineTo(l.x, l.y); val r = b(50f, -82f); lineTo(r.x, r.y); close()
        }
        drawPath(beamPath, androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Palette.Lime.copy(alpha = beamA * 0.25f), Palette.Lime.copy(alpha = beamA)),
            startY = by - (82 + bob) * u, endY = by - (36 + bob) * u))
    }
    for (wx in listOf(-17f, 19f)) {
        drawCircle(white, 7 * u, o(wx, 0f))
        drawCircle(ink, 7 * u, o(wx, 0f), style = line)
    }
    // the deck and the front: one rounded body, the steering stem, the handlebar
    drawRoundRect(white, b(-27f, -15f), androidx.compose.ui.geometry.Size(42 * u, 9 * u), androidx.compose.ui.geometry.CornerRadius(4.5f * u))
    drawRoundRect(ink, b(-27f, -15f), androidx.compose.ui.geometry.Size(42 * u, 9 * u), androidx.compose.ui.geometry.CornerRadius(4.5f * u), style = line)
    drawLine(ink, b(14f, -10f), b(17f, -29f), 2.5f * u, cap = round)
    drawLine(ink, b(12f, -29f), b(19f, -29f), 2.5f * u, cap = round)
    // the rider: one dark stroke from the seat to the shoulders, an arm to the bar, a lime helmet
    drawLine(ink, b(-13f, -17f), b(-8f, -35f), 7 * u, cap = round)
    drawLine(ink, b(-8f, -32f), b(13f, -29f), 3 * u, cap = round)
    drawCircle(Palette.Lime.copy(alpha = alpha), 5.5f * u, b(-6f, -42f))
    drawCircle(ink, 5.5f * u, b(-6f, -42f), style = line)
    // the phone on the handlebar
    drawRoundRect(white, b(16f, -40f), androidx.compose.ui.geometry.Size(6 * u, 8 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
    drawRoundRect(ink, b(16f, -40f), androidx.compose.ui.geometry.Size(6 * u, 8 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = line)
}

/**
 * The street at [offset] (units; null: the still picture, a sign just taken), the rider riding in during the opening
 * ([enter] 0 → 1), then in the middle.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRide(offset: Float?, enter: Float) {
    val u = size.height / 150f
    val width = size.width / u
    val off = offset ?: StreetScroll.stillOffset(width)
    val riderX = -50f + (width / 2 + 50f - StreetScroll.CAMERA) * enter
    val camX = riderX + StreetScroll.CAMERA   // the phone takes what it passes
    drawRect(Palette.GreenLight, androidx.compose.ui.geometry.Offset(0f, 108 * u), androidx.compose.ui.geometry.Size(size.width, 34 * u))
    val shift = StreetScroll.laneShift(off)
    var i = -1
    while (true) {
        val x0 = (i * StreetScroll.GAP - shift) * u
        if (x0 > size.width) break
        drawLine(Palette.LimeDeep, androidx.compose.ui.geometry.Offset(x0, 125 * u), androidx.compose.ui.geometry.Offset(x0 + 26 * u, 125 * u),
            3 * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        i++
    }
    var beam = 0f
    for (p in StreetScroll.visible(width, off)) {
        val past = StreetScroll.passed(camX, p)
        val flash = if (offset == null) 0f else StreetScroll.flash(past)
        beam = maxOf(beam, flash)
        drawItem(p.item, p.left * u, if (past >= 0f) 1f else 0f, flash, StreetScroll.framed(past), u)
    }
    drawRider(riderX * u, u, alpha = enter.coerceIn(0f, 1f), bob = if (offset == null) 0f else StreetScroll.bob(off),
        beam = beam * enter)
}

/** No session yet: what a session is, and one big way to start the first. */
@Composable
private fun EmptySessions(onNew: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        StreetIllustration(Modifier.fillMaxWidth().height(150.dp), animated = true)
        Box(Modifier.height(24.dp))
        Text(stringResource(R.string.sessions_empty), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.height(8.dp))
        Text(stringResource(R.string.sessions_empty_text), fontSize = 16.sp, color = Palette.Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.height(28.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Add, null)
            Box(Modifier.size(8.dp))
            Text(stringResource(R.string.home_new_session), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
