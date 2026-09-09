package com.futurepath.actionbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "actionbox_settings")

/** Wall-clock time of day the daily digest fires — see [SettingsRepository.digestTime]. */
data class DigestTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "hour must be 0-23, was $hour" }
        require(minute in 0..59) { "minute must be 0-59, was $minute" }
    }
}

/** The app-wide appearance preference — see [SettingsRepository.themeMode] and
 * [com.futurepath.actionbox.ui.theme.ActionBoxTheme]. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * User-configurable app settings, persisted via Jetpack DataStore (survives process death,
 * unlike in-memory state) rather than Room — these are small standalone flags with no
 * relationship to the notification/learning tables, so a separate lightweight store is a
 * better fit than adding columns to an unrelated entity.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.settingsDataStore

    /**
     * Whether [NotificationRepository] applies local correction-learning boosts when
     * classifying new notifications (see NotificationRepository.learningBoostsFor). Disabling
     * this does NOT erase the learning history in LearningPatternDao or stop the user from
     * correcting individual notifications — it only stops those past corrections from biasing
     * future classification, so re-enabling it later picks the learned patterns back up.
     */
    val correctionLearningEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CORRECTION_LEARNING_ENABLED] ?: true
    }

    /**
     * Whether this device is on the Pro plan. There's no real billing/subscription
     * integration yet — this is a manual toggle in Settings standing in for that until one
     * exists, so the retention-limit enforcement it gates (see
     * NotificationRepository.enforceRetentionPolicy) has something real to test against.
     */
    val isPro: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[IS_PRO] ?: false
    }

    /**
     * Whether the reminder system — the daily digest AND WAITING follow-up nudges (see
     * [DigestWorker][com.futurepath.actionbox.reminders.DigestWorker] and
     * [WaitingNudgeWorker][com.futurepath.actionbox.reminders.WaitingNudgeWorker]) — is on.
     * One switch for both rather than two: they're both "ActionBox proactively tells you
     * something" notifications, and splitting them into separate toggles is more settings-
     * screen complexity than the difference is worth for v1.
     */
    val digestsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DIGESTS_ENABLED] ?: true
    }

    /** Defaults to 9:00 AM — see [DigestTime]. */
    val digestTime: Flow<DigestTime> = dataStore.data.map { prefs ->
        DigestTime(
            hour = prefs[DIGEST_HOUR] ?: DEFAULT_DIGEST_HOUR,
            minute = prefs[DIGEST_MINUTE] ?: DEFAULT_DIGEST_MINUTE
        )
    }.distinctUntilChanged()

    /**
     * The user's chosen appearance — defaults to following the system setting. Stored by enum
     * name (not ordinal) so reordering [ThemeMode]'s declaration later can't silently remap a
     * previously-saved choice to a different mode; an unrecognized/corrupted stored value falls
     * back to [ThemeMode.SYSTEM] the same way a missing one does.
     */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { stored ->
            try {
                ThemeMode.valueOf(stored)
            } catch (e: IllegalArgumentException) {
                ThemeMode.SYSTEM
            }
        } ?: ThemeMode.SYSTEM
    }.distinctUntilChanged()

    suspend fun setCorrectionLearningEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[CORRECTION_LEARNING_ENABLED] = enabled }
    }

    suspend fun setPro(isPro: Boolean) {
        dataStore.edit { prefs -> prefs[IS_PRO] = isPro }
    }

    suspend fun setDigestsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DIGESTS_ENABLED] = enabled }
    }

    suspend fun setDigestTime(time: DigestTime) {
        dataStore.edit { prefs ->
            prefs[DIGEST_HOUR] = time.hour
            prefs[DIGEST_MINUTE] = time.minute
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE] = mode.name }
    }

    /**
     * The user's chosen UI language — defaults to following the system setting. Stored by enum
     * name, same reasoning as [themeMode]. This is ActionBox's OWN, fully-owned source of truth
     * for the selection (see [AppLanguage]'s doc for why this replaced querying
     * [androidx.appcompat.app.AppCompatDelegate.getApplicationLocales] directly: that reads
     * transient in-process state that isn't reliably restored across process death without the
     * app declaring AppCompat's own opt-in auto-storage service, which this app never did —
     * DataStore is a single, always-correct, directly-testable source of truth instead).
     */
    val appLanguage: Flow<AppLanguage> = dataStore.data.map { prefs ->
        resolveAppLanguage(prefs[APP_LANGUAGE])
    }.distinctUntilChanged()

    /**
     * Also mirrors the choice into [EarlyAppLanguagePrefs] — see that object's doc for why
     * `attachBaseContext()` needs a completely separate, non-DataStore path to read this same
     * value back synchronously and safely.
     */
    suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { prefs -> prefs[APP_LANGUAGE] = language.name }
        EarlyAppLanguagePrefs.write(appContext, language)
    }

    companion object {
        private val CORRECTION_LEARNING_ENABLED = booleanPreferencesKey("correction_learning_enabled")
        private val IS_PRO = booleanPreferencesKey("is_pro")
        private val DIGESTS_ENABLED = booleanPreferencesKey("digests_enabled")
        private val DIGEST_HOUR = intPreferencesKey("digest_hour")
        private val DIGEST_MINUTE = intPreferencesKey("digest_minute")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val APP_LANGUAGE = stringPreferencesKey("app_language")

        const val DEFAULT_DIGEST_HOUR = 9
        const val DEFAULT_DIGEST_MINUTE = 0

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
