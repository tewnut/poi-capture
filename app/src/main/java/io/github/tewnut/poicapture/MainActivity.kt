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
import io.github.tewnut.poicapture.core.PhotoRoles
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
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Palette.Green, secondary = Color(0xFF35B385),
                secondaryContainer = Palette.GreenLight, background = Color(0xFFF9FAFB), surface = Color.White,
            )) { App() }
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
    /** Display name typed by the contributor (may be blank: then the part of the email before @ is used). */
    fun contributor(ctx: Context): String = sp(ctx).getString("contributor", "") ?: ""
    fun saveContributor(ctx: Context, v: String) = sp(ctx).edit().putString("contributor", v).apply()
    /** Email typed at onboarding (format checked, not verified). */
    fun email(ctx: Context): String = sp(ctx).getString("email", "") ?: ""
    fun saveEmail(ctx: Context, v: String) = sp(ctx).edit().putString("email", v).apply()
    fun onboarded(ctx: Context): Boolean = sp(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("onboarded", v).apply()

    /** Photo kinds of POI mode: the contributor's own list (Settings), else the built-in default. */
    fun photoRoles(ctx: Context): PhotoRoles = sp(ctx).getString("photoRoles", null)?.let { s ->
        runCatching { JSONObject(s).let { PhotoRoles.fromJson(it, it.optString("source", "local")) } }.getOrNull()
    } ?: PhotoRoles.DEFAULT
    /**
     * Ask the kind of each photo while shooting a place? Off by default (the agent tells a front from a sign or a menu
     * by itself); on when the contributor wants it.
     */
    fun askPhotoRole(ctx: Context): Boolean = sp(ctx).getBoolean("askPhotoRole", false)
    fun setAskPhotoRole(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("askPhotoRole", v).apply()
    fun savePhotoRoles(ctx: Context, r: PhotoRoles?) = sp(ctx).edit().apply {
        if (r == null || r.source == "default") remove("photoRoles") else putString("photoRoles", r.toJson().toString())
    }.apply()
}

/**
 * Hardware keys while capturing: the volume keys (and Bluetooth selfie remotes, which send volume-up) shoot or mark,
 * so a rider can point out a shop from the handlebar without touching the screen. Set by the capture screen.
 */
object CaptureKeys {
    @Volatile var handler: (() -> Unit)? = null
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    // first run: the onboarding (email, permissions, road data) comes first
    var onboarded by rememberSaveable { mutableStateOf(Prefs.onboarded(ctx)) }
    // home (the sessions list) is the root; New session and Settings open on top of it and go back to it
    var screen by rememberSaveable { mutableStateOf("home") }   // home | new | settings
    var capturing by rememberSaveable { mutableStateOf<String?>(null) }
    var mapOf by rememberSaveable { mutableStateOf<String?>(null) }
    var mapTitle by rememberSaveable { mutableStateOf("") }   // the session's name, as in the list

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        // the app was reopened while a session is still recording in the background: go back to it
        CaptureService.activeSession.value?.let { capturing = it }
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
    if (!onboarded && capturing == null) {
        OnboardingScreen(onDone = { onboarded = true; screen = "home" })
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
        CaptureScreen(sid, onDone = { capturing = null; screen = "home" }, onCancel = { capturing = null; screen = "new" })
        return
    }
    val ms = mapOf
    if (ms != null) {
        BackHandler { mapOf = null }
        Column(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding()) {
            io.github.tewnut.poicapture.ui.ScreenHeader(mapTitle.ifBlank { stringResource(R.string.map_title) }, onBack = { mapOf = null },
                subtitle = stringResource(R.string.map_title).takeIf { mapTitle.isNotBlank() })
            SessionMap(ms, refresh = null, live = false, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    when (screen) {
        "new" -> SetupScreen(onStart = { capturing = it }, onOpenSettings = { screen = "settings" }, onBack = { screen = "home" })
        "settings" -> SettingsScreen(onBack = { screen = "home" },
            onReplayOnboarding = { Prefs.setOnboarded(ctx, false); onboarded = false })
        else -> SessionsScreen(onNew = { screen = "new" }, onOpenMap = { id, title -> mapTitle = title; mapOf = id }, onOpenSettings = { screen = "settings" })
    }
}
