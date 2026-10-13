package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection のライフサイクルと VirtualDisplay の管理を担当するコントローラー。
 *
 * MediaProjection の取得・VirtualDisplay の作成・停止処理を一手に引き受け、
 * Android バージョン差異（特に Android 14+ のコールバック登録順序要件）を吸収する。
 * 出力先の ImageReader は呼び出し側（ScreenCaptureHelper）が所有・解放する。
 *
 * ## 使い捨て前提（ワンショット設計）
 *
 * このクラスは `start()` → `stop()` のワンショットで寿命が終了する使い捨て設計である。
 * **再利用は不可**。`stop()` 後に `start()` を呼んでも consent token が null 化済みのため失敗する。
 * キャプチャを再実行する場合は、新たな MediaProjection 同意取得のうえで新規インスタンスを作成すること。
 *
 * ## リソースリーク防止
 * - applicationContext に正規化して Activity 参照を保持しない
 * - MediaProjection.Callback を named field で管理し、stop() 時に unregister する
 * - stop() は AtomicBoolean で冪等化し、多重呼び出しを安全にする
 * - consent token (`data: Intent`) は stop() 内で null 化され、メモリ上に残留しない
 */
class ProjectionController(
    context: Context,
    private val resultCode: Int,
    private var data: Intent?
) {
    private companion object {
        const val TAG = "ProjectionController"
    }

    // Activity Leak 防止: applicationContext に正規化する
    private val appContext = context.applicationContext

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** unregister のために保持する named callback field */
    private var projectionCallback: MediaProjection.Callback? = null

    /** stop() の多重呼び出しを防ぐフラグ */
    private val isStopped = AtomicBoolean(false)

    /**
     * MediaProjection を取得し、[surface] へ描画する VirtualDisplay を作成する。
     *
     * Android 14 (UPSIDE_DOWN_CAKE) 以降は createVirtualDisplay() より前に
     * MediaProjection.Callback を登録する必要があるため、内部で順序を保証している。
     *
     * @return VirtualDisplay を作成できた場合 true。consent token の失効、Foreground Service 未昇格
     *         （SecurityException）、stop() 済み等で作成できなかった場合 false
     */
    fun start(width: Int, height: Int, densityDpi: Int, surface: Surface): Boolean {
        if (isStopped.get()) return false
        val consentData = data ?: return false
        val manager = appContext.getSystemService(MediaProjectionManager::class.java) ?: return false

        return try {
            val projection = manager.getMediaProjection(resultCode, consentData) ?: return false
            mediaProjection = projection

            // Android 14+ ではコールバック登録が createVirtualDisplay() より先に必要。
            // 匿名クラスではなく named field で保持し、stop() 時に unregister できるようにする
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        stop()
                    }
                }
                projectionCallback = cb
                projection.registerCallback(cb, Handler(Looper.getMainLooper()))
            }

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            virtualDisplay != null
        } catch (e: SecurityException) {
            // Foreground Service が mediaProjection 型に昇格していない、または token が再利用された
            Log.w(TAG, "start: projection rejected", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "start: projection in invalid state", e)
            false
        }
    }

    /**
     * VirtualDisplay と MediaProjection を解放する。
     *
     * AtomicBoolean による冪等化により、多重呼び出しに対して安全。
     * MediaProjection.Callback を unregister してから stop() することで、
     * コールバック経由の再入とリークを防止する。consent token（data）も null 化する。
     */
    fun stop() {
        if (!isStopped.compareAndSet(false, true)) return

        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null

        virtualDisplay?.release()
        mediaProjection?.stop()

        virtualDisplay = null
        mediaProjection = null
        data = null
    }
}
