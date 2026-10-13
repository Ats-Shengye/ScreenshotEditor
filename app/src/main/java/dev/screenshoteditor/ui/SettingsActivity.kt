package dev.screenshoteditor.ui

import android.os.Bundle
import android.util.Log
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.screenshoteditor.R
import dev.screenshoteditor.data.SettingsDataStore
import dev.screenshoteditor.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "SettingsActivity"
        const val NO_SELECTION = -1
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsDataStore: SettingsDataStore

    /** Radio button id -> persisted value. Single source of truth for both reading and writing. */
    private lateinit var delayButtons: Map<Int, Int>
    private lateinit var clearButtons: Map<Int, Int>

    /**
     * Listeners are attached only after the stored values have been applied to the views.
     *
     * Applying a value programmatically (RadioButton.isChecked = true) fires the group's listener.
     * If the listener were already attached, merely opening this screen would write the displayed
     * value back, and any mismatch between the XML default and the stored value would silently
     * change the user's setting.
     */
    private val delayListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
        val seconds = delayButtons[checkedId] ?: return@OnCheckedChangeListener
        save { settingsDataStore.updateDelaySeconds(seconds) }
    }

    private val clearListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
        val seconds = clearButtons[checkedId] ?: return@OnCheckedChangeListener
        save { settingsDataStore.updateClearSeconds(seconds) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsDataStore = SettingsDataStore(this)
        delayButtons = mapOf(
            binding.rbDelayNone.id to 0,
            binding.rbDelay1s.id to 1,
            binding.rbDelay2s.id to 2,
            binding.rbDelay3s.id to 3,
            binding.rbDelay5s.id to 5
        )
        clearButtons = mapOf(
            binding.rbClear15s.id to 15,
            binding.rbClear30s.id to 30,
            binding.rbClear60s.id to 60,
            binding.rbClearNever.id to 0
        )

        supportActionBar?.setTitle(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.btnResetDefaults.setOnClickListener { resetToDefaults() }

        loadSettings()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            applyToViews(settingsDataStore.settings.first())
        }
    }

    /** Shows [settings] without writing anything back (listeners are detached while applying). */
    private fun applyToViews(settings: SettingsDataStore.Settings) {
        binding.rgCaptureDelay.setOnCheckedChangeListener(null)
        binding.rgClipboardClear.setOnCheckedChangeListener(null)

        check(binding.rgCaptureDelay, delayButtons, settings.delaySeconds)
        check(binding.rgClipboardClear, clearButtons, settings.clearSeconds)

        binding.rgCaptureDelay.setOnCheckedChangeListener(delayListener)
        binding.rgClipboardClear.setOnCheckedChangeListener(clearListener)
    }

    private fun check(group: RadioGroup, buttons: Map<Int, Int>, value: Int) {
        val id = buttons.entries.firstOrNull { it.value == value }?.key ?: NO_SELECTION
        if (id == NO_SELECTION) group.clearCheck() else group.check(id)
    }

    private fun save(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                // Only reachable if the id->value maps and SettingsRules drift apart (programming error).
                Log.w(TAG, "save: rejected value", e)
            } catch (e: IOException) {
                Log.w(TAG, "save: settings write failed", e)
                Toast.makeText(this@SettingsActivity, R.string.message_settings_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetToDefaults() {
        lifecycleScope.launch {
            try {
                settingsDataStore.resetAllToDefaults()
            } catch (e: IOException) {
                Log.w(TAG, "resetToDefaults: settings write failed", e)
                Toast.makeText(this@SettingsActivity, R.string.message_settings_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            applyToViews(settingsDataStore.settings.first())
            Toast.makeText(this@SettingsActivity, R.string.message_settings_reset, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
