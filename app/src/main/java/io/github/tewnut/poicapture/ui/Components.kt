package io.github.tewnut.poicapture.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tewnut.poicapture.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.tewnut.poicapture.core.Side

object Palette {
    val Green = Color(0xFF0B6B4C)
    val GreenLight = Color(0xFFD7F0E5)
    val Amber = Color(0xFFB54708)
    val AmberLight = Color(0xFFFEF0C7)
    val Red = Color(0xFFD92D20)
    val Blue = Color(0xFF1570EF)
    val Ink = Color(0xFF101828)
    val Muted = Color(0xFF667085)
    val Road = Color(0xFF98A2B3)
    val House = Color(0xFFE4E7EC)
}

/**
 * Live position on screens outside the capture (setup map, GPS readiness). [stale] = a remembered position (last known
 * fix older than a minute, maybe from elsewhere): fine to centre a map, not to say which road the contributor is on.
 */
data class LiveLocation(val lat: Double, val lon: Double, val accuracyM: Float, val simulated: Boolean, val atMs: Long,
                        val gps: Boolean = true, val stale: Boolean = false)

fun isEmulator(): Boolean = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")

/** Why there is no usable position yet, in the order the contributor has to fix it. */
enum class LocStatus { NO_PERMISSION, APPROXIMATE_ONLY, DISABLED, SEARCHING, OK }

data class LocationState(val fix: LiveLocation?, val status: LocStatus, val satsUsed: Int, val satsSeen: Int, val searchingSinceMs: Long) {
    /** A current position (not a remembered one). */
    val live: LiveLocation? get() = fix?.takeIf { !it.stale }
}

/** Continuous location updates while the composable is on screen; see [rememberLocationState]. */
@Composable
fun rememberLiveLocation(): LiveLocation? = rememberLocationState().live

/**
 * Position and what blocks it. Re-checks the permission and the system location switch whenever the screen resumes
 * (back from Settings or a permission dialog) or the switch changes, and registers again: before, a phone whose
 * location was off, or granted later, stayed on "Đang tìm vị trí GPS…" forever. Uses GPS plus the fused provider
 * (Wi-Fi / cell, quick first fix indoors) and counts satellites so a long search is explained.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberLocationState(): LocationState {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var fix by remember { mutableStateOf<LiveLocation?>(null) }
    var sats by remember { mutableStateOf(0 to 0) }
    var recheck by remember { mutableIntStateOf(0) }
    val since = remember { System.currentTimeMillis() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) recheck++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { recheck++ } }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    val fine = remember(recheck) { granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val coarse = remember(recheck) { granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) }
    val enabled = remember(recheck) { runCatching { lm.isLocationEnabled }.getOrDefault(true) }

    DisposableEffect(fine, enabled) {
        if (!fine || !enabled) return@DisposableEffect onDispose {}
        fun accept(l: Location, remembered: Boolean) {
            val isGps = l.provider == LocationManager.GPS_PROVIDER
            val cur = fix
            // a Wi-Fi / cell fix never replaces a fresh GPS fix
            if (cur != null && cur.gps && !cur.stale && !isGps && System.currentTimeMillis() - cur.atMs < 10_000) return
            val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
            val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider
            fix = LiveLocation(l.latitude, l.longitude, l.accuracy, mock || isEmulator(), System.currentTimeMillis(),
                gps = isGps, stale = remembered && ageMs > 60_000)
        }
        val listener = LocationListener { accept(it, remembered = false) }
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in lm.allProviders) add(LocationManager.FUSED_PROVIDER)
            else add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (fix == null) {
            (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let { accept(it, remembered = true) }
        }
        for (p in providers) runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = (0 until status.satelliteCount).count { status.usedInFix(it) } to status.satelliteCount
            }
            override fun onStopped() { sats = 0 to 0 }
        }
        runCatching { lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(ctx), gnss) }
        onDispose {
            lm.removeUpdates(listener)
            runCatching { lm.unregisterGnssStatusCallback(gnss) }
        }
    }
    val status = when {
        !fine && !coarse -> LocStatus.NO_PERMISSION
        !fine -> LocStatus.APPROXIMATE_ONLY
        !enabled -> LocStatus.DISABLED
        fix == null || fix!!.stale -> LocStatus.SEARCHING
        else -> LocStatus.OK
    }
    return LocationState(fix, status, sats.first, sats.second, since)
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/**
 * What to do when there is no position: grant the permission, switch on precise location, switch on the phone's
 * location, or (searching) how many satellites are seen and where to stand. Nothing when the position is fine.
 */
@Composable
fun LocationHelp(state: LocationState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    fun appSettings() = ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", ctx.packageName, null)))
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) { while (state.status == LocStatus.SEARCHING) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val (text, action, onAction) = when (state.status) {
        LocStatus.OK -> return
        LocStatus.NO_PERMISSION -> Triple(stringResource(R.string.common_loc_no_permission), stringResource(R.string.common_allow), {
            request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) })
        LocStatus.APPROXIMATE_ONLY -> Triple(stringResource(R.string.common_loc_approximate), stringResource(R.string.common_turn_on), {
            request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) })
        LocStatus.DISABLED -> Triple(stringResource(R.string.common_loc_disabled), stringResource(R.string.common_turn_on_location), {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        LocStatus.SEARCHING -> {
            val s = ((now - state.searchingSinceMs) / 1000).coerceAtLeast(0)
            val sats = if (state.satsSeen > 0) stringResource(R.string.common_sats, state.satsSeen, state.satsUsed) + " " else ""
            Triple(sats + stringResource(if (s < 20) R.string.common_searching else R.string.common_searching_long),
                null, {})
        }
    }
    Row(modifier.fillMaxWidth().background(if (state.status == LocStatus.SEARCHING) Palette.AmberLight else Color(0xFFFEE4E2), RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, Modifier.weight(1f), color = if (state.status == LocStatus.SEARCHING) Palette.Amber else Palette.Red, fontSize = 14.sp)
        if (action != null) androidx.compose.material3.TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.Bold) }
        if (state.status == LocStatus.NO_PERMISSION || state.status == LocStatus.APPROXIMATE_ONLY) {
            androidx.compose.material3.TextButton(onClick = { appSettings() }) { Text(stringResource(R.string.common_settings)) }
        }
    }
}

/**
 * "− value +" that stays on one line on any phone width and font size: fixed 64×48 dp buttons, the value (and an
 * optional caption under it) takes the space between them.
 */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, caption: String? = null,
            minus: String = "−", plus: String = "+", minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(64.dp, 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(minus, fontSize = 16.sp, maxLines = 1, softWrap = false) }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Palette.Ink, maxLines = 1)
            if (caption != null) Text(caption, fontSize = 13.sp, color = Palette.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        androidx.compose.material3.OutlinedButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(64.dp, 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(plus, fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

/** Rounded status label with an optional coloured dot. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dot: Color? = null, bg: Color = Color(0xB3000000), fg: Color = Color.White,
         size: TextUnit = 14.sp, bold: Boolean = false) {
    Row(modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot != null) Box(Modifier.size(9.dp).background(dot, CircleShape))
        Text(text, color = fg, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/**
 * Top-down sketch for the side choice: a two-lane road (Vietnam drives on the right), the rider going up in the right
 * lane, houses on both sides, the chosen side's houses highlighted and a camera cone pointing at them.
 */
@Composable
fun SideIllustration(side: Side, selected: Boolean, modifier: Modifier = Modifier) {
    val accent = if (selected) Palette.Green else Palette.Muted
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val roadW = w * 0.34f
        val roadX = (w - roadW) / 2
        // houses
        val houseW = roadX - w * 0.08f
        for (i in 0..2) {
            val y = h * 0.06f + i * h * 0.31f
            val hh = h * 0.25f
            for (s in listOf(Side.LEFT, Side.RIGHT)) {
                val x = if (s == Side.LEFT) w * 0.03f else roadX + roadW + w * 0.05f
                val on = s == side
                drawRoundRect(if (on) accent.copy(alpha = if (selected) 0.9f else 0.5f) else Palette.House,
                    Offset(x, y), Size(houseW, hh), CornerRadius(6f, 6f))
            }
        }
        // road and centre line
        drawRect(Palette.Road.copy(alpha = 0.35f), Offset(roadX, 0f), Size(roadW, h))
        drawLine(Color.White, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))
        // rider in the right lane, heading up
        val rx = roadX + roadW * 0.75f
        val ry = h * 0.62f
        val arrow = Path().apply {
            moveTo(rx, ry - h * 0.16f); lineTo(rx - roadW * 0.17f, ry); lineTo(rx + roadW * 0.17f, ry); close()
        }
        drawPath(arrow, Palette.Ink)
        drawLine(Palette.Ink, Offset(rx, ry), Offset(rx, ry + h * 0.14f), strokeWidth = 6f)
        // camera cone toward the chosen side
        val cy = ry - h * 0.04f
        val tx = if (side == Side.RIGHT) roadX + roadW + w * 0.05f else w * 0.03f + houseW
        val cone = Path().apply {
            moveTo(rx, cy); lineTo(tx, cy - h * 0.13f); lineTo(tx, cy + h * 0.13f); close()
        }
        drawPath(cone, accent.copy(alpha = 0.28f))
        drawPath(cone, accent, style = Stroke(width = 3f))
    }
}
