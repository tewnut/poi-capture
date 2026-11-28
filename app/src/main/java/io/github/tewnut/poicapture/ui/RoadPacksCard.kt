package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.RoadPackDownloads
import io.github.tewnut.poicapture.net.RoadSource
import java.util.Locale

// The Settings page of offline road data is ui/settings/OfflineMapsPage.kt; the row below is shared with it, the
// onboarding and New session.

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
