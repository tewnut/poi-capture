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
import androidx.compose.runtime.collectAsState
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
                              val relocated: Int, val pois: Int = 0, val marks: Int = 0, val previews: List<Preview> = emptyList())

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
                        if (!s.road.auto && s.kind != SessionKind.POI) roadText(ctx, s.road) else ctx.getString(R.string.session_no_street),
                        ways = s.road.ways)
                SessionRow(s, title,
                    items.count { it.optString("kind") == "photo" },
                    items.count { it.optString("kind") == "video" && store.videoFile(s.id, it.getString("id")).length() > 0 },
                    TrackStats.of(store.trackFile(s.id), s.settings.maxAccuracyM),
                    store.dir(s.id).walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    items.count { (it.has("placeLat") && !it.isNull("placeLat")) || (it.has("relocatedLat") && !it.isNull("relocatedLat")) },
                    pois.size, if (s.kind == SessionKind.SWEEP) store.marks(s.id).size else 0,
                    previews(s.kind, items, pois))
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
                markExported(s)
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
                    withContext(Dispatchers.IO) { io.github.tewnut.poicapture.capture.Uploads.forget(ctx, s.id); store.delete(s.id) }
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
                        onSend: () -> Unit, onShare: () -> Unit, onMap: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val s = r.s
    val empty = r.photos + r.videos == 0
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
                if (s.mode == Mode.VIDEO) ctx.plural(R.plurals.sessions_videos, r.videos) else ctx.plural(R.plurals.sessions_photos, r.photos),
                km(r.stats.distanceM).takeIf { r.stats.distanceM > 0 },
                if (r.marks > 0) ctx.plural(R.plurals.sessions_marks, r.marks) else null,
                mb(r.bytes).takeIf { !empty },
            ).joinToString("  ·  "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink.copy(alpha = 0.8f))
            Hint(listOfNotNull(
                fmt(s.startedAt ?: s.createdAt),
                duration(ctx, r.stats.durationMs).takeIf { r.stats.durationMs > 0 },
                if (s.kind == SessionKind.POI) null else ctx.getString(if (s.sideOfTravel == Side.RIGHT) R.string.sessions_side_right else R.string.sessions_side_left)
                    .let { if (s.mount != null) ctx.getString(R.string.sessions_side_measured, it) else it },
                if (s.kind == SessionKind.POI) null else ctx.getString(when (s.mode) {
                    Mode.PHOTO_AUTO -> R.string.sessions_mode_auto; Mode.PHOTO_MANUAL -> R.string.sessions_mode_manual; Mode.VIDEO -> R.string.sessions_mode_video }),
                if (r.relocated > 0) ctx.plural(R.plurals.sessions_relocated, r.relocated) else null,
            ).joinToString(" · "))
            if (s.endedAt == null && !recording) Hint(ctx.getString(R.string.sessions_interrupted), color = Palette.Amber)
            if (!empty && !recording) Box(Modifier.padding(top = 8.dp, end = 12.dp)) { SendControl(up, busy, onSend) }
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
 * on the server: when. Shared by hand (no server): Sent, and Send to server stays.
 */
@Composable
private fun SendControl(up: UploadState?, busy: Boolean, onSend: () -> Unit) {
    val status: @Composable (String, Color, Int?) -> Unit = { text, color, action ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f), maxLines = 2)
            if (action != null) TextButton(onClick = onSend, modifier = Modifier.height(36.dp)) { Text(stringResource(action)) }
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
                Text(if (r.s.mode == Mode.VIDEO && !poi) "🎬" else if (poi) "📍" else "🛵", fontSize = 36.sp)
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { i, p ->
                Tile(r.s.id, p, Modifier.weight(1f).fillMaxHeight(), label = poi,
                    more = if (poi && i == shown.lastIndex) r.previews.size - shown.size else 0)
            }
        }
        if (shown.isNotEmpty()) Box(Modifier.fillMaxWidth().height(52.dp)
            .background(Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill((if (poi) "📍 " else "🛵 ") + stringResource(if (poi) R.string.sessions_kind_poi else R.string.sessions_kind_sweep),
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

/** No session yet: what a session is, and one big way to start the first. */
@Composable
private fun EmptySessions(onNew: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(120.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
            Text("📷", fontSize = 52.sp)
        }
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
