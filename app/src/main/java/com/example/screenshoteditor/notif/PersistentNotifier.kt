package com.example.screenshoteditor.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.screenshoteditor.R
import com.example.screenshoteditor.capture.CaptureService

class PersistentNotifier(private val context: Context) {
    
    companion object {
        const val CHANNEL_ID = "screenshot_service"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_CAPTURE = "action_capture"
        const val ACTION_STOP = "action_stop"
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    fun createForegroundNotification(): Notification {
        val captureIntent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_CAPTURE
        }
        val capturePendingIntent = PendingIntent.getService(
            context, 
            0, 
            captureIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 
            1, 
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("スクリーンショット")
            .setContentText("画面キャプチャが利用可能です")
            .setSmallIcon(R.drawable.ic_screenshot)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                R.drawable.ic_camera,
                "撮影",
                capturePendingIntent
            )
            .addAction(
                R.drawable.ic_stop,
                "停止",
                stopPendingIntent
            )
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "スクリーンショットサービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "スクリーンショット撮影サービスの通知"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showTemporaryNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}