package dev.screenshoteditor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touch-driven crop frame drawn over a bitmap.
 *
 * All frame arithmetic (aspect ratio fitting, dragging, resizing) lives in [CropGeometry]; this
 * class only maps between the view and the bitmap and translates touch events into geometry calls.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // Sizes are specified in dp so that handles keep the same physical size across densities.
        // The touch radius gives a 48dp touch target, the Material minimum for touch targets.
        const val HANDLE_RADIUS_DP = 10f
        const val HANDLE_TOUCH_RADIUS_DP = 24f
        const val MIN_CROP_SIZE_DP = 48f
        const val FRAME_STROKE_DP = 1.5f
    }

    private enum class TouchMode { NONE, DRAG, RESIZE }

    private val density = resources.displayMetrics.density
    private val handleRadius = HANDLE_RADIUS_DP * density
    private val handleTouchRadius = HANDLE_TOUCH_RADIUS_DP * density
    private val minCropSize = MIN_CROP_SIZE_DP * density

    private var bitmap: Bitmap? = null
    private val bitmapMatrix = Matrix()

    /** Bitmap area in view coordinates. Empty until both the bitmap and the view size are known. */
    private var bitmapBox = Box(0f, 0f, 0f, 0f)

    /** Current crop frame in view coordinates; always inside [bitmapBox]. */
    private var cropBox = Box(0f, 0f, 0f, 0f)

    /** Reused for drawing to avoid allocations in onDraw. */
    private val drawRect = RectF()

    private val paint = Paint().apply { isAntiAlias = true }

    private val cropPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = FRAME_STROKE_DP * density
    }

    private val overlayPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var touchMode = TouchMode.NONE
    private var touchHandle = CropHandle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Locked width / height ratio, or null for free cropping. */
    private var aspectRatio: Float? = null

    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        setupBitmap()
        invalidate()
    }

    /**
     * Locks the crop frame to [ratio] (width / height), or unlocks it with null.
     *
     * Non-finite or non-positive ratios are rejected (treated as "free") instead of producing NaN
     * geometry.
     */
    fun setAspectRatio(ratio: Float?) {
        aspectRatio = ratio?.takeIf { it.isFinite() && it > 0f }
        applyAspectRatio()
        invalidate()
    }

    fun reset() {
        setupBitmap()
        invalidate()
    }

    /**
     * Returns the cropped region of the original bitmap, or null when no bitmap is set or the view
     * matrix is not invertible.
     *
     * Note: Bitmap.createBitmap may return the original instance when the crop covers the whole
     * bitmap. Callers must therefore not recycle the result independently of the source.
     */
    fun getCroppedBitmap(): Bitmap? {
        val originalBitmap = bitmap ?: return null
        if (!cropBox.hasArea) return null

        val inverse = Matrix()
        if (!bitmapMatrix.invert(inverse)) return null

        val points = floatArrayOf(cropBox.left, cropBox.top, cropBox.right, cropBox.bottom)
        inverse.mapPoints(points)

        // Edge Case: left/top must leave room for at least one pixel, otherwise createBitmap throws
        // (x + width > bitmap.width) when the frame sits on the right or bottom edge.
        val left = points[0].toInt().coerceIn(0, originalBitmap.width - 1)
        val top = points[1].toInt().coerceIn(0, originalBitmap.height - 1)
        val right = points[2].toInt().coerceIn(left + 1, originalBitmap.width)
        val bottom = points[3].toInt().coerceIn(top + 1, originalBitmap.height)

        return Bitmap.createBitmap(originalBitmap, left, top, right - left, bottom - top)
    }

    private fun setupBitmap() {
        val bmp = bitmap ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || bmp.width <= 0 || bmp.height <= 0) return

        // Fit the bitmap into the view and centre it.
        val scale = min(viewWidth / bmp.width, viewHeight / bmp.height)
        bitmapMatrix.reset()
        bitmapMatrix.postScale(scale, scale)
        bitmapMatrix.postTranslate((viewWidth - bmp.width * scale) / 2f, (viewHeight - bmp.height * scale) / 2f)

        drawRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        bitmapMatrix.mapRect(drawRect)
        bitmapBox = Box(drawRect.left, drawRect.top, drawRect.right, drawRect.bottom)

        cropBox = bitmapBox
        applyAspectRatio()
    }

    private fun applyAspectRatio() {
        val ratio = aspectRatio ?: return
        if (!bitmapBox.hasArea) return
        cropBox = CropGeometry.fitAspect(cropBox, bitmapBox, ratio, minCropSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let { canvas.drawBitmap(it, bitmapMatrix, paint) }

        drawRect.set(cropBox.left, cropBox.top, cropBox.right, cropBox.bottom)

        // Dim everything outside the crop frame.
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        canvas.withSave {
            clipOutRect(drawRect)
            drawRect(0f, 0f, viewWidth, viewHeight, overlayPaint)
        }

        canvas.drawRect(drawRect, cropPaint)
        drawHandles(canvas)
    }

    private fun drawHandles(canvas: Canvas) {
        for (handle in HANDLES) {
            canvas.drawCircle(handleX(handle), handleY(handle), handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchHandle = findTouchedHandle(event.x, event.y)
                touchMode = when {
                    touchHandle != CropHandle.NONE -> TouchMode.RESIZE
                    cropBox.hasArea && event.x in cropBox.left..cropBox.right &&
                        event.y in cropBox.top..cropBox.bottom -> TouchMode.DRAG
                    else -> TouchMode.NONE
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                when (touchMode) {
                    TouchMode.DRAG -> cropBox = CropGeometry.move(cropBox, dx, dy, bitmapBox)
                    TouchMode.RESIZE -> cropBox = CropGeometry.resize(
                        cropBox, touchHandle, dx, dy, bitmapBox, minCropSize, aspectRatio
                    )
                    TouchMode.NONE -> {}
                }
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }

            // ACTION_CANCEL arrives when a parent or the system steals the gesture; without resetting
            // here the next ACTION_MOVE would continue a stale drag.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A tap (no drag) is reported as a click so that accessibility services see it.
                if (event.actionMasked == MotionEvent.ACTION_UP && touchMode == TouchMode.NONE) performClick()
                touchMode = TouchMode.NONE
                touchHandle = CropHandle.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Corners are checked before edges so that a small frame still resizes from its corners. */
    private fun findTouchedHandle(x: Float, y: Float): CropHandle {
        if (!cropBox.hasArea) return CropHandle.NONE
        for (handle in HANDLES) {
            if (hypot(x - handleX(handle), y - handleY(handle)) < handleTouchRadius) return handle
        }
        return CropHandle.NONE
    }

    private fun handleX(handle: CropHandle): Float = when {
        handle.movesLeft -> cropBox.left
        handle.movesRight -> cropBox.right
        else -> cropBox.centerX
    }

    private fun handleY(handle: CropHandle): Float = when {
        handle.movesTop -> cropBox.top
        handle.movesBottom -> cropBox.bottom
        else -> cropBox.centerY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && bitmap != null) {
            setupBitmap()
        }
    }
}

/** Handle hit-test and drawing order: corners first, then edges. */
private val HANDLES = listOf(
    CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT,
    CropHandle.TOP, CropHandle.BOTTOM, CropHandle.LEFT, CropHandle.RIGHT
)
