package com.futurepath.actionbox.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * One entry in Settings -> Appearance -> Language. This is deliberately unrelated to
 * [ThemeMode]'s DataStore-backed persistence: [AppCompatDelegate.setApplicationLocales] is the
 * officially recommended API for a per-app UI language preference (see
 * https://developer.android.com/guide/topics/resources/app-languages), and it already persists
 * the choice itself — on API 33+ it delegates straight to the system LocaleManager, and on API
 * 26-32 (this app's minSdk is 26) the androidx.appcompat dependency auto-merges a manifest entry
 * that stores/restores the choice in its own private SharedPreferences file across process
 * restarts. Reinventing that in DataStore would just be a second, redundant source of truth.
 *
 * [tag] is a BCP-47 language tag matched against a values-<tag> resource folder (res/values-ar,
 * values-es, ...). [nativeName] is what the picker shows: each language's own name in its own
 * script ("العربية", not "Arabic") is what a user scanning for their language actually recognizes.
 *
 * IMPORTANT — this only changes ActionBox's own UI strings (buttons, labels, category names,
 * Settings text). It has no effect on and no relationship to how notifications are classified —
 * see NotificationClassifier's Arabic pattern coverage for that separate, unrelated feature.
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM_DEFAULT(null, "System default"),
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    PORTUGUESE("pt", "Português"),
    HINDI("hi", "हिन्दी"),
    TURKISH("tr", "Türkçe"),
    // "in" (not the modern ISO "id") deliberately matches Android's own long-standing resource-
    // folder convention for Indonesian (values-in) — the framework treats "in"/"id" as aliases
    // of the same locale either way, but using "in" consistently for both the applied locale tag
    // and the resource folder name avoids depending on that aliasing at all.
    INDONESIAN("in", "Bahasa Indonesia"),
    URDU("ur", "اردو");

    /** Applies this as the app's UI language. `null` [tag] (SYSTEM_DEFAULT) means "follow the device's OS language setting" — [LocaleListCompat.getEmptyLocaleList] is exactly that instruction, not "no preference stored yet". */
    fun apply() {
        val locales = tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        /** The currently-applied per-app language — [SYSTEM_DEFAULT] when none has been set. */
        fun current(): AppLanguage {
            val applied = AppCompatDelegate.getApplicationLocales()
            if (applied.isEmpty) return SYSTEM_DEFAULT
            val appliedTag = applied[0]?.language
            return entries.firstOrNull { it.tag == appliedTag } ?: SYSTEM_DEFAULT
        }
    }
}
