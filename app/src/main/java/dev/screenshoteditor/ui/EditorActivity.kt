package dev.screenshoteditor.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.screenshoteditor.R
import dev.screenshoteditor.data.ClipboardShare
import dev.screenshoteditor.data.MediaStoreSaver
import dev.screenshoteditor.data.SettingsDataStore
import dev.screenshoteditor.data.TempCache
import dev.screenshoteditor.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class EditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EditorActivity"
        const val EXTRA_IMAGE_PATH = "image_path"

        /** Aspect ratios offered in the ratio dialog (null = free). Labels: R.array.aspect_ratio_labels. */
        private val ASPECT_RATIOS: List<Float?> = listOf(null, 1f, 4f / 3f, 16f / 9f, 9f / 16f)
    }

    private lateinit var binding: ActivityEditorBinding
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var mediaSaver: MediaStoreSaver
    private lateinit var clipboardShare: ClipboardShare

    private var imagePath: String? = null

    /** Guards against double taps starting two saves/copies of the same image. */
    private var actionInProgress = false

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
            failAndFinish(R.string.message_screenshot_failed)
            return
        }

        // Path Traversal Prevention: only files inside TempCache's temp directory are accepted.
        if (!isPathSafe(rawPath)) {
            // The rejected path is not logged: it is untrusted input and may contain control characters.
            Log.w(TAG, "onCreate: image path outside the temp directory rejected")
            failAndFinish(R.string.message_invalid_path)
            return
        }

        imagePath = rawPath
        setupViews()
        loadImage(rawPath)
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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // XML で指定された初期パディングをリスナー登録前に取得する。
        // コールバック内で view.paddingBottom を読むと、2 回目以降は
        // systemBars.bottom が上書きされた値になり累積するため、事前取得が必須。
        val initialBottomPadding = binding.actionBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialBottomPadding + systemBars.bottom)
            insets
        }
    }

    /** Decodes the capture off the main thread (a full-screen PNG takes long enough to drop frames). */
    private fun loadImage(path: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bitmap == null) {
                Log.w(TAG, "loadImage: decode failed")
                failAndFinish(R.string.message_screenshot_failed)
                return@launch
            }
            binding.cropView.setBitmap(bitmap)
        }
    }

    private fun setupViews() {
        binding.btnSave.setOnClickListener { runExclusive { saveAndFinish(it) } }
        binding.btnCopy.setOnClickListener { runExclusive { copyAndFinish(it) } }
        binding.btnShare.setOnClickListener { runExclusive { share(it) } }
        binding.btnCancel.setOnClickListener {
            deleteTempFile()
            finish()
        }
        binding.btnReset.setOnClickListener { binding.cropView.reset() }
        binding.btnAspectRatio.setOnClickListener { showAspectRatioDialog() }
    }

    /**
     * Crops the image and runs [action] unless another action is still running.
     */
    private fun runExclusive(action: suspend (Bitmap) -> Unit) {
        if (actionInProgress) return
        val cropped = binding.cropView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, R.string.message_screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        actionInProgress = true
        lifecycleScope.launch {
            try {
                action(cropped)
            } finally {
                actionInProgress = false
            }
        }
    }

    private suspend fun saveAndFinish(bitmap: Bitmap) {
        if (mediaSaver.saveBitmapToGallery(bitmap)) {
            Toast.makeText(this, R.string.message_saved, Toast.LENGTH_SHORT).show()
            deleteTempFile()
            finish()
        } else {
            Toast.makeText(this, R.string.message_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun copyAndFinish(bitmap: Bitmap) {
        val settings = settingsDataStore.settings.first()
        if (clipboardShare.copyImageToClipboard(bitmap, settings.clearSeconds)) {
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
            deleteTempFile()
            finish()
        } else {
            Toast.makeText(this, R.string.message_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun share(bitmap: Bitmap) {
        val chooser = clipboardShare.createShareIntent(bitmap)
        if (chooser == null) {
            Toast.makeText(this, R.string.message_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "share: no chooser available", e)
            Toast.makeText(this, R.string.message_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAspectRatioDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_aspect_ratio_title)
            .setItems(R.array.aspect_ratio_labels) { _, which ->
                binding.cropView.setAspectRatio(ASPECT_RATIOS.getOrNull(which))
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
            val allowedDir = TempCache.getTempDir(this).canonicalPath
            val canonicalPath = File(path).canonicalPath
            canonicalPath.startsWith(allowedDir + File.separator)
        } catch (e: IOException) {
            Log.w(TAG, "isPathSafe: path validation failed", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "isPathSafe: path validation failed", e)
            false
        }
    }

    private fun deleteTempFile() {
        imagePath?.let { TempCache.deleteQuietly(File(it)) }
    }

    private fun failAndFinish(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    // Design Decision: the decoded bitmap is not recycled in onDestroy. Since API 26 bitmap pixels
    // live in native memory released by the GC, and an explicit recycle() could race with a PNG
    // encode still running on Dispatchers.IO (getCroppedBitmap may return the same instance for a
    // full-image crop), crashing with "Can't compress a recycled bitmap".
}
