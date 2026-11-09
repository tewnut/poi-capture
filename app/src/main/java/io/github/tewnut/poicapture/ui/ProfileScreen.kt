package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
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

/** Profile (set up by the onboarding): who records, the facade distance, mounting, road data, server. */
@Composable
fun ProfileScreen(onDone: () -> Unit, onReplayOnboarding: () -> Unit) {
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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.profile_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)

        ProfileCard(stringResource(R.string.profile_contributor)) {
            OutlinedTextField(email, { email = it.trim(); save() }, label = { Text(stringResource(R.string.profile_email)) }, singleLine = true,
                isError = !emailOk,
                supportingText = { Text(stringResource(if (emailOk) R.string.profile_email_ok else R.string.profile_email_bad)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it; save() }, label = { Text(stringResource(R.string.profile_display_name)) }, singleLine = true,
                placeholder = { Text(email.substringBefore('@')) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        }

        ProfileCard(stringResource(R.string.language_title)) { LanguageRow() }

        ProfileCard(stringResource(R.string.profile_facade_title)) {
            Hint(stringResource(R.string.profile_facade_hint))
            val plan = CapturePlan.of(settings.facadeDistanceM)
            Stepper("${settings.facadeDistanceM.toInt()} m",
                onMinus = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM - 1).coerceAtLeast(2.0)); save() },
                onPlus = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM + 1).coerceAtMost(20.0)); save() },
                caption = stringResource(R.string.profile_facade_caption,
                    String.format(java.util.Locale.ROOT, "%.1f", plan.photoEveryM), plan.maxSpeedKmh),
                minus = "−1 m", plus = "+1 m",
                minusEnabled = settings.facadeDistanceM > 2.0, plusEnabled = settings.facadeDistanceM < 20.0)
        }

        ProfileCard(stringResource(R.string.profile_road_data)) { RoadPacksCard() }

        var advanced by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(if (advanced) R.string.profile_advanced_hide else R.string.profile_advanced_show))
        }
        if (advanced) ProfileCard(stringResource(R.string.profile_mount_title)) {
            Hint(stringResource(R.string.profile_mount_hint))
            val deg = settings.mountOffsetDeg.toInt()
            Stepper(if (deg == 0) "0°" else "${kotlin.math.abs(deg)}°",
                onMinus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg - 5).coerceAtLeast(-45.0)); save() },
                onPlus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg + 5).coerceAtMost(45.0)); save() },
                caption = stringResource(when { deg > 0 -> R.string.profile_mount_forward; deg < 0 -> R.string.profile_mount_backward
                    else -> R.string.profile_mount_square }),
                minus = "−5°", plus = "+5°")
        }

        if (advanced) ProfileCard(stringResource(R.string.profile_roles_title)) {
            var ask by remember { mutableStateOf(Prefs.askPhotoRole(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_roles_ask), modifier = Modifier.weight(1f), color = Palette.Ink)
                androidx.compose.material3.Switch(ask, { ask = it; Prefs.setAskPhotoRole(ctx, it) },
                    enabled = Prefs.photoRoles(ctx).source != "server")
            }
            Hint(stringResource(R.string.profile_roles_hint))
            if (ask) PhotoRolesCard()
        }

        Button(onClick = {
            save()
            Thread { io.github.tewnut.poicapture.capture.Uploads.enqueuePending(ctx) }.start()   // pick up waiting sessions with the new settings
            onDone()
        }, enabled = emailOk, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.profile_done), fontWeight = FontWeight.Bold)
        }
        androidx.compose.material3.TextButton(onClick = onReplayOnboarding, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.profile_replay_onboarding))
        }
    }
}

/** System / English / Tiếng Việt: the app's UI language (Android 13+: the system per-app language). */
@Composable
private fun LanguageRow() {
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

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Palette.Ink)
            content()
        }
    }
}
