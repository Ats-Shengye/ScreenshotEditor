package dev.screenshoteditor.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.screenshoteditor.R
import dev.screenshoteditor.data.*
import dev.screenshoteditor.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EditorActivity"
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

        setupImmersiveMode()

        settingsDataStore = SettingsDataStore(this)
        mediaSaver = MediaStoreSaver(this)
        clipboardShare = ClipboardShare(this)

        val rawPath = intent.getStringExtra(EXTRA_IMAGE_PATH)

        if (rawPath == null) {
            Toast.makeText(this, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // パストラバーサル攻撃を防ぐため、受け取ったパスがtempDir配下であることを検証する
        if (!isPathSafe(rawPath)) {
            Log.w(TAG, "onCreate: invalid image path rejected: $rawPath")
            Toast.makeText(this, "無効なファイルパスです", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        imagePath = rawPath
        loadImage()
        setupViews()
    }
    
    /**
     * EditorActivity のみで適用する immersive sticky 設定。
     *
     * トリミング操作中に画面上端をタップしたときに、ステータスバーが
     * 降りてきてクロップ操作と干渉する問題を回避する目的。
     *
     * - ステータスバーのみ非表示（ナビゲーションバーは維持）
     * - 上端スワイプでステータスバーを一時表示可能
     *   （[WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]）
     * - MainActivity / SettingsActivity では通常表示のまま
     *
     * AppBar（[binding.actionBar]）の底部パディングには
     * システムバーのインセットを加算し、ナビゲーションバーと
     * コンテンツの間隔を維持する。
     */
    private fun setupImmersiveMode() {
        // システムバーがコンテンツ領域に重なることを許可（edge-to-edge 化）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        // ステータスバーを非表示にする
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // 上端スワイプで一時表示→自動で再非表示（タップ干渉を防ぐ）
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // XML で指定された初期パディングをリスナー登録前に取得する。
        // コールバック内で view.paddingBottom を読むと、2 回目以降は
        // systemBars.bottom が上書きされた値になり累積するため、事前取得が必須。
        val initialBottomPadding = binding.actionBar.paddingBottom

        // action_bar（下部ボタン群）に初期パディング + ナビゲーションバー分の
        // 底部インセットを合算して適用する。
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }
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
                Log.w(TAG, "loadImage failed", e)
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
    
    /**
     * 受け取ったファイルパスがアプリのtempDir配下であることを検証する。
     * パストラバーサル（"../"等）による任意ファイルアクセスを防ぐため、
     * canonicalPathで正規化したパスを比較する。
     */
    private fun isPathSafe(path: String): Boolean {
        return try {
            val file = File(path)
            val allowedDir = TempCache.getTempDir(this).canonicalPath
            val canonicalPath = file.canonicalPath
            canonicalPath.startsWith(allowedDir + File.separator) || canonicalPath == allowedDir
        } catch (e: Exception) {
            Log.w(TAG, "isPathSafe: path validation failed", e)
            false
        }
    }

    private fun deleteTempFile() {
        imagePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "deleteTempFile failed: $path", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        originalBitmap?.recycle()
    }
}
