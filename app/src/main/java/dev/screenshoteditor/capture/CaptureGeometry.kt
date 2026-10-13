package dev.screenshoteditor.capture

import android.app.Activity
import android.view.WindowInsets
import kotlin.math.max

/**
 * Display geometry needed to set up a capture.
 *
 * Design Decision: the values are read from an Activity's WindowManager. A WindowManager obtained
 * from the application context is not associated with a window, which StrictMode reports as
 * IncorrectContextUse and which may return metrics that do not match the visible display.
 *
 * Known limitation: an Activity's metrics follow its own configuration. If the device rotates while
 * CaptureActivity waits for a delayed capture, the metrics are taken after the wait and normally
 * reflect the rotation, but this has to be confirmed on a device.
 *
 * @property width            Capture width in pixels (whole display, not just this window).
 * @property height           Capture height in pixels.
 * @property densityDpi       Display density passed to the VirtualDisplay.
 * @property statusBarHeight  Height in pixels trimmed from the top of the capture.
 */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val statusBarHeight: Int
) {
    init {
        require(width > 0 && height > 0) { "capture size must be positive" }
        require(densityDpi > 0) { "density must be positive" }
        require(statusBarHeight >= 0) { "status bar height must not be negative" }
    }

    companion object {
        fun fromActivity(activity: Activity): CaptureGeometry {
            val windowManager = activity.windowManager
            // maximumWindowMetrics covers the whole display even in split-screen, which is what a
            // screenshot captures; currentWindowMetrics would only cover this task's bounds.
            val maximum = windowManager.maximumWindowMetrics
            val current = windowManager.currentWindowMetrics
            val statusBars = WindowInsets.Type.statusBars()
            // Edge Case: the insets attached to the maximum metrics may be empty on some releases,
            // so the larger of the two values is used (an overestimate is not possible here because
            // both describe the same status bar).
            val statusBarHeight = max(
                maximum.windowInsets.getInsetsIgnoringVisibility(statusBars).top,
                current.windowInsets.getInsetsIgnoringVisibility(statusBars).top
            )
            return CaptureGeometry(
                width = maximum.bounds.width(),
                height = maximum.bounds.height(),
                densityDpi = activity.resources.displayMetrics.densityDpi,
                statusBarHeight = statusBarHeight
            )
        }
    }
}

/** A rectangular region of pixels inside a bitmap. */
data class PixelRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/** Pure pixel arithmetic for turning an ImageReader frame into the final screenshot. */
object CaptureRegion {

    /**
     * Returns the region of the padded frame bitmap that contains the screenshot.
     *
     * An ImageReader row may be longer than the image (rowStride > pixelStride * width). The frame is
     * therefore copied into a bitmap [bufferWidth] pixels wide; the extra columns on the right are
     * padding garbage and must be cut off together with the status bar at the top.
     *
     * Edge Case: a status bar height of 0, or one not smaller than the image, trims nothing.
     *
     * @param bufferWidth     Width of the padded bitmap (rowStride / pixelStride).
     * @param imageWidth      Real image width.
     * @param imageHeight     Real image height.
     * @param statusBarHeight Rows to remove from the top.
     */
    fun contentRegion(bufferWidth: Int, imageWidth: Int, imageHeight: Int, statusBarHeight: Int): PixelRegion {
        require(imageWidth > 0 && imageHeight > 0) { "image size must be positive" }
        require(bufferWidth >= imageWidth) { "buffer narrower than image" }
        val top = if (statusBarHeight in 1 until imageHeight) statusBarHeight else 0
        return PixelRegion(x = 0, y = top, width = imageWidth, height = imageHeight - top)
    }
}
