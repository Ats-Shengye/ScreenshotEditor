package com.example.screenshoteditor.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.screenshoteditor.R
import com.example.screenshoteditor.data.*
import com.example.screenshoteditor.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val ACTION_SAVE = "save"
        const val ACTION_COPY_DISCARD = "copy_discard"
        const val ACTION_DISCARD = "discard"
    }
    
    private lateinit var binding: ActivityEditorBinding
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var mediaSaver: MediaStoreSaver
    private lateinit var clipboardShare: ClipboardShare
    
    private var imagePath: String? = null
    private var originalBitmap: Bitmap? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsDataStore = SettingsDataStore(this)
        mediaSaver = MediaStoreSaver(this)
        clipboardShare = ClipboardShare(this)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)

        if (imagePath == null) {
            Toast.makeText(this, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadImage()
        setupViews()
    }
    
    private fun loadImage() {
        imagePath?.let { path ->
            try {
                val file = File(path)

                originalBitmap = BitmapFactory.decodeFile(path, null)
                if (originalBitmap != null) {
                    binding.cropView.setBitmap(originalBitmap!!)
                } else {
                    Toast.makeText(this, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun setupViews() {
        binding.btnSave.setOnClickListener {
            executeAction(ACTION_SAVE)
        }
        
        binding.btnCopy.setOnClickListener {
            executeAction(ACTION_COPY_DISCARD)
        }
        
        binding.btnShare.setOnClickListener {
            handleShare()
        }
        
        binding.btnCancel.setOnClickListener {
            executeAction(ACTION_DISCARD)
        }
        
        // Toolbar buttons
        
        binding.btnReset?.setOnClickListener {
            binding.cropView.reset()
        }
        
        binding.btnAspectRatio?.setOnClickListener {
            showAspectRatioDialog()
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 現在のレイアウトではメニューを使用しない
        return false
    }
    
    private fun handleComplete() {
        lifecycleScope.launch {
            val settings = settingsDataStore.settings.first()
            
            if (settings.rememberAction && settings.rememberedAction != null) {
                // Execute remembered action
                executeAction(settings.rememberedAction)
            } else {
                // Show action dialog
                showActionDialog(settings.rememberAction)
            }
        }
    }
    
    private fun showActionDialog(rememberEnabled: Boolean) {
        val items = arrayOf(
            getString(R.string.dialog_save),
            getString(R.string.dialog_copy_and_discard),
            getString(R.string.dialog_discard)
        )
        
        var selectedAction: String? = null
        var shouldRemember = false
        
        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_save_options_title)
            .setSingleChoiceItems(items, -1) { _, which ->
                selectedAction = when (which) {
                    0 -> ACTION_SAVE
                    1 -> ACTION_COPY_DISCARD
                    2 -> ACTION_DISCARD
                    else -> null
                }
            }
        
        if (rememberEnabled) {
            dialogBuilder.setMultiChoiceItems(
                arrayOf("この選択を記憶する"),
                booleanArrayOf(false)
            ) { _, _, isChecked ->
                shouldRemember = isChecked
            }
        }
        
        dialogBuilder
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedAction?.let { action ->
                    if (shouldRemember) {
                        lifecycleScope.launch {
                            settingsDataStore.updateRememberedAction(action)
                        }
                    }
                    executeAction(action)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    private fun executeAction(action: String) {
        lifecycleScope.launch {
            val croppedBitmap = binding.cropView.getCroppedBitmap()
            if (croppedBitmap == null) {
                Toast.makeText(this@EditorActivity, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            when (action) {
                ACTION_SAVE -> {
                    saveBitmap(croppedBitmap)
                }
                ACTION_COPY_DISCARD -> {
                    copyToClipboard(croppedBitmap)
                    deleteTempFile()
                    finish()
                }
                ACTION_DISCARD -> {
                    deleteTempFile()
                    finish()
                }
            }
        }
    }
    
    private suspend fun saveBitmap(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val success = mediaSaver.saveBitmapToGallery(bitmap)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@EditorActivity, R.string.message_saved, Toast.LENGTH_SHORT).show()
                    deleteTempFile()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, R.string.message_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private suspend fun copyToClipboard(bitmap: Bitmap) {
        val cacheFile = TempCache.getCacheFile(this@EditorActivity)
        withContext(Dispatchers.IO) {
            val success = clipboardShare.copyImageToClipboard(bitmap, cacheFile)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@EditorActivity, R.string.message_copied, Toast.LENGTH_SHORT).show()
                    
                    // Auto clear clipboard if enabled
                    val settings = settingsDataStore.settings.first()
                    if (settings.autoClearClipboard) {
                        clipboardShare.scheduleClear(settings.clearSeconds)
                    }
                } else {
                    Toast.makeText(this@EditorActivity, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun handleShare() {
        lifecycleScope.launch {
            val croppedBitmap = binding.cropView.getCroppedBitmap()
            if (croppedBitmap == null) {
                Toast.makeText(this@EditorActivity, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val shareFile = TempCache.getCacheFile(this@EditorActivity)
            withContext(Dispatchers.IO) {
                clipboardShare.shareImage(croppedBitmap, shareFile)
            }
        }
    }
    
    private fun showAspectRatioDialog() {
        val ratios = arrayOf("自由", "1:1", "4:3", "16:9", "9:16")
        val ratioValues = arrayOf(null, 1f, 4f/3f, 16f/9f, 9f/16f)
        
        AlertDialog.Builder(this)
            .setTitle("縦横比")
            .setItems(ratios) { _, which ->
                binding.cropView.setAspectRatio(ratioValues[which])
            }
            .show()
    }
    
    private fun deleteTempFile() {
        imagePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        originalBitmap?.recycle()
    }
}
