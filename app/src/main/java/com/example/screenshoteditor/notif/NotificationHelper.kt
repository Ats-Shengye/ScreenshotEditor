package com.example.screenshoteditor.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.screenshoteditor.MainActivity
import com.example.screenshoteditor.R
import com.example.screenshoteditor.capture.CaptureActivity

object NotificationHelper {
    
    const val CHANNEL_ID_SERVICE = "screenshot_service"
    const val CHANNEL_ID_CAPTURE = "screenshot_capture"
    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_CAPTURE = 1002
    
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // サービス用チャンネル
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "スクリーンショットサービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "スクリーンショット撮影サービスの常駐通知"
                setShowBadge(false)
            }
            
            // 撮影用チャンネル
            val captureChannel = NotificationChannel(
                CHANNEL_ID_CAPTURE,
                "スクリーンショット撮影",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "スクリーンショット撮影完了の通知"
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(captureChannel)
        }
    }
    
    fun createServiceNotification(context: Context): android.app.Notification {
        createNotificationChannels(context)
        
        val mainIntent = Intent(context, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val captureIntent = Intent(context, CaptureActivity::class.java)
        val capturePendingIntent = PendingIntent.getActivity(
            context,
            1,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setContentTitle("スクリーンショットエディター")
            .setContentText("タップして撮影")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(capturePendingIntent)
            .addAction(
                android.R.drawable.ic_menu_camera,
                "撮影",
                capturePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "設定",
                mainPendingIntent
            )
            .setOngoing(true)
            .build()
    }
    
    fun showCaptureCompleteNotification(context: Context, imagePath: String?) {
        createNotificationChannels(context)
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CAPTURE)
            .setContentTitle("スクリーンショット保存完了")
            // パス等の内部情報は表示しない
            .setContentText("画像を保存しました")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_CAPTURE, notification)
    }
}
