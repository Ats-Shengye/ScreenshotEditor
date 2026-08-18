package dev.screenshoteditor.capture

import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import dev.screenshoteditor.R
import dev.screenshoteditor.data.SettingsDataStore
import dev.screenshoteditor.ui.EditorActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * スクリーンキャプチャのオーケストレーションを担当する Activity。
 *
 * 責務:
 * 1. キャプチャ同意ダイアログの表示・結果受け取り
 * 2. CaptureService の Foreground 昇格待機（Flow 経由）
 * 3. ScreenCaptureHelper への処理委譲
 * 4. EditorActivity への遷移
 *
 * キャプチャの低レベル処理（VirtualDisplay / Bitmap / ファイル保存）は
 * ScreenCaptureHelper に委譲する。
 * MediaProjection の取得は ProjectionController が担当するため、ここでは呼ばない。
 */
class CaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CaptureActivity"
        private const val PREPARE_TIMEOUT_MS = 2000L
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var settingsDataStore: SettingsDataStore
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var screenCaptureHelper: ScreenCaptureHelper? = null

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, getString(R.string.message_permission_required), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            settingsDataStore = SettingsDataStore(this)
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: throw IllegalStateException("MediaProjectionManager not available")

            activityScope.launch {
                val settings = settingsDataStore.settings.first()
                val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (settings.disableOnLock && keyguard.isKeyguardLocked) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CaptureActivity, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                    withContext(Dispatchers.Main) {
                        screenCapturePermissionLauncher.launch(captureIntent)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // MediaProjectionManager 取得失敗など、システムサービスが利用不可の場合
            Log.w(TAG, "onCreate: system service unavailable", e)
            Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "onCreate: unexpected error", e)
            Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * CaptureService の Foreground 昇格を待機し、完了後にキャプチャを開始する。
     *
     * MediaProjection の取得（getMediaProjection）は Foreground Service 起動後に
     * 行う必要があるため、per-request CompletableDeferred の complete を受け取ってから
     * ScreenCaptureHelper を構築する。
     *
     * Deferred を先に取得してから Intent を送信することで、Service 側の complete() が
     * Activity 側の await() より前に走るレースコンディションを排除している。
     */
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (!CaptureService.isRunning) {
            ServiceLauncher.startCaptureService(this)
        }

        // per-request Deferred を先に取得してから Intent を送信する（レースコンディション防止）
        val prepareDeferred = CaptureService.awaitPrepareComplete()

        activityScope.launch {
            ServiceLauncher.startCaptureService(this@CaptureActivity, CaptureService.ACTION_PREPARE_CAPTURE)

            // prepareDeferred が外部から cancel された場合のみ到達する。
            // activityScope 自体が cancel された場合はコルーチン自体が終了するため
            // このcatchは動かない（Activity 終了中なのでUI通知不要）。
            val result = try {
                withTimeoutOrNull(PREPARE_TIMEOUT_MS) { prepareDeferred.await() }
            } catch (e: CancellationException) {
                Log.w(TAG, "startScreenCapture: prepare cancelled (service destroyed?)", e)
                null  // Service 側の cancel → タイムアウトと同等処理
            }

            if (result == null) {
                Log.w(TAG, "startScreenCapture: prepare timeout or cancelled, aborting")
                // タイムアウト・キャンセル時は未完了の Deferred をキャンセルしてリソースを解放する
                CaptureService.cancelPrepare()
                Toast.makeText(this@CaptureActivity, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                continueScreenCapture(resultCode, data)
            }
        }
    }

    private suspend fun continueScreenCapture(resultCode: Int, data: Intent) {
        val settings = settingsDataStore.settings.first()
        if (!settings.immediateCapture && settings.delaySeconds > 0) {
            delay(settings.delaySeconds * 1000L)
        }

        withContext(Dispatchers.Main) {
            val helper = ScreenCaptureHelper(this@CaptureActivity, resultCode, data)
            screenCaptureHelper = helper
            helper.capture { tempFile ->
                screenCaptureHelper = null
                if (tempFile != null) {
                    openEditor(tempFile.absolutePath)
                } else {
                    Toast.makeText(this@CaptureActivity, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun openEditor(imagePath: String) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_IMAGE_PATH, imagePath)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // activityScope.cancel() で起動中の coroutine（Flow 待機を含む）が自動キャンセルされる
        activityScope.cancel()
        screenCaptureHelper?.release()
        screenCaptureHelper = null
    }
}
