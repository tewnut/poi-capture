package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
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

/** Settings (first filled in by the onboarding): who records, language, facade distance, road data, advanced options. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayOnboarding: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.contributor(ctx)) }
    var email by remember { mutableStateOf(Prefs.email(ctx)) }
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }
    val emailOk = Onboarding.isEmail(email)

    fun save() {
        Prefs.saveContributor(ctx, name.trim())
        if (emailOk) Prefs.saveEmail(ctx, email.trim())
        Prefs.saveSettings(ctx, settings)
    }

    // every change is saved as it is made; leaving saves once more (back arrow or the system back)
    fun leave() { save(); onBack() }
    androidx.activity.compose.BackHandler { leave() }

    Column(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding()) {
    ScreenHeader(stringResource(R.string.settings_title), onBack = ::leave)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        SettingsCard(stringResource(R.string.settings_contributor)) {
            OutlinedTextField(email, { email = it.trim(); save() }, label = { Text(stringResource(R.string.settings_email)) }, singleLine = true,
                isError = !emailOk,
                supportingText = { Text(stringResource(if (emailOk) R.string.settings_email_ok else R.string.settings_email_bad)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it; save() }, label = { Text(stringResource(R.string.settings_display_name)) }, singleLine = true,
                placeholder = { Text(email.substringBefore('@')) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        }

        SettingsCard(stringResource(R.string.language_title)) { LanguageRow() }

        SettingsCard(stringResource(R.string.settings_road_data)) { RoadPacksCard() }

        var advanced by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(if (advanced) R.string.settings_advanced_hide else R.string.settings_advanced_show))
        }
        if (advanced) SettingsCard(stringResource(R.string.settings_mount_title)) {
            Hint(stringResource(R.string.settings_mount_hint))
            val deg = settings.mountOffsetDeg.toInt()
            Stepper(if (deg == 0) "0°" else "${kotlin.math.abs(deg)}°",
                onMinus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg - 5).coerceAtLeast(-45.0)); save() },
                onPlus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg + 5).coerceAtMost(45.0)); save() },
                caption = stringResource(when { deg > 0 -> R.string.settings_mount_forward; deg < 0 -> R.string.settings_mount_backward
                    else -> R.string.settings_mount_square }),
                minus = "−5°", plus = "+5°")
        }

        if (advanced) SettingsCard(stringResource(R.string.settings_roles_title)) {
            var ask by remember { mutableStateOf(Prefs.askPhotoRole(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_roles_ask), modifier = Modifier.weight(1f), color = Palette.Ink)
                androidx.compose.material3.Switch(ask, { ask = it; Prefs.setAskPhotoRole(ctx, it) })
            }
            Hint(stringResource(R.string.settings_roles_hint))
            if (ask) PhotoRolesCard()
        }

        androidx.compose.material3.TextButton(onClick = onReplayOnboarding, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.settings_replay_onboarding))
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

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Palette.Ink)
            content()
        }
    }
}
