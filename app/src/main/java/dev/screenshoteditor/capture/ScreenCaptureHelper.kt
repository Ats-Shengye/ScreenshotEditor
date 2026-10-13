package dev.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.screenshoteditor.data.TempCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * スクリーンキャプチャの低レベル処理を担当するヘルパークラス（使い捨て）。
 *
 * VirtualDisplay の作成・最初のフレームの取得・パディング列とステータスバーの切り落とし・
 * 一時ファイル保存を引き受ける。MediaProjection のライフサイクルは ProjectionController に委譲する。
 *
 * スレッドモデル: Image（ImageReader のバッファ）に触れる処理と release() は Main スレッドに限定する。
 * IO スレッドは Image から複製した Bitmap だけを扱う。こうすることで、release() が ImageReader を
 * close した後に別スレッドが解放済みバッファを読む競合を構造的に排除している。
 *
 * リソースリーク防止:
 * - applicationContext に正規化して Activity 参照を保持しない
 * - capture() は AtomicBoolean で多重呼び出しをガード
 * - release() は AtomicBoolean で冪等化し、helperScope のキャンセル・リスナー解除・
 *   VirtualDisplay / MediaProjection / ImageReader の解放をまとめて行う
 *
 * @param context    Context（Activity Context 可だが内部で applicationContext に正規化）
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

        /**
         * VirtualDisplay 作成から最初のフレームが届くまでの上限（ミリ秒）。
         * 通常は数十ミリ秒で届く。低速端末でも十分な余裕を持たせつつ、
         * FLAG_SECURE 等でフレームが来ない場合に利用者を長く待たせない値として 2 秒とする。
         */
        private const val FRAME_TIMEOUT_MS = 2000L

        /**
         * acquireLatestImage() は内部で次のフレームを 1 枚確保してから古いものを捨てるため、
         * maxImages が 1 だと 2 枚目以降の取得に失敗しうる。2 枚あれば足りる。
         */
        private const val MAX_IMAGES = 2
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionController = ProjectionController(appContext, resultCode, data)
    private var imageReader: ImageReader? = null

    private val isCapturing = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    /** release() でキャンセルし、Activity 破棄後に onResult が呼ばれないようにする。 */
    private val helperScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * スクリーンキャプチャを実行し、結果を一時ファイルとして [onResult] に返す。Main スレッドから呼ぶこと。
     *
     * 前提条件: CaptureService が mediaProjection 型の Foreground Service に昇格済みであること。
     *
     * - 多重呼び出し時は onResult(null) を即返す
     * - 完了後（成功・失敗とも）内部リソースは自動的に解放される
     * - 完了前に [release] された場合、[onResult] は呼ばれない（呼び出し元の Activity が既に破棄されている前提）
     *
     * @param geometry 撮影サイズとステータスバー高さ（視覚的 Context から取得したもの）
     * @param onResult Main スレッドで呼ばれる。引数は一時ファイル（失敗時は null）
     */
    fun capture(geometry: CaptureGeometry, onResult: (File?) -> Unit) {
        if (isReleased.get() || !isCapturing.compareAndSet(false, true)) {
            onResult(null)
            return
        }
        helperScope.launch {
            val file = try {
                performCapture(geometry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // ImageReader / Image が想定外の状態（既に close 済み等）
                Log.w(TAG, "capture failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "capture failed", e)
                null
            } catch (e: UnsupportedOperationException) {
                // 端末が RGBA_8888 以外のフォーマットでフレームを返した場合
                Log.w(TAG, "capture failed", e)
                null
            }
            release()
            onResult(file)
        }
    }

    /**
     * 保持しているリソースを解放する。冪等。Activity の onDestroy でも呼ぶこと。
     *
     * 順序: コルーチン停止 → 生産側（VirtualDisplay / MediaProjection）停止 → リスナー解除 →
     * 消費側（ImageReader）close。生産側を先に止めることで、close 済みの Surface へ描画されるのを防ぐ。
     */
    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        helperScope.cancel()
        projectionController.stop()
        imageReader?.let { reader ->
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
        imageReader = null
    }

    // --- private ---

    private suspend fun performCapture(geometry: CaptureGeometry): File? {
        val reader = ImageReader.newInstance(geometry.width, geometry.height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader

        val image = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
            awaitFirstImage(reader) {
                projectionController.start(geometry.width, geometry.height, geometry.densityDpi, reader.surface)
            }
        }
        if (image == null) {
            Log.w(TAG, "performCapture: no frame within timeout or projection failed")
            return null
        }

        val content = image.use { copyToBitmap(it, geometry.statusBarHeight) }
        return withContext(Dispatchers.IO) {
            try {
                saveBitmapToTemp(content)
            } finally {
                content.recycle()
            }
        }
    }

    /**
     * リスナーを登録してから [startProducer] を呼び、最初に届いたフレームを返す。
     * [startProducer] が false を返した場合（VirtualDisplay を作れなかった）は即座に null を返す。
     *
     * タイムアウト等でキャンセルされた場合はリスナーを解除する。キャンセルと同時にフレームが届いた
     * 場合は resume の onCancellation で Image を close する（kotlinx.coroutines の契約）。
     */
    private suspend fun awaitFirstImage(reader: ImageReader, startProducer: () -> Boolean): Image? =
        suspendCancellableCoroutine { cont ->
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "awaitFirstImage: acquire failed", e)
                    null
                }
                if (image != null) {
                    r.setOnImageAvailableListener(null, null)
                    cont.resume(image) { _, unused, _ -> unused.close() }
                }
            }, mainHandler)
            cont.invokeOnCancellation {
                // Called on an arbitrary thread; post so that the reader is only touched on Main.
                mainHandler.post { if (!isReleased.get()) reader.setOnImageAvailableListener(null, null) }
            }
            if (!startProducer()) {
                reader.setOnImageAvailableListener(null, null)
                cont.resume(null) { _, _, _ -> }
            }
        }

    /**
     * Image を Bitmap に複製し、右端のパディング列と上端のステータスバーを 1 回の createBitmap で切り落とす。
     * Main スレッドで呼ぶ（Image のバッファは ImageReader の close と同じスレッドでのみ読む）。
     */
    private fun copyToBitmap(image: Image, statusBarHeight: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride % pixelStride != 0) {
            throw UnsupportedOperationException("Unexpected plane layout")
        }
        val bufferWidth = rowStride / pixelStride
        val region = CaptureRegion.contentRegion(bufferWidth, image.width, image.height, statusBarHeight)

        val padded = Bitmap.createBitmap(bufferWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val content = Bitmap.createBitmap(padded, region.x, region.y, region.width, region.height)
        // createBitmap は切り出し範囲が全体と一致すると同一インスタンスを返すため、参照比較で判定する
        if (content !== padded) padded.recycle()
        return content
    }

    /** IO スレッドで呼ぶ。失敗時は作りかけのファイルを消して null を返す。 */
    private fun saveBitmapToTemp(bitmap: Bitmap): File? {
        var tempFile: File? = null
        return try {
            val file = TempCache.createTempFile(appContext)
            tempFile = file
            val written = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) throw IOException("PNG encoding failed")
            file
        } catch (e: IOException) {
            Log.w(TAG, "saveBitmapToTemp: IO error writing temp file", e)
            tempFile?.let { TempCache.deleteQuietly(it) }
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "saveBitmapToTemp: permission denied writing temp file", e)
            tempFile?.let { TempCache.deleteQuietly(it) }
            null
        }
    }
}
