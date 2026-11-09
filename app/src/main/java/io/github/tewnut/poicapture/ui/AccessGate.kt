package io.github.tewnut.poicapture.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tewnut.poicapture.R

/** What the app cannot work without: the camera, *precise* location, and the phone's location (GPS) switched on. */
data class RequiredAccess(val camera: Boolean, val preciseLocation: Boolean, val locationOn: Boolean) {
    val ok: Boolean get() = camera && preciseLocation && locationOn
    val permissionsOk: Boolean get() = camera && preciseLocation
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun requiredAccess(ctx: Context): RequiredAccess {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val on = runCatching { lm.isLocationEnabled && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    return RequiredAccess(granted(ctx, Manifest.permission.CAMERA), granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION), on)
}

/**
 * [requiredAccess], read again whenever the app comes back to the front (a permission dialog, Settings, the quick
 * settings shade) or the location switch changes.
 */
@Composable
fun rememberRequiredAccess(): RequiredAccess {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { tick++ } }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return remember(tick) { requiredAccess(ctx) }
}

/**
 * Full screen in front of the app until [access] is ok: GPS is a must (every photo needs a precise position), so
 * there is no way past it. Asks for the permissions, then sends to the app's Settings when Android stops asking, then
 * to the location switch.
 */
@Composable
fun AccessGate(access: RequiredAccess) {
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true }
    fun appSettings() = ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", ctx.packageName, null)))
    fun locationSettings() = ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))

    val (button, action) = when {
        !access.permissionsOk && !asked -> stringResource(R.string.onboarding_perms_grant) to {
            request.launch(buildList {
                if (!access.camera) add(Manifest.permission.CAMERA)
                if (!access.preciseLocation) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            }.toTypedArray())
        }
        !access.permissionsOk -> stringResource(R.string.onboarding_open_settings) to { appSettings() }
        else -> stringResource(R.string.common_turn_on_location) to { locationSettings() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding().padding(top = 24.dp)) {
        StepFrame(stringResource(R.string.gate_title), stringResource(R.string.gate_lead), button, onNext = action) {
            WhiteCard {
                Row("📍", R.string.onboarding_perm_location, R.string.gate_precise_why, access.preciseLocation)
                Row("🛰️", R.string.gate_location_on, R.string.gate_location_on_why, access.locationOn)
                Row("📷", R.string.onboarding_perm_camera, R.string.onboarding_perm_camera_why, access.camera)
            }
            if (asked && !access.permissionsOk) Hint(stringResource(R.string.gate_denied), color = Palette.Amber)
        }
    }
}

@Composable
private fun Row(icon: String, title: Int, why: Int, ok: Boolean) =
    IconRow(icon, stringResource(title), stringResource(why)) {
        Text(if (ok) "✓" else stringResource(R.string.onboarding_perm_missing), color = if (ok) Palette.Green else Palette.Amber,
            fontWeight = FontWeight.Bold, fontSize = if (ok) 20.sp else 13.sp)
    }

/** The phone lacks something the capture cannot do without (see DeviceCheck): say what, and that is all. */
@Composable
fun UnsupportedDevice(missing: List<io.github.tewnut.poicapture.capture.DeviceCheck.Need>) {
    val activity = LocalContext.current as? android.app.Activity
    fun finish() { activity?.finish() }
    Box(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding().padding(top = 24.dp)) {
        StepFrame(stringResource(R.string.device_title), stringResource(R.string.device_lead),
            stringResource(R.string.device_close), onNext = { finish() }) {
            WhiteCard {
                for (n in missing) IconRow("✗", stringResource(n.title), stringResource(n.why))
            }
            Hint(stringResource(R.string.device_footer))
        }
    }
}
