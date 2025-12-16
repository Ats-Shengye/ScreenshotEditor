package com.example.screenshoteditor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private const val DEFAULT_IMMEDIATE_CAPTURE = true
        private const val DEFAULT_DELAY_SECONDS = 3
        private const val DEFAULT_REMEMBER_ACTION = false
        private const val DEFAULT_AUTO_CLEAR_CLIPBOARD = false
        private const val DEFAULT_CLEAR_SECONDS = 60
        private const val DEFAULT_PERSISTENT_NOTIFICATION = true
        private const val DEFAULT_NOTIFICATION_CONFIRMATION = false
        private const val DEFAULT_DISABLE_ON_LOCK = true

        private val KEY_IMMEDIATE_CAPTURE = booleanPreferencesKey("immediate_capture")
        private val KEY_DELAY_SECONDS = intPreferencesKey("delay_seconds")
        private val KEY_REMEMBER_ACTION = booleanPreferencesKey("remember_action")
        private val KEY_REMEMBERED_ACTION = stringPreferencesKey("remembered_action")
        private val KEY_AUTO_CLEAR_CLIPBOARD = booleanPreferencesKey("auto_clear_clipboard")
        private val KEY_CLEAR_SECONDS = intPreferencesKey("clear_seconds")
        private val KEY_PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
        private val KEY_NOTIFICATION_CONFIRMATION = booleanPreferencesKey("notification_confirmation")
        private val KEY_DISABLE_ON_LOCK = booleanPreferencesKey("disable_on_lock")
    }
    
    data class Settings(
        val immediateCapture: Boolean = DEFAULT_IMMEDIATE_CAPTURE,
        val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
        val rememberAction: Boolean = DEFAULT_REMEMBER_ACTION,
        val rememberedAction: String? = null,
        val autoClearClipboard: Boolean = DEFAULT_AUTO_CLEAR_CLIPBOARD,
        val clearSeconds: Int = DEFAULT_CLEAR_SECONDS,
        val persistentNotification: Boolean = DEFAULT_PERSISTENT_NOTIFICATION,
        val notificationConfirmation: Boolean = DEFAULT_NOTIFICATION_CONFIRMATION,
        val disableOnLock: Boolean = DEFAULT_DISABLE_ON_LOCK
    )
    
    val settings: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            immediateCapture = preferences[KEY_IMMEDIATE_CAPTURE] ?: DEFAULT_IMMEDIATE_CAPTURE,
            delaySeconds = preferences[KEY_DELAY_SECONDS] ?: DEFAULT_DELAY_SECONDS,
            rememberAction = preferences[KEY_REMEMBER_ACTION] ?: DEFAULT_REMEMBER_ACTION,
            rememberedAction = preferences[KEY_REMEMBERED_ACTION],
            autoClearClipboard = preferences[KEY_AUTO_CLEAR_CLIPBOARD] ?: DEFAULT_AUTO_CLEAR_CLIPBOARD,
            clearSeconds = preferences[KEY_CLEAR_SECONDS] ?: DEFAULT_CLEAR_SECONDS,
            persistentNotification = preferences[KEY_PERSISTENT_NOTIFICATION] ?: DEFAULT_PERSISTENT_NOTIFICATION,
            notificationConfirmation = preferences[KEY_NOTIFICATION_CONFIRMATION] ?: DEFAULT_NOTIFICATION_CONFIRMATION,
            disableOnLock = preferences[KEY_DISABLE_ON_LOCK] ?: DEFAULT_DISABLE_ON_LOCK
        )
    }
    
    suspend fun updateImmediateCapture(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IMMEDIATE_CAPTURE] = value
        }
    }
    
    suspend fun updateDelaySeconds(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DELAY_SECONDS] = value
        }
    }
    
    suspend fun updateRememberAction(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_REMEMBER_ACTION] = value
        }
    }
    
    suspend fun updateRememberedAction(value: String?) {
        context.dataStore.edit { preferences ->
            if (value != null) {
                preferences[KEY_REMEMBERED_ACTION] = value
            } else {
                preferences.remove(KEY_REMEMBERED_ACTION)
            }
        }
    }
    
    suspend fun updateAutoClearClipboard(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_CLEAR_CLIPBOARD] = value
        }
    }
    
    suspend fun updateClearSeconds(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CLEAR_SECONDS] = value
        }
    }
    
    suspend fun updatePersistentNotification(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PERSISTENT_NOTIFICATION] = value
        }
    }
    
    suspend fun updateNotificationConfirmation(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_CONFIRMATION] = value
        }
    }
    
    suspend fun updateDisableOnLock(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DISABLE_ON_LOCK] = value
        }
    }
    
    suspend fun resetRememberedAction() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_REMEMBERED_ACTION)
        }
    }

    suspend fun resetAllToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}
