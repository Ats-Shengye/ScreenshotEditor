package com.example.screenshoteditor.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screenshoteditor.MainActivity
import com.example.screenshoteditor.R
import com.example.screenshoteditor.capture.CaptureActivity
import com.example.screenshoteditor.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CaptureService : Service() {
    
    companion object {
        private const val TAG = "CaptureService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screenshot_service"
        const val ACTION_CAPTURE = "com.example.screenshoteditor.ACTION_CAPTURE"
        const val ACTION_STOP = "com.example.screenshoteditor.ACTION_STOP"
        const val ACTION_PREPARE_CAPTURE = "com.example.screenshoteditor.ACTION_PREPARE_CAPTURE"

        @Volatile
        var isRunning = false
            private set

        /**
         * ACTION_PREPARE_CAPTUREでForeground昇格が完了した際に呼ばれるコールバック。
         * CaptureActivityがここにコールバックを登録し、通知を受け取ってから
         * getMediaProjection()を呼ぶことで、postDelayed固定待ちを排除する。
         * MainLooperで呼ばれることを保証する。
         */
        @Volatile
        var onPrepareComplete: (() -> Unit)? = null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            isRunning = false
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent==nullはシステムによる再起動を意味する。consent tokenが失われているため停止する
        if (intent == null) {
            Log.w(TAG, "onStartCommand: intent is null (service restarted by system), stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            when (intent.action) {
                ACTION_PREPARE_CAPTURE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    }
                    // startForeground完了後にコールバックを呼び出す
                    // MainLooperへpostして確実にForeground昇格後に通知する
                    Handler(Looper.getMainLooper()).post {
                        onPrepareComplete?.invoke()
                        onPrepareComplete = null
                    }
                }
                ACTION_CAPTURE -> {
                    serviceScope.launch {
                        try {
                            handleCaptureAction()
                        } catch (e: Exception) {
                            Log.w(TAG, "handleCaptureAction failed", e)
                        }
                    }
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "onStartCommand failed", e)
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
            Log.w(TAG, "startCaptureActivity failed", e)
        }
    }
    
    private fun isScreenLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}
