package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import android.view.WindowManager
import dev.screenshoteditor.data.TempCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * スクリーンキャプチャの低レベル処理を担当するヘルパークラス。
 *
 * VirtualDisplay の作成・画像取得・ステータスバートリム・一時ファイル保存を一手に引き受け、
 * CaptureActivity をオーケストレーション層に縮退させる。
 * MediaProjection のライフサイクル管理は ProjectionController に委譲する。
 *
 * リソースリーク防止のため:
 * - applicationContext に正規化して Activity 参照を保持しない
 * - capture() は AtomicBoolean で多重呼び出しをガード
 * - release() は AtomicBoolean で冪等化
 * - helperScope で postDelayed を coroutine 化し、release() 時に連動キャンセル
 *
 * @param context    リソースアクセスに使用する Context（Activity Context 可だが内部で正規化）
 * @param resultCode MediaProjection 同意取得時の resultCode
 * @param data       MediaProjection 同意取得時の Intent
 */
class ScreenCaptureHelper(
    context: Context,
    resultCode: Int,
    data: Intent
) {

    companion object {
        private const val TAG = "ScreenCaptureHelper"

        /** VirtualDisplay 作成から最初のフレームが届くまでの待機時間（ミリ秒） */
        private const val FRAME_WAIT_MS = 100L
    }

    // Activity Leak 防止: applicationContext に正規化する
    private val appContext = context.applicationContext

    private val projectionController = ProjectionController(appContext, resultCode, data)
    private var imageReader: ImageReader? = null

    /** capture() の多重呼び出しを防ぐフラグ */
    private val isCapturing = AtomicBoolean(false)

    /** release() の多重呼び出しを防ぐフラグ */
    private val isReleased = AtomicBoolean(false)

    /**
     * postDelayed の代替として使用する coroutine scope。
     * release() で cancel() することで、Activity 破棄後のコールバック実行を防ぐ。
     */
    private val helperScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * スクリーンキャプチャを実行し、結果を一時ファイルとして返す。
     *
     * capture() は同時に 1 回しか実行できない（多重呼び出し時は onResult(null) を即返す）。
     * VirtualDisplay の作成から Bitmap 取得・保存まで一連の処理を行う。
     * 成功時は一時ファイルを、失敗時は null を [onResult] で返す。
     * 内部リソース（ImageReader / VirtualDisplay / MediaProjection）は処理完了後に
     * 自動的に解放する。
     *
     * 前提条件: CaptureService が Foreground 昇格済みであること（getMediaProjection が
     * Foreground Service 内から呼ばれる必要があるため、呼び出し前に昇格を完了させること）。
     *
     * @param onResult 処理完了時に Main スレッドで呼ばれるコールバック。引数は一時ファイル（失敗時は null）
     */
    fun capture(onResult: (File?) -> Unit) {
        // 多重呼び出しガード
        if (!isCapturing.compareAndSet(false, true)) {
            onResult(null)
            return
        }
        try {
            performScreenCapture(onResult)
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            release()
            onResult(null)
        }
    }

    /**
     * 保持しているリソース（ImageReader / VirtualDisplay / MediaProjection）を解放する。
     *
     * AtomicBoolean による冪等化により、多重呼び出しに対して安全。
     * capture() 完了後は自動的に呼ばれる。Activity の onDestroy でも呼ぶこと。
     * helperScope.cancel() により、実行中の coroutine（delay 待機を含む）も連動キャンセルされる。
     */
    fun release() {
        // 多重呼び出しを AtomicBoolean で防ぐ
        if (!isReleased.compareAndSet(false, true)) return

        // helperScope をキャンセルして delay 待機中の coroutine を停止する
        helperScope.cancel()
        imageReader?.close()
        imageReader = null
        projectionController.stop()
    }

    // --- private ---

    /**
     * VirtualDisplay を作成し、[FRAME_WAIT_MS] 後に captureScreenshot() を呼ぶ。
     *
     * postDelayed の代わりに helperScope.launch + delay を使用することで、
     * release() 時に coroutine を確実にキャンセルできる。
     *
     * Android 14 (UPSIDE_DOWN_CAKE) 以降は MediaProjection コールバック登録を
     * createVirtualDisplay() より先に行う必要があるため、ProjectionController.start() 内で
     * 処理される。ここでは窓サイズの取得と ImageReader の初期化を行う。
     */
    private fun performScreenCapture(onResult: (File?) -> Unit) {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = appContext.resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        imageReader = reader

        projectionController.start(width, height, density, reader)

        // postDelayed の代わりに helperScope で管理する coroutine を使用する。
        // release() で helperScope.cancel() されれば delay も連動キャンセルされる。
        helperScope.launch {
            delay(FRAME_WAIT_MS)
            captureScreenshot(onResult)
        }
    }

    /**
     * ImageReader から最新フレームを取得し、Bitmap に変換して一時ファイルに保存する。
     * Main スレッドで呼ばれることを前提とする（helperScope は Dispatchers.Main）。
     *
     * Bitmap のライフサイクル:
     * 1. rawBitmap: Image から生成。trimStatusBar() が新 Bitmap を返した場合のみ recycle する
     *    （trimStatusBar が rawBitmap をそのまま返す場合は同一参照なので recycle しない）
     * 2. trimmedBitmap: saveBitmapToTemp() によるファイル保存後に recycle する
     */
    private suspend fun captureScreenshot(onResult: (File?) -> Unit) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val rawBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // IO 処理は IO スレッドで実行する
                val tempFile = withContext(Dispatchers.IO) {
                    val trimmedBitmap = trimStatusBar(rawBitmap)
                    // trimStatusBar が新 Bitmap を返した場合のみ rawBitmap を解放する。
                    // statusBarHeight == 0 の場合は rawBitmap がそのまま返るため、
                    // 参照比較（!==）で同一かどうかを判定する
                    if (trimmedBitmap !== rawBitmap && !rawBitmap.isRecycled) {
                        rawBitmap.recycle()
                    }
                    val file = saveBitmapToTemp(trimmedBitmap)
                    // ファイル保存が完了したら trimmedBitmap も解放する
                    if (!trimmedBitmap.isRecycled) {
                        trimmedBitmap.recycle()
                    }
                    file
                }

                release()
                onResult(tempFile)
            } else {
                Log.w(TAG, "captureScreenshot: acquireLatestImage returned null")
                release()
                onResult(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureScreenshot failed", e)
            release()
            onResult(null)
        }
    }

    /**
     * ステータスバー領域を上部からトリムした Bitmap を返す。
     *
     * ステータスバーの高さが 0 または Bitmap の高さ以上の場合は元の Bitmap をそのまま返す。
     */
    private fun trimStatusBar(bitmap: Bitmap): Bitmap {
        val statusBarHeight = StatusBarInsets.getStatusBarHeight(appContext)
        return if (statusBarHeight > 0 && statusBarHeight < bitmap.height) {
            Bitmap.createBitmap(
                bitmap,
                0,
                statusBarHeight,
                bitmap.width,
                bitmap.height - statusBarHeight
            )
        } else {
            bitmap
        }
    }

    /**
     * Bitmap を PNG 形式で一時ファイルに保存し、File オブジェクトを返す。
     * IO スレッドで呼ばれることを前提とする。
     *
     * @return 保存した File。IO エラー発生時は null
     */
    private fun saveBitmapToTemp(bitmap: Bitmap): File? {
        return try {
            val tempFile = TempCache.createTempFile(appContext)
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tempFile
        } catch (e: java.io.IOException) {
            Log.w(TAG, "saveBitmapToTemp: IO error writing temp file", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "saveBitmapToTemp: permission denied writing temp file", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "saveBitmapToTemp failed", e)
            null
        }
    }
}
