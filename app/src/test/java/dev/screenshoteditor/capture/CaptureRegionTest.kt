package dev.screenshoteditor.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [CaptureRegion.contentRegion], which decides which part of the padded ImageReader
 * frame becomes the screenshot.
 */
class CaptureRegionTest {

    @Test
    fun contentRegion_paddedRows_cutsPaddingColumnsAndStatusBar() {
        // 1080px wide image delivered with rowStride 4352 bytes / 4 bytes per pixel = 1088 px per row.
        // The 8 extra columns are padding and must not reach the screenshot (they showed up as a
        // garbage strip on the right edge before).
        val region = CaptureRegion.contentRegion(bufferWidth = 1088, imageWidth = 1080, imageHeight = 2400, statusBarHeight = 110)

        assertEquals(PixelRegion(x = 0, y = 110, width = 1080, height = 2290), region)
    }

    @Test
    fun contentRegion_noPaddingNoStatusBar_isWholeImage() {
        val region = CaptureRegion.contentRegion(1080, 1080, 2400, 0)
        assertEquals(PixelRegion(0, 0, 1080, 2400), region)
    }

    @Test
    fun contentRegion_statusBarNotSmallerThanImage_trimsNothing() {
        // Boundary: equal height would leave a 0-row bitmap, which createBitmap rejects.
        assertEquals(PixelRegion(0, 0, 100, 50), CaptureRegion.contentRegion(100, 100, 50, 50))
        assertEquals(PixelRegion(0, 0, 100, 50), CaptureRegion.contentRegion(100, 100, 50, 500))
    }

    @Test
    fun contentRegion_statusBarOneRowLessThanImage_leavesOneRow() {
        assertEquals(PixelRegion(0, 49, 100, 1), CaptureRegion.contentRegion(100, 100, 50, 49))
    }

    @Test(expected = IllegalArgumentException::class)
    fun contentRegion_bufferNarrowerThanImage_isRejected() {
        CaptureRegion.contentRegion(1000, 1080, 2400, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun contentRegion_emptyImage_isRejected() {
        CaptureRegion.contentRegion(0, 0, 0, 0)
    }
}
