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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
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
    var msgError by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    fun reloadInstalled() { installed = RoadSource.packs(ctx).installed().map { Triple(it.id, it.name, it.version) to it.file.length() } }
    LaunchedEffect(refresh) { reloadInstalled() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = ctx.getString(R.string.packs_importing)
        msgError = false
        scope.launch {
            msg = try {
                val p = withContext(Dispatchers.IO) {
                    val tmp = File(RoadSource.packs(ctx).dir.apply { mkdirs() }, ".import.part")
                    ctx.contentResolver.openInputStream(uri)!!.use { s -> tmp.outputStream().use { s.copyTo(it) } }
                    RoadSource.packs(ctx).install(tmp)
                }
                reloadInstalled()
                ctx.getString(R.string.packs_import_done, p.name, p.version)
            } catch (e: Exception) { msgError = true; ctx.getString(R.string.packs_import_failed, errorText(ctx, e)) }
        }
    }

    Hint(stringResource(R.string.packs_intro))

    // installed
    if (installed.isEmpty()) Hint(stringResource(R.string.packs_none), color = Palette.Amber)
    for ((info, bytes) in installed) {
        val (id, name, version) = info
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Hint(stringResource(R.string.packs_installed_line, sizeMb(bytes), version))
            }
            TextButton(onClick = { confirmDelete = Triple(id, name, bytes) }) { Text(stringResource(R.string.packs_delete), color = Palette.Red) }
        }
    }

    // catalogue
    OutlinedTextField(url, { url = it.trim(); Prefs.saveSettings(ctx, Prefs.settings(ctx).copy(roadPackIndexUrl = url)) },
        label = { Text(stringResource(R.string.packs_source_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Column {
        OutlinedButton(enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            msg = ctx.getString(R.string.packs_loading_list)
            msgError = false
            scope.launch {
                msg = try {
                    catalogue = withContext(Dispatchers.IO) { RoadPackCatalogue.fetch(ctx, url) }
                    ctx.resources.getQuantityString(R.plurals.packs_count, catalogue.size, catalogue.size)
                } catch (e: Exception) { msgError = true; ctx.getString(R.string.packs_list_failed, errorText(ctx, e)) }
            }
        }) { Text(stringResource(R.string.packs_load_list)) }
        TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text(stringResource(R.string.packs_import_file)) }
    }
    if (msg.isNotEmpty()) Hint(msg, color = if (msgError) Palette.Red else Palette.Blue)
    for (e in catalogue) {
        val have = installed.firstOrNull { it.first.first == e.id }?.first?.third
        PackRow(e, have, anyNetwork = true, onInstalled = { refresh++ })   // asked for now, size shown: 4G is fine
    }
    Hint(stringResource(R.string.packs_attribution))

    confirmDelete?.let { (id, name, bytes) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.packs_delete_title, name)) },
            text = { Text(stringResource(R.string.packs_delete_text, sizeMb(bytes))) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch { withContext(Dispatchers.IO) { RoadSource.packs(ctx).delete(id) }; refresh++ }
            }) { Text(stringResource(R.string.packs_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.packs_keep)) } },
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
                Hint(when {
                    have == null -> stringResource(R.string.packs_entry_line, sizeMb(e.bytes), e.version)
                    have == e.version -> stringResource(R.string.packs_entry_line_current, sizeMb(e.bytes), e.version)
                    else -> stringResource(R.string.packs_entry_line_older, sizeMb(e.bytes), e.version, have)
                })
            }
            when {
                running != null -> TextButton(onClick = { RoadPackDownloads.cancel(ctx, e.id) }) { Text(stringResource(R.string.packs_cancel)) }
                have == e.version -> {}
                else -> Button(onClick = { RoadPackDownloads.enqueue(ctx, e, anyNetwork) }) {
                    Text(stringResource(if (have == null) R.string.packs_download else R.string.packs_update))
                }
            }
        }
        if (running != null) {
            val done = running.progress.getLong("done", 0)
            if (running.state == WorkInfo.State.ENQUEUED && done == 0L) Hint(stringResource(if (anyNetwork) R.string.packs_waiting_network else R.string.packs_waiting_wifi))
            else LinearProgressIndicator(progress = { if (e.bytes > 0) (done.toFloat() / e.bytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        work.firstOrNull { it.state == WorkInfo.State.FAILED }?.let { Hint(stringResource(R.string.packs_download_failed, it.outputData.getString("error").orEmpty()), color = Palette.Red) }
    }
}

fun sizeMb(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
