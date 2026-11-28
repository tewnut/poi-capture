package io.github.tewnut.poicapture.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.AppLanguage
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.capture.UploadServer
import io.github.tewnut.poicapture.ui.settings.CardHead
import io.github.tewnut.poicapture.ui.settings.NavGroup
import io.github.tewnut.poicapture.ui.settings.NavItem
import io.github.tewnut.poicapture.ui.settings.OfflineMapsPage
import io.github.tewnut.poicapture.ui.settings.SectionLabel
import io.github.tewnut.poicapture.ui.settings.SettingsLogic
import io.github.tewnut.poicapture.ui.settings.SettingsPage
import io.github.tewnut.poicapture.ui.settings.WhiteBox
import io.github.tewnut.poicapture.ui.settings.installedPacks

/** The pages of Settings; [HOME] is the list that opens them. */
private object Page {
    const val HOME = "home"
    const val CONTRIBUTOR = "contributor"
    const val LANGUAGE = "language"
    const val MAPS = "maps"
    const val TASKS = "tasks"
    const val UPLOAD = "upload"
    const val CAMERA360 = "camera360"
    const val ABOUT = "about"
}

/**
 * Settings (first filled in by the onboarding): a short list of rows, each with its current value, each opening its own
 * page (contributor ID, language, offline maps, tasks, upload server, Camera 360, about). Every change is saved as it is
 * made; back (the arrow or the system back) goes from a page to the list, from the list out of Settings.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayOnboarding: () -> Unit) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(Prefs.contributorId(ctx)) }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }

    fun save() {
        // the contributor ID only: each page saves its own settings straight to Prefs (a copy of all the settings
        // kept here would write back old values over what a page just saved, e.g. the offline maps catalogue address)
        Prefs.saveContributorId(ctx, id)
    }

    // every change is saved as it is made; leaving saves once more
    fun leave() { save(); onBack() }
    fun home() { save(); page = Page.HOME }
    androidx.activity.compose.BackHandler { if (page == Page.HOME) leave() else home() }

    when (page) {
        Page.CONTRIBUTOR -> SettingsPage(stringResource(R.string.settings_row_contributor), ::home) {
            item {
                WhiteBox {
                    CardHead("🪪", stringResource(R.string.settings_contributor), stringResource(R.string.settings_email_ok))
                    ContributorIdField(id, { id = it; save() }, onDone = ::home)
                }
            }
        }
        Page.LANGUAGE -> SettingsPage(stringResource(R.string.language_title), ::home) {
            item { WhiteBox { LanguageList() } }
        }
        Page.MAPS -> OfflineMapsPage(onBack = ::home)
        Page.TASKS -> SettingsPage(stringResource(R.string.settings_row_tasks), ::home) {
            item {
                WhiteBox {
                    CardHead("📋", stringResource(R.string.settings_tasks))
                    TasksCard()
                }
            }
        }
        Page.UPLOAD -> SettingsPage(stringResource(R.string.upload_title), ::home) {
            item {
                WhiteBox {
                    CardHead("☁️", stringResource(R.string.upload_title), stringResource(R.string.upload_sub))
                    UploadServerCard()
                }
            }
        }
        Page.CAMERA360 -> Camera360Page(onBack = ::home)
        Page.ABOUT -> SettingsPage(stringResource(R.string.settings_about), ::home) { aboutPage(onReplayOnboarding) }
        else -> SettingsHome(id, onOpen = { page = it }, onBack = ::leave)
    }
}

/** The list of Settings: one row per page with its current value. Values are read again each time it shows. */
@Composable
private fun SettingsHome(contributorId: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val language = AppLanguage.current(ctx)
    val storage = remember { SettingsLogic.storage(runCatching { installedPacks(ctx).map { it.bytes } }.getOrDefault(emptyList())) }
    val openTasks = remember { runCatching { CaptureService.tasks(ctx).all().count { it.open } }.getOrDefault(0) }
    val uploadOk = remember { UploadServer.configured(ctx) }
    val version = remember { appVersion(ctx) }

    val items1 = listOf(
        NavItem("🪪", stringResource(R.string.settings_row_contributor),
            contributorId.takeIf { it.isNotBlank() }?.let { SettingsLogic.maskContributor(it) } ?: stringResource(R.string.settings_not_set)) { onOpen(Page.CONTRIBUTOR) },
        NavItem("🌐", stringResource(R.string.language_title), stringResource(when (language) {
            "vi" -> R.string.language_vi
            "en" -> R.string.language_en
            else -> R.string.onboarding_language_phone
        })) { onOpen(Page.LANGUAGE) },
    )
    val items2 = listOf(
        NavItem("🗺️", stringResource(R.string.settings_maps_title),
            if (storage.count == 0) stringResource(R.string.settings_maps_none)
            else pluralStringResource(R.plurals.settings_maps_summary, storage.count, storage.count, SettingsLogic.formatBytes(storage.bytes)),
            valueColor = if (storage.count == 0) Palette.Amber else Palette.Muted) { onOpen(Page.MAPS) },
        NavItem("📋", stringResource(R.string.settings_row_tasks),
            if (openTasks == 0) stringResource(R.string.settings_tasks_empty)
            else pluralStringResource(R.plurals.settings_tasks_open, openTasks, openTasks)) { onOpen(Page.TASKS) },
        NavItem("☁️", stringResource(R.string.upload_title),
            stringResource(if (uploadOk) R.string.settings_upload_connected else R.string.settings_upload_not_set),
            valueColor = if (uploadOk) Palette.Green else Palette.Blue) { onOpen(Page.UPLOAD) },   // not set: an invitation, not an error
        NavItem("📷", stringResource(R.string.c360_title), camera360Value(ctx)) { onOpen(Page.CAMERA360) },
    )
    val items3 = listOf(
        NavItem("ℹ️", stringResource(R.string.settings_about),
            version?.let { stringResource(R.string.settings_about_version, it) } ?: "") { onOpen(Page.ABOUT) },
    )
    SettingsPage(stringResource(R.string.settings_title), onBack) {
        item { NavGroup(items1) }
        item { NavGroup(items2) }
        item { NavGroup(items3) }
    }
}

/** Settings' Camera 360 row: "Không dùng", "GoPro MAX", "Ricoh Theta X · đã ghép" (connected once) / "· chưa ghép". */
@Composable
private fun camera360Value(ctx: Context): String {
    val model = remember { Prefs.camera360Model(ctx) }
    return when (model) {
        io.github.tewnut.poicapture.core.Camera360Model.NONE -> stringResource(R.string.c360_model_none)
        io.github.tewnut.poicapture.core.Camera360Model.GOPRO -> stringResource(R.string.c360_model_gopro)
        io.github.tewnut.poicapture.core.Camera360Model.INSTA360 -> stringResource(R.string.c360_model_insta)
        io.github.tewnut.poicapture.core.Camera360Model.OTHER -> stringResource(R.string.c360_model_other_name)
        io.github.tewnut.poicapture.core.Camera360Model.THETA -> {
            val name = remember { Prefs.thetaModelName(ctx) }
            thetaDisplayName(name) + " · " + stringResource(if (name.isNotBlank()) R.string.c360_paired else R.string.c360_not_paired)
        }
    }
}

private fun appVersion(ctx: Context): String? =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()

/** About: the version and when it was installed, the intro again, the licences of what the app is built with. */
private fun androidx.compose.foundation.lazy.LazyListScope.aboutPage(onReplayOnboarding: () -> Unit) {
    item {
        val ctx = LocalContext.current
        // the version, and when this copy was installed or last updated (which build is on the phone)
        val info = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
        WhiteBox {
            Text(stringResource(R.string.app_name), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Palette.Ink)
            if (info != null) {
                val installed = remember(info) {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(info.lastUpdateTime))
                }
                Hint(stringResource(R.string.settings_about_version, info.versionName.orEmpty()) + "\n" +
                    stringResource(R.string.settings_installed, installed))
            }
        }
    }
    item {
        NavGroup(listOf(NavItem("📖", stringResource(R.string.settings_replay_onboarding), stringResource(R.string.settings_replay_sub),
            onClick = onReplayOnboarding)))
    }
    item { SectionLabel(stringResource(R.string.settings_about_licences)) }
    item {
        val lines = listOf(
            stringResource(R.string.about_lic_app) to "Apache License 2.0",
            stringResource(R.string.about_lic_osm) to "ODbL 1.0",
            stringResource(R.string.about_lic_vnts) to "CC BY-SA 4.0",
            "YOLOX (Megvii)" to "Apache License 2.0",
            "ONNX Runtime (Microsoft)" to "MIT License",
            stringResource(R.string.about_lic_font) to "SIL Open Font License 1.1",
        )
        WhiteBox {
            lines.forEachIndexed { i, (what, licence) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(what, color = Palette.Ink, fontSize = 14.sp)
                    Text(licence, color = Palette.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (i < lines.lastIndex) HorizontalDivider(color = Palette.Line)
            }
        }
    }
    item { Hint(stringResource(R.string.about_notice), Modifier.padding(horizontal = 4.dp)) }
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
