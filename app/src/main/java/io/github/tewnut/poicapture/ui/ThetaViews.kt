package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.ThetaLink
import io.github.tewnut.poicapture.core.ThetaOsc
import io.github.tewnut.poicapture.core.ThetaShooter

/** Camera 360 setup with a Ricoh Theta (docs/camera360.md §4): the camera's Wi-Fi, Connect, its state, the spacing. */
@Composable
fun ThetaSetupCard(everyM: Double, onEveryM: (Double) -> Unit) {
    val ctx = LocalContext.current
    var serial by remember { mutableStateOf(Prefs.thetaSerial(ctx)) }
    var password by remember { mutableStateOf(Prefs.thetaPassword(ctx)) }
    val link by ThetaLink.state.collectAsState()
    Column(Modifier.fillMaxWidth().background(Palette.Canvas, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(stringResource(R.string.theta_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(serial, { serial = it.trim() }, Modifier.weight(1.4f), singleLine = true,
                label = { Text(stringResource(R.string.theta_serial), fontSize = 12.sp) },
                placeholder = { Text("THETAYR12345678.OSC", fontSize = 12.sp) })
            OutlinedTextField(password, { password = it.trim() }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.theta_password), fontSize = 12.sp) },
                placeholder = { Text(ThetaOsc.passwordFrom(serial).ifEmpty { "…" }, fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = serial.isNotBlank() && link.status != ThetaLink.Status.CONNECTING, onClick = {
                Prefs.saveThetaWifi(ctx, serial, password)
                ThetaLink.connect(ctx, serial, password)
            }) { Text(stringResource(if (link.status == ThetaLink.Status.CONNECTED) R.string.theta_reconnect else R.string.theta_connect)) }
            ThetaStatus(link, Modifier.weight(1f), dark = false)
        }
        if (serial.isBlank()) Hint(stringResource(R.string.theta_serial_needed), color = Palette.Amber)
        Text(stringResource(R.string.theta_every), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Palette.Muted)
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val opts = listOf(5.0, 8.0, 12.0, 20.0)
            opts.forEachIndexed { i, m ->
                SegmentedButton(selected = everyM == m, onClick = { onEveryM(m) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, opts.size)) { Text("${m.toInt()} m") }
            }
        }
    }
}

/** The link's state in one line: model and battery, connecting, lost, or why it failed. */
@Composable
fun ThetaStatus(link: ThetaLink.Ui, modifier: Modifier = Modifier, dark: Boolean) {
    val (text, color) = when (link.status) {
        ThetaLink.Status.CONNECTED -> (stringResource(R.string.theta_connected, link.model ?: "Theta") +
            (link.batteryPct?.let { " · " + stringResource(R.string.theta_battery, it) } ?: "")) to Color(0xFF47CD89)
        ThetaLink.Status.CONNECTING -> stringResource(R.string.theta_connecting) to Color(0xFFFEC84B)
        ThetaLink.Status.LOST -> stringResource(R.string.theta_lost) to Palette.Red
        ThetaLink.Status.FAILED -> stringResource(R.string.theta_failed, link.error ?: "?") to Palette.Red
        ThetaLink.Status.OFF -> stringResource(R.string.theta_not_connected) to (if (dark) Color.White.copy(alpha = 0.7f) else Palette.Muted)
    }
    Text("● $text", modifier = modifier, color = if (!dark && link.status == ThetaLink.Status.CONNECTED) Palette.Ink else color,
        fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
}

/** During a Theta ride (Camera360Screen): connected or not, photos taken, skipped, the last error, reconnect. */
@Composable
fun ThetaRidePanel(stats: ThetaShooter.Stats) {
    val ctx = LocalContext.current
    val link by ThetaLink.state.collectAsState()
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ThetaStatus(link, dark = true)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ThetaCount(stats.shots.toString(), stringResource(R.string.theta_shots))
            ThetaCount(stats.skipped.toString(), stringResource(R.string.theta_skipped))
            if (stats.failed > 0) ThetaCount(stats.failed.toString(), stringResource(R.string.theta_failed_count))
        }
        stats.lastError?.let { Text(stringResource(R.string.theta_last_error, it), color = Palette.AmberLight, fontSize = 13.sp, maxLines = 2) }
        if (link.status != ThetaLink.Status.CONNECTED && link.status != ThetaLink.Status.CONNECTING)
            OutlinedButton(onClick = { ThetaLink.reconnect(ctx, Prefs.thetaSerial(ctx), Prefs.thetaPassword(ctx)) },
                modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(stringResource(R.string.theta_reconnect), color = Color.White)
            }
    }
}

@Composable
private fun ThetaCount(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = Palette.Lime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
    Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
}
