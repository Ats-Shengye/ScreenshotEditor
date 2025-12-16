package com.example.screenshoteditor.capture

import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowMetrics
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResult // Added import
import androidx.activity.result.contract.ActivityResultContracts
import com.example.screenshoteditor.R
import com.example.screenshoteditor.data.TempCache
import com.example.screenshoteditor.data.SettingsDataStore
import com.example.screenshoteditor.ui.EditorActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
// Removed: import android.app.Activity

class CaptureActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var settingsDataStore: SettingsDataStore
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult -> // Added type annotation
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
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (!CaptureService.isRunning) {
            val serviceIntent = Intent(this, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                continueScreenCapture()
            }, 500)
        } else {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            continueScreenCapture()
        }
    }
    
    private fun continueScreenCapture() {
        if (mediaProjection == null) {
            Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        activityScope.launch {
            val settings = settingsDataStore.settings.first()
            if (!settings.immediateCapture && settings.delaySeconds > 0) {
                delay(settings.delaySeconds * 1000L)
            }

            withContext(Dispatchers.Main) {
                performScreenCapture()
            }
        }
    }
    
    private fun performScreenCapture() {
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            captureScreenshot()
        }, 100)
    }

    private fun captureScreenshot() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val trimmedBitmap = trimStatusBar(bitmap)

                val tempFile = saveBitmapToTemp(trimmedBitmap)

                cleanup()

                if (tempFile != null) {
                    openEditor(tempFile.absolutePath)
                } else {
                    Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
                cleanup()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.message_screenshot_failed), Toast.LENGTH_SHORT).show()
            cleanup()
            finish()
        }
    }

    private fun trimStatusBar(bitmap: Bitmap): Bitmap {
        val statusBarHeight = StatusBarInsets.getStatusBarHeight(this)
        return if (statusBarHeight > 0 && statusBarHeight < bitmap.height) {
            Bitmap.createBitmap(
                bitmap,
                0,
                statusBarHeight,
                bitmap.width,
                bitmap.height - statusBarHeight
            )
        } else {
            bitmap
        }
    }
    
    private fun saveBitmapToTemp(bitmap: Bitmap): File? {
        return try {
            val tempFile = TempCache.createTempFile(this)
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }
    
    private fun openEditor(imagePath: String) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_IMAGE_PATH, imagePath)
        }
        startActivity(intent)
        finish()
    }
    
    private fun cleanup() {
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        
        imageReader = null
        virtualDisplay = null
        mediaProjection = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        activityScope.cancel()
    }
}
