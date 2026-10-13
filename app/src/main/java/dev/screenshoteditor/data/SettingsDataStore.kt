package dev.screenshoteditor.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(ObsoleteSettingsMigration) }
)

/**
 * Allowed values and defaults for the persisted settings.
 *
 * Trust Boundary: values read from disk are treated as untrusted (they may come from an older app
 * version or a corrupted file) and are mapped back to a default when they are not in the allowed
 * set. Writes outside the allowed set are rejected so that the UI cannot persist an invalid value.
 */
object SettingsRules {
    /** Capture delay choices offered by the settings screen. 0 means immediate capture. */
    val ALLOWED_DELAY_SECONDS: Set<Int> = setOf(0, 1, 2, 3, 5)

    /** Clipboard auto-clear choices offered by the settings screen. 0 means "never clear". */
    val ALLOWED_CLEAR_SECONDS: Set<Int> = setOf(0, 15, 30, 60)

    /**
     * Immediate capture by default. Previously the default was "immediate = true, delay = 3",
     * which the settings screen could not represent and silently rewrote to a 3 second delay.
     */
    const val DEFAULT_DELAY_SECONDS = 0

    /**
     * Design Decision: auto-clear is disabled by default (opt-in). The app cannot tell whether the
     * clipboard still holds its own clip when the timer fires (Android 10+ blocks clipboard reads
     * from unfocused apps), so clearing by default would also wipe whatever the user copied in
     * another app in the meantime.
     * Security Note: a copied screenshot stays readable by any app that later gets focus or by the
     * clipboard history until something else replaces it. Users who care can pick 15/30/60 seconds.
     */
    const val DEFAULT_CLEAR_SECONDS = 0

    /** Capture is refused while the device is locked unless explicitly disabled. */
    const val DEFAULT_DISABLE_ON_LOCK = true

    fun sanitizeDelaySeconds(raw: Int?): Int = raw?.takeIf { it in ALLOWED_DELAY_SECONDS } ?: DEFAULT_DELAY_SECONDS

    fun sanitizeClearSeconds(raw: Int?): Int = raw?.takeIf { it in ALLOWED_CLEAR_SECONDS } ?: DEFAULT_CLEAR_SECONDS
}

/**
 * Removes keys written by earlier versions that no longer have any reader:
 * immediate_capture (now derived from delay_seconds), auto_clear_clipboard (now derived from
 * clear_seconds), remember_action / remembered_action / persistent_notification /
 * notification_confirmation (features removed). Keeping unused data violates data minimisation.
 */
internal object ObsoleteSettingsMigration : DataMigration<Preferences> {
    private val obsoleteBooleanKeys = listOf(
        booleanPreferencesKey("immediate_capture"),
        booleanPreferencesKey("auto_clear_clipboard"),
        booleanPreferencesKey("remember_action"),
        booleanPreferencesKey("persistent_notification"),
        booleanPreferencesKey("notification_confirmation")
    )
    private val obsoleteRememberedAction = stringPreferencesKey("remembered_action")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        obsoleteBooleanKeys.any { it in currentData } || obsoleteRememberedAction in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        obsoleteBooleanKeys.forEach { mutable.remove(it) }
        mutable.remove(obsoleteRememberedAction)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

class SettingsDataStore(context: Context) {

    // Activity Leak Prevention: the DataStore is process-wide, so only the application context is kept.
    private val appContext = context.applicationContext

    private companion object {
        const val TAG = "SettingsDataStore"
        val KEY_DELAY_SECONDS = intPreferencesKey("delay_seconds")
        val KEY_CLEAR_SECONDS = intPreferencesKey("clear_seconds")
        val KEY_DISABLE_ON_LOCK = booleanPreferencesKey("disable_on_lock")
    }

    /**
     * @property delaySeconds    Seconds to wait between consent and capture. 0 = immediate.
     * @property clearSeconds    Seconds until the copied screenshot is cleared from the clipboard.
     *                           0 = never clear.
     * @property disableOnLock   Refuse to capture while the keyguard is shown. No UI exposes this;
     *                           it stays at its secure default unless the stored value says otherwise.
     */
    data class Settings(
        val delaySeconds: Int = SettingsRules.DEFAULT_DELAY_SECONDS,
        val clearSeconds: Int = SettingsRules.DEFAULT_CLEAR_SECONDS,
        val disableOnLock: Boolean = SettingsRules.DEFAULT_DISABLE_ON_LOCK
    ) {
        val isDelayedCapture: Boolean get() = delaySeconds > 0
        val isClipboardAutoClearEnabled: Boolean get() = clearSeconds > 0
    }

    /**
     * Fail-Safe: an unreadable settings file yields the defaults (which are the secure values)
     * instead of crashing every screen that reads settings.
     */
    val settings: Flow<Settings> = appContext.dataStore.data.catch { e ->
        if (e is IOException) {
            Log.w(TAG, "settings: read failed, using defaults", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }.map { preferences ->
        Settings(
            delaySeconds = SettingsRules.sanitizeDelaySeconds(preferences[KEY_DELAY_SECONDS]),
            clearSeconds = SettingsRules.sanitizeClearSeconds(preferences[KEY_CLEAR_SECONDS]),
            disableOnLock = preferences[KEY_DISABLE_ON_LOCK] ?: SettingsRules.DEFAULT_DISABLE_ON_LOCK
        )
    }

    /** @throws IllegalArgumentException when [value] is not one of [SettingsRules.ALLOWED_DELAY_SECONDS]. */
    suspend fun updateDelaySeconds(value: Int) {
        require(value in SettingsRules.ALLOWED_DELAY_SECONDS) { "Unsupported delay: $value" }
        appContext.dataStore.edit { it[KEY_DELAY_SECONDS] = value }
    }

    /** @throws IllegalArgumentException when [value] is not one of [SettingsRules.ALLOWED_CLEAR_SECONDS]. */
    suspend fun updateClearSeconds(value: Int) {
        require(value in SettingsRules.ALLOWED_CLEAR_SECONDS) { "Unsupported clear delay: $value" }
        appContext.dataStore.edit { it[KEY_CLEAR_SECONDS] = value }
    }

    suspend fun resetAllToDefaults() {
        appContext.dataStore.edit { it.clear() }
    }
}
