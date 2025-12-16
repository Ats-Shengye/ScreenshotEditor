package com.example.screenshoteditor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var bitmap: Bitmap? = null
    private val bitmapMatrix = Matrix()
    private val cropRect = RectF()
    private val bitmapRect = RectF()
    
    private val paint = Paint().apply {
        isAntiAlias = true
    }
    
    private val cropPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val overlayPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
    }
    
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private var touchMode = TouchMode.NONE
    private var touchHandle = Handle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    private val handleRadius = 30f
    private val handleTouchRadius = 50f
    
    private var aspectRatio: Float? = null
    
    enum class TouchMode {
        NONE, DRAG, RESIZE
    }
    
    enum class Handle {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP, BOTTOM, LEFT, RIGHT
    }
    
    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        setupBitmap()
        invalidate()
    }
    
    fun setAspectRatio(ratio: Float?) {
        aspectRatio = ratio
        if (ratio != null) {
            adjustCropRectToAspectRatio()
        }
        invalidate()
    }
    
    fun rotate(degrees: Float) {
        bitmapMatrix.postRotate(degrees, width / 2f, height / 2f)
        invalidate()
    }
    
    fun reset() {
        setupBitmap()
        invalidate()
    }
    
    fun getCroppedBitmap(): Bitmap? {
        val originalBitmap = bitmap ?: return null
        
        // 変換行列の逆行列を作成
        val inverse = Matrix()
        if (!bitmapMatrix.invert(inverse)) return null
        
        // クロップ領域を元の画像座標系に変換
        val points = floatArrayOf(
            cropRect.left, cropRect.top,
            cropRect.right, cropRect.bottom
        )
        inverse.mapPoints(points)
        
        val left = points[0].toInt().coerceIn(0, originalBitmap.width)
        val top = points[1].toInt().coerceIn(0, originalBitmap.height)
        val right = points[2].toInt().coerceIn(0, originalBitmap.width)
        val bottom = points[3].toInt().coerceIn(0, originalBitmap.height)
        
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        
        return Bitmap.createBitmap(originalBitmap, left, top, width, height)
    }
    
    private fun setupBitmap() {
        val bmp = bitmap ?: return
        
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        if (viewWidth == 0f || viewHeight == 0f) return
        
        // ビットマップをビューに合わせてスケーリング
        val scale = min(viewWidth / bmp.width, viewHeight / bmp.height)
        
        bitmapMatrix.reset()
        bitmapMatrix.postScale(scale, scale)
        
        // 中央に配置
        val scaledWidth = bmp.width * scale
        val scaledHeight = bmp.height * scale
        val dx = (viewWidth - scaledWidth) / 2
        val dy = (viewHeight - scaledHeight) / 2
        
        bitmapMatrix.postTranslate(dx, dy)
        
        // ビットマップの領域を更新
        bitmapRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        bitmapMatrix.mapRect(bitmapRect)
        
        // 初期クロップ領域をビットマップ全体に設定
        cropRect.set(bitmapRect)
    }
    
    private fun adjustCropRectToAspectRatio() {
        val ratio = aspectRatio ?: return
        
        val currentWidth = cropRect.width()
        val currentHeight = cropRect.height()
        val currentRatio = currentWidth / currentHeight
        
        if (abs(currentRatio - ratio) < 0.01) return
        
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        
        val newWidth: Float
        val newHeight: Float
        
        if (currentRatio > ratio) {
            // 幅を基準に高さを調整
            newWidth = currentWidth
            newHeight = currentWidth / ratio
        } else {
            // 高さを基準に幅を調整
            newHeight = currentHeight
            newWidth = currentHeight * ratio
        }
        
        cropRect.set(
            centerX - newWidth / 2,
            centerY - newHeight / 2,
            centerX + newWidth / 2,
            centerY + newHeight / 2
        )
        
        // ビットマップ領域内に収める
        constrainCropRect()
    }
    
    private fun constrainCropRect() {
        cropRect.intersect(bitmapRect)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // ビットマップを描画
        bitmap?.let {
            canvas.drawBitmap(it, bitmapMatrix, paint)
        }
        
        // 半透明オーバーレイを描画
        canvas.save()
        canvas.clipRect(cropRect, Region.Op.DIFFERENCE)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.restore()
        
        // クロップ枠を描画
        canvas.drawRect(cropRect, cropPaint)
        
        // ハンドルを描画
        drawHandles(canvas)
    }
    
    private fun drawHandles(canvas: Canvas) {
        // コーナーハンドル
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint)
        
        // エッジハンドル
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        canvas.drawCircle(centerX, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(centerX, cropRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.left, centerY, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, centerY, handleRadius, handlePaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchHandle = getTouchedHandle(event.x, event.y)
                touchMode = if (touchHandle != Handle.NONE) {
                    TouchMode.RESIZE
                } else if (cropRect.contains(event.x, event.y)) {
                    TouchMode.DRAG
                } else {
                    TouchMode.NONE
                }
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                
                when (touchMode) {
                    TouchMode.DRAG -> {
                        cropRect.offset(dx, dy)
                        constrainCropRect()
                    }
                    TouchMode.RESIZE -> {
                        resizeCropRect(touchHandle, dx, dy)
                    }
                    TouchMode.NONE -> {}
                }
                
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                touchMode = TouchMode.NONE
                touchHandle = Handle.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun getTouchedHandle(x: Float, y: Float): Handle {
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        
        // コーナーをチェック
        if (distance(x, y, cropRect.left, cropRect.top) < handleTouchRadius) return Handle.TOP_LEFT
        if (distance(x, y, cropRect.right, cropRect.top) < handleTouchRadius) return Handle.TOP_RIGHT
        if (distance(x, y, cropRect.left, cropRect.bottom) < handleTouchRadius) return Handle.BOTTOM_LEFT
        if (distance(x, y, cropRect.right, cropRect.bottom) < handleTouchRadius) return Handle.BOTTOM_RIGHT
        
        // エッジをチェック
        if (distance(x, y, centerX, cropRect.top) < handleTouchRadius) return Handle.TOP
        if (distance(x, y, centerX, cropRect.bottom) < handleTouchRadius) return Handle.BOTTOM
        if (distance(x, y, cropRect.left, centerY) < handleTouchRadius) return Handle.LEFT
        if (distance(x, y, cropRect.right, centerY) < handleTouchRadius) return Handle.RIGHT
        
        return Handle.NONE
    }
    
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    
    private fun resizeCropRect(handle: Handle, dx: Float, dy: Float) {
        val minSize = 100f
        
        when (handle) {
            Handle.TOP_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceAtMost(cropRect.right - minSize)
                cropRect.top = (cropRect.top + dy).coerceAtMost(cropRect.bottom - minSize)
            }
            Handle.TOP_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceAtLeast(cropRect.left + minSize)
                cropRect.top = (cropRect.top + dy).coerceAtMost(cropRect.bottom - minSize)
            }
            Handle.BOTTOM_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceAtMost(cropRect.right - minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceAtLeast(cropRect.top + minSize)
            }
            Handle.BOTTOM_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceAtLeast(cropRect.left + minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceAtLeast(cropRect.top + minSize)
            }
            Handle.TOP -> {
                cropRect.top = (cropRect.top + dy).coerceAtMost(cropRect.bottom - minSize)
            }
            Handle.BOTTOM -> {
                cropRect.bottom = (cropRect.bottom + dy).coerceAtLeast(cropRect.top + minSize)
            }
            Handle.LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceAtMost(cropRect.right - minSize)
            }
            Handle.RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceAtLeast(cropRect.left + minSize)
            }
            Handle.NONE -> {}
        }
        
        // アスペクト比を維持
        aspectRatio?.let {
            adjustCropRectToAspectRatio()
        }
        
        constrainCropRect()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && bitmap != null) {
            setupBitmap()
        }
    }
}