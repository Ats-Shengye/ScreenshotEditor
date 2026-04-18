package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection のライフサイクルと VirtualDisplay の管理を担当するコントローラー。
 *
 * MediaProjection の取得・VirtualDisplay の作成・停止処理を一手に引き受け、
 * Android バージョン差異（特に Android 14+ のコールバック登録順序要件）を吸収する。
 *
 * ## 使い捨て前提（ワンショット設計）
 *
 * このクラスは `start()` → `stop()` のワンショットで寿命が終了する使い捨て設計である。
 * **再利用は不可**。`stop()` 後に再度 `start()` を呼んではならない（isStopped フラグにより
 * start() 内の処理が前提条件を満たせなくなるほか、consent token も null 化済みのため）。
 * キャプチャを再実行する場合は、新たな MediaProjection 同意取得のうえで
 * 新規インスタンスを作成すること。
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
    // Activity Leak 防止: applicationContext に正規化する
    private val appContext = context.applicationContext

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** unregister のために保持する named callback field */
    private var projectionCallback: MediaProjection.Callback? = null

    /** stop() の多重呼び出しを防ぐフラグ */
    private val isStopped = AtomicBoolean(false)

    private val mediaProjectionManager = appContext.getSystemService(
        Context.MEDIA_PROJECTION_SERVICE
    ) as MediaProjectionManager

    /**
     * MediaProjection を取得して VirtualDisplay を作成する。
     *
     * Android 14 (UPSIDE_DOWN_CAKE) 以降は createVirtualDisplay() より前に
     * MediaProjection.Callback を登録する必要があるため、内部で順序を保証している。
     *
     * @param width       キャプチャ幅（ピクセル）
     * @param height      キャプチャ高さ（ピクセル）
     * @param density     画面密度（dpi）
     * @param imageReader キャプチャ出力先の ImageReader
     * @return 作成された VirtualDisplay。取得失敗時は null
     */
    fun start(
        width: Int,
        height: Int,
        density: Int,
        imageReader: ImageReader
    ): VirtualDisplay? {
        this.imageReader = imageReader
        val consentData = data ?: return null
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, consentData)

        // Android 14+ ではコールバック登録が createVirtualDisplay() より先に必要。
        // 匿名クラスではなく named field で保持し、stop() 時に unregister できるようにする
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }
            projectionCallback = cb
            mediaProjection?.registerCallback(cb, Handler(Looper.getMainLooper()))
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        return virtualDisplay
    }

    /**
     * 保持しているリソース（ImageReader / VirtualDisplay / MediaProjection）をすべて解放する。
     *
     * AtomicBoolean による冪等化により、多重呼び出しに対して安全。
     * MediaProjection.Callback を unregister してから stop() することでリークを防止する。
     * consent token（data）も null 化してメモリ内に保持しない。
     */
    fun stop() {
        // 多重呼び出しを AtomicBoolean で防ぐ
        if (!isStopped.compareAndSet(false, true)) return

        // unregister を先に行ってから stop() する（コールバック経由の再入を防止）
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null

        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()

        imageReader = null
        virtualDisplay = null
        mediaProjection = null
        // consent token を null 化してメモリ内に保持しない
        data = null
    }
}
