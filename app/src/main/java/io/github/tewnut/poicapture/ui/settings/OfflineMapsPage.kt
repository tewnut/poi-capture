package io.github.tewnut.poicapture.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.RoadPackCatalogue
import io.github.tewnut.poicapture.capture.RoadPackDownloads
import io.github.tewnut.poicapture.net.RoadSource
import io.github.tewnut.poicapture.ui.Hint
import io.github.tewnut.poicapture.ui.Palette
import io.github.tewnut.poicapture.ui.PackRow
import io.github.tewnut.poicapture.ui.errorText
import io.github.tewnut.poicapture.ui.lastKnownPosition
import io.github.tewnut.poicapture.ui.sizeMb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A pack on the phone, as listed on the page. */
internal data class InstalledPack(val id: String, val name: String, val version: String, val bytes: Long, val downloadedMs: Long)

internal fun installedPacks(ctx: Context): List<InstalledPack> = RoadSource.packs(ctx).installed()
    .map { InstalledPack(it.id, it.name, it.version, it.file.length(), it.file.lastModified()) }
    .sortedBy { SettingsLogic.fold(it.name) }

/**
 * Settings → Offline maps: road packs per region, so the road is recognised without network (and without the public
 * Overpass servers). Storage used and "update all"; the regions on the phone (update, delete); adding a region from the
 * catalogue (search, by part of the country, a suggestion where the phone is); the catalogue's address and importing a
 * pack file shared by hand. Downloads go through [RoadPackDownloads] as everywhere else.
 */
@Composable
fun OfflineMapsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Prefs.settings(ctx).roadPackIndexUrl) }
    var catalogue by remember { mutableStateOf(RoadSource.cachedCatalogue(ctx)) }
    var installed by remember { mutableStateOf(installedPacks(ctx)) }
    var msg by remember { mutableStateOf("") }
    var msgError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<InstalledPack?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<SettingsLogic.Zone>()) }
    var sourceOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refresh) { installed = withContext(Dispatchers.IO) { installedPacks(ctx) } }

    fun loadList() {
        if (url.isBlank()) { sourceOpen = true; return }
        msg = ctx.getString(R.string.packs_loading_list); msgError = false; loading = true
        scope.launch {
            msg = try {
                catalogue = withContext(Dispatchers.IO) { RoadPackCatalogue.fetch(ctx, url) }
                ctx.resources.getQuantityString(R.plurals.packs_count, catalogue.size, catalogue.size)
            } catch (e: Exception) { msgError = true; ctx.getString(R.string.packs_list_failed, errorText(ctx, e)) }
            loading = false
        }
    }

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
                refresh++
                ctx.getString(R.string.packs_import_done, p.name, p.version)
            } catch (e: Exception) { msgError = true; ctx.getString(R.string.packs_import_failed, errorText(ctx, e)) }
        }
    }

    val have = installed.associate { it.id to it.version }
    val updates = SettingsLogic.updates(have, catalogue)
    val storage = SettingsLogic.storage(installed.map { it.bytes })
    val notInstalled = catalogue.filter { it.id !in have }
    val here = remember { lastKnownPosition(ctx) }
    val suggestion = here?.let { RoadSource.suggest(catalogue, it.lat, it.lon) }?.takeIf { it.id !in have }
    val groups = SettingsLogic.group(notInstalled, query)
    val searching = query.isNotBlank()

    SettingsPage(stringResource(R.string.settings_maps_title), onBack) {
        // ---- storage used, update all
        item(key = "head") {
            WhiteBox {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconCircle("🗺️")
                    Column(Modifier.weight(1f)) {
                        Text(SettingsLogic.formatBytes(storage.bytes), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Palette.Ink)
                        Hint(if (storage.count == 0) stringResource(R.string.settings_maps_none)
                             else pluralStringResource(R.plurals.maps_on_phone, storage.count, storage.count))
                    }
                }
                if (updates.isNotEmpty()) Button(onClick = { for (e in updates) RoadPackDownloads.enqueue(ctx, e, anyNetwork = true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Green)) {
                    Text(stringResource(R.string.maps_update_all) + " (" + updates.size + ")")
                }
                Hint(stringResource(R.string.packs_intro))
            }
        }
        if (msg.isNotEmpty()) item(key = "msg") { Hint(msg, Modifier.padding(horizontal = 4.dp), color = if (msgError) Palette.Red else Palette.Blue) }

        // ---- on the phone
        item(key = "downloaded") { SectionLabel(stringResource(R.string.maps_downloaded)) }
        if (installed.isEmpty()) item(key = "none") { Hint(stringResource(R.string.packs_none), Modifier.padding(horizontal = 4.dp), color = Palette.Amber) }
        items(installed, key = { "i-" + it.id }) { p ->
            InstalledRow(p, catalogue.firstOrNull { it.id == p.id && it.version != p.version && it.version.isNotBlank() },
                onDelete = { confirmDelete = p }, onInstalled = { refresh++ })
        }

        // ---- add a region
        item(key = "add") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.maps_add), Modifier.weight(1f))
                TextButton(enabled = !loading, onClick = ::loadList) { Text(stringResource(R.string.maps_refresh)) }
            }
        }
        if (catalogue.isEmpty()) item(key = "nolist") {
            WhiteBox {
                Hint(stringResource(R.string.maps_no_list))
                OutlinedButton(enabled = !loading, onClick = ::loadList, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.packs_load_list))
                }
            }
        } else {
            item(key = "search") {
                OutlinedTextField(query, { query = it.take(60) }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.maps_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Muted) },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, stringResource(R.string.maps_search_clear)) }
                    } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }
            if (suggestion != null && !searching) item(key = "suggest") {
                WhiteBox {
                    Text(stringResource(R.string.maps_suggested), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.Green)
                    PackRow(suggestion, null, anyNetwork = true, onInstalled = { refresh++ })
                }
            }
            if (notInstalled.isEmpty()) item(key = "all") { Hint(stringResource(R.string.maps_all_downloaded), Modifier.padding(horizontal = 4.dp)) }
            else if (groups.isEmpty()) item(key = "nomatch") { Hint(stringResource(R.string.maps_no_match, query.trim()), Modifier.padding(horizontal = 4.dp)) }
            for ((zone, list) in groups) {
                val expanded = searching || zone in open
                item(key = "z-" + zone.name) {
                    ZoneHeader(zone, list.size, expanded, enabled = !searching) {
                        open = if (zone in open) open - zone else open + zone
                    }
                }
                if (expanded) items(list, key = { "c-" + it.id }) { e ->
                    WhiteBox(Modifier.padding(start = 8.dp)) { PackRow(e, null, anyNetwork = true, onInstalled = { refresh++ }) }
                }
            }
        }

        // ---- the catalogue's address, a pack file by hand
        item(key = "source") {
            WhiteBox(padding = false) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { sourceOpen = !sourceOpen }
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.maps_source), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    Chevron(sourceOpen)
                }
                if (sourceOpen) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it.trim(); Prefs.saveSettings(ctx, Prefs.settings(ctx).copy(roadPackIndexUrl = url)) },
                        label = { Text(stringResource(R.string.packs_source_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = url.isNotBlank() && !loading, onClick = ::loadList,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.packs_load_list)) }
                    TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.packs_import_file)) }
                }
            }
        }
        item(key = "attribution") { Hint(stringResource(R.string.packs_attribution), Modifier.padding(horizontal = 4.dp)) }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.packs_delete_title, p.name)) },
            text = { Text(stringResource(R.string.packs_delete_text, sizeMb(p.bytes))) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch { withContext(Dispatchers.IO) { RoadSource.packs(ctx).delete(p.id) }; refresh++ }
            }) { Text(stringResource(R.string.packs_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.packs_keep)) } },
        )
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.Muted, modifier = Modifier.rotate(turn))
}

/** A part of the country: its name and how many regions, folded or open. */
@Composable
private fun ZoneHeader(zone: SettingsLogic.Zone, count: Int, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name = stringResource(when (zone) {
        SettingsLogic.Zone.NORTH -> R.string.maps_zone_north
        SettingsLogic.Zone.CENTRAL -> R.string.maps_zone_central
        SettingsLogic.Zone.SOUTH -> R.string.maps_zone_south
    })
    WhiteBox(padding = false) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
            Text(pluralStringResource(R.plurals.maps_regions, count, count), fontSize = 13.sp, color = Palette.Muted,
                modifier = Modifier.padding(end = 8.dp))
            Chevron(expanded)
        }
    }
}

/**
 * A region on the phone: name, size, data date and download date; Update (when the catalogue has newer data, with the
 * download's progress) and Delete (asks first).
 */
@Composable
private fun InstalledRow(p: InstalledPack, newer: RoadSource.Entry?, onDelete: () -> Unit, onInstalled: () -> Unit) {
    val ctx = LocalContext.current
    val work by RoadPackDownloads.flow(ctx, p.id).collectAsState(initial = emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(work.firstOrNull()?.state) { if (work.firstOrNull()?.state == WorkInfo.State.SUCCEEDED) onInstalled() }
    val downloaded = remember(p.downloadedMs) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(p.downloadedMs))
    }
    WhiteBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(stringResource(R.string.maps_installed_line, SettingsLogic.formatBytes(p.bytes), p.version, downloaded))
                if (newer != null) Hint(stringResource(R.string.maps_newer, newer.version, SettingsLogic.formatBytes(newer.bytes)), color = Palette.Blue)
            }
            when {
                running != null -> TextButton(onClick = { RoadPackDownloads.cancel(ctx, p.id) }) { Text(stringResource(R.string.packs_cancel)) }
                newer != null -> Button(onClick = { RoadPackDownloads.enqueue(ctx, newer, anyNetwork = true) }) {
                    Text(stringResource(R.string.packs_update))
                }
            }
            if (running == null) IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, stringResource(R.string.packs_delete), tint = Palette.Red)
            }
        }
        if (running != null && newer != null) {
            val done = running.progress.getLong("done", 0)
            if (running.state == WorkInfo.State.ENQUEUED && done == 0L) Hint(stringResource(R.string.packs_waiting_network))
            else LinearProgressIndicator(progress = { if (newer.bytes > 0) (done.toFloat() / newer.bytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth())
        }
        work.firstOrNull { it.state == WorkInfo.State.FAILED }?.let {
            Hint(stringResource(R.string.packs_download_failed, it.outputData.getString("error").orEmpty()), color = Palette.Red)
        }
    }
}
