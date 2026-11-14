package io.github.tewnut.poicapture.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import io.github.tewnut.poicapture.AppLanguage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.RoadPackCatalogue
import io.github.tewnut.poicapture.capture.RoadPackDownloads
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.Onboarding
import io.github.tewnut.poicapture.net.RoadSource
import java.util.Locale
import kotlin.math.roundToInt

private enum class Step { LANGUAGE, WELCOME, EMAIL, PERMISSIONS, ROADS, READY }

/**
 * First run, once per phone: language → what the app does → email (not verified) → permissions → offline road data for
 * where the contributor is → mounting and facade distance. Every step is a must; all of it can be changed later in
 * Settings.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // saveable: picking a language recreates the activity, the onboarding stays on its step
    var step by rememberSaveable { mutableStateOf(Step.LANGUAGE) }
    BackHandler(enabled = step.ordinal > 0) { step = Step.entries[step.ordinal - 1] }

    Column(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding()) {
        // top bar: back + progress
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step.ordinal > 0) TextButton(onClick = { step = Step.entries[step.ordinal - 1] }) { Text(stringResource(R.string.onboarding_back)) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 12.dp)) {
                for (s in Step.entries) {
                    Box(Modifier.size(width = if (s == step) 22.dp else 8.dp, height = 8.dp)
                        .background(if (s.ordinal <= step.ordinal) Palette.Green else Palette.House, RoundedCornerShape(50)))
                }
            }
        }
        when (step) {
            Step.LANGUAGE -> LanguageStep { step = Step.WELCOME }
            Step.WELCOME -> WelcomeStep { step = Step.EMAIL }
            Step.EMAIL -> EmailStep(ctx) { step = Step.PERMISSIONS }
            Step.PERMISSIONS -> PermissionsStep(ctx) { step = Step.ROADS }
            Step.ROADS -> RoadsStep(ctx) { step = Step.READY }
            Step.READY -> ReadyStep(ctx) { Prefs.setOnboarded(ctx, true); onDone() }
        }
    }
}

/** Scrolling body + the primary button pinned at the bottom (and an optional secondary action above it). */
@Composable
internal fun StepFrame(title: String, lead: String?, button: String, enabled: Boolean = true, onNext: () -> Unit,
                      secondary: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            if (lead != null) Text(lead, fontSize = 16.sp, color = Palette.Muted)
            content()
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            secondary?.invoke()
            Button(onClick = onNext, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(button, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun IconRow(icon: String, title: String, text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
            Hint(text)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun WhiteCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// ---------------------------------------------------------------- 0. language

/** First of all, so everything after it reads in the contributor's language (the title is in both). */
@Composable
private fun LanguageStep(onNext: () -> Unit) {
    StepFrame(stringResource(R.string.onboarding_language_title), stringResource(R.string.onboarding_language_lead),
        stringResource(R.string.onboarding_continue), onNext = onNext) {
        WhiteCard { LanguageList() }
        Hint(stringResource(R.string.onboarding_language_later))
    }
}

/**
 * The languages as a list in the style of the other steps (icon, name, one line under it), the choice marked on the
 * right. Each language is named in itself ("Tiếng Việt", "English"), so it is found whatever the phone's language;
 * "the phone's language" says which one that is.
 */
@Composable
private fun LanguageList() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.current(ctx)) }
    val phone = remember {
        android.content.res.Resources.getSystem().configuration.locales[0].let { l ->
            l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
        }
    }
    val options = listOf(
        Triple("vi", "🇻🇳", stringResource(R.string.language_vi) to stringResource(R.string.onboarding_language_vi_text)),
        Triple("en", "🇬🇧", stringResource(R.string.language_en) to stringResource(R.string.onboarding_language_en_text)),
        Triple(AppLanguage.SYSTEM, "📱", stringResource(R.string.onboarding_language_phone) to stringResource(R.string.onboarding_language_phone_text, phone)),
    )
    options.forEachIndexed { i, (tag, icon, texts) ->
        val selected = current == tag
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.GreenLight.copy(alpha = 0.45f) else Color.Transparent)
            .selectable(selected, role = Role.RadioButton) {
                current = tag
                ctx.findActivity()?.let { AppLanguage.set(it, tag) }
            }
            .padding(vertical = 6.dp, horizontal = 4.dp)) {
            IconRow(icon, texts.first, texts.second) {
                RadioButton(selected = selected, onClick = null)
            }
        }
        if (i < options.lastIndex) HorizontalDivider(color = Palette.House)
    }
}

// ---------------------------------------------------------------- 1. welcome

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepFrame(stringResource(R.string.app_name), stringResource(R.string.onboarding_welcome_lead),
        stringResource(R.string.onboarding_welcome_button), onNext = onNext,
        secondary = { Hint(stringResource(R.string.onboarding_welcome_time), Modifier.padding(bottom = 8.dp)) }) {
        Spacer(Modifier.height(4.dp))
        IconRow("🛵", stringResource(R.string.onboarding_sweep_title), stringResource(R.string.onboarding_sweep_text))
        IconRow("🏪", stringResource(R.string.onboarding_place_title), stringResource(R.string.onboarding_place_text))
        IconRow("📍", stringResource(R.string.onboarding_position_title), stringResource(R.string.onboarding_position_text))
        IconRow("📶", stringResource(R.string.onboarding_offline_title), stringResource(R.string.onboarding_offline_text))
    }
}

// ---------------------------------------------------------------- 2. email

@Composable
private fun EmailStep(ctx: Context, onNext: () -> Unit) {
    var email by rememberSaveable { mutableStateOf(Prefs.email(ctx)) }
    var name by rememberSaveable { mutableStateOf(Prefs.contributor(ctx)) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val valid = Onboarding.isEmail(email)
    fun next() {
        if (!valid) { touched = true; return }
        Prefs.saveEmail(ctx, email.trim())
        Prefs.saveContributor(ctx, name.trim())
        onNext()
    }
    StepFrame(stringResource(R.string.onboarding_email_title), stringResource(R.string.onboarding_email_lead),
        stringResource(R.string.onboarding_continue), enabled = email.isNotBlank(), onNext = ::next) {
        OutlinedTextField(email, { email = it.trim(); if (touched && Onboarding.isEmail(email)) touched = false },
            label = { Text(stringResource(R.string.onboarding_email_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_email_placeholder)) }, singleLine = true,
            isError = touched && !valid,
            supportingText = { if (touched && !valid) Text(stringResource(R.string.onboarding_email_invalid)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.onboarding_name_label)) },
            placeholder = { val ph = stringResource(R.string.onboarding_name_placeholder); Text(email.substringBefore('@').ifBlank { ph }) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = { Text(stringResource(R.string.onboarding_name_help)) },
            modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- 3. permissions

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private data class Perm(val icon: String, @StringRes val title: Int, @StringRes val why: Int, val names: List<String>)

private fun perms(): List<Perm> = buildList {
    add(Perm("📷", R.string.onboarding_perm_camera, R.string.onboarding_perm_camera_why, listOf(Manifest.permission.CAMERA)))
    add(Perm("📍", R.string.onboarding_perm_location, R.string.onboarding_perm_location_why,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
    if (Build.VERSION.SDK_INT >= 33) {
        add(Perm("🔔", R.string.onboarding_perm_notifications, R.string.onboarding_perm_notifications_why,
            listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

/** "a", "a and b", "a, b and c" (permission names, lower case) in the UI's language. */
private fun joinTitles(ctx: Context, list: List<Perm>): String {
    val t = list.map { ctx.getString(it.title).lowercase(Locale.ROOT) }
    return if (t.size <= 1) t.joinToString() else ctx.getString(R.string.onboarding_and, t.dropLast(1).joinToString(", "), t.last())
}

@Composable
private fun PermissionsStep(ctx: Context, onNext: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; tick++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val list = remember { perms() }
    // re-read the grants after each request / return from Settings
    val missing = remember(tick) { list.filter { p -> !granted(ctx, p.names.first()) } }
    val required = missing
    // the phone's location switch too: GPS is a must, and the next step looks for the province
    val access = rememberRequiredAccess()

    // every permission is a must: no "Later"; the button asks, then (Android stops asking) opens Settings
    StepFrame(stringResource(R.string.onboarding_perms_title), stringResource(R.string.onboarding_perms_lead),
        stringResource(when {
            missing.isNotEmpty() && asked -> R.string.onboarding_open_settings
            missing.isNotEmpty() -> R.string.onboarding_perms_grant
            !access.locationOn -> R.string.common_turn_on_location
            else -> R.string.onboarding_continue
        }),
        onNext = {
            when {
                missing.isNotEmpty() && asked -> settings.launch(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null)))
                missing.isNotEmpty() -> request.launch(missing.flatMap { it.names }.toTypedArray())
                !access.locationOn -> ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
                else -> onNext()
            }
        }) {
        WhiteCard {
            for (p in list) {
                val ok = p !in missing
                IconRow(p.icon, stringResource(p.title), stringResource(p.why)) {
                    Text(if (ok) "✓" else stringResource(R.string.onboarding_perm_missing), color = if (ok) Palette.Green else Palette.Amber,
                        fontWeight = FontWeight.Bold, fontSize = if (ok) 20.sp else 13.sp)
                }
            }
            IconRow("🛰️", stringResource(R.string.gate_location_on), stringResource(R.string.gate_location_on_why)) {
                Text(if (access.locationOn) "✓" else stringResource(R.string.onboarding_perm_missing),
                    color = if (access.locationOn) Palette.Green else Palette.Amber,
                    fontWeight = FontWeight.Bold, fontSize = if (access.locationOn) 20.sp else 13.sp)
            }
        }
        if (missing.any { it.title == R.string.onboarding_perm_location }) Hint(stringResource(R.string.gate_precise_why))
        if (asked && required.isNotEmpty()) {
            Hint(stringResource(R.string.onboarding_perms_required, joinTitles(ctx, required)), color = Palette.Amber)
            // Settings is the main button now; this one asks once more (Android may still show its dialog)
            TextButton(onClick = { request.launch(missing.flatMap { it.names }.toTypedArray()) }) {
                Text(stringResource(R.string.onboarding_perms_grant))
            }
        }
    }
}

// ---------------------------------------------------------------- 4. road data

@Composable
private fun RoadsStep(ctx: Context, onNext: () -> Unit) {
    val url = remember { Prefs.settings(ctx).roadPackIndexUrl }
    var catalogue by remember { mutableStateOf(RoadSource.cachedCatalogue(ctx)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(emptyMap<String, String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        installed = withContext(Dispatchers.IO) { RoadSource.packs(ctx).installed().associate { it.id to it.version } }
    }
    LaunchedEffect(attempt) {
        if (url.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try { catalogue = withContext(Dispatchers.IO) { RoadPackCatalogue.fetch(ctx, url) } }
        catch (e: Exception) { error = errorText(ctx, e) }
        loading = false
    }
    // which province: a Wi-Fi / cell fix is close enough, and indoors it comes long before GPS
    val live = rememberLocationState().approx
    val suggestion = live?.let { RoadSource.suggest(catalogue, it.lat, it.lon) }
    // the province the contributor is in: download its pack right away (once), no tap needed
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(suggestion?.id, installed) {
        val s = suggestion ?: return@LaunchedEffect
        if (!autoStarted && installed[s.id] != s.version) {
            RoadPackDownloads.enqueue(ctx, s, anyNetwork = true)
            autoStarted = true
        }
    }
    var showAll by rememberSaveable { mutableStateOf(false) }

    // a must: no way on until at least one province / city pack is on the phone
    val haveRoads = installed.isNotEmpty()
    StepFrame(stringResource(R.string.onboarding_roads_title), stringResource(R.string.onboarding_roads_lead),
        stringResource(if (haveRoads) R.string.onboarding_continue else R.string.onboarding_roads_need),
        enabled = haveRoads, onNext = onNext) {
        when {
            loading && catalogue.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Hint(stringResource(R.string.onboarding_roads_loading))
            }
            error != null && catalogue.isEmpty() -> {
                Hint(stringResource(R.string.onboarding_roads_error, error ?: ""), color = Palette.Red)
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.onboarding_retry)) }
            }
        }
        if (suggestion != null) {
            WhiteCard {
                Text(stringResource(R.string.onboarding_roads_here, suggestion.name), fontWeight = FontWeight.Bold, color = Palette.Green, fontSize = 17.sp)
                PackRow(suggestion, installed[suggestion.id], anyNetwork = true, onInstalled = { refresh++ })
                Hint(stringResource(if (installed[suggestion.id] == suggestion.version) R.string.onboarding_roads_installed
                    else R.string.onboarding_roads_downloading))
            }
        } else if (catalogue.isNotEmpty()) {
            Hint(stringResource(if (live == null) R.string.onboarding_roads_waiting else R.string.onboarding_roads_no_pack))
        }
        val others = catalogue.filter { it.id != suggestion?.id }.sortedBy { it.name }
        if (others.isNotEmpty()) {
            if (suggestion != null && !showAll) {
                TextButton(onClick = { showAll = true }) {
                    Text(pluralStringResource(R.plurals.onboarding_roads_other_count, others.size, others.size))
                }
            } else WhiteCard {
                Text(stringResource(if (suggestion != null) R.string.onboarding_roads_others else R.string.onboarding_roads_all),
                    fontWeight = FontWeight.Bold, color = Palette.Ink)
                for (e in others) PackRow(e, installed[e.id], anyNetwork = true, onInstalled = { refresh++ })
            }
        }
        if (catalogue.isNotEmpty()) Hint(stringResource(R.string.onboarding_roads_credit))
    }
}

// ---------------------------------------------------------------- 5. ready

@Composable
private fun ReadyStep(ctx: Context, onDone: () -> Unit) {
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }
    val email = remember { Prefs.email(ctx) }
    val name = remember { Onboarding.displayName(Prefs.contributor(ctx), email) }
    val missing = perms().filter { p -> !granted(ctx, p.names.first()) && p.icon != "🔔" }
    val packs = remember { RoadSource.packs(ctx).installed().map { it.name } }
    val plan = CapturePlan.of(settings.facadeDistanceM)

    StepFrame(stringResource(R.string.onboarding_ready_title), null, stringResource(R.string.onboarding_ready_button),
        onNext = { Prefs.saveSettings(ctx, settings); onDone() }) {
        WhiteCard {
            Text(stringResource(R.string.onboarding_mount_title), fontWeight = FontWeight.Bold, color = Palette.Ink)
            Hint(stringResource(R.string.onboarding_mount_text))
        }
        WhiteCard {
            Summary(true, stringResource(R.string.onboarding_summary_name, name), email)
            Summary(missing.isEmpty(), if (missing.isEmpty()) stringResource(R.string.onboarding_summary_perms_ok)
                else stringResource(R.string.onboarding_summary_perms_missing, missing.joinToString(", ") { ctx.getString(it.title).lowercase(Locale.ROOT) }),
                if (missing.isEmpty()) null else stringResource(R.string.onboarding_summary_perms_fix))
            Summary(packs.isNotEmpty(), if (packs.isNotEmpty()) stringResource(R.string.onboarding_summary_roads, packs.joinToString(", "))
                else stringResource(R.string.onboarding_summary_roads_online),
                if (packs.isNotEmpty()) null else stringResource(R.string.onboarding_summary_roads_pending))
        }
        Hint(stringResource(R.string.onboarding_ready_after))
    }
}

@Composable
private fun Summary(ok: Boolean, title: String, sub: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ok) "✓" else "!", color = if (ok) Palette.Green else Palette.Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.width(18.dp))
        Column {
            Text(title, color = Palette.Ink, fontWeight = FontWeight.Medium)
            if (sub != null) Hint(sub)
        }
    }
}
