package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.AppLanguage
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.Onboarding
import androidx.compose.ui.text.input.KeyboardType
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Settings (first filled in by the onboarding): the contributor ID, language, offline road data, and folded away the
 * advanced options (mount angle, photo kinds). Every change is saved as it is made.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayOnboarding: () -> Unit) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(Prefs.contributorId(ctx)) }
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }

    fun save() {
        Prefs.saveContributorId(ctx, id)
        Prefs.saveSettings(ctx, settings)
    }

    // every change is saved as it is made; leaving saves once more (back arrow or the system back)
    fun leave() { save(); onBack() }
    androidx.activity.compose.BackHandler { leave() }

    Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
    ScreenHeader(stringResource(R.string.settings_title), onBack = ::leave)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        SettingsCard("🪪", stringResource(R.string.settings_contributor), stringResource(R.string.settings_email_ok)) {
            ContributorIdField(id, { id = it; save() })
        }

        SettingsCard("🌐", stringResource(R.string.language_title)) { LanguageList() }

        SettingsCard("🗺️", stringResource(R.string.settings_road_data)) { RoadPacksCard() }

        var advanced by remember { mutableStateOf(false) }
        SettingsRow("⚙️", stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_sub),
            trailing = if (advanced) "▴" else "▾") { advanced = !advanced }
        if (advanced) SettingsCard("🛵", stringResource(R.string.settings_mount_title), stringResource(R.string.settings_mount_hint)) {
            val deg = settings.mountOffsetDeg.toInt()
            Stepper(if (deg == 0) "0°" else "${kotlin.math.abs(deg)}°",
                onMinus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg - 5).coerceAtLeast(-45.0)); save() },
                onPlus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg + 5).coerceAtMost(45.0)); save() },
                caption = stringResource(when { deg > 0 -> R.string.settings_mount_forward; deg < 0 -> R.string.settings_mount_backward
                    else -> R.string.settings_mount_square }),
                minus = "−5°", plus = "+5°")
        }

        if (advanced) SettingsCard("🏷️", stringResource(R.string.settings_roles_title), stringResource(R.string.settings_roles_hint)) {
            var ask by remember { mutableStateOf(Prefs.askPhotoRole(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_roles_ask), modifier = Modifier.weight(1f), color = Palette.Ink)
                androidx.compose.material3.Switch(ask, { ask = it; Prefs.setAskPhotoRole(ctx, it) })
            }
            if (ask) PhotoRolesCard()
        }

        SettingsRow("📖", stringResource(R.string.settings_replay_onboarding), stringResource(R.string.settings_replay_sub),
            trailing = "›", onClick = onReplayOnboarding)

        // the version, and when this copy was installed or last updated (which build is on the phone)
        val info = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
        if (info != null) {
            val installed = remember(info) {
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(info.lastUpdateTime))
            }
            Hint(stringResource(R.string.app_name) + " " + info.versionName + "\n" + stringResource(R.string.settings_installed, installed),
                Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
        }
    }
    }
}

/** System / English / Tiếng Việt: the app's UI language (Android 13+: the system per-app language). */
@Composable
internal fun LanguageRow() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.current(ctx)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tag in AppLanguage.TAGS) {
            val label = stringResource(when (tag) {
                "en" -> R.string.language_en
                "vi" -> R.string.language_vi
                else -> R.string.language_system
            })
            FilterChip(current == tag, {
                current = tag
                ctx.findActivity()?.let { AppLanguage.set(it, tag) }
            }, { Text(label) })
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** A settings section: an icon in a green circle, the title (and a line under it), then its controls. */
@Composable
private fun SettingsCard(icon: String, title: String, sub: String? = null, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Palette.Line), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHead(icon, title, sub)
            content()
        }
    }
}

/** A tappable settings line (opens or folds something): icon, title, one line under it, a mark on the right. */
@Composable
private fun SettingsRow(icon: String, title: String, sub: String, trailing: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Palette.Line), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionHead(icon, title, sub) }
            Text(trailing, fontSize = 22.sp, color = Palette.Muted, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionHead(icon: String, title: String, sub: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 18.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
            if (sub != null) Hint(sub)
        }
    }
}
