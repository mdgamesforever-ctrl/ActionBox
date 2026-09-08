package com.futurepath.actionbox.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * One entry in Settings -> Appearance -> Language. The selection itself is persisted via
 * [SettingsRepository.appLanguage]/[SettingsRepository.setAppLanguage] (DataStore, same pattern
 * as [ThemeMode]) — NOT read back from [AppCompatDelegate.getApplicationLocales] the way an
 * earlier version of this file did. That turned out to be unreliable as a source of truth: it's
 * in-process runtime state that isn't restored across process death unless the app also declares
 * AppCompat's own opt-in `AppLocalesMetadataHolderService` auto-storage service in its manifest
 * (verified by extracting the real androidx.appcompat:appcompat:1.7.0 AAR's own manifest, which
 * declares nothing beyond a bare `<uses-sdk>` — none of that wiring is auto-merged just by adding
 * the dependency), which this app never did. DataStore is a single, always-correct, and directly
 * unit-testable source of truth instead — see [resolveAppLanguage].
 *
 * [apply] (AppCompatDelegate) is still called on top of that, purely so the OS's own per-app
 * language record — the "App language" row in the system Settings app on API 33+, powered by the
 * `android:localeConfig` manifest entry — stays in sync with our own stored choice. The actual
 * in-app resource localization for THIS app's own Compose UI on every API level (26+) instead
 * goes through explicit `Context` wrapping in `attachBaseContext` (see MainActivity and
 * ActionBoxApplication), which doesn't depend on any AppCompatDelegate behavior that can't be
 * directly verified by reading the code.
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
    ITALIAN("it", "Italiano"),
    TURKISH("tr", "Türkçe"),
    // "in" (not the modern ISO "id") deliberately matches Android's own long-standing resource-
    // folder convention for Indonesian (values-in) — the framework treats "in"/"id" as aliases
    // of the same locale either way, but using "in" consistently for both the applied locale tag
    // and the resource folder name avoids depending on that aliasing at all.
    INDONESIAN("in", "Bahasa Indonesia"),
    URDU("ur", "اردو");

    /**
     * Syncs the OS's own per-app language record — see this enum's class doc for why this is a
     * secondary, system-integration-only mechanism rather than what this app relies on for its
     * own UI to actually render in the right language. `null` [tag] (SYSTEM_DEFAULT) means
     * "follow the device's OS language setting" — [LocaleListCompat.getEmptyLocaleList] is
     * exactly that instruction, not "no preference stored yet".
     *
     * Deliberately NOT named `apply` — that collides in spirit (though not in overload
     * resolution) with Kotlin's stdlib `T.apply { }` scope function and reads confusingly at
     * call sites like `language.apply()`.
     */
    fun syncToSystemLocaleRecord() {
        val locales = tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

/**
 * Parses a [SettingsRepository]-stored enum name back into an [AppLanguage], falling back to
 * [AppLanguage.SYSTEM_DEFAULT] for a missing or unrecognized value — the same pattern
 * [SettingsRepository.themeMode] uses for [ThemeMode], pulled into a standalone function so it's
 * directly unit-testable without a DataStore/Context in the loop (see AppLanguageTest).
 */
internal fun resolveAppLanguage(stored: String?): AppLanguage {
    if (stored == null) return AppLanguage.SYSTEM_DEFAULT
    return try {
        AppLanguage.valueOf(stored)
    } catch (e: IllegalArgumentException) {
        AppLanguage.SYSTEM_DEFAULT
    }
}
