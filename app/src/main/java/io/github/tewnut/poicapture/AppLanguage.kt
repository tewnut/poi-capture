package io.github.tewnut.poicapture

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's UI language: the phone's by default, or English / Vietnamese picked in Settings.
 * - Android 13+: the system per-app language (LocaleManager; also in the system settings, see locales_config.xml).
 * - Android 11-12: the choice is kept in Prefs and applied by [wrap] in each component's attachBaseContext.
 */
object AppLanguage {
    /** Language tags offered in Settings; "" = follow the phone. */
    const val SYSTEM = ""
    val TAGS = listOf(SYSTEM, "en", "vi")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("road-capture", Context.MODE_PRIVATE)

    /** The current choice: "", "en" or "vi". */
    fun current(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            return if (l.isEmpty) SYSTEM else l[0].language
        }
        return sp(ctx).getString("language", SYSTEM) ?: SYSTEM
    }

    /** Switch language; the activity is recreated (by the system on 13+, here before). */
    fun set(activity: Activity, tag: String) {
        if (tag == current(activity)) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            sp(activity).edit().putString("language", tag).commit()
            activity.recreate()
        }
    }

    /** Android 11-12: [base] with the language picked in Settings (13+ and "follow the phone": [base] itself). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = sp(base).getString("language", SYSTEM) ?: SYSTEM
        if (tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
