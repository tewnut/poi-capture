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
import io.github.tewnut.poicapture.core.SessionKind
import io.github.tewnut.poicapture.core.Side
import io.github.tewnut.poicapture.core.TrackStats
import io.github.tewnut.poicapture.core.iso
import java.io.File
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class SessionRow(val s: Session, val title: String, val photos: Int, val videos: Int, val stats: TrackStats, val bytes: Long,
                              val relocated: Int, val pois: Int = 0, val marks: Int = 0)

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
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload, active) {
        rows = withContext(Dispatchers.IO) {
            store.list().map { s ->
                val items = store.items(s.id)
                val pois = if (s.kind == SessionKind.POI) store.pois(s.id) else emptyList()
                val names = pois.mapNotNull { it.optJSONObject("fields")?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                // saved sessions carry their name; older ones get the same "{street} [yymmdd]" from their photos
                // (sessions saved as "Unnamed road …" before v0.14 are named again, after the nearest big road)
                val title = s.name?.takeIf { !it.startsWith(io.github.tewnut.poicapture.core.SessionNaming.UNNAMED_PREFIX) }
                    ?: TrackStats.sessionName(items, s.startedAt ?: s.createdAt,
                        if (!s.road.auto && s.kind != SessionKind.POI) roadText(ctx, s.road) else ctx.getString(R.string.session_no_street),
                        ways = s.road.ways)
                SessionRow(s, title,
                    items.count { it.optString("kind") == "photo" },
                    items.count { it.optString("kind") == "video" && store.videoFile(s.id, it.getString("id")).length() > 0 },
                    TrackStats.of(store.trackFile(s.id), s.settings.maxAccuracyM),
                    store.dir(s.id).walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    items.count { (it.has("placeLat") && !it.isNull("placeLat")) || (it.has("relocatedLat") && !it.isNull("relocatedLat")) },
                    pois.size, if (s.kind == SessionKind.SWEEP) store.marks(s.id).size else 0)
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

    val list = rows
    Box(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding()) {
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list != null) {
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
        items(list ?: emptyList(), key = { it.s.id }) { r ->
            SessionCard(r, active == r.s.id, busy == r.s.id,
                onSend = { send(r.s) },
                onMap = { onOpenMap(r.s.id, r.title) },
                onSave = { saving = r.s; saveLauncher.launch(Exporter.fileName(r.s)) },
                onDelete = { confirmDel = r.s })
        }
    }
    }
    if (list != null && list.isNotEmpty()) androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onNew, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).height(60.dp),
        containerColor = Palette.Green, contentColor = Color.White,
        icon = { Icon(Icons.Filled.Add, null) },
        text = { Text(stringResource(R.string.home_new_session), fontSize = 18.sp, fontWeight = FontWeight.Bold) })
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
                    withContext(Dispatchers.IO) { store.delete(s.id) }
                    msg = ctx.getString(R.string.sessions_deleted)
                    reload++
                }
            }) { Text(stringResource(R.string.sessions_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text(stringResource(R.string.sessions_keep)) } },
        )
    }
}

@Composable
private fun SessionCard(r: SessionRow, recording: Boolean, busy: Boolean,
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
                        if (!empty) DropdownMenuItem({ Text(stringResource(R.string.sessions_save_zip)) }, { menu = false; onSave() })
                        DropdownMenuItem({ Text(stringResource(R.string.sessions_delete_session), color = Palette.Red) }, { menu = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    recording -> Badge(stringResource(R.string.sessions_badge_recording), Palette.Blue, Color(0xFFD1E9FF))
                    empty -> Badge(stringResource(R.string.sessions_badge_empty), Palette.Muted, Color(0xFFF2F4F7))
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
                if (s.kind == SessionKind.POI) null else ctx.getString(when (s.mode) {
                    Mode.PHOTO_AUTO -> R.string.sessions_mode_auto; Mode.PHOTO_MANUAL -> R.string.sessions_mode_manual; Mode.VIDEO -> R.string.sessions_mode_video }),
                if (r.relocated > 0) ctx.plural(R.plurals.sessions_relocated, r.relocated) else null,
                if (s.endedAt == null && !recording) ctx.getString(R.string.sessions_interrupted) else null,
            ).joinToString(" · "), color = if (s.endedAt == null && !recording) Palette.Amber else Palette.Muted)
            if (!empty && !recording) {
                Row(Modifier.padding(top = 6.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.exportedAt == null) {
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
