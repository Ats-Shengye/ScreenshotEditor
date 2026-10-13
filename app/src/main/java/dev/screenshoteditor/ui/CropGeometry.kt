package dev.screenshoteditor.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable axis-aligned rectangle in view coordinates.
 *
 * Design Decision: android.graphics.RectF is intentionally not used here. In local JVM unit tests
 * the android.jar methods are stubs (RectF.width() etc. are not implemented), so keeping the crop
 * geometry on a plain Kotlin type lets [CropGeometry] be tested without Robolectric.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** True when the rectangle encloses a positive area. */
    val hasArea: Boolean get() = width > 0f && height > 0f
}

/** Draggable handle of the crop frame. */
enum class CropHandle {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT;

    val movesLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT || this == LEFT
    val movesRight: Boolean get() = this == TOP_RIGHT || this == BOTTOM_RIGHT || this == RIGHT
    val movesTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == TOP
    val movesBottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT || this == BOTTOM
    val isCorner: Boolean get() = (movesLeft || movesRight) && (movesTop || movesBottom)
}

/**
 * Pure crop-frame geometry used by [CropView].
 *
 * Invariants guaranteed by every public function (given a `bounds` with positive area and an input
 * rectangle already inside `bounds`):
 * - The result lies inside `bounds`.
 * - When an aspect ratio is supplied, `result.width / result.height` equals that ratio
 *   (within float rounding).
 * - The result is never smaller than `minSize` on either side, unless `bounds` itself is smaller;
 *   in that case the minimum is relaxed to what `bounds` can hold so that no clamp range inverts.
 */
object CropGeometry {

    /**
     * Returns the largest rectangle with the given aspect ratio that fits inside both the current
     * crop frame and the bitmap bounds, centred on the current frame.
     *
     * Shrinking (rather than growing and then intersecting) is what keeps the ratio intact: a grown
     * rectangle clipped by `bounds` loses its ratio, which was the cause of a selected ratio having
     * no visible effect on a full-image selection.
     *
     * Edge Case: when the shrunk rectangle would fall below `minSize`, it is enlarged back to the
     * minimum (as far as `bounds` allows) and shifted to stay inside `bounds`.
     *
     * @param ratio width / height; must be finite and positive.
     */
    fun fitAspect(current: Box, bounds: Box, ratio: Float, minSize: Float): Box {
        require(ratio.isFinite() && ratio > 0f) { "ratio must be finite and positive" }
        if (!bounds.hasArea) return current
        val base = intersect(current, bounds) ?: bounds

        var width: Float
        var height: Float
        if (base.width / base.height > ratio) {
            height = base.height
            width = height * ratio
        } else {
            width = base.width
            height = width / ratio
        }

        val maxWidth = maxWidthInside(bounds.width, bounds.height, ratio)
        val minWidth = min(minWidthFor(ratio, minSize), maxWidth)
        width = clamp(width, minWidth, maxWidth)
        height = width / ratio

        return placeInside(base.centerX, base.centerY, width, height, bounds)
    }

    /**
     * Translates [rect] by (dx, dy) without changing its size, stopping at the bitmap bounds.
     *
     * The movement is clamped before it is applied; clipping afterwards would shrink the frame
     * whenever it touches an edge.
     */
    fun move(rect: Box, dx: Float, dy: Float, bounds: Box): Box {
        val clampedDx = clamp(dx, bounds.left - rect.left, bounds.right - rect.right)
        val clampedDy = clamp(dy, bounds.top - rect.top, bounds.bottom - rect.bottom)
        return Box(rect.left + clampedDx, rect.top + clampedDy, rect.right + clampedDx, rect.bottom + clampedDy)
    }

    /**
     * Resizes [rect] by dragging [handle] by (dx, dy).
     *
     * Free mode moves only the dragged edges. Fixed-ratio mode keeps the side(s) opposite to the
     * dragged handle fixed (the anchor) so that the frame does not jump around its centre.
     *
     * @param ratio width / height, or null for free resizing.
     */
    fun resize(rect: Box, handle: CropHandle, dx: Float, dy: Float, bounds: Box, minSize: Float, ratio: Float?): Box {
        if (handle == CropHandle.NONE || !bounds.hasArea) return rect
        if (ratio == null) return resizeFree(rect, handle, dx, dy, bounds, minSize)
        require(ratio.isFinite() && ratio > 0f) { "ratio must be finite and positive" }
        return if (handle.isCorner) {
            resizeCornerWithRatio(rect, handle, dx, dy, bounds, minSize, ratio)
        } else {
            resizeEdgeWithRatio(rect, handle, dx, dy, bounds, minSize, ratio)
        }
    }

    /** Returns the overlap of [a] and [b], or null when they do not overlap with a positive area. */
    fun intersect(a: Box, b: Box): Box? {
        val result = Box(max(a.left, b.left), max(a.top, b.top), min(a.right, b.right), min(a.bottom, b.bottom))
        return if (result.hasArea) result else null
    }

    // --- private ---

    private fun resizeFree(rect: Box, handle: CropHandle, dx: Float, dy: Float, bounds: Box, minSize: Float): Box {
        val minWidth = min(minSize, bounds.width)
        val minHeight = min(minSize, bounds.height)
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        if (handle.movesLeft) left = clamp(left + dx, bounds.left, right - minWidth)
        if (handle.movesRight) right = clamp(right + dx, left + minWidth, bounds.right)
        if (handle.movesTop) top = clamp(top + dy, bounds.top, bottom - minHeight)
        if (handle.movesBottom) bottom = clamp(bottom + dy, top + minHeight, bounds.bottom)
        return Box(left, top, right, bottom)
    }

    private fun resizeCornerWithRatio(
        rect: Box,
        handle: CropHandle,
        dx: Float,
        dy: Float,
        bounds: Box,
        minSize: Float,
        ratio: Float
    ): Box {
        // The corner diagonally opposite to the dragged one stays where it is.
        val anchorX = if (handle.movesLeft) rect.right else rect.left
        val anchorY = if (handle.movesTop) rect.bottom else rect.top
        val proposedWidth = if (handle.movesLeft) rect.width - dx else rect.width + dx
        val proposedHeight = if (handle.movesTop) rect.height - dy else rect.height + dy

        // Design Decision: follow the axis the finger moved further (relative to the current size).
        // Taking max() of both would make a purely inward horizontal drag a no-op, and taking min()
        // would make a purely outward drag a no-op.
        val scaleX = if (rect.width > 0f) proposedWidth / rect.width else 1f
        val scaleY = if (rect.height > 0f) proposedHeight / rect.height else 1f
        var width = if (abs(scaleX - 1f) >= abs(scaleY - 1f)) proposedWidth else proposedHeight * ratio

        val availableWidth = if (handle.movesLeft) anchorX - bounds.left else bounds.right - anchorX
        val availableHeight = if (handle.movesTop) anchorY - bounds.top else bounds.bottom - anchorY
        val maxWidth = maxWidthInside(availableWidth, availableHeight, ratio)
        width = clamp(width, min(minWidthFor(ratio, minSize), maxWidth), maxWidth)
        val height = width / ratio

        val left = if (handle.movesLeft) anchorX - width else anchorX
        val top = if (handle.movesTop) anchorY - height else anchorY
        return Box(left, top, left + width, top + height)
    }

    private fun resizeEdgeWithRatio(
        rect: Box,
        handle: CropHandle,
        dx: Float,
        dy: Float,
        bounds: Box,
        minSize: Float,
        ratio: Float
    ): Box {
        return if (handle.movesLeft || handle.movesRight) {
            // The opposite vertical edge is the anchor; the frame grows/shrinks vertically around
            // its current centre and is shifted only as far as needed to stay inside bounds.
            val anchorX = if (handle.movesLeft) rect.right else rect.left
            val proposedWidth = if (handle.movesLeft) rect.width - dx else rect.width + dx
            val availableWidth = if (handle.movesLeft) anchorX - bounds.left else bounds.right - anchorX
            val maxWidth = maxWidthInside(availableWidth, bounds.height, ratio)
            val width = clamp(proposedWidth, min(minWidthFor(ratio, minSize), maxWidth), maxWidth)
            val height = width / ratio
            val left = if (handle.movesLeft) anchorX - width else anchorX
            val top = clamp(rect.centerY - height / 2f, bounds.top, bounds.bottom - height)
            Box(left, top, left + width, top + height)
        } else {
            val anchorY = if (handle.movesTop) rect.bottom else rect.top
            val proposedHeight = if (handle.movesTop) rect.height - dy else rect.height + dy
            val availableHeight = if (handle.movesTop) anchorY - bounds.top else bounds.bottom - anchorY
            val maxWidth = maxWidthInside(bounds.width, availableHeight, ratio)
            val width = clamp(proposedHeight * ratio, min(minWidthFor(ratio, minSize), maxWidth), maxWidth)
            val height = width / ratio
            val top = if (handle.movesTop) anchorY - height else anchorY
            val left = clamp(rect.centerX - width / 2f, bounds.left, bounds.right - width)
            Box(left, top, left + width, top + height)
        }
    }

    /** Largest width whose ratio-preserving height still fits in the available space. */
    private fun maxWidthInside(availableWidth: Float, availableHeight: Float, ratio: Float): Float =
        max(0f, min(availableWidth, availableHeight * ratio))

    /** Smallest width for which both width and height are at least [minSize]. */
    private fun minWidthFor(ratio: Float, minSize: Float): Float = max(minSize, minSize * ratio)

    private fun placeInside(centerX: Float, centerY: Float, width: Float, height: Float, bounds: Box): Box {
        val left = clamp(centerX - width / 2f, bounds.left, bounds.right - width)
        val top = clamp(centerY - height / 2f, bounds.top, bounds.bottom - height)
        return Box(left, top, left + width, top + height)
    }

    /**
     * Clamps [value] to [low, high]. Unlike coerceIn, an inverted range (possible through float
     * rounding when a rectangle exactly fills the bounds) does not throw; [low] wins.
     */
    private fun clamp(value: Float, low: Float, high: Float): Float =
        if (high < low) low else value.coerceIn(low, high)
}
