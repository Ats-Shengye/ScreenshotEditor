package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * CaptureService の起動ヘルパー。
 *
 * Android O (API 26) 以降は startForegroundService、それ未満は startService を
 * 使用する必要がある。この分岐ロジックが複数箇所に重複していたため、ここに集約する。
 */
object ServiceLauncher {

    /**
     * CaptureService を起動する。
     *
     * Activity Context が渡された場合でも applicationContext に正規化することで、
     * Service 起動 Intent に Activity の参照が残留するのを防ぐ。
     *
     * @param context  起動元 Context（Activity Context 可。内部で applicationContext に正規化）
     * @param action   Intent に付与する action 文字列。null の場合は action なしで起動する
     */
    fun startCaptureService(context: Context, action: String? = null) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, CaptureService::class.java).apply {
            action?.let { this.action = it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }
}
