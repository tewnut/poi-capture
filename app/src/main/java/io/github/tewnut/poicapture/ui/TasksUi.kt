package io.github.tewnut.poicapture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.FieldTask
import io.github.tewnut.poicapture.core.FieldTasks
import io.github.tewnut.poicapture.core.Geo
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.iso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Map / list colour of a task. */
fun taskColor(t: FieldTask): Color = when {
    !t.open -> Color(0xFF98A2B3)
    t.removalCheck -> Palette.Red
    else -> Color(0xFFF79009)
}

/** Picks a tasks file (GeoJSON from the reviewer) and imports it; [onResult] gets the message to show. */
@Composable
fun rememberTaskImporter(onResult: (msg: String, error: Boolean) -> Unit): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { s ->
                        val b = s.readNBytes(5 * 1024 * 1024 + 1)
                        if (b.size > 5 * 1024 * 1024) throw FieldTasks.BadFile("too large")
                        String(b, Charsets.UTF_8)
                    }
                    CaptureService.tasks(ctx).import(text)
                }
                onResult(ctx.resources.getQuantityString(R.plurals.tasks_imported, list.count { it.open }, list.count { it.open }), false)
            } catch (e: FieldTasks.BadFile) {
                onResult(ctx.getString(R.string.tasks_bad_file), true)
            } catch (e: Exception) {
                onResult(ctx.getString(R.string.packs_import_failed, errorText(ctx, e)), true)
            }
        }
    }
}

val TASK_FILE_TYPES = arrayOf("application/geo+json", "application/json", "application/octet-stream", "text/plain", "*/*")

/** Settings → Tasks: how many, import a file, clear. */
@Composable
fun TasksCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(CaptureService.tasks(ctx).all()) }
    var msg by remember { mutableStateOf("") }
    var msgError by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val importer = rememberTaskImporter { m, err -> msg = m; msgError = err; tasks = CaptureService.tasks(ctx).all() }
    Hint(stringResource(R.string.tasks_intro))
    if (tasks.isEmpty()) Hint(stringResource(R.string.tasks_none), color = Palette.Amber)
    else Text(stringResource(R.string.tasks_counts, tasks.count { it.open }, tasks.count { !it.open }), fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importer.launch(TASK_FILE_TYPES) }) { Text(stringResource(R.string.tasks_import)) }
        if (tasks.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.tasks_clear), color = Palette.Red) }
    }
    if (msg.isNotEmpty()) Hint(msg, color = if (msgError) Palette.Red else Palette.Blue)
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.tasks_clear_title)) },
        text = { Text(stringResource(R.string.tasks_clear_text)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch { withContext(Dispatchers.IO) { CaptureService.tasks(ctx).clear() }; tasks = emptyList(); msg = "" }
        }) { Text(stringResource(R.string.tasks_clear), color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.packs_keep)) } },
    )
}

/** The task list, closest first; [focus] = a task tapped on the map (shown first). Done / skip / directions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksSheet(me: LatLon?, focus: String?, onChanged: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(CaptureService.tasks(ctx).all()) }
    var showDone by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val importer = rememberTaskImporter { m, _ -> msg = m; tasks = CaptureService.tasks(ctx).all(); onChanged() }
    fun dist(t: FieldTask) = me?.let { Geo.distanceM(it, t.at) }
    fun set(t: FieldTask, state: String) = scope.launch {
        withContext(Dispatchers.IO) { CaptureService.tasks(ctx).set(t.id, state, iso(System.currentTimeMillis()), "contributor") }
        tasks = CaptureService.tasks(ctx).all(); onChanged()
    }
    val shown = tasks.filter { showDone || it.open || it.id == focus }
        .sortedWith(compareBy<FieldTask>({ it.id != focus }, { !it.open }, { dist(it) ?: Double.MAX_VALUE }))
    val list = rememberLazyListState()
    LaunchedEffect(focus) { list.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tasks_title), style = ScreenTitle.copy(fontSize = 22.sp), modifier = Modifier.weight(1f))
                TextButton(onClick = { importer.launch(TASK_FILE_TYPES) }) { Text(stringResource(R.string.tasks_import_short)) }
            }
            Hint(stringResource(R.string.tasks_sheet_hint, FieldTasks.NEAR_M.roundToInt()))
            if (msg.isNotEmpty()) Hint(msg, color = Palette.Blue)
            if (tasks.isEmpty()) Hint(stringResource(R.string.tasks_none), color = Palette.Amber)
            LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.id }) { t ->
                    TaskRow(t, dist(t), t.id == focus,
                        onDone = { set(t, FieldTask.DONE) }, onSkip = { set(t, FieldTask.SKIPPED) }, onReopen = { set(t, FieldTask.OPEN) },
                        onGo = {
                            val label = Uri.encode(t.name.ifBlank { "?" })
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${t.at.lat},${t.at.lon}?q=${t.at.lat},${t.at.lon}($label)"))) }
                        })
                    HorizontalDivider(color = Palette.Line)
                }
            }
            val closed = tasks.count { !it.open }
            if (closed > 0) TextButton(onClick = { showDone = !showDone }) {
                Text(if (showDone) stringResource(R.string.tasks_hide_done) else pluralStringResource(R.plurals.tasks_show_done, closed, closed))
            }
        }
    }
}

@Composable
private fun TaskRow(t: FieldTask, distM: Double?, focused: Boolean, onDone: () -> Unit, onSkip: () -> Unit, onReopen: () -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (focused) Palette.GreenLight.copy(alpha = 0.5f) else Color.Transparent).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).background(taskColor(t), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(t.name.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(stringResource(if (t.removalCheck) R.string.tasks_type_check else R.string.tasks_type_visit) +
                    (distM?.let { " · " + distanceText(it) } ?: "") +
                    when (t.state) { FieldTask.DONE -> " · " + stringResource(R.string.tasks_state_done); FieldTask.SKIPPED -> " · " + stringResource(R.string.tasks_state_skipped); else -> "" })
            }
        }
        if (t.why.isNotBlank()) Text(t.why, color = Palette.Muted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (t.open) Hint(stringResource(if (t.removalCheck) R.string.tasks_how_check else R.string.tasks_how_visit), color = Palette.Blue)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onGo) { Text(stringResource(R.string.tasks_go)) }
            if (t.open) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.tasks_done)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.tasks_skip), color = Palette.Muted) }
            } else TextButton(onClick = onReopen) { Text(stringResource(R.string.tasks_reopen), color = Palette.Muted) }
        }
    }
}

private fun distanceText(m: Double) = if (m < 1000) "${m.roundToInt()} m" else String.format(java.util.Locale.ROOT, "%.1f km", m / 1000)
