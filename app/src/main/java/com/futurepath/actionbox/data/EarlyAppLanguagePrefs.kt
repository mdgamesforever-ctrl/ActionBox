package com.futurepath.actionbox.data

import android.content.Context

/**
 * A plain SharedPreferences mirror of [SettingsRepository.appLanguage], used ONLY from
 * `attachBaseContext()` overrides (see MainActivity, ActionBoxApplication) — the one place in
 * this app that genuinely cannot go through [SettingsRepository]/DataStore.
 *
 * Two real problems ruled that out, both confirmed by tracing actual AOSP source rather than
 * assumed:
 *
 * 1. `Application.attachBaseContext(context)` is invoked by `Application.attach()` BEFORE
 *    `LoadedApk.mApplication` is set to this Application instance (that assignment happens in
 *    the caller, `LoadedApk.makeApplicationInner()`, only after `Instrumentation.newApplication()`
 *    returns). `Context.getApplicationContext()` resolves to `LoadedApk.getApplication()` — so at
 *    this exact point in the lifecycle, `context.applicationContext` can legitimately return
 *    `null`. [SettingsRepository.getInstance] took a non-null `Context` parameter and immediately
 *    called `.applicationContext` on the value passed to it; the previous version of this file
 *    called that from inside `attachBaseContext`, so a null `Context!` (platform type) flowed
 *    into a non-null Kotlin parameter — an instant `NullPointerException`, before
 *    `CrashLogger.installGlobalHandler()` even runs in `onCreate()`, which is exactly why it
 *    never showed up in the in-app crash log.
 * 2. Independently of (1), DataStore's `Flow`-based API has no synchronous read — the previous
 *    version used `runBlocking { flow.first() }`, which is fragile precisely this early (before
 *    the app, and anything DataStore's implementation might depend on, has finished attaching).
 *
 * Plain `SharedPreferences` sidesteps both: `Context.getSharedPreferences()` needs no
 * `.applicationContext` (it resolves entirely against the calling Context's own package/data
 * directory, the same file regardless of which Context instance in this app you call it from),
 * and reading it is genuinely synchronous with no coroutine machinery involved at all.
 *
 * [SettingsRepository.setAppLanguage] writes here too, so this mirror and the DataStore-backed
 * reactive [SettingsRepository.appLanguage] (used everywhere else — the Settings UI, the
 * ViewModel's StateFlow) never disagree.
 */
internal object EarlyAppLanguagePrefs {
    private const val PREFS_NAME = "actionbox_early_prefs"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun read(context: Context): AppLanguage {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, null)
        return resolveAppLanguage(stored)
    }

    fun write(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, language.name)
            .apply()
    }
}
