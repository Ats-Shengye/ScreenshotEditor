package com.example.screenshoteditor.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.screenshoteditor.MainActivity
import com.example.screenshoteditor.R
import com.example.screenshoteditor.capture.CaptureActivity
import com.example.screenshoteditor.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CaptureService : Service() {
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screenshot_service"
        const val ACTION_CAPTURE = "com.example.screenshoteditor.ACTION_CAPTURE"
        const val ACTION_STOP = "com.example.screenshoteditor.ACTION_STOP"
        
        @Volatile
        var isRunning = false
            private set
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settingsDataStore: SettingsDataStore
    private var projectionController: ProjectionController? = null
    
    override fun onCreate() {
        super.onCreate()
        try {
            isRunning = true
            settingsDataStore = SettingsDataStore(this)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            isRunning = false
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_CAPTURE -> {
                    serviceScope.launch {
                        try {
                            handleCaptureAction()
                        } catch (e: Exception) {
                        }
                    }
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
            START_STICKY
        } catch (e: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        projectionController?.stop()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_description)
                }

                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                throw e
            }
        }
    }
    
    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val captureActivityIntent = Intent(this, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val capturePendingIntent = PendingIntent.getActivity(
            this,
            1,
            captureActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_camera,
                getString(R.string.notification_action_capture),
                capturePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }
    
    private suspend fun handleCaptureAction() {
        val settings = settingsDataStore.settings.first()
        
        // Check if screen is locked and capture is disabled
        if (settings.disableOnLock && isScreenLocked()) {
            return
        }
        
        // Handle delayed capture if enabled
        if (!settings.immediateCapture) {
            delay(settings.delaySeconds * 1000L)
        }
        
        try {
            val captureIntent = Intent(this, CaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(captureIntent)
        } catch (e: Exception) {
        }
    }
    
    private fun isScreenLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}
