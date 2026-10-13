package dev.screenshoteditor.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.screenshoteditor.MainActivity
import dev.screenshoteditor.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps the capture notification available and, on request, upgrades
 * itself to the mediaProjection foreground type required before MediaProjection can be obtained.
 *
 * The persistent notification itself is mandatory for a foreground service, so it has no on/off
 * setting.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screenshot_service"
        const val ACTION_STOP = "dev.screenshoteditor.ACTION_STOP"
        const val ACTION_PREPARE_CAPTURE = "dev.screenshoteditor.ACTION_PREPARE_CAPTURE"

        private val runningState = MutableStateFlow(false)

        /** Observable running state, used by MainActivity to update its UI after start/stop. */
        val running: StateFlow<Boolean> = runningState.asStateFlow()

        val isRunning: Boolean get() = runningState.value

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
         * Service へ送信すること。順序を逆にすると Service 側の complete が先に走る
         * レースコンディションが発生する。
         */
        fun awaitPrepareComplete(): CompletableDeferred<Unit> {
            pendingPrepare?.cancel()
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

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            runningState.value = true
        } catch (e: IllegalStateException) {
            // API 31+ の ForegroundServiceStartNotAllowedException を含む。
            // バックグラウンド起動制限や startForeground 失敗時に発生する
            Log.w(TAG, "onCreate: failed to start foreground service", e)
            stopSelf()
        } catch (e: SecurityException) {
            // マニフェストの foregroundServiceType と権限の不整合
            Log.w(TAG, "onCreate: foreground service type not permitted", e)
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
        try {
            when (intent.action) {
                ACTION_PREPARE_CAPTURE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    }
                    // startForeground 完了後に per-request Deferred を complete して CaptureActivity に通知する
                    pendingPrepare?.complete(Unit)
                    pendingPrepare = null
                }
                ACTION_STOP -> stopSelf()
            }
        } catch (e: IllegalStateException) {
            // ACTION_PREPARE_CAPTURE 時の startForeground 失敗（API31+ バックグラウンド制限等）
            Log.w(TAG, "onStartCommand: foreground service start failed", e)
            stopSelf()
        } catch (e: SecurityException) {
            // mediaProjection 型への昇格は同意取得後でないと拒否される（Android 14+）
            Log.w(TAG, "onStartCommand: foreground service type not permitted", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runningState.value = false
        // Service が破棄される際、まだ待機中の Deferred があればキャンセルして宙吊りを防ぐ
        cancelPrepare()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val captureActivityIntent = Intent(this, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val capturePendingIntent = PendingIntent.getActivity(
            this, 1, captureActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
}
