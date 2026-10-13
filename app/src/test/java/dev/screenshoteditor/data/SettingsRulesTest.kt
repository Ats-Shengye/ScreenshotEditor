package dev.screenshoteditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsRules] (validation of persisted values) and the derived flags on
 * [SettingsDataStore.Settings].
 */
class SettingsRulesTest {

    @Test
    fun defaults_areImmediateCaptureAndClipboardClearDisabled() {
        val defaults = SettingsDataStore.Settings()

        // Regression guard: the old defaults (immediate=true, delay=3) could not be shown by the
        // settings screen and turned into a 3 second delay as soon as the screen was opened.
        assertEquals(0, defaults.delaySeconds)
        assertFalse(defaults.isDelayedCapture)
        // Auto-clear is opt-in: it cannot tell its own clip apart from what other apps copied later.
        assertEquals(0, defaults.clearSeconds)
        assertFalse(defaults.isClipboardAutoClearEnabled)
        assertTrue(defaults.disableOnLock)
    }

    @Test
    fun defaults_areThemselvesAllowedValues() {
        assertTrue(SettingsRules.DEFAULT_DELAY_SECONDS in SettingsRules.ALLOWED_DELAY_SECONDS)
        assertTrue(SettingsRules.DEFAULT_CLEAR_SECONDS in SettingsRules.ALLOWED_CLEAR_SECONDS)
    }

    @Test
    fun sanitizeDelaySeconds_allowedValues_arePreserved() {
        for (value in SettingsRules.ALLOWED_DELAY_SECONDS) {
            assertEquals(value, SettingsRules.sanitizeDelaySeconds(value))
        }
    }

    @Test
    fun sanitizeDelaySeconds_missingOrUnknownValues_fallBackToDefault() {
        // 4 is not offered by the UI; negative and huge values could only come from a corrupted file.
        for (value in listOf(null, -1, 4, 10, Int.MAX_VALUE)) {
            assertEquals(SettingsRules.DEFAULT_DELAY_SECONDS, SettingsRules.sanitizeDelaySeconds(value))
        }
    }

    @Test
    fun sanitizeClearSeconds_zeroMeansNeverAndIsPreserved() {
        assertEquals(0, SettingsRules.sanitizeClearSeconds(0))
        assertFalse(SettingsDataStore.Settings(clearSeconds = 0).isClipboardAutoClearEnabled)
    }

    @Test
    fun sanitizeClearSeconds_missingOrUnknownValues_fallBackToDefault() {
        for (value in listOf(null, -15, 1, 45, 3600)) {
            assertEquals(SettingsRules.DEFAULT_CLEAR_SECONDS, SettingsRules.sanitizeClearSeconds(value))
        }
    }
}
