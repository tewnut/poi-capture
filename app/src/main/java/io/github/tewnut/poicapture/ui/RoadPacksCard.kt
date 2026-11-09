package io.github.tewnut.poicapture.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.capture.RoadPackCatalogue
import io.github.tewnut.poicapture.capture.RoadPackDownloads
import io.github.tewnut.poicapture.net.RoadSource
import java.io.File
import java.util.Locale

/**
 * "Dữ liệu đường offline": road packs per region, so the road is recognised without network (and without the public
 * Overpass servers). Installed packs, the catalogue (index.json from a URL), and importing a pack file shared by hand.
 */
@Composable
fun RoadPacksCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Prefs.settings(ctx).roadPackIndexUrl) }
    var catalogue by remember { mutableStateOf(RoadSource.cachedCatalogue(ctx)) }
    var installed by remember { mutableStateOf(RoadSource.packs(ctx).installed().map { Triple(it.id, it.name, it.version) to it.file.length() }) }
    var msg by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    fun reloadInstalled() { installed = RoadSource.packs(ctx).installed().map { Triple(it.id, it.name, it.version) to it.file.length() } }
    LaunchedEffect(refresh) { reloadInstalled() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = "Đang nhập gói…"
        scope.launch {
            msg = try {
                val p = withContext(Dispatchers.IO) {
                    val tmp = File(RoadSource.packs(ctx).dir.apply { mkdirs() }, ".import.part")
                    ctx.contentResolver.openInputStream(uri)!!.use { s -> tmp.outputStream().use { s.copyTo(it) } }
                    RoadSource.packs(ctx).install(tmp)
                }
                reloadInstalled()
                "Đã cài ${p.name} (${p.version})."
            } catch (e: Exception) { "Không nhập được: ${e.message}" }
        }
    }

    Hint("Tải trước dữ liệu đường theo vùng để app nhận tên đường ngay cả khi không có mạng. " +
        "Ngoài vùng đã tải, app vẫn lấy từ OpenStreetMap khi có mạng và nhớ lại trên máy.")

    // installed
    if (installed.isEmpty()) Hint("Chưa có gói nào trên máy.", color = Palette.Amber)
    for ((info, bytes) in installed) {
        val (id, name, version) = info
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Hint("Đã cài · ${sizeMb(bytes)} · dữ liệu ngày $version")
            }
            TextButton(onClick = { confirmDelete = Triple(id, name, bytes) }) { Text("Xoá", color = Palette.Red) }
        }
    }

    // catalogue
    OutlinedTextField(url, { url = it.trim(); Prefs.saveSettings(ctx, Prefs.settings(ctx).copy(roadPackIndexUrl = url)) },
        label = { Text("Nguồn gói (địa chỉ index.json)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Column {
        OutlinedButton(enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            msg = "Đang tải danh sách…"
            scope.launch {
                msg = try {
                    catalogue = withContext(Dispatchers.IO) { RoadPackCatalogue.fetch(ctx, url) }
                    "Có ${catalogue.size} gói."
                } catch (e: Exception) { "Không tải được danh sách: ${e.message}" }
            }
        }) { Text("Tải danh sách") }
        TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Nhập file gói (Zalo, Drive…)") }
    }
    if (msg.isNotEmpty()) Hint(msg, color = if (msg.startsWith("Không")) Palette.Red else Palette.Blue)
    for (e in catalogue) {
        val have = installed.firstOrNull { it.first.first == e.id }?.first?.third
        PackRow(e, have, anyNetwork = true, onInstalled = { refresh++ })   // asked for now, size shown: 4G is fine
    }
    Hint("Dữ liệu đường © OpenStreetMap contributors (ODbL), qua Geofabrik.")

    confirmDelete?.let { (id, name, bytes) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xoá gói “$name”?") },
            text = { Text("Giải phóng ${sizeMb(bytes)}. Muốn dùng lại phải tải lại gói.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch { withContext(Dispatchers.IO) { RoadSource.packs(ctx).delete(id) }; refresh++ }
            }) { Text("Xoá", color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Không") } },
        )
    }
}

/**
 * One pack of the catalogue: name, size, date, and Tải / Cập nhật / Huỷ with the download progress.
 * [have] = version installed on the phone (null = none). [anyNetwork] = download now even on 4G (the size is shown).
 */
@Composable
fun PackRow(e: RoadSource.Entry, have: String?, anyNetwork: Boolean, onInstalled: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val work by RoadPackDownloads.flow(ctx, e.id).collectAsState(initial = emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(work.firstOrNull()?.state) { if (work.firstOrNull()?.state == WorkInfo.State.SUCCEEDED) onInstalled() }
    Column(modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Hint("${sizeMb(e.bytes)} · dữ liệu ngày ${e.version}" + when {
                    have == null -> ""
                    have == e.version -> " · đã có ✓"
                    else -> " · máy đang có bản $have"
                })
            }
            when {
                running != null -> TextButton(onClick = { RoadPackDownloads.cancel(ctx, e.id) }) { Text("Huỷ") }
                have == e.version -> {}
                else -> Button(onClick = { RoadPackDownloads.enqueue(ctx, e, anyNetwork) }) { Text(if (have == null) "Tải" else "Cập nhật") }
            }
        }
        if (running != null) {
            val done = running.progress.getLong("done", 0)
            if (running.state == WorkInfo.State.ENQUEUED && done == 0L) Hint(if (anyNetwork) "Đang chờ mạng…" else "Đang chờ Wi-Fi…")
            else LinearProgressIndicator(progress = { if (e.bytes > 0) (done.toFloat() / e.bytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        work.firstOrNull { it.state == WorkInfo.State.FAILED }?.let { Hint("Tải lỗi: ${it.outputData.getString("error")}", color = Palette.Red) }
    }
}

fun sizeMb(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
