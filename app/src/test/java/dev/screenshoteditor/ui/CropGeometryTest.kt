package dev.screenshoteditor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CropGeometry].
 *
 * The bitmap area used throughout is a portrait screenshot shown at 1080x2400 view pixels
 * (a typical 20:9 phone), which is the case where the old implementation failed: with the whole
 * image selected, choosing 1:1 grew the frame vertically and the bounds clipped it straight back,
 * so the ratio had no effect.
 */
class CropGeometryTest {

    private val bounds = Box(0f, 0f, 1080f, 2400f)
    private val minSize = 144f // 48dp at density 3.0
    private val eps = 0.01f

    private fun assertInside(rect: Box, outer: Box = bounds) {
        assertTrue("left $rect", rect.left >= outer.left - eps)
        assertTrue("top $rect", rect.top >= outer.top - eps)
        assertTrue("right $rect", rect.right <= outer.right + eps)
        assertTrue("bottom $rect", rect.bottom <= outer.bottom + eps)
    }

    private fun assertRatio(rect: Box, ratio: Float) {
        assertEquals("ratio of $rect", ratio, rect.width / rect.height, 0.001f)
    }

    // --- fitAspect ---

    @Test
    fun fitAspect_fullPortraitSelectionWithSquare_shrinksToFullWidthSquare() {
        val result = CropGeometry.fitAspect(bounds, bounds, 1f, minSize)

        // The largest square inside 1080x2400 is 1080x1080, centred vertically.
        assertEquals(Box(0f, 660f, 1080f, 1740f), result)
    }

    @Test
    fun fitAspect_everyOfferedRatio_staysInsideBoundsAndKeepsRatio() {
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f, 9f / 16f)) {
            val result = CropGeometry.fitAspect(bounds, bounds, ratio, minSize)
            assertInside(result)
            assertRatio(result, ratio)
        }
    }

    @Test
    fun fitAspect_neverGrowsBeyondCurrentFrame() {
        // A small frame must shrink to fit inside itself, not grow out of it.
        val current = Box(100f, 100f, 600f, 400f) // 500x300
        val result = CropGeometry.fitAspect(current, bounds, 1f, minSize)

        assertInside(result, current)
        assertEquals(300f, result.width, eps)
        assertRatio(result, 1f)
    }

    @Test
    fun fitAspect_frameBelowMinimum_isEnlargedToMinimumInsideBounds() {
        // A 200x150 frame at the top-left corner with 9:16 would shrink to ~84x150 (narrower than
        // the minimum); the result is enlarged to the minimum width and pushed inside the bounds.
        val current = Box(0f, 0f, 200f, 150f)
        val result = CropGeometry.fitAspect(current, bounds, 9f / 16f, minSize)

        assertInside(result)
        assertRatio(result, 9f / 16f)
        assertTrue(result.width >= minSize - eps && result.height >= minSize - eps)
    }

    @Test
    fun fitAspect_emptyBounds_returnsCurrentUnchanged() {
        val current = Box(1f, 2f, 3f, 4f)
        assertEquals(current, CropGeometry.fitAspect(current, Box(0f, 0f, 0f, 0f), 1f, minSize))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitAspect_nonPositiveRatio_isRejected() {
        CropGeometry.fitAspect(bounds, bounds, 0f, minSize)
    }

    // --- move ---

    @Test
    fun move_pastRightEdge_stopsAtEdgeWithoutShrinking() {
        val rect = Box(500f, 500f, 900f, 900f)
        val result = CropGeometry.move(rect, 500f, 0f, bounds)

        // Only 180px of room on the right: the frame stops there and keeps its 400x400 size.
        assertEquals(Box(680f, 500f, 1080f, 900f), result)
    }

    @Test
    fun move_pastTopLeftCorner_stopsAtCornerWithoutShrinking() {
        val rect = Box(50f, 30f, 450f, 330f)
        val result = CropGeometry.move(rect, -1000f, -1000f, bounds)

        assertEquals(Box(0f, 0f, 400f, 300f), result)
    }

    @Test
    fun move_withinBounds_appliesFullDelta() {
        val rect = Box(100f, 100f, 200f, 200f)
        assertEquals(Box(110f, 80f, 210f, 180f), CropGeometry.move(rect, 10f, -20f, bounds))
    }

    // --- resize, free mode ---

    @Test
    fun resizeFree_rightEdgePastBounds_isClampedToBounds() {
        val rect = Box(100f, 100f, 500f, 500f)
        val result = CropGeometry.resize(rect, CropHandle.RIGHT, 2000f, 0f, bounds, minSize, null)

        assertEquals(Box(100f, 100f, 1080f, 500f), result)
    }

    @Test
    fun resizeFree_leftEdgePastRightEdge_keepsMinimumWidth() {
        val rect = Box(100f, 100f, 500f, 500f)
        val result = CropGeometry.resize(rect, CropHandle.LEFT, 1000f, 0f, bounds, minSize, null)

        assertEquals(500f - minSize, result.left, eps)
        assertEquals(500f, result.right, eps)
    }

    @Test
    fun resizeFree_boundsSmallerThanMinimum_doesNotThrow() {
        // A tiny bitmap (e.g. a 100x80 crop of a very small image) must not invert clamp ranges.
        val tiny = Box(0f, 0f, 100f, 80f)
        val result = CropGeometry.resize(tiny, CropHandle.BOTTOM_RIGHT, -50f, -50f, tiny, minSize, null)

        assertInside(result, tiny)
    }

    // --- resize, fixed ratio ---

    @Test
    fun resizeWithRatio_cornerDrag_keepsOppositeCornerAnchored() {
        val ratio = 4f / 3f
        val rect = Box(100f, 200f, 500f, 500f) // 400x300
        val result = CropGeometry.resize(rect, CropHandle.BOTTOM_RIGHT, 80f, 10f, bounds, minSize, ratio)

        assertEquals(100f, result.left, eps)
        assertEquals(200f, result.top, eps)
        assertRatio(result, ratio)
        assertEquals(480f, result.width, eps) // horizontal movement dominated
    }

    @Test
    fun resizeWithRatio_topLeftDrag_keepsBottomRightAnchored() {
        val ratio = 1f
        val rect = Box(300f, 300f, 700f, 700f)
        val result = CropGeometry.resize(rect, CropHandle.TOP_LEFT, -50f, -20f, bounds, minSize, ratio)

        assertEquals(700f, result.right, eps)
        assertEquals(700f, result.bottom, eps)
        assertRatio(result, ratio)
    }

    @Test
    fun resizeWithRatio_purelyInwardHorizontalCornerDrag_shrinks() {
        // Regression guard for the "follow the dominant axis" rule: max() of both axes would ignore this.
        val rect = Box(100f, 100f, 500f, 500f)
        val result = CropGeometry.resize(rect, CropHandle.BOTTOM_RIGHT, -100f, 0f, bounds, minSize, 1f)

        assertEquals(300f, result.width, eps)
        assertRatio(result, 1f)
    }

    @Test
    fun resizeWithRatio_cornerDragBeyondBounds_isLimitedByNearestBound() {
        val ratio = 1f
        val rect = Box(600f, 100f, 900f, 400f)
        val result = CropGeometry.resize(rect, CropHandle.BOTTOM_RIGHT, 5000f, 5000f, bounds, minSize, ratio)

        assertInside(result)
        assertRatio(result, ratio)
        assertEquals(1080f, result.right, eps) // right edge is the binding constraint
        assertEquals(600f, result.left, eps)
    }

    @Test
    fun resizeWithRatio_edgeHandles_anchorOppositeEdgeAndStayInside() {
        val ratio = 16f / 9f
        val rect = Box(100f, 1000f, 580f, 1270f) // 480x270
        val cases = mapOf(
            CropHandle.RIGHT to Pair(300f, 0f),
            CropHandle.LEFT to Pair(-80f, 0f),
            CropHandle.TOP to Pair(0f, -400f),
            CropHandle.BOTTOM to Pair(0f, 5000f)
        )
        for ((handle, delta) in cases) {
            val result = CropGeometry.resize(rect, handle, delta.first, delta.second, bounds, minSize, ratio)
            assertInside(result)
            assertRatio(result, ratio)
            when (handle) {
                CropHandle.RIGHT -> assertEquals(rect.left, result.left, eps)
                CropHandle.LEFT -> assertEquals(rect.right, result.right, eps)
                CropHandle.TOP -> assertEquals(rect.bottom, result.bottom, eps)
                CropHandle.BOTTOM -> assertEquals(rect.top, result.top, eps)
                else -> Unit
            }
        }
    }

    @Test
    fun resizeWithRatio_edgeNearBoundary_shiftsInsteadOfOverflowing() {
        // Frame touching the top: growing it from the RIGHT edge needs more height than fits above
        // the centre line, so it is shifted down rather than pushed outside the bounds.
        val ratio = 1f
        val rect = Box(100f, 0f, 300f, 200f)
        val result = CropGeometry.resize(rect, CropHandle.RIGHT, 200f, 0f, bounds, minSize, ratio)

        assertInside(result)
        assertRatio(result, ratio)
        assertEquals(100f, result.left, eps)
        assertEquals(0f, result.top, eps)
    }

    @Test
    fun resizeWithRatio_shrinkBelowMinimum_stopsAtMinimum() {
        val ratio = 16f / 9f
        val rect = Box(100f, 100f, 580f, 370f)
        val result = CropGeometry.resize(rect, CropHandle.BOTTOM_RIGHT, -1000f, -1000f, bounds, minSize, ratio)

        assertRatio(result, ratio)
        assertTrue("height ${result.height}", result.height >= minSize - eps)
    }

    @Test
    fun resize_noneHandle_returnsInputUnchanged() {
        val rect = Box(10f, 10f, 200f, 200f)
        assertEquals(rect, CropGeometry.resize(rect, CropHandle.NONE, 50f, 50f, bounds, minSize, 1f))
    }
}
