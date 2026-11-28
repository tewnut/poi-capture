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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.UploadServer
import io.github.tewnut.poicapture.capture.Uploads
import io.github.tewnut.poicapture.core.CloudMe
import io.github.tewnut.poicapture.core.SetupLink
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
 * The upload server's settings (Settings → Upload server, and the onboarding step): Scan setup QR, tus endpoint,
 * optional token, a connection test (OPTIONS, then who the token is from GET /me), and automatic uploads. Saved as
 * typed; [onChange] after each change of the address or the token.
 */
@Composable
fun UploadServerCard(onChange: () -> Unit = {}, keyFirst: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(UploadServer.url(ctx)) }
    var token by remember { mutableStateOf(UploadServer.token(ctx)) }
    var showToken by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(UploadServer.auto(ctx)) }
    var wifi by remember { mutableStateOf(UploadServer.wifiOnly(ctx)) }
    var removeAfter by remember { mutableStateOf(UploadServer.removeAfter(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // text, ok
    var noUpload by remember { mutableStateOf(false) }   // the tested token lacks the upload scope
    var scanned by remember { mutableStateOf<Pair<String, Boolean>?>(null) }  // what the last scan did, ok
    val valid = Tus.endpoint(url) != null
    // keyFirst (the onboarding's access key step): the project's server is one line with Change, the scan comes first
    var editUrl by remember { mutableStateOf(false) }
    /** The project's server answers 401 without its token: the field says so instead of "optional". */
    val projectServer = valid && Tus.endpoint(url) == Tus.endpoint(UploadServer.DEFAULT_URL)

    fun test() {
        testing = true; result = null; noUpload = false
        scope.launch {
            result = try {
                val client = TusClient(url, token)
                // who the token is from and what it may do; no /me: not the project's kind of server
                val me = withContext(Dispatchers.IO) { client.me() }
                if (me == null) ctx.getString(R.string.upload_test_not_server) to false
                else {
                    noUpload = !me.canUpload
                    ctx.getString(R.string.upload_test_me, me.name, scopesLabel(ctx, me)) to true
                }
            } catch (e: TusClient.HttpError) {
                ctx.getString(if (e.code == 401 || e.code == 403) R.string.upload_test_token else R.string.upload_test_http, e.code) to false
            } catch (e: Exception) {
                ctx.getString(R.string.upload_test_failed, errorText(ctx, e)) to false
            }
            testing = false
        }
    }

    val scanClick = {
        scanned = null
        scanSetupQr(ctx, onError = { scanned = it to false }) { text ->
            when (val p = SetupLink.parse(text)) {
                is SetupLink.Parsed.Bad -> scanned = ctx.getString(when (p.problem) {
                    SetupLink.Problem.NOT_HTTP -> R.string.upload_scan_not_setup
                    SetupLink.Problem.NO_TOKEN -> R.string.upload_scan_no_token
                }) to false
                is SetupLink.Parsed.Ok -> {
                    val link = p.link
                    url = link.endpoint; UploadServer.setUrl(ctx, url)
                    token = link.token; UploadServer.setToken(ctx, token)
                    showToken = false
                    scanned = (link.name?.let { ctx.getString(R.string.upload_scan_done, it) }
                        ?: ctx.getString(R.string.upload_scan_done_unnamed)) to true
                    onChange()
                    test()
                }
            }
        }
    }
    if (keyFirst) Button(enabled = !testing, onClick = scanClick, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Green)) { Text(stringResource(R.string.upload_scan), fontSize = 16.sp) }
    else OutlinedButton(enabled = !testing, onClick = scanClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.upload_scan)) }
    scanned?.let { (text, ok) -> Hint(text, color = if (ok) Palette.Green else Palette.Red) }

    val host = remember(url) { runCatching { java.net.URI(url.trim()).host }.getOrNull() }
    if (keyFirst && projectServer && !editUrl && host != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_server_at, host), modifier = Modifier.weight(1f), color = Palette.Muted, fontSize = 13.sp)
        TextButton(onClick = { editUrl = true }) { Text(stringResource(R.string.upload_server_change)) }
    } else OutlinedTextField(url, { url = it.take(300); UploadServer.setUrl(ctx, url); result = null; scanned = null; onChange() },
        label = { Text(stringResource(R.string.upload_url_label)) },
        placeholder = { Text("https://example.org/files/") }, singleLine = true,
        isError = url.isNotBlank() && !valid,
        supportingText = if (url.isNotBlank() && !valid) { { Text(stringResource(R.string.upload_url_invalid)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    if (valid && url.trim().startsWith("http://", ignoreCase = true)) Hint(stringResource(R.string.upload_http_warning), color = Palette.Amber)
    OutlinedTextField(token, { token = it.take(500); UploadServer.setToken(ctx, token); result = null; scanned = null; onChange() },
        label = { Text(stringResource(if (projectServer) R.string.upload_token_label_required else R.string.upload_token_label)) }, singleLine = true,
        supportingText = if (keyFirst) null else { { Text(stringResource(R.string.upload_token_help)) } },   // first run: no details
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = if (token.isNotEmpty()) { { TextButton(onClick = { showToken = !showToken }) {
            Text(stringResource(if (showToken) R.string.upload_token_hide else R.string.upload_token_show)) } } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    if (projectServer && token.isBlank() && !keyFirst)
        Hint(stringResource(R.string.upload_default_needs_token), color = Palette.Amber)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = valid && !testing, onClick = { test() }) { Text(stringResource(if (testing) R.string.upload_testing else R.string.upload_test)) }
        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    result?.let { (text, ok) -> Hint(text, color = if (ok) Palette.Green else Palette.Red) }
    if (result != null && noUpload) Hint(stringResource(R.string.upload_test_no_upload), color = Palette.Amber)
    if (keyFirst) return   // first run: automatic upload stays on; its options are in Settings → Upload server
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_auto), modifier = Modifier.weight(1f), color = Palette.Ink)
        Switch(auto, { auto = it; UploadServer.setAuto(ctx, it) })
    }
    if (auto) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_wifi), modifier = Modifier.weight(1f), color = Palette.Ink)
        Switch(wifi, { wifi = it; UploadServer.setWifiOnly(ctx, it) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.upload_remove_after), modifier = Modifier.weight(1f), color = Palette.Ink)
        Switch(removeAfter, { removeAfter = it; UploadServer.setRemoveAfter(ctx, it) })
    }
    Hint(stringResource(R.string.upload_never_recording))
}

/**
 * Google's code scanner (Play services): its own camera screen, so no camera permission and no camera UI here.
 * [onText] gets the QR's text, [onError] a message to show; a cancel does nothing. The text is never logged: it
 * holds the token.
 */
private fun scanSetupQr(ctx: Context, onError: (String) -> Unit, onText: (String) -> Unit) {
    val error = { e: Exception ->
        onError(if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) ctx.getString(R.string.upload_scan_unavailable)
                else ctx.getString(R.string.upload_scan_failed, e.message ?: e.javaClass.simpleName))
    }
    try {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code -> onText(code.rawValue.orEmpty()) }
            .addOnFailureListener { e -> error(e) }
    } catch (e: Exception) {
        error(e)
    }
}

/** "Send rides, View & download, Admin" in the phone's language; a scope the app does not know as the server sent it. */
private fun scopesLabel(ctx: Context, me: CloudMe): String {
    val labels = me.scopes.map { scope ->
        when (scope) {
            CloudMe.UPLOAD -> ctx.getString(R.string.upload_scope_upload)
            CloudMe.READ -> ctx.getString(R.string.upload_scope_read)
            CloudMe.PROCESS -> ctx.getString(R.string.upload_scope_process)
            CloudMe.DELETE -> ctx.getString(R.string.upload_scope_delete)
            else -> scope
        }
    } + listOfNotNull(if (me.admin) ctx.getString(R.string.upload_scope_admin) else null)
    return labels.ifEmpty { listOf("–") }.joinToString(", ")
}

private fun shortTime(isoTime: String?): String = isoTime?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: ""
