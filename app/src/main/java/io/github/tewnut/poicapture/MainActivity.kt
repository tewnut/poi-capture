package io.github.tewnut.poicapture

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.ui.SessionMap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.json.JSONObject
import org.osmdroid.config.Configuration
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.Settings
import io.github.tewnut.poicapture.ui.CaptureScreen
import io.github.tewnut.poicapture.ui.Palette
import io.github.tewnut.poicapture.ui.SettingsScreen
import io.github.tewnut.poicapture.ui.SessionsScreen
import io.github.tewnut.poicapture.ui.SetupScreen
import io.github.tewnut.poicapture.ui.AccessGate
import io.github.tewnut.poicapture.ui.rememberRequiredAccess
import io.github.tewnut.poicapture.ui.OnboardingScreen
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    // Android 11-12: the language picked in Settings (13+: the system applies the per-app language itself)
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // logo on green while starting; before super.onCreate
        // dark status-bar icons on the light app background (they were white on white before)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName   // OSM tile usage policy: identify the app
        }
        // rides cut short (the app was killed while recording) go up like any ended ride, when auto upload is on
        if (savedInstanceState == null) io.github.tewnut.poicapture.capture.Uploads.resumeInterrupted(this)
        setContent {
            io.github.tewnut.poicapture.ui.PoiTheme { App() }
        }
    }

    private fun captureKey(keyCode: Int) = CaptureKeys.handler != null && keyCode in setOf(
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_CAMERA)

    private var lastCaptureKeyMs = 0L

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!captureKey(keyCode)) return super.onKeyDown(keyCode, event)
        // one action per press: no auto-repeat, and cheap Bluetooth remotes that send a key twice count once
        val now = android.os.SystemClock.uptimeMillis()
        if (event.repeatCount == 0 && now - lastCaptureKeyMs > 350) {
            lastCaptureKeyMs = now
            CaptureKeys.handler?.invoke()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        if (captureKey(keyCode)) true else super.onKeyUp(keyCode, event)
}

/** Per-device profile and settings (SharedPreferences). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("road-capture", Context.MODE_PRIVATE)
    fun settings(ctx: Context): Settings =
        Settings.fromJson(sp(ctx).getString("settings", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    fun saveSettings(ctx: Context, s: Settings) = sp(ctx).edit().putString("settings", s.toJson().toString()).apply()
    /**
     * The contributor ID the team gave this person (optional, "" = none): goes with each session as `contributor`.
     * Replaces the email and display name of app <= 0.14 (no personal data is asked any more).
     */
    fun contributorId(ctx: Context): String = sp(ctx).getString("contributorId", "") ?: ""
    fun saveContributorId(ctx: Context, v: String) = sp(ctx).edit().putString("contributorId", cleanId(v))
        .remove("email").remove("contributor").apply()
    /** An ID as typed, kept to a sane length on one line. */
    fun cleanId(v: String): String = v.replace(Regex("\\s+"), " ").trim().take(64)
    fun onboarded(ctx: Context): Boolean = sp(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("onboarded", v).apply()

    /** Street sweep: on a mount or in a passenger's hand, as last chosen. */
    fun holding(ctx: Context): io.github.tewnut.poicapture.core.Holding =
        io.github.tewnut.poicapture.core.Holding.of(sp(ctx).getString("holding", null))
    fun saveHolding(ctx: Context, h: io.github.tewnut.poicapture.core.Holding) = sp(ctx).edit().putString("holding", h.json).apply()
    /** New ride: the kind last chosen (sweep, place, indoor); 360 is an option of sweep ([use360]), never a kind here. */
    fun rideKind(ctx: Context): io.github.tewnut.poicapture.core.SessionKind =
        io.github.tewnut.poicapture.core.SessionKind.entries.firstOrNull { it.json == sp(ctx).getString("rideKind", null) }
            ?.takeIf { it != io.github.tewnut.poicapture.core.SessionKind.CAMERA360 } ?: io.github.tewnut.poicapture.core.SessionKind.SWEEP
    fun saveRideKind(ctx: Context, k: io.github.tewnut.poicapture.core.SessionKind) = sp(ctx).edit().putString("rideKind", k.json).apply()
    /** Street sweep: what the last ride was for (the next one starts with it). */
    fun target(ctx: Context): io.github.tewnut.poicapture.core.SweepTarget =
        io.github.tewnut.poicapture.core.SweepTarget.of(sp(ctx).getString("target", null))
    fun saveTarget(ctx: Context, t: io.github.tewnut.poicapture.core.SweepTarget) = sp(ctx).edit().putString("target", t.json).apply()
    /** Maps: satellite imagery instead of the street map (remembered). */
    fun satellite(ctx: Context): Boolean = sp(ctx).getBoolean("satellite", false)
    fun saveSatellite(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("satellite", v).apply()
    /** Camera 360: the phone controls a Ricoh Theta (docs/camera360.md §4), its serial / Wi-Fi name, password, spacing. */
    fun thetaOn(ctx: Context): Boolean = camera360Model(ctx) == io.github.tewnut.poicapture.core.Camera360Model.THETA
    /**
     * The 360 camera chosen in Settings → Camera 360 (none / gopro / insta360 / theta / other). Before it existed the
     * New ride sheet had a "control a Ricoh Theta" switch (`thetaOn`): on = theta.
     */
    fun camera360Model(ctx: Context): io.github.tewnut.poicapture.core.Camera360Model =
        io.github.tewnut.poicapture.core.Camera360Model.of(sp(ctx).getString("camera360Model", null), sp(ctx).getBoolean("thetaOn", false))
    fun saveCamera360Model(ctx: Context, m: io.github.tewnut.poicapture.core.Camera360Model) =
        sp(ctx).edit().putString("camera360Model", m.json).remove("thetaOn").apply()
    /** The Theta's model name from its last connect ("RICOH THETA X"), for Settings' row; "" = never connected. */
    fun thetaModelName(ctx: Context): String = sp(ctx).getString("thetaModelName", "") ?: ""
    fun saveThetaModelName(ctx: Context, v: String) = sp(ctx).edit().putString("thetaModelName", v).apply()
    /** Quét đường with a 360 camera (Camera: 360°) instead of the phone's camera. */
    fun use360(ctx: Context): Boolean = sp(ctx).getBoolean("use360", false)
    fun saveUse360(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("use360", v).apply()
    fun thetaSerial(ctx: Context): String = sp(ctx).getString("thetaSerial", "") ?: ""
    /** Only a changed password is kept, encrypted like the upload token; empty = the serial's digits (Theta default). */
    fun thetaPassword(ctx: Context): String = sp(ctx).getString("thetaPassword", null)
        ?.let { runCatching { io.github.tewnut.poicapture.capture.UploadServer.Secret.decrypt(it) }.getOrNull() } ?: ""
    /** A different camera (serial) is not "paired" until it connects: its model name is forgotten. */
    fun saveThetaWifi(ctx: Context, serial: String, password: String) =
        sp(ctx).edit().putString("thetaSerial", serial).apply {
            if (serial != thetaSerial(ctx)) remove("thetaModelName")
            if (password.isEmpty()) remove("thetaPassword")
            else putString("thetaPassword", io.github.tewnut.poicapture.capture.UploadServer.Secret.encrypt(password))
        }.apply()
    fun thetaEveryM(ctx: Context): Double =
        sp(ctx).getFloat("thetaEveryM", io.github.tewnut.poicapture.core.ThetaOsc.DEFAULT_EVERY_M.toFloat()).toDouble()
    fun saveThetaEveryM(ctx: Context, v: Double) = sp(ctx).edit().putFloat("thetaEveryM", v.toFloat()).apply()
    /** Camera 360 → Kiểm tra camera: the date ("2026-11-28") of the last fully passed check of [m]; "" = never. */
    fun camera360CheckedOn(ctx: Context, m: io.github.tewnut.poicapture.core.Camera360Model): String =
        sp(ctx).getString("c360Checked_${m.json}", "") ?: ""
    fun saveCamera360CheckedOn(ctx: Context, m: io.github.tewnut.poicapture.core.Camera360Model, date: String?) =
        sp(ctx).edit().apply { if (date == null) remove("c360Checked_${m.json}") else putString("c360Checked_${m.json}", date) }.apply()
    /** Camera 360 → Kiểm tra trước khi đi: (the day they were ticked, the ticked item ids) for [m]. */
    fun camera360Ticks(ctx: Context, m: io.github.tewnut.poicapture.core.Camera360Model): Pair<String, Set<String>> =
        (sp(ctx).getString("c360TicksDate_${m.json}", "") ?: "") to (sp(ctx).getStringSet("c360Ticks_${m.json}", null)?.toSet() ?: emptySet())
    fun saveCamera360Ticks(ctx: Context, m: io.github.tewnut.poicapture.core.Camera360Model, date: String, ticks: Set<String>) =
        sp(ctx).edit().putString("c360TicksDate_${m.json}", date).putStringSet("c360Ticks_${m.json}", ticks.toSet()).apply()
    /** A ride was started on this phone once: New ride then folds its choices into one line (Đổi opens them). */
    fun startedRide(ctx: Context): Boolean = sp(ctx).getBoolean("startedRide", false)
    fun setStartedRide(ctx: Context) = sp(ctx).edit().putBoolean("startedRide", true).apply()
}

/**
 * Hardware keys while capturing: the volume keys (and Bluetooth selfie remotes, which send volume-up) shoot, in Place
 * capture and manual street sweep. Set by the capture screen.
 */
object CaptureKeys {
    @Volatile var handler: (() -> Unit)? = null
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    // first run: the onboarding (contributor ID, permissions, road data) comes first
    var onboarded by rememberSaveable { mutableStateOf(Prefs.onboarded(ctx)) }
    // the onboarding again from Settings → About: the app stays set up (onboarded), it can be left at any step
    var replaying by rememberSaveable { mutableStateOf(false) }
    // home (the sessions list) is the root; New session and Settings open on top of it and go back to it
    var screen by rememberSaveable { mutableStateOf("home") }   // home | new | settings
    var capturing by rememberSaveable { mutableStateOf<String?>(null) }
    var mapOf by rememberSaveable { mutableStateOf<String?>(null) }
    var mapTitle by rememberSaveable { mutableStateOf("") }   // the session's name, as in the list
    // a ride just ended with Save: its summary (RideEnd.encode) before the list
    var summary by rememberSaveable { mutableStateOf<String?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    // the app comes to the front (launcher, recents, the ride's notification: singleTask, so no new screen) while a
    // ride is still recording in the background: go back to it, wherever the app was (the list, New ride, Settings)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && capturing == null) CaptureService.activeSession.value?.let { capturing = it }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val access = rememberRequiredAccess()
    LaunchedEffect(onboarded, access.permissionsOk) {
        // the onboarding asks, with the reasons; camera + location are the gate's below. Notifications only: optional.
        if (!onboarded || !access.permissionsOk || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val unsupported = remember { io.github.tewnut.poicapture.capture.DeviceCheck.missing(ctx) }
    if (unsupported.isNotEmpty()) {
        io.github.tewnut.poicapture.ui.UnsupportedDevice(unsupported)
        return
    }
    if ((!onboarded || replaying) && capturing == null) {
        OnboardingScreen(replay = replaying && onboarded, onDone = {
            screen = if (replaying && onboarded) "settings" else "home"
            onboarded = true; replaying = false
        })
        return
    }
    // the ride just saved: its numbers and upload, then the list
    val ended = io.github.tewnut.poicapture.core.RideEnd.decode(summary)
    if (ended != null && capturing == null) {
        io.github.tewnut.poicapture.ui.RideSummaryScreen(ended, onDone = { summary = null; screen = "home" }, onOpenSettings = { summary = null; screen = "settings" })
        return
    }
    // GPS is a must: no way into the app without precise location, the camera and the phone's location on.
    // A session already recording keeps its screen (the service reports a lost GPS itself).
    if (!access.ok && capturing == null) {
        AccessGate(access)
        return
    }

    val sid = capturing
    if (sid != null) {
        CaptureScreen(sid, onDone = { e -> capturing = null; screen = "home"; summary = e?.encode() },
            onCancel = { capturing = null; screen = "new" })
        return
    }
    val ms = mapOf
    if (ms != null) {
        BackHandler { mapOf = null }
        Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
            io.github.tewnut.poicapture.ui.ScreenHeader(mapTitle.ifBlank { stringResource(R.string.map_title) }, onBack = { mapOf = null },
                subtitle = stringResource(R.string.map_title).takeIf { mapTitle.isNotBlank() })
            SessionMap(ms, refresh = null, live = false, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    when (screen) {
        "new" -> SetupScreen(onStart = { capturing = it }, onOpenSettings = { screen = "settings" }, onBack = { screen = "home" })
        "settings" -> SettingsScreen(onBack = { screen = "home" },
            onReplayOnboarding = { replaying = true })
        else -> SessionsScreen(onNew = { screen = "new" }, onOpenMap = { id, title -> mapTitle = title; mapOf = id }, onOpenSettings = { screen = "settings" })
    }
}
