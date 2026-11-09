package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.size
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import io.github.tewnut.poicapture.core.UploadState
import io.github.tewnut.poicapture.capture.ServerApi
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.Prefs
import org.json.JSONObject
import kotlinx.coroutines.delay
import androidx.compose.material3.LinearProgressIndicator
import io.github.tewnut.poicapture.core.SessionKind
import io.github.tewnut.poicapture.core.Side
import io.github.tewnut.poicapture.core.TrackStats
import io.github.tewnut.poicapture.core.Vehicle
import io.github.tewnut.poicapture.core.iso
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class SessionRow(val s: Session, val title: String, val photos: Int, val videos: Int, val stats: TrackStats, val bytes: Long,
                              val relocated: Int, val pois: Int = 0, val marks: Int = 0)

@Composable
fun SessionsScreen(onNew: () -> Unit, onOpenMap: (String) -> Unit) {
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
    var reload by remember { mutableIntStateOf(0) }
    val configured = remember { Uploads.configured(ctx) }
    val wifiOnly = remember { !Prefs.settings(ctx).uploadOnMobileData }
    var uploads by remember { mutableStateOf<Map<String, Pair<UploadState, Boolean>>>(emptyMap()) }   // state, changed since
    var agent by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }

    LaunchedEffect(reload, active) {
        rows = withContext(Dispatchers.IO) {
            store.list().map { s ->
                val items = store.items(s.id)
                val pois = if (s.kind == SessionKind.POI) store.pois(s.id) else emptyList()
                val names = pois.mapNotNull { it.optJSONObject("fields")?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                val title = when {
                    s.kind == SessionKind.POI -> names.take(2).joinToString(", ").ifEmpty { ctx.plural(R.plurals.sessions_places, pois.size) }
                    s.road.auto -> TrackStats.mainRoads(items).joinToString(", ").ifEmpty { ctx.getString(R.string.road_auto) }
                    else -> roadText(ctx, s.road)
                }
                SessionRow(s, title,
                    items.count { it.optString("kind") == "photo" },
                    items.count { it.optString("kind") == "video" && store.videoFile(s.id, it.getString("id")).length() > 0 },
                    TrackStats.of(store.trackFile(s.id), s.settings.maxAccuracyM),
                    store.dir(s.id).walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    items.count { it.has("relocatedLat") && !it.isNull("relocatedLat") },
                    pois.size, if (s.kind == SessionKind.SWEEP) store.marks(s.id).size else 0)
            }
        }
    }

    // background upload progress (written by the worker): refresh every 2 s while the list is visible
    LaunchedEffect(rows, configured) {
        if (!configured) return@LaunchedEffect
        val ids = rows?.map { it.s.id } ?: return@LaunchedEffect
        while (true) {
            uploads = withContext(Dispatchers.IO) {
                ids.associateWith { id -> store.uploadState(id).let { it to (it.state == "uploaded" && store.contentVersion(id) > it.uploadedVersion) } }
            }
            delay(2000)
        }
    }
    // what the agent made of uploaded sessions (only with the optional server API)
    LaunchedEffect(rows, configured, uploads.isNotEmpty()) {
        if (!configured || uploads.isEmpty()) return@LaunchedEffect      // wait for the upload states first
        while (true) {
            val done = uploads.filterValues { it.first.state == "uploaded" }.keys
            if (done.isNotEmpty()) agent = withContext(Dispatchers.IO) { done.mapNotNull { id -> ServerApi.status(ctx, id)?.let { id to it } }.toMap() }
            delay(30_000)
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

    val list = rows
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sessions_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                if (list != null && configured) {
                    val pending = list.filter { it.photos + it.videos > 0 && active != it.s.id &&
                        uploads[it.s.id]?.let { (u, changed) -> u.state != "uploaded" || changed } != false }
                    Text(
                        if (pending.isEmpty()) stringResource(R.string.sessions_all_uploaded)
                        else ctx.plural(R.plurals.sessions_not_uploaded, pending.size, pending.size, mb(pending.sumOf { it.bytes })),
                        color = if (pending.isEmpty()) Palette.Green else Palette.Amber, fontWeight = FontWeight.SemiBold,
                    )
                    Hint(stringResource(if (wifiOnly) R.string.sessions_auto_upload_wifi else R.string.sessions_auto_upload_any,
                        mb(store.root.apply { mkdirs() }.usableSpace)))
                } else if (list != null) {
                    val unsent = list.filter { it.s.exportedAt == null && it.photos + it.videos > 0 && active != it.s.id }
                    Text(
                        if (unsent.isEmpty()) stringResource(R.string.sessions_none_unsent)
                        else ctx.plural(R.plurals.sessions_unsent, unsent.size, unsent.size, km(unsent.sumOf { it.stats.distanceM }), mb(unsent.sumOf { it.bytes })),
                        color = if (unsent.isEmpty()) Palette.Muted else Palette.Amber, fontWeight = FontWeight.SemiBold,
                    )
                    Hint(stringResource(R.string.sessions_local_only, mb(store.root.apply { mkdirs() }.usableSpace)))
                }
                if (msg.isNotEmpty()) Hint(msg, color = if (msgError) Palette.Red else Palette.Green)
            }
        }
        if (list == null) item {
            Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Hint(stringResource(R.string.sessions_loading))
            }
        }
        if (list != null && list.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sessions_empty), color = Palette.Muted)
                    Button(onClick = onNew) { Text(stringResource(R.string.sessions_first)) }
                }
            }
        }
        items(list ?: emptyList(), key = { it.s.id }) { r ->
            SessionCard(r, active == r.s.id, busy == r.s.id, if (configured) uploads[r.s.id] else null, agent[r.s.id], wifiOnly,
                onUpload = { scope.launch { withContext(Dispatchers.IO) { Uploads.enqueue(ctx, r.s.id) }; msgError = false; msg = ctx.getString(R.string.sessions_queued) } },
                onSend = { send(r.s) },
                onMap = { onOpenMap(r.s.id) },
                onSave = { saving = r.s; saveLauncher.launch(Exporter.fileName(r.s)) },
                onDelete = { confirmDel = r.s })
        }
        item { Box(Modifier.padding(8.dp)) }
    }

    confirmDel?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                val up = uploads[s.id]
                Text(stringResource(when {
                    up != null && up.first.state == "uploaded" && !up.second -> R.string.sessions_delete_uploaded
                    up != null && up.first.state == "uploaded" -> R.string.sessions_delete_changed
                    s.exportedAt != null -> R.string.sessions_delete_sent
                    else -> R.string.sessions_delete_unsent
                }))
            },
            confirmButton = { TextButton(onClick = {
                confirmDel = null
                msgError = false; msg = ctx.getString(R.string.sessions_deleting)
                scope.launch {
                    withContext(Dispatchers.IO) { store.delete(s.id); Uploads.forget(ctx, s.id) }
                    msg = ctx.getString(R.string.sessions_deleted)
                    reload++
                }
            }) { Text(stringResource(R.string.sessions_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text(stringResource(R.string.sessions_keep)) } },
        )
    }
}

@Composable
private fun SessionCard(r: SessionRow, recording: Boolean, busy: Boolean, upload: Pair<UploadState, Boolean>?, agentStatus: JSONObject?,
                        wifiOnly: Boolean, onUpload: () -> Unit,
                        onSend: () -> Unit, onMap: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val s = r.s
    val empty = r.photos + r.videos == 0
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.Ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, enabled = !recording && !busy) { Icon(Icons.Filled.MoreVert, stringResource(R.string.sessions_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (!empty && upload != null) DropdownMenuItem({ Text(stringResource(R.string.sessions_send_other_app)) }, { menu = false; onSend() })
                        if (!empty) DropdownMenuItem({ Text(stringResource(R.string.sessions_save_zip)) }, { menu = false; onSave() })
                        DropdownMenuItem({ Text(stringResource(R.string.sessions_delete_session), color = Palette.Red) }, { menu = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val u = upload?.first
                when {
                    recording -> Badge(stringResource(R.string.sessions_badge_recording), Palette.Blue, Color(0xFFD1E9FF))
                    empty -> Badge(stringResource(R.string.sessions_badge_empty), Palette.Muted, Color(0xFFF2F4F7))
                    upload != null && upload.second -> Badge(stringResource(R.string.sessions_badge_reupload), Palette.Amber, Palette.AmberLight)
                    u?.state == "uploaded" -> Badge(stringResource(R.string.sessions_badge_uploaded), Palette.Green, Palette.GreenLight)
                    u?.state == "uploading" -> Badge(stringResource(R.string.sessions_badge_uploading, (u.progress * 100).toInt()), Palette.Blue, Color(0xFFD1E9FF))
                    u?.state == "queued" -> Badge(stringResource(if (wifiOnly) R.string.sessions_badge_wait_wifi else R.string.sessions_badge_wait_network), Palette.Amber, Palette.AmberLight)
                    u?.state == "failed" -> Badge(stringResource(R.string.sessions_badge_failed), Palette.Red, Color(0xFFFEE4E2))
                    upload != null -> Badge(stringResource(R.string.sessions_badge_not_uploaded), Palette.Amber, Palette.AmberLight)
                    s.exportedAt != null -> Badge(stringResource(R.string.sessions_badge_sent), Palette.Green, Palette.GreenLight)
                    else -> Badge(stringResource(R.string.sessions_badge_not_sent), Palette.Amber, Palette.AmberLight)
                }
            }
            Hint(listOfNotNull(
                fmt(s.startedAt ?: s.createdAt),
                duration(ctx, r.stats.durationMs).takeIf { r.stats.durationMs > 0 },
                km(r.stats.distanceM).takeIf { r.stats.distanceM > 0 },
                if (s.kind == SessionKind.POI) ctx.plural(R.plurals.sessions_places, r.pois) else null,
                if (s.mode == Mode.VIDEO) ctx.plural(R.plurals.sessions_videos, r.videos) else ctx.plural(R.plurals.sessions_photos, r.photos),
                if (r.marks > 0) ctx.plural(R.plurals.sessions_marks, r.marks) else null,
            ).joinToString(" · "))
            Hint(listOfNotNull(
                ctx.getString(if (s.kind == SessionKind.POI) R.string.sessions_kind_poi else R.string.sessions_kind_sweep),
                if (s.kind == SessionKind.POI) null else ctx.getString(if (s.sideOfTravel == Side.RIGHT) R.string.sessions_side_right else R.string.sessions_side_left)
                    .let { if (s.mount != null) ctx.getString(R.string.sessions_side_measured, it) else it },
                when (s.vehicle) { Vehicle.CAR -> ctx.getString(R.string.sessions_vehicle_car); Vehicle.MOTORBIKE -> ctx.getString(R.string.sessions_vehicle_motorbike); else -> null },
                if (s.kind == SessionKind.POI) null else ctx.getString(when (s.mode) {
                    Mode.PHOTO_AUTO -> R.string.sessions_mode_auto; Mode.PHOTO_MANUAL -> R.string.sessions_mode_manual; Mode.VIDEO -> R.string.sessions_mode_video }),
                if (r.relocated > 0) ctx.plural(R.plurals.sessions_relocated, r.relocated) else null,
                if (s.endedAt == null && !recording) ctx.getString(R.string.sessions_interrupted) else null,
            ).joinToString(" · "), color = if (s.endedAt == null && !recording) Palette.Amber else Palette.Muted)
            upload?.first?.let { u ->
                when {
                    u.state == "uploading" || (u.state == "failed" && u.offset > 0) ->
                        LinearProgressIndicator(progress = { u.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp))
                    else -> {}
                }
                if (u.state == "failed" && u.error != null) Hint(stringResource(R.string.sessions_upload_error, u.error), color = Palette.Red)
            }
            agentStatus?.let { a ->
                Hint(when (a.optString("state")) {
                    "done" -> listOfNotNull(ctx.getString(R.string.sessions_agent_done),
                        if (a.has("poisFound")) a.optInt("poisFound").let { ctx.plural(R.plurals.sessions_agent_found, it) } else null,
                        a.optJSONArray("recapture")?.length()?.takeIf { it > 0 }?.let { ctx.plural(R.plurals.sessions_agent_recapture, it) },
                    ).joinToString(" · ")
                    "processing" -> stringResource(R.string.sessions_agent_processing)
                    "failed" -> stringResource(R.string.sessions_agent_failed)
                    else -> stringResource(R.string.sessions_agent_received)
                }, color = Palette.Blue)
            }
            if (!empty && !recording) {
                Row(Modifier.padding(top = 6.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (upload != null) {
                        val (u, changed) = upload
                        if (u.state == "none" || u.state == "failed" || changed) {
                            Button(onClick = onUpload, modifier = Modifier.weight(1f)) { Text(stringResource(if (changed) R.string.sessions_reupload else R.string.sessions_upload_now), maxLines = 1) }
                        }
                    } else if (s.exportedAt == null) {
                        Button(onClick = onSend, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(if (busy) R.string.sessions_packing else R.string.sessions_send), maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = onSend, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(if (busy) R.string.sessions_packing else R.string.sessions_send_again), maxLines = 1) }
                    }
                    if (r.photos > 0) OutlinedButton(onClick = onMap, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sessions_map), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) =
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))

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
