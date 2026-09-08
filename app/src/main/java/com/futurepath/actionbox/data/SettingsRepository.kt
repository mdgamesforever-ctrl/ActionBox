package com.futurepath.actionbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "actionbox_settings")

/**
 * User-configurable app settings, persisted via Jetpack DataStore (survives process death,
 * unlike in-memory state) rather than Room — these are small standalone flags with no
 * relationship to the notification/learning tables, so a separate lightweight store is a
 * better fit than adding columns to an unrelated entity.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

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

    suspend fun setCorrectionLearningEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[CORRECTION_LEARNING_ENABLED] = enabled }
    }

    suspend fun setPro(isPro: Boolean) {
        dataStore.edit { prefs -> prefs[IS_PRO] = isPro }
    }

    companion object {
        private val CORRECTION_LEARNING_ENABLED = booleanPreferencesKey("correction_learning_enabled")
        private val IS_PRO = booleanPreferencesKey("is_pro")

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
