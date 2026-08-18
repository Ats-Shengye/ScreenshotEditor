package dev.screenshoteditor.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.screenshoteditor.MainActivity
import dev.screenshoteditor.R
import dev.screenshoteditor.capture.CaptureActivity
import dev.screenshoteditor.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CaptureService : Service() {
    
    companion object {
        private const val TAG = "CaptureService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screenshot_service"
        const val ACTION_CAPTURE = "dev.screenshoteditor.ACTION_CAPTURE"
        const val ACTION_STOP = "dev.screenshoteditor.ACTION_STOP"
        const val ACTION_PREPARE_CAPTURE = "dev.screenshoteditor.ACTION_PREPARE_CAPTURE"

        @Volatile
        var isRunning = false
            private set

        /**
         * ACTION_PREPARE_CAPTURE で Foreground 昇格が完了したことを CaptureActivity に通知する
         * per-request の CompletableDeferred。
         *
         * SharedFlow と異なり、1 回のリクエストに対し 1 つの Deferred が対応するため、
         * 古い emit の残留による誤消費が発生しない。
         * CaptureActivity は awaitPrepareComplete() で Deferred を取得してから
         * Service への Intent を送信し、Service 側で complete() を呼ぶ。
         */
        @Volatile
        private var pendingPrepare: CompletableDeferred<Unit>? = null

        /**
         * Foreground昇格完了を待つための CompletableDeferred を生成・登録する。
         *
         * **Main thread から呼ぶこと**。内部で [pendingPrepare] への
         * read-modify-write 操作があるが、Main thread シングルスレッド前提で
         * 逐次化により安全性を担保している。別スレッドから呼ぶとレースが発生する。
         *
         * 既に保留中の Deferred がある場合は cancel してから新規 Deferred に
         * 置き換える（並走呼び出し防御）。
         *
         * 呼び出し元は返却された Deferred を取得した直後に [ACTION_PREPARE_CAPTURE] を
         * Service へ送信すること。順序を逆にすると Service 側の [complete] が先に走る
         * レースコンディションが発生する。
         */
        fun awaitPrepareComplete(): CompletableDeferred<Unit> {
            pendingPrepare?.cancel()  // 既存Deferredを明示キャンセル（並走・連続呼び出し防御）
            val deferred = CompletableDeferred<Unit>()
            pendingPrepare = deferred
            return deferred
        }

        /**
         * 保留中の Deferred をキャンセルして null 化する。
         * Activity のタイムアウト時・onDestroy 時に呼ぶことで、
         * Deferred のリーク（完了待ちのまま宙吊り）を防止する。
         */
        fun cancelPrepare() {
            pendingPrepare?.cancel()
            pendingPrepare = null
        }
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
        } catch (e: IllegalStateException) {
            // API 31+ の ForegroundServiceStartNotAllowedException を含む。
            // バックグラウンド起動制限や startForeground 失敗時に発生する
            Log.w(TAG, "onCreate: failed to start foreground service", e)
            isRunning = false
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "onCreate: unexpected error during initialization", e)
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
                    // startForeground 完了後に per-request Deferred を complete して CaptureActivity に通知する
                    pendingPrepare?.complete(Unit)
                    pendingPrepare = null
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
        } catch (e: IllegalStateException) {
            // ACTION_PREPARE_CAPTURE 時の startForeground 失敗（API31+ バックグラウンド制限等）
            Log.w(TAG, "onStartCommand: foreground service start failed", e)
            stopSelf()
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
        // Service が破棄される際、まだ待機中の Deferred があればキャンセルして宙吊りを防ぐ
        cancelPrepare()
        serviceScope.cancel()
        projectionController?.stop()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
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
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "startCaptureActivity: CaptureActivity not found", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "startCaptureActivity: permission denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "startCaptureActivity failed", e)
        }
    }
    
    private fun isScreenLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}
