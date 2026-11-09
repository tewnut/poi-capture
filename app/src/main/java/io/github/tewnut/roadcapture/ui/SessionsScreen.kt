package io.github.tewnut.roadcapture.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tewnut.roadcapture.capture.CaptureService
import io.github.tewnut.roadcapture.core.Exporter
import io.github.tewnut.roadcapture.core.Mode
import io.github.tewnut.roadcapture.core.Session
import io.github.tewnut.roadcapture.core.UploadState
import io.github.tewnut.roadcapture.capture.ServerApi
import io.github.tewnut.roadcapture.capture.Uploads
import io.github.tewnut.roadcapture.Prefs
import org.json.JSONObject
import kotlinx.coroutines.delay
import androidx.compose.material3.LinearProgressIndicator
import io.github.tewnut.roadcapture.core.SessionKind
import io.github.tewnut.roadcapture.core.Side
import io.github.tewnut.roadcapture.core.TrackStats
import io.github.tewnut.roadcapture.core.Vehicle
import io.github.tewnut.roadcapture.core.iso
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
                    s.kind == SessionKind.POI -> names.take(2).joinToString(", ").ifEmpty { "${pois.size} địa điểm" }
                    s.road.auto -> TrackStats.mainRoads(items).joinToString(", ").ifEmpty { "Tự động theo GPS" }
                    else -> s.road.label
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
        msg = "Đang đóng gói…"
        scope.launch {
            msg = try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { Exporter.writeZip(store, s.id, it) } }
                markExported(s)
                "Đã lưu ${Exporter.fileName(s)}."
            } catch (e: Exception) { "Lưu không được: ${e.message}" }
            busy = null
        }
    }

    fun send(s: Session) {
        busy = s.id
        msg = "Đang đóng gói…"
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    File(dir, Exporter.fileName(s)).also { f -> f.outputStream().use { Exporter.writeZip(store, s.id, it) } }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Gửi ${f.name}"))
                markExported(s)
                msg = "Đã đóng gói ${mb(f.length())}. Chọn Zalo, Drive, email… để gửi."
            } catch (e: Exception) { msg = "Không đóng gói được: ${e.message}" }
            busy = null
        }
    }

    val list = rows
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Đã ghi", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                if (list != null && configured) {
                    val pending = list.filter { it.photos + it.videos > 0 && active != it.s.id &&
                        uploads[it.s.id]?.let { (u, changed) -> u.state != "uploaded" || changed } != false }
                    Text(
                        if (pending.isEmpty()) "Mọi phiên đã được tải lên máy chủ."
                        else "${pending.size} phiên chưa tải lên · ${mb(pending.sumOf { it.bytes })}",
                        color = if (pending.isEmpty()) Palette.Green else Palette.Amber, fontWeight = FontWeight.SemiBold,
                    )
                    Hint((if (wifiOnly) "Tự tải lên khi có Wi-Fi" else "Tự tải lên khi có mạng (cả 4G)") +
                        ", tiếp tục được nếu mất mạng giữa chừng. Còn trống ${mb(store.root.apply { mkdirs() }.usableSpace)}.")
                } else if (list != null) {
                    val unsent = list.filter { it.s.exportedAt == null && it.photos + it.videos > 0 && active != it.s.id }
                    Text(
                        if (unsent.isEmpty()) "Không có phiên nào chờ gửi."
                        else "${unsent.size} phiên chưa gửi · ${km(unsent.sumOf { it.stats.distanceM })} · ${mb(unsent.sumOf { it.bytes })}",
                        color = if (unsent.isEmpty()) Palette.Muted else Palette.Amber, fontWeight = FontWeight.SemiBold,
                    )
                    Hint("Dữ liệu chỉ nằm trong máy tới khi bạn gửi. Còn trống ${mb(store.root.apply { mkdirs() }.usableSpace)}.")
                }
                if (msg.isNotEmpty()) Hint(msg, color = Palette.Green)
            }
        }
        if (list != null && list.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Chưa có phiên ghi nào.", color = Palette.Muted)
                    Button(onClick = onNew) { Text("Ghi phiên đầu tiên") }
                }
            }
        }
        items(list ?: emptyList(), key = { it.s.id }) { r ->
            SessionCard(r, active == r.s.id, busy == r.s.id, if (configured) uploads[r.s.id] else null, agent[r.s.id], wifiOnly,
                onUpload = { scope.launch { withContext(Dispatchers.IO) { Uploads.enqueue(ctx, r.s.id) }; msg = "Đã đưa vào hàng chờ tải lên." } },
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
            title = { Text("Xoá phiên này?") },
            text = { Text(if (s.exportedAt == null) "Phiên CHƯA được gửi. Ảnh và video sẽ mất hẳn." else "Ảnh và video trong máy sẽ bị xoá.") },
            confirmButton = { TextButton(onClick = { store.delete(s.id); Uploads.forget(ctx, s.id); confirmDel = null; msg = "Đã xoá phiên."; reload++ }) { Text("Xoá", color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("Không") } },
        )
    }
}

@Composable
private fun SessionCard(r: SessionRow, recording: Boolean, busy: Boolean, upload: Pair<UploadState, Boolean>?, agentStatus: JSONObject?,
                        wifiOnly: Boolean, onUpload: () -> Unit,
                        onSend: () -> Unit, onMap: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val s = r.s
    val empty = r.photos + r.videos == 0
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val u = upload?.first
                when {
                    recording -> Badge("Đang ghi", Palette.Blue, Color(0xFFD1E9FF))
                    empty -> Badge("Trống", Palette.Muted, Color(0xFFF2F4F7))
                    upload != null && upload.second -> Badge("Cần tải lại", Palette.Amber, Palette.AmberLight)
                    u?.state == "uploaded" -> Badge("Đã tải lên", Palette.Green, Palette.GreenLight)
                    u?.state == "uploading" -> Badge("Đang tải ${(u.progress * 100).toInt()}%", Palette.Blue, Color(0xFFD1E9FF))
                    u?.state == "queued" -> Badge(if (wifiOnly) "Chờ Wi-Fi" else "Chờ mạng", Palette.Amber, Palette.AmberLight)
                    u?.state == "failed" -> Badge("Lỗi tải, sẽ thử lại", Palette.Red, Color(0xFFFEE4E2))
                    upload != null -> Badge("Chưa tải lên", Palette.Amber, Palette.AmberLight)
                    s.exportedAt != null -> Badge("Đã gửi", Palette.Green, Palette.GreenLight)
                    else -> Badge("Chưa gửi", Palette.Amber, Palette.AmberLight)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !recording && !busy) { Icon(Icons.Filled.MoreVert, "Thêm") }
                    DropdownMenu(menu, { menu = false }) {
                        if (!empty && upload != null) DropdownMenuItem({ Text("Gửi qua ứng dụng khác") }, { menu = false; onSend() })
                        if (!empty) DropdownMenuItem({ Text("Lưu file zip vào máy") }, { menu = false; onSave() })
                        DropdownMenuItem({ Text("Xoá phiên", color = Palette.Red) }, { menu = false; onDelete() })
                    }
                }
            }
            Hint(listOfNotNull(
                fmt(s.startedAt ?: s.createdAt),
                duration(r.stats.durationMs).takeIf { r.stats.durationMs > 0 },
                km(r.stats.distanceM).takeIf { r.stats.distanceM > 0 },
                if (s.kind == SessionKind.POI) "${r.pois} địa điểm" else null,
                if (s.mode == Mode.VIDEO) "${r.videos} đoạn video" else "${r.photos} ảnh",
                if (r.marks > 0) "${r.marks} đánh dấu" else null,
            ).joinToString(" · "))
            Hint(listOfNotNull(
                if (s.kind == SessionKind.POI) "Chụp địa điểm" else "Quét đường",
                if (s.kind == SessionKind.POI) null else (if (s.sideOfTravel == Side.RIGHT) "Bên phải" else "Bên trái") + (if (s.mount != null) " (đã đo)" else ""),
                when (s.vehicle) { Vehicle.CAR -> "Ô tô"; Vehicle.MOTORBIKE -> "Xe máy"; else -> null },
                if (s.kind == SessionKind.POI) null else when (s.mode) { Mode.PHOTO_AUTO -> "ảnh tự động"; Mode.PHOTO_MANUAL -> "ảnh bấm tay"; Mode.VIDEO -> "video" },
                if (r.relocated > 0) "${r.relocated} ảnh đã sửa vị trí" else null,
                if (s.endedAt == null && !recording) "bị gián đoạn (vẫn gửi được)" else null,
            ).joinToString(" · "), color = if (s.endedAt == null && !recording) Palette.Amber else Palette.Muted)
            upload?.first?.let { u ->
                when {
                    u.state == "uploading" || (u.state == "failed" && u.offset > 0) ->
                        LinearProgressIndicator(progress = { u.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp))
                    else -> {}
                }
                if (u.state == "failed" && u.error != null) Hint("Lỗi: ${u.error}", color = Palette.Red)
            }
            agentStatus?.let { a ->
                Hint(when (a.optString("state")) {
                    "done" -> "Agent đã xử lý" + (if (a.has("poisFound")) " · ${a.optInt("poisFound")} địa điểm" else "") +
                        (a.optJSONArray("recapture")?.length()?.takeIf { it > 0 }?.let { " · $it cần chụp lại" } ?: "")
                    "processing" -> "Agent đang xử lý…"
                    "failed" -> "Agent xử lý lỗi: ${a.optString("error")}"
                    else -> "Máy chủ đã nhận"
                }, color = Palette.Blue)
            }
            if (!empty && !recording) {
                Row(Modifier.padding(top = 6.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (upload != null) {
                        val (u, changed) = upload
                        if (u.state == "none" || u.state == "failed" || changed) {
                            Button(onClick = onUpload) { Text(if (changed) "Tải lại" else "Tải lên ngay") }
                        }
                    } else if (s.exportedAt == null) {
                        Button(onClick = onSend, enabled = !busy) { Text(if (busy) "Đang đóng gói…" else "Gửi") }
                    } else {
                        OutlinedButton(onClick = onSend, enabled = !busy) { Text(if (busy) "Đang đóng gói…" else "Gửi lại") }
                    }
                    if (r.photos > 0) OutlinedButton(onClick = onMap) { Text("Bản đồ") }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) =
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))

private fun mb(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
private fun km(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)
private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min < 1) "${ms / 1000} giây" else if (min < 60) "$min phút" else "${min / 60} giờ ${min % 60} phút"
}
private fun fmt(isoTime: String?): String = isoTime?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: "–"
