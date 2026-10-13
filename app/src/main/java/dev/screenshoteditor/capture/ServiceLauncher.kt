package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent

/**
 * CaptureService の起動ヘルパー（MainActivity と CaptureActivity から共通利用）。
 */
object ServiceLauncher {

    /**
     * CaptureService を Foreground Service として起動する。
     *
     * Activity Context が渡された場合でも applicationContext に正規化することで、
     * Service 起動 Intent に Activity の参照が残留するのを防ぐ。
     * minSdk 30 のため startForegroundService を常に使う。
     *
     * @param context  起動元 Context（Activity Context 可。内部で applicationContext に正規化）
     * @param action   Intent に付与する action 文字列。null の場合は action なしで起動する
     * @throws IllegalStateException バックグラウンドからの起動が制限されている場合
     *         （ForegroundServiceStartNotAllowedException を含む）
     * @throws SecurityException     起動が許可されていない場合
     */
    fun startCaptureService(context: Context, action: String? = null) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, CaptureService::class.java).apply {
            action?.let { this.action = it }
        }
        appContext.startForegroundService(intent)
    }
}
