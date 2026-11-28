package io.github.tewnut.poicapture.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.AndroidExifGps
import io.github.tewnut.poicapture.capture.PlatformPhoneGps
import io.github.tewnut.poicapture.capture.ThetaCheckLink
import io.github.tewnut.poicapture.capture.ThetaLink
import io.github.tewnut.poicapture.core.Camera360Check
import io.github.tewnut.poicapture.core.Camera360Model
import io.github.tewnut.poicapture.core.CheckFix
import io.github.tewnut.poicapture.core.CheckOutcome
import io.github.tewnut.poicapture.core.CheckResult
import io.github.tewnut.poicapture.core.CheckState
import io.github.tewnut.poicapture.core.CheckStep
import io.github.tewnut.poicapture.core.ManualItem
import io.github.tewnut.poicapture.core.ThetaCheckRunner
import io.github.tewnut.poicapture.ui.settings.CardHead
import io.github.tewnut.poicapture.ui.settings.WhiteBox
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** "Checked today" per 360 camera (Theta: the last run passed today; the others: all ticked today), observed by the UI. */
object Camera360Checks {
    private val version = mutableIntStateOf(0)
    private fun today() = LocalDate.now().toString()

    fun checkedToday(ctx: Context, model: Camera360Model): Boolean {
        version.intValue
        return when (model) {
            Camera360Model.NONE -> false
            Camera360Model.THETA -> Camera360Check.checkedToday(Prefs.camera360CheckedOn(ctx, model), LocalDate.now())
            else -> Camera360Check.manualDone(model, ticks(ctx, model))
        }
    }

    fun ticks(ctx: Context, model: Camera360Model): Set<String> {
        version.intValue
        val (date, ticks) = Prefs.camera360Ticks(ctx, model)
        return Camera360Check.ticksToday(date, ticks, LocalDate.now())
    }

    fun saveTicks(ctx: Context, model: Camera360Model, ticks: Set<String>) {
        Prefs.saveCamera360Ticks(ctx, model, today(), ticks)
        if (Camera360Check.manualDone(model, ticks)) Prefs.saveCamera360CheckedOn(ctx, model, today())
        else if (Prefs.camera360CheckedOn(ctx, model) == today()) Prefs.saveCamera360CheckedOn(ctx, model, null)
        version.intValue++
    }

    fun markPassed(ctx: Context, model: Camera360Model) {
        Prefs.saveCamera360CheckedOn(ctx, model, today())
        version.intValue++
    }
}

/** New ride's line: "· đã kiểm tra hôm nay ✓" (green) or "· chưa kiểm tra" (amber); nothing without a camera. */
@Composable
fun camera360CheckSuffix(model: Camera360Model): Pair<String, Color>? {
    if (model == Camera360Model.NONE) return null
    return if (Camera360Checks.checkedToday(LocalContext.current, model))
        stringResource(R.string.check_line_today) to Palette.Green
    else stringResource(R.string.check_line_not) to Palette.Amber
}

/**
 * Ricoh Theta: "Kiểm tra camera" runs [ThetaCheckRunner] on a background thread and lists each step (✓ ✗ ⚠ –, a
 * spinner on the current one, a fix for what failed, the test photo's thumbnail). Huỷ / leaving the page cancels it.
 * The Wi-Fi is left to the page's own rule (released on leaving it unless a ride holds it).
 */
@Composable
fun ThetaCheckCard(everyM: Double) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<CheckState?>(null) }
    var runner by remember { mutableStateOf<ThetaCheckRunner?>(null) }
    DisposableEffect(Unit) { onDispose { runner?.cancel?.set(true) } }
    val running = runner != null

    fun start() {
        val app = ctx.applicationContext
        val serial = Prefs.thetaSerial(app)
        val r = ThetaCheckRunner(ThetaCheckLink(app, serial, Prefs.thetaPassword(app)), PlatformPhoneGps(app), AndroidExifGps, serial)
        runner = r
        val main = Handler(Looper.getMainLooper())
        thread(name = "theta-check", isDaemon = true) {
            val end = r.run { s -> main.post { if (runner === r) state = s } }
            main.post {
                if (end.passed) Camera360Checks.markPassed(app, Camera360Model.THETA)
                if (runner === r) { state = end; runner = null }
            }
        }
    }

    WhiteBox {
        CardHead("🩺", stringResource(R.string.check_title), stringResource(R.string.check_sub))
        val rideOn = ThetaLink.forRide
        Button(enabled = !rideOn || running, onClick = { if (running) runner?.cancel?.set(true) else start() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = if (running) ButtonDefaults.buttonColors(containerColor = Palette.Muted) else ButtonDefaults.buttonColors()) {
            Text(stringResource(when {
                running -> R.string.check_cancel
                state != null -> R.string.check_again
                else -> R.string.check_run
            }), fontWeight = FontWeight.Bold)
        }
        if (rideOn && !running) Hint(stringResource(R.string.check_ride_on), color = Palette.Amber)
        if (!running && Camera360Checks.checkedToday(ctx, Camera360Model.THETA))
            Text(stringResource(R.string.check_today), color = Palette.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        val s = state
        if (s != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in s.results) {
                CheckRow(r.outcome, stepTitle(r.step), stepDetail(r, everyM), r.fix?.let { fixText(it) },
                    r.error?.takeIf { r.outcome == CheckOutcome.FAIL || r.outcome == CheckOutcome.WARN })
                if (r.step == CheckStep.THUMB) s.thumb?.let { ThumbImage(it) }
            }
        }
        InfoRow(stringResource(R.string.check_power_info))
    }
}

@Composable
private fun ThumbImage(bytes: ByteArray) {
    val bmp = remember(bytes) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() } ?: return
    Image(bmp, null, Modifier.padding(start = 34.dp).widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.FillWidth)
}

@Composable
private fun CheckRow(outcome: CheckOutcome, title: String, detail: String?, fix: String?, error: String?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (outcome) {
                CheckOutcome.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                CheckOutcome.OK -> Mark("✓", Palette.Green)
                CheckOutcome.FAIL -> Mark("✗", Palette.Red)
                CheckOutcome.WARN -> Mark("⚠", Palette.Amber)
                CheckOutcome.SKIPPED -> Mark("–", Palette.Muted)
                CheckOutcome.PENDING -> Mark("·", Palette.Muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (outcome == CheckOutcome.SKIPPED || outcome == CheckOutcome.PENDING) Palette.Muted else Palette.Ink,
                fontWeight = if (outcome == CheckOutcome.RUNNING) FontWeight.Bold else FontWeight.Normal)
            detail?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
            if (outcome == CheckOutcome.FAIL || outcome == CheckOutcome.WARN) {
                fix?.let { Text(it, fontSize = 13.sp, color = if (outcome == CheckOutcome.FAIL) Palette.Red else Palette.Amber, fontWeight = FontWeight.Bold) }
                error?.let { Text(it, fontSize = 12.sp, color = Palette.Muted, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun Mark(t: String, color: Color) = Text(t, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)

@Composable
private fun InfoRow(text: String) = Row(Modifier.fillMaxWidth().background(Palette.Canvas, RoundedCornerShape(10.dp)).padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("ℹ", fontSize = 16.sp, color = Palette.Muted)
    Text(text, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
}

@Composable
private fun stepTitle(s: CheckStep): String = stringResource(when (s) {
    CheckStep.WIFI -> R.string.check_step_wifi
    CheckStep.CAMERA -> R.string.check_step_camera
    CheckStep.CLOCK -> R.string.check_step_clock
    CheckStep.GPS -> R.string.check_step_gps
    CheckStep.SHOT -> R.string.check_step_shot
    CheckStep.THUMB -> R.string.check_step_thumb
    CheckStep.EXIF_GPS -> R.string.check_step_exif
    CheckStep.DELETE -> R.string.check_step_delete
})

@Composable
private fun stepDetail(r: CheckResult, everyM: Double): String? = when (r.step) {
    CheckStep.CAMERA -> listOfNotNull(r.model?.let { thetaDisplayName(it) }, r.serial?.let { stringResource(R.string.check_serial, it) },
        r.batteryPct?.let { stringResource(R.string.theta_battery, it) }).joinToString(" · ").ifEmpty { null }
    CheckStep.GPS -> r.accuracyM?.let { stringResource(R.string.check_accuracy, it.roundToInt()) }
    CheckStep.SHOT -> r.shotSeconds?.let { secs ->
        val v = Camera360Check.maxKmh(everyM, secs) ?: return@let null
        stringResource(R.string.check_shot_speed, String.format(Locale.getDefault(), "%.1f", secs), everyM.roundToInt(), v.roundToInt())
    }
    CheckStep.EXIF_GPS -> r.distanceM?.let { stringResource(R.string.check_exif_off, it.roundToInt()) }
    else -> null
}

@Composable
private fun fixText(f: CheckFix): String = stringResource(when (f) {
    CheckFix.SERIAL_MISSING -> R.string.check_fix_serial
    CheckFix.WIFI -> R.string.theta_failed_wifi
    CheckFix.NOT_RICOH -> R.string.check_fix_not_ricoh
    CheckFix.WRONG_SERIAL -> R.string.check_fix_wrong_serial
    CheckFix.NO_ANSWER -> R.string.check_fix_no_answer
    CheckFix.CLOCK -> R.string.check_fix_clock
    CheckFix.NO_GPS -> R.string.check_fix_no_gps
    CheckFix.GPS_REFUSED -> R.string.check_fix_gps_refused
    CheckFix.SHOT -> R.string.check_fix_shot
    CheckFix.DOWNLOAD -> R.string.check_fix_download
    CheckFix.EXIF_NO_GPS -> R.string.check_fix_exif_no_gps
    CheckFix.EXIF_FAR -> R.string.check_fix_exif_far
    CheckFix.DELETE -> R.string.check_fix_delete
    CheckFix.ERROR -> R.string.check_fix_error
})

/** GoPro MAX / Insta360 / other: "Kiểm tra trước khi đi", ticked by hand, kept for today only. */
@Composable
fun ManualCheckCard(model: Camera360Model) {
    val ctx = LocalContext.current
    val items = Camera360Check.manualItems(model)
    if (items.isEmpty()) return
    val ticks = Camera360Checks.ticks(ctx, model)
    WhiteBox {
        CardHead("✅", stringResource(R.string.check_manual_title), stringResource(R.string.check_manual_sub))
        Column {
            for (item in items) {
                val on = item.id in ticks
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .toggleable(on, role = Role.Checkbox) { Camera360Checks.saveTicks(ctx, model, if (on) ticks - item.id else ticks + item.id) }
                    .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                    Text(stringResource(itemText(item)), fontSize = 15.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
                }
            }
        }
        if (Camera360Check.manualDone(model, ticks))
            Text(stringResource(R.string.check_today), color = Palette.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private fun itemText(i: ManualItem): Int = when (i) {
    ManualItem.LABS -> R.string.check_item_labs
    ManualItem.SETUP_QR -> R.string.check_item_setup_qr
    ManualItem.CAMERA_GPS -> R.string.check_item_gps
    ManualItem.CLOCK -> R.string.check_item_clock
    ManualItem.GPS_IF_ANY -> R.string.check_item_gps_any
    ManualItem.CARD -> R.string.check_item_card
    ManualItem.BATTERY -> R.string.check_item_battery
}
