package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.NetworkType
import androidx.work.WorkInfo
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.UploadServer
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.core.Tus
import io.github.tewnut.poicapture.core.UploadStatus
import io.github.tewnut.poicapture.net.TusClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The upload server's settings (Settings → Upload server, and the onboarding step): tus endpoint, optional token,
 * a connection test (OPTIONS), and automatic uploads. Saved as typed; [onUrlChange] after each change of the address.
 */
@Composable
fun UploadServerCard(onUrlChange: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(UploadServer.url(ctx)) }
    var token by remember { mutableStateOf(UploadServer.token(ctx)) }
    var showToken by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(UploadServer.auto(ctx)) }
    var wifi by remember { mutableStateOf(UploadServer.wifiOnly(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // text, ok
    val valid = Tus.endpoint(url) != null

    OutlinedTextField(url, { url = it.take(300); UploadServer.setUrl(ctx, url); result = null; onUrlChange() },
        label = { Text(stringResource(R.string.upload_url_label)) },
        placeholder = { Text("https://example.org/files/") }, singleLine = true,
        isError = url.isNotBlank() && !valid,
        supportingText = if (url.isNotBlank() && !valid) { { Text(stringResource(R.string.upload_url_invalid)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    if (valid && url.trim().startsWith("http://", ignoreCase = true)) Hint(stringResource(R.string.upload_http_warning), color = Palette.Amber)
    OutlinedTextField(token, { token = it.take(500); UploadServer.setToken(ctx, token); result = null },
        label = { Text(stringResource(R.string.upload_token_label)) }, singleLine = true,
        supportingText = { Text(stringResource(R.string.upload_token_help)) },
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = if (token.isNotEmpty()) { { TextButton(onClick = { showToken = !showToken }) {
            Text(stringResource(if (showToken) R.string.upload_token_hide else R.string.upload_token_show)) } } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    if (valid && token.isBlank() && Tus.endpoint(url) == Tus.endpoint(UploadServer.DEFAULT_URL))
        Hint(stringResource(R.string.upload_default_needs_token), color = Palette.Amber)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = valid && !testing, onClick = {
            testing = true; result = null
            scope.launch {
                result = try {
                    val info = withContext(Dispatchers.IO) { TusClient(url, token).options() }
                    when {
                        info.versions.isEmpty() -> ctx.getString(R.string.upload_test_not_tus) to false
                        !info.supports100 || !info.canCreate -> ctx.getString(R.string.upload_test_no_create, info.versions.joinToString()) to false
                        else -> ctx.getString(R.string.upload_test_ok, info.versions.joinToString(), info.extensions.joinToString(", ")) to true
                    }
                } catch (e: TusClient.HttpError) {
                    ctx.getString(if (e.code == 401 || e.code == 403) R.string.upload_test_token else R.string.upload_test_http, e.code) to false
                } catch (e: Exception) {
                    ctx.getString(R.string.upload_test_failed, errorText(ctx, e)) to false
                }
                testing = false
            }
        }) { Text(stringResource(if (testing) R.string.upload_testing else R.string.upload_test)) }
        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    result?.let { (text, ok) -> Hint(text, color = if (ok) Palette.Green else Palette.Red) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_auto), modifier = Modifier.weight(1f), color = Palette.Ink)
        Switch(auto, { auto = it; UploadServer.setAuto(ctx, it) })
    }
    if (auto) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_wifi), modifier = Modifier.weight(1f), color = Palette.Ink)
        Switch(wifi, { wifi = it; UploadServer.setWifiOnly(ctx, it) })
    }
    Hint(stringResource(R.string.upload_never_recording))
}

/**
 * One session's upload on its card: not uploaded / waiting / uploading n % / uploaded / failed, with Upload or Retry.
 * Shown only when an upload server is set.
 */
@Composable
fun UploadLine(sessionId: String) {
    val ctx = LocalContext.current
    val changed by Uploads.changed.collectAsState()
    val rec = remember(sessionId, changed) { Uploads.record(ctx, sessionId) }
    val infos by remember(sessionId) { Uploads.flow(ctx, sessionId) }.collectAsState(initial = emptyList())
    val work = infos.firstOrNull { !it.state.isFinished }
    val running = work?.state == WorkInfo.State.RUNNING
    val done = work?.progress?.getLong("done", -1L)?.takeIf { it >= 0 } ?: rec.offset
    val total = work?.progress?.getLong("total", 0L)?.takeIf { it > 0 } ?: rec.size
    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0

    val (text, color) = when {
        running -> stringResource(R.string.upload_status_uploading, pct) to Palette.Blue
        work != null && rec.error != null -> stringResource(R.string.upload_status_retrying, rec.error) to Palette.Amber
        work != null && work.constraints.requiredNetworkType == NetworkType.UNMETERED ->
            stringResource(R.string.upload_status_wifi) to Palette.Muted
        work != null -> stringResource(R.string.upload_status_waiting) to Palette.Muted
        rec.status == UploadStatus.DONE -> stringResource(R.string.upload_status_done, shortTime(rec.uploadedAt)) to Palette.Green
        rec.status == UploadStatus.FAILED -> stringResource(R.string.upload_status_failed, rec.error ?: "") to Palette.Red
        else -> stringResource(R.string.upload_status_none) to Palette.Muted
    }
    Column(Modifier.padding(top = 6.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☁ $text", fontSize = 13.sp, color = color, modifier = Modifier.weight(1f), maxLines = 2)
            val action = when {
                running -> null
                work != null -> R.string.upload_now          // waiting for Wi-Fi or a retry: go now, any network
                rec.status == UploadStatus.FAILED -> R.string.upload_retry
                rec.status == UploadStatus.DONE -> null
                else -> R.string.upload_action
            }
            if (action != null) TextButton(onClick = { Uploads.enqueue(ctx, sessionId, manual = true) },
                modifier = Modifier.height(36.dp)) { Text(stringResource(action)) }
        }
        if (running) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

private fun shortTime(isoTime: String?): String = isoTime?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: ""
