package io.github.tewnut.poicapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.NetworkType
import androidx.work.WorkInfo
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.capture.UploadServer
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.core.RideEnd
import io.github.tewnut.poicapture.core.RideUpload
import io.github.tewnut.poicapture.core.TrackStats
import io.github.tewnut.poicapture.core.UploadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * After "Kết thúc và lưu": the ride in numbers (photos, distance, time, signs seen; a 360 ride its track and, with a
 * Theta, the shots taken and skipped) and its upload, live, until Xong goes back to the list.
 */
@Composable
fun RideSummaryScreen(end: RideEnd, onDone: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onDone)
    // distance and time from the track, once; the ride may already be cut into parts by its upload
    val stats by produceState<TrackStats?>(null, end.sessionId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = CaptureService.store(ctx)
                val sessions = store.load(end.sessionId)?.let { listOf(it) } ?: store.list().filter { it.parentId == end.sessionId }
                sessions.map { TrackStats.of(store.trackFile(it.id), it.settings.maxAccuracyM) }.takeIf { it.isNotEmpty() }
                    ?.let { l -> TrackStats(l.sumOf { it.distanceM }, l.sumOf { it.durationMs }, l.sumOf { it.points }) }
            }.getOrNull()
        }
    }
    val (upload, retry) = rememberRideUpload(end.sessionId)

    Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.summary_title), style = ScreenTitle)
            val items = buildList {
                if (!end.cam360) {
                    if (end.videos > 0 && end.photos == 0) add("${end.videos}" to stringResource(R.string.capture_end_stat_videos))
                    else add("${end.photos}" to stringResource(R.string.capture_end_stat_photos))
                }
                stats?.takeIf { it.distanceM > 0 }?.let { add(distanceText(it.distanceM) to stringResource(R.string.capture_end_stat_distance)) }
                stats?.takeIf { it.durationMs > 0 }?.let { add("${(it.durationMs / 60_000).coerceAtLeast(1)}′" to stringResource(R.string.capture_end_stat_minutes)) }
                end.signs?.let { add("$it" to stringResource(R.string.capture_end_stat_signs)) }
                end.thetaShots?.let { add("$it" to stringResource(R.string.summary_theta_shots)) }
                end.thetaSkipped?.let { add("$it" to stringResource(R.string.summary_theta_skipped)) }
            }
            // the ride in numbers, lime on black as in the end dialog; three to a row
            if (items.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Palette.Black, RoundedCornerShape(20.dp)).padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in items.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((value, label) in row) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, color = Palette.Lime, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
            }
            UploadLine(upload, retry)
        }
        Button(onClick = onDone, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.summary_done), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UploadLine(up: RideUpload.State, retry: () -> Unit) {
    val (text, color, action) = when (up.kind) {
        RideUpload.Kind.NO_TOKEN -> Triple(stringResource(R.string.summary_upload_no_token), Palette.Amber, null)
        RideUpload.Kind.NOT_SENT -> Triple(stringResource(R.string.summary_upload_not_sent), Palette.Muted, R.string.sessions_send_now)
        RideUpload.Kind.WAITING -> Triple(stringResource(R.string.summary_upload_waiting), Palette.Muted, null)
        RideUpload.Kind.WIFI -> Triple(stringResource(R.string.summary_upload_wifi), Palette.Muted, R.string.sessions_send_now)
        RideUpload.Kind.SENDING -> Triple(stringResource(R.string.summary_upload_sending, up.pct), Palette.Blue, null)
        RideUpload.Kind.SENT -> Triple(stringResource(R.string.summary_upload_sent), Palette.Green, null)
        RideUpload.Kind.FAILED -> Triple(stringResource(R.string.summary_upload_failed, up.error ?: ""), Palette.Red, R.string.upload_retry)
    }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (action != null) TextButton(onClick = retry) { Text(stringResource(action), fontWeight = FontWeight.Bold) }
        }
        if (up.kind == RideUpload.Kind.SENDING) LinearProgressIndicator(progress = { up.pct / 100f },
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp))
    }
}

/**
 * The ride's upload, live: the record of each session it became (itself, or its street parts) and their background
 * work. Returns the state and "send now" for whatever is not on its way.
 */
@Composable
private fun rememberRideUpload(sessionId: String): Pair<RideUpload.State, () -> Unit> {
    val ctx = LocalContext.current
    val configured = remember { UploadServer.configured(ctx) }
    val changed by Uploads.changed.collectAsState()
    val finished by Uploads.finished.collectAsState()
    val seen = remember { mutableSetOf<String>() }   // parts ever seen: one sent and removed still counts
    var ids by remember { mutableStateOf(listOf(sessionId)) }
    var exists by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var records by remember { mutableStateOf(emptyMap<String, UploadRecord>()) }
    LaunchedEffect(changed, finished) {
        val (i, e, r) = withContext(Dispatchers.IO) {
            val all = runCatching { CaptureService.store(ctx).list() }.getOrDefault(emptyList())
            val partsNow = all.filter { it.parentId == sessionId }.map { it.id }
            val list = RideUpload.ids(sessionId, partsNow, seen)
            val have = all.map { it.id }.toSet()
            Triple(list, list.associateWith { it in have }, list.associateWith { Uploads.record(ctx, it) })
        }
        seen += i.filter { it != sessionId }
        ids = i; exists = e; records = r
    }
    val infos by remember(ids) { combine(ids.map { Uploads.flow(ctx, it) }) { it.toList() } }.collectAsState(initial = emptyList())
    val parts = ids.mapIndexed { k, id ->
        val rec = records[id] ?: UploadRecord(id)
        val work = infos.takeIf { it.size == ids.size }?.get(k)?.firstOrNull { !it.state.isFinished }
        RideUpload.Part(
            exists = exists[id] ?: true,   // not read yet: still there
            status = rec.status, queued = work != null, running = work?.state == WorkInfo.State.RUNNING,
            wifiWait = work != null && work.state != WorkInfo.State.RUNNING && work.constraints.requiredNetworkType == NetworkType.UNMETERED,
            error = rec.error,
            done = work?.progress?.getLong("done", -1L)?.takeIf { it >= 0 } ?: rec.offset,
            total = work?.progress?.getLong("total", 0L)?.takeIf { it > 0 } ?: rec.size)
    }
    val state = RideUpload.of(configured, parts)
    val sendNow = {
        for ((k, p) in parts.withIndex()) {
            val kind = RideUpload.kindOf(p)
            if (kind == RideUpload.Kind.FAILED || kind == RideUpload.Kind.NOT_SENT || kind == RideUpload.Kind.WIFI) Uploads.enqueue(ctx, ids[k], manual = true)
        }
    }
    return state to sendNow
}

private fun distanceText(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)
