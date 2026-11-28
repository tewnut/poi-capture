package io.github.tewnut.poicapture.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.ThetaLink
import io.github.tewnut.poicapture.core.Camera360Model
import io.github.tewnut.poicapture.core.GoProLabs
import io.github.tewnut.poicapture.core.QrModules
import io.github.tewnut.poicapture.ui.settings.CardHead
import io.github.tewnut.poicapture.ui.settings.WhiteBox
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Settings → Camera 360 (also opened from New ride and from the onboarding): which 360 camera the contributor uses
 * ([Prefs.camera360Model]) and its setup: the Ricoh Theta's pairing (serial, password, Connect, spacing, reading the
 * serial off its sticker), the GoPro Labs setup QR for a GoPro MAX, a note for the cameras synced by the ride's QR.
 * Full screen, scrolls, stays clear of the keyboard. [releaseOnLeave]: let the Theta's Wi-Fi go when the page closes
 * (Settings, onboarding); New ride keeps it for the ride and releases it itself.
 */
@Composable
fun Camera360Page(onBack: () -> Unit, releaseOnLeave: Boolean = true) {
    val ctx = LocalContext.current
    var model by remember { mutableStateOf(Prefs.camera360Model(ctx)) }
    var everyM by remember { mutableStateOf(Prefs.thetaEveryM(ctx)) }
    var guide by remember { mutableStateOf(false) }
    var goproQr by remember { mutableStateOf(false) }
    if (releaseOnLeave) DisposableEffect(Unit) { onDispose { if (!ThetaLink.forRide) ThetaLink.release() } }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding().imePadding()) {
        ScreenHeader(stringResource(R.string.c360_title), onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WhiteBox {
                CardHead("📷", stringResource(R.string.c360_your_camera))
                val options = listOf(
                    Camera360Model.NONE to R.string.c360_model_none,
                    Camera360Model.GOPRO to R.string.c360_model_gopro,
                    Camera360Model.INSTA360 to R.string.c360_model_insta,
                    Camera360Model.THETA to R.string.c360_model_theta,
                    Camera360Model.OTHER to R.string.c360_model_other)
                Column {
                    options.forEachIndexed { i, (m, label) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .selectable(model == m, role = Role.RadioButton) {
                                if (model == Camera360Model.THETA && m != Camera360Model.THETA && !ThetaLink.forRide) ThetaLink.release()
                                model = m
                                Prefs.saveCamera360Model(ctx, m)
                            }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model == m, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(stringResource(label), fontSize = 16.sp, color = Palette.Ink,
                                fontWeight = if (model == m) FontWeight.Bold else FontWeight.Normal)
                        }
                        if (i < options.lastIndex) HorizontalDivider(color = Palette.Line)
                    }
                }
            }
            when (model) {
                Camera360Model.NONE -> Hint(stringResource(R.string.c360_none_hint), Modifier.padding(horizontal = 4.dp))
                Camera360Model.THETA -> WhiteBox {
                    CardHead("🔗", stringResource(R.string.c360_theta_title))
                    ThetaSetupCard(everyM) { everyM = it; Prefs.saveThetaEveryM(ctx, it) }
                }
                Camera360Model.GOPRO -> WhiteBox {
                    CardHead("🔳", stringResource(R.string.c360_gopro_qr_title), stringResource(R.string.c360_gopro_qr_sub))
                    Button(onClick = { goproQr = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(stringResource(R.string.c360_gopro_qr_show), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GoProLabs.LABS_URL))) }
                    }) { Text(stringResource(R.string.c360_gopro_labs_link)) }
                }
                Camera360Model.INSTA360, Camera360Model.OTHER ->
                    Hint(stringResource(R.string.c360_sync_note), Modifier.padding(horizontal = 4.dp))
            }
            if (model != Camera360Model.NONE) OutlinedButton(onClick = { guide = true }, modifier = Modifier.fillMaxWidth()) {
                Text("📖 " + stringResource(R.string.guide360_open), fontWeight = FontWeight.Bold)
            }
        }
    }
    // the guide's tabs: GoPro MAX (records on its own, synced by the ride's QR: also the way for Insta360 / others), Theta
    if (guide) Camera360Guide(initial = if (model == Camera360Model.THETA) 1 else 0) { guide = false }
    if (goproQr) GoProLabsQrScreen { goproQr = false }
}

/** [Camera360Page] over whatever is open (New ride, onboarding): a full-screen window of its own. */
@Composable
fun Camera360PageDialog(onClose: () -> Unit, releaseOnLeave: Boolean = true) {
    FullScreenDialog(onClose) { Camera360Page(onBack = onClose, releaseOnLeave = releaseOnLeave) }
}

/** A dialog filling the screen, edge to edge (its content pads for the system bars and the keyboard). */
@Composable
fun FullScreenDialog(onClose: () -> Unit, content: @Composable () -> Unit) =
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { content() }

/** The window a composable is drawn in: the dialog's when inside one, else the activity's. */
@Composable
private fun currentWindow(): Window? =
    (LocalView.current.parent as? DialogWindowProvider)?.window ?: (LocalContext.current as? Activity)?.window

/**
 * The GoPro Labs setup QR ([GoProLabs.setupCommand]), full screen, black on white, one line of instruction: the phone's
 * clock is redrawn into it every [GoProLabs.REDRAW_MS]. Screen at full brightness and kept on while shown.
 */
@Composable
fun GoProLabsQrScreen(onClose: () -> Unit) = FullScreenDialog(onClose) {
    val window = currentWindow()
    val view = LocalView.current
    DisposableEffect(window) {
        val before = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        view.keepScreenOn = true
        onDispose {
            if (before != null) window.attributes = window.attributes.apply { screenBrightness = before }
            view.keepScreenOn = false
        }
    }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            val cmd = GoProLabs.setupCommand(Instant.ofEpochMilli(t), ZoneId.systemDefault())
            val m = QrModules.encode(cmd)
            qr = Bitmap.createBitmap(m.pixels(), m.size, m.size, Bitmap.Config.ARGB_8888).asImageBitmap()
            text = cmd
            delay((GoProLabs.REDRAW_MS - (System.currentTimeMillis() - t)).coerceIn(1, GoProLabs.REDRAW_MS))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        val side = minOf(maxWidth, maxHeight - 170.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Text(stringResource(R.string.c360_gopro_qr_hint), color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // one pixel per module (quiet zone included): scaled up with no filtering, sharp edges
                qr?.let { Image(it, null, Modifier.size(side), filterQuality = FilterQuality.None) } ?: Box(Modifier.size(side))
                Text(text, color = Color.Black.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onClose, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
                Text(stringResource(R.string.c360_sync_done), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** How the chosen 360 camera is set up, in one line for New ride and the Settings row. */
@Composable
fun camera360Line(model: Camera360Model, link: ThetaLink.Ui, everyM: Double, withSpacing: Boolean): String {
    val ctx = LocalContext.current
    return when (model) {
        Camera360Model.NONE -> stringResource(R.string.c360_line_none)
        Camera360Model.GOPRO -> stringResource(R.string.c360_line_sync, stringResource(R.string.c360_model_gopro))
        Camera360Model.INSTA360 -> stringResource(R.string.c360_line_sync, stringResource(R.string.c360_model_insta))
        Camera360Model.OTHER -> stringResource(R.string.c360_line_sync, stringResource(R.string.c360_model_other_name))
        Camera360Model.THETA -> {
            val connected = link.status == ThetaLink.Status.CONNECTED
            val name = thetaDisplayName(link.model?.takeIf { connected } ?: Prefs.thetaModelName(ctx))
            val state = stringResource(when (link.status) {
                ThetaLink.Status.CONNECTED -> R.string.c360_state_connected
                ThetaLink.Status.CONNECTING -> R.string.c360_state_connecting
                ThetaLink.Status.LOST -> R.string.c360_state_lost
                ThetaLink.Status.FAILED -> R.string.c360_state_failed
                ThetaLink.Status.OFF -> R.string.c360_state_off
            })
            listOfNotNull(name, state,
                link.batteryPct?.takeIf { connected }?.let { stringResource(R.string.theta_battery, it) },
                if (withSpacing) stringResource(R.string.c360_every, everyM.toInt()) else null).joinToString(" · ")
        }
    }
}

/**
 * New ride, Camera 360°: the chosen camera and its state in one line ("Ricoh Theta X · đã kết nối · pin 80% · mỗi 8 m",
 * "GoPro MAX · đồng bộ bằng mã QR", "Chưa chọn camera"), Kết nối for a Theta not connected, and Đổi / Chọn camera
 * opening the Camera 360 page.
 */
@Composable
fun Camera360SetupLine(model: Camera360Model, everyM: Double, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val link by ThetaLink.state.collectAsState()
    val theta = model == Camera360Model.THETA
    if (theta) RememberThetaModel(link)
    val ok = when (model) {
        Camera360Model.NONE -> false
        Camera360Model.THETA -> link.status == ThetaLink.Status.CONNECTED
        else -> true
    }
    Row(Modifier.fillMaxWidth().background(if (ok) Palette.GreenLight.copy(alpha = 0.45f) else Palette.Canvas, RoundedCornerShape(14.dp))
        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("📷 " + camera360Line(model, link, everyM, withSpacing = true), Modifier.weight(1f), fontWeight = FontWeight.Bold,
            fontSize = 15.sp, color = if (model == Camera360Model.NONE) Palette.Amber else Palette.Ink, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        val serial = Prefs.thetaSerial(ctx)
        if (theta && serial.isNotBlank() && link.status != ThetaLink.Status.CONNECTED && link.status != ThetaLink.Status.CONNECTING)
            TextButton(onClick = { ThetaLink.connect(ctx, serial, Prefs.thetaPassword(ctx)) }) {
                Text(stringResource(R.string.theta_connect), fontWeight = FontWeight.Bold)
            }
        TextButton(onClick = onOpen) {
            Text(stringResource(if (model == Camera360Model.NONE) R.string.c360_choose else R.string.setup_change), fontWeight = FontWeight.Bold)
        }
    }
}
