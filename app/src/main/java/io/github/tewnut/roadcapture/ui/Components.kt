package io.github.tewnut.roadcapture.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.tewnut.roadcapture.core.Side

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

/** Live position on screens outside the capture (setup map, GPS readiness). */
data class LiveLocation(val lat: Double, val lon: Double, val accuracyM: Float, val simulated: Boolean, val atMs: Long)

fun isEmulator(): Boolean = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")

/** Continuous location updates while the composable is on screen (GPS + network for a quick first fix). */
@SuppressLint("MissingPermission")
@Composable
fun rememberLiveLocation(): LiveLocation? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<LiveLocation?>(null) }
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun accept(l: Location) {
            val cur = loc
            // a network fix never replaces a fresher GPS fix
            if (cur != null && l.provider != LocationManager.GPS_PROVIDER && System.currentTimeMillis() - cur.atMs < 10_000) return
            val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider
            loc = LiveLocation(l.latitude, l.longitude, l.accuracy, mock || isEmulator(), System.currentTimeMillis())
        }
        val listener = LocationListener { accept(it) }
        if (granted) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let { accept(it) }
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                }
            }
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return loc
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
