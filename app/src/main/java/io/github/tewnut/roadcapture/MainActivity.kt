package io.github.tewnut.roadcapture

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import io.github.tewnut.roadcapture.ui.SessionMap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import io.github.tewnut.roadcapture.capture.CaptureService
import io.github.tewnut.roadcapture.core.Settings
import io.github.tewnut.roadcapture.core.Vehicle
import io.github.tewnut.roadcapture.ui.CaptureScreen
import io.github.tewnut.roadcapture.ui.Palette
import io.github.tewnut.roadcapture.ui.ProfileScreen
import io.github.tewnut.roadcapture.ui.SessionsScreen
import io.github.tewnut.roadcapture.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
}

/** Per-device profile and settings (SharedPreferences). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("road-capture", Context.MODE_PRIVATE)
    fun settings(ctx: Context): Settings =
        Settings.fromJson(sp(ctx).getString("settings", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    fun saveSettings(ctx: Context, s: Settings) = sp(ctx).edit().putString("settings", s.toJson().toString()).apply()
    fun contributor(ctx: Context): String = sp(ctx).getString("contributor", "") ?: ""
    fun saveContributor(ctx: Context, v: String) = sp(ctx).edit().putString("contributor", v).apply()
    fun vehicle(ctx: Context): Vehicle = Vehicle.of(sp(ctx).getString("vehicle", null))
    fun saveVehicle(ctx: Context, v: Vehicle) = sp(ctx).edit().putString("vehicle", v.json).apply()
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    // first run: the profile (name) comes first
    var tab by rememberSaveable { mutableStateOf(if (Prefs.contributor(ctx).isBlank()) "profile" else "new") }
    var capturing by rememberSaveable { mutableStateOf<String?>(null) }
    var mapOf by rememberSaveable { mutableStateOf<String?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(wanted.toTypedArray())
        // the app was reopened while a session is still recording in the background: go back to it
        CaptureService.activeSession.value?.let { capturing = it }
        // sessions finished earlier (or cut off) that are not on the server yet
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.tewnut.roadcapture.capture.Uploads.enqueuePending(ctx) }
    }

    val sid = capturing
    if (sid != null) {
        CaptureScreen(sid, onDone = { capturing = null; tab = "sessions" }, onCancel = { capturing = null; tab = "new" })
        return
    }
    val ms = mapOf
    if (ms != null) {
        BackHandler { mapOf = null }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { mapOf = null }) { Text("← Đã ghi") }
                Text("Vị trí ảnh", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            SessionMap(ms, refresh = null, live = false, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.GreenLight, selectedIconColor = Palette.Green,
                    selectedTextColor = Palette.Green)
                NavigationBarItem(tab == "new", { tab = "new" }, { Icon(Icons.Filled.Add, null) }, label = { Text("Ghi mới") }, colors = colors)
                NavigationBarItem(tab == "sessions", { tab = "sessions" }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Đã ghi") }, colors = colors)
                NavigationBarItem(tab == "profile", { tab = "profile" }, { Icon(Icons.Filled.Person, null) }, label = { Text("Hồ sơ") }, colors = colors)
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                "sessions" -> SessionsScreen(onNew = { tab = "new" }, onOpenMap = { mapOf = it })
                "profile" -> ProfileScreen(onDone = { tab = "new" })
                else -> SetupScreen(onStart = { capturing = it }, onOpenProfile = { tab = "profile" })
            }
        }
    }
}
