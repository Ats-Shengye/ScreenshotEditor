package com.example.screenshoteditor.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.screenshoteditor.data.SettingsDataStore
import com.example.screenshoteditor.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsDataStore: SettingsDataStore
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsDataStore = SettingsDataStore(this)
        
        setupViews()
        loadSettings()
    }
    
    private fun setupViews() {
        supportActionBar?.title = "設定"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // 撮影タイミング設定
        binding.rgCaptureDelay.setOnCheckedChangeListener { _, checkedId ->
            lifecycleScope.launch {
                val delay = when (checkedId) {
                    binding.rbDelayNone.id -> 0
                    binding.rbDelay1s.id -> 1
                    binding.rbDelay2s.id -> 2
                    binding.rbDelay3s.id -> 3
                    binding.rbDelay5s.id -> 5
                    else -> 0
                }
                settingsDataStore.updateDelaySeconds(delay)
                settingsDataStore.updateImmediateCapture(delay == 0)
            }
        }
        
        // 完了アクション記憶設定
        binding.switchRememberAction.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsDataStore.updateRememberAction(isChecked)
            }
        }
        
        // クリップボード自動クリア設定
        binding.rgClipboardClear.setOnCheckedChangeListener { _, checkedId ->
            lifecycleScope.launch {
                val clearTime = when (checkedId) {
                    binding.rbClear15s.id -> 15
                    binding.rbClear30s.id -> 30
                    binding.rbClear60s.id -> 60
                    binding.rbClearNever.id -> 0
                    else -> 0
                }
                settingsDataStore.updateClearSeconds(clearTime)
            }
        }
        
        // 通知設定
        binding.switchShowNotification.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsDataStore.updatePersistentNotification(isChecked)
            }
        }
        
        // デフォルトに戻すボタン
        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
        }
    }
    
    private fun loadSettings() {
        lifecycleScope.launch {
            val currentSettings = settingsDataStore.settings.first()
            
            // 撮影タイミング
            when (currentSettings.delaySeconds) {
                0 -> binding.rbDelayNone.isChecked = true
                1 -> binding.rbDelay1s.isChecked = true
                2 -> binding.rbDelay2s.isChecked = true
                3 -> binding.rbDelay3s.isChecked = true
                5 -> binding.rbDelay5s.isChecked = true
            }
            
            // 完了アクション記憶
            binding.switchRememberAction.isChecked = currentSettings.rememberAction
            
            // クリップボード自動クリア
            when (currentSettings.clearSeconds) {
                15 -> binding.rbClear15s.isChecked = true
                30 -> binding.rbClear30s.isChecked = true
                60 -> binding.rbClear60s.isChecked = true
                0 -> binding.rbClearNever.isChecked = true
            }
            
            // 通知設定
            binding.switchShowNotification.isChecked = currentSettings.persistentNotification
        }
    }
    
    private fun resetToDefaults() {
        lifecycleScope.launch {
            settingsDataStore.resetAllToDefaults()
            loadSettings()
            Toast.makeText(this@SettingsActivity, "設定をリセットしました", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
