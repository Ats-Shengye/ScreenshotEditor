package dev.screenshoteditor.capture

import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import dev.screenshoteditor.R
import dev.screenshoteditor.data.SettingsDataStore
import dev.screenshoteditor.data.TempCache
import dev.screenshoteditor.ui.EditorActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * スクリーンキャプチャのオーケストレーションを担当する透明 Activity。
 *
 * 責務:
 * 1. ロック中撮影の拒否判定とキャプチャ同意ダイアログの表示
 * 2. CaptureService の Foreground（mediaProjection 型）昇格待機
 * 3. 遅延撮影の待機（待機中は下のアプリへタッチを通す）
 * 4. ScreenCaptureHelper への処理委譲と EditorActivity への遷移
 *
 * キャプチャの低レベル処理（VirtualDisplay / Bitmap / ファイル保存）は ScreenCaptureHelper に委譲する。
 * コルーチンは lifecycleScope（Main）で動かし、onDestroy で自動キャンセルされる。
 */
class CaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CaptureActivity"
        private const val PREPARE_TIMEOUT_MS = 2000L
    }

    private lateinit var settingsDataStore: SettingsDataStore
    private var screenCaptureHelper: ScreenCaptureHelper? = null
    private var prepareDeferred: CompletableDeferred<Unit>? = null

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startScreenCapture(result.resultCode, data)
        } else {
            failAndFinish(R.string.message_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsDataStore = SettingsDataStore(this)

        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        if (mediaProjectionManager == null) {
            Log.w(TAG, "onCreate: MediaProjectionManager unavailable")
            failAndFinish(R.string.message_screenshot_failed)
            return
        }

        lifecycleScope.launch {
            val settings = settingsDataStore.settings.first()
            val keyguard = getSystemService(KeyguardManager::class.java)
            // Security Note: refuse while locked so that the notification/tile cannot be used to
            // capture lock-screen contents. A missing KeyguardManager is treated as locked (fail-safe).
            if (settings.disableOnLock && (keyguard == null || keyguard.isKeyguardLocked)) {
                failAndFinish(R.string.message_screenshot_failed)
                return@launch
            }
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    /**
     * CaptureService の Foreground 昇格を待機し、完了後にキャプチャを開始する。
     *
     * MediaProjection の取得（getMediaProjection）は Foreground Service 昇格後に行う必要があるため、
     * per-request CompletableDeferred の complete を受け取ってから ScreenCaptureHelper を構築する。
     * Deferred を先に取得してから Intent を送信することで、Service 側の complete() が
     * Activity 側の await() より前に走るレースコンディションを排除している。
     */
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val deferred = CaptureService.awaitPrepareComplete()
        prepareDeferred = deferred

        lifecycleScope.launch {
            try {
                if (!CaptureService.isRunning) ServiceLauncher.startCaptureService(this@CaptureActivity)
                ServiceLauncher.startCaptureService(this@CaptureActivity, CaptureService.ACTION_PREPARE_CAPTURE)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startScreenCapture: service start not allowed", e)
                abortPrepare()
                return@launch
            } catch (e: SecurityException) {
                Log.w(TAG, "startScreenCapture: service start not permitted", e)
                abortPrepare()
                return@launch
            }

            val prepared = try {
                withTimeoutOrNull(PREPARE_TIMEOUT_MS) { deferred.await() }
            } catch (e: CancellationException) {
                // Either the Service cancelled the Deferred (onDestroy / a newer request) or this
                // coroutine itself was cancelled. ensureActive() rethrows in the latter case so that
                // nothing touches a destroyed Activity.
                ensureActive()
                Log.w(TAG, "startScreenCapture: prepare cancelled by service", e)
                null
            }

            if (prepared == null) {
                Log.w(TAG, "startScreenCapture: prepare timeout or cancelled, aborting")
                abortPrepare()
            } else {
                continueScreenCapture(resultCode, data)
            }
        }
    }

    private suspend fun continueScreenCapture(resultCode: Int, data: Intent) {
        val settings = settingsDataStore.settings.first()
        if (settings.isDelayedCapture) {
            letTouchesPassThrough()
            delay(settings.delaySeconds * 1000L)
        }

        val helper = ScreenCaptureHelper(this, resultCode, data)
        screenCaptureHelper = helper
        helper.capture(CaptureGeometry.fromActivity(this)) { tempFile ->
            screenCaptureHelper = null
            if (isFinishing || isDestroyed) {
                // Nobody will open this capture; do not leave screen contents on disk.
                tempFile?.let { TempCache.deleteQuietly(it) }
                return@capture
            }
            if (tempFile != null) openEditor(tempFile) else failAndFinish(R.string.message_screenshot_failed)
        }
    }

    /**
     * Lets the user keep operating the app underneath while a delayed capture is pending.
     *
     * Design Decision: this Activity stays in the foreground instead of calling moveTaskToBack().
     * Starting EditorActivity after the delay from the background would be a background activity
     * launch, and a running foreground service (even of type mediaProjection) is not one of the
     * documented exemptions, so the editor might silently never open. Keeping the Activity resumed
     * keeps that start allowed ("the app has a visible window").
     *
     * The window is made non-touchable and fully transparent (alpha 0): touches inside it pass
     * through, and Android 12+ does not block pass-through touches for completely transparent
     * windows. NOT_TOUCH_MODAL sends touches outside the (floating) window to the window behind
     * instead of consuming them, and NOT_FOCUSABLE hands key focus to the app underneath.
     */
    private fun letTouchesPassThrough() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.attributes = window.attributes.apply { alpha = 0f }
    }

    private fun openEditor(imageFile: File) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_IMAGE_PATH, imageFile.absolutePath)
        }
        startActivity(intent)
        finish()
    }

    /** Cancels a pending prepare request and reports the failure. */
    private fun abortPrepare() {
        CaptureService.cancelPrepare()
        failAndFinish(R.string.message_screenshot_failed)
    }

    private fun failAndFinish(@StringRes message: Int) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope is cancelled by the framework; resources outside it are released here.
        prepareDeferred?.cancel()
        prepareDeferred = null
        screenCaptureHelper?.release()
        screenCaptureHelper = null
    }
}
