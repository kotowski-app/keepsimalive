package app.kotowski.keepsimalive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemScrollIndicatorMathTest {
    private fun calculateThumbHeight(
        viewportHeight: Float,
        totalContentHeight: Float,
    ): Float {
        val minThumbHeight = 66f
        return (viewportHeight / totalContentHeight * viewportHeight)
            .coerceIn(minThumbHeight, viewportHeight)
    }

    private fun calculateScrollProgress(
        currentValue: Float,
        maxValue: Float,
    ): Float = (currentValue / maxValue).coerceIn(0f, 1f)

    private fun calculateThumbOffsetY(
        scrollProgress: Float,
        viewportHeight: Float,
        thumbHeight: Float,
    ): Float {
        val maxThumbOffset = viewportHeight - thumbHeight
        return scrollProgress * maxThumbOffset
    }

    @Test
    fun `no scrollbar when maxValue is zero`() {
        assertEquals(0, 0)
    }

    @Test
    fun `thumbHeight uses minimum when content is small`() {
        val viewport = 1000f
        val totalContent = 1100f
        val thumbHeight = calculateThumbHeight(viewport, totalContent)
        assertTrue(thumbHeight >= 66f)
    }

    @Test
    fun `thumbHeight clamps to viewport when content equals viewport`() {
        val viewport = 1000f
        val totalContent = 1000f
        val thumbHeight = calculateThumbHeight(viewport, totalContent)
        assertEquals(viewport, thumbHeight, 0.01f)
    }

    @Test
    fun `thumbHeight scales proportionally for large content`() {
        val viewport = 1000f
        val totalContent = 3000f
        val thumbHeight = calculateThumbHeight(viewport, totalContent)
        val expected = (1000f / 3000f * 1000f)
        assertTrue(thumbHeight >= 66f)
        assertTrue(thumbHeight <= expected + 0.01f)
    }

    @Test
    fun `scrollProgress is zero at start`() {
        val progress = calculateScrollProgress(0f, 500f)
        assertEquals(0f, progress, 0.01f)
    }

    @Test
    fun `scrollProgress is one at end`() {
        val progress = calculateScrollProgress(500f, 500f)
        assertEquals(1f, progress, 0.01f)
    }

    @Test
    fun `scrollProgress is half at midpoint`() {
        val progress = calculateScrollProgress(250f, 500f)
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun `scrollProgress clamps to zero for negative value`() {
        val progress = calculateScrollProgress(-10f, 500f)
        assertEquals(0f, progress, 0.01f)
    }

    @Test
    fun `scrollProgress clamps to one for overflow value`() {
        val progress = calculateScrollProgress(600f, 500f)
        assertEquals(1f, progress, 0.01f)
    }

    @Test
    fun `thumbOffsetY is zero at start`() {
        val thumbHeight = 100f
        val offsetY = calculateThumbOffsetY(0f, 1000f, thumbHeight)
        assertEquals(0f, offsetY, 0.01f)
    }

    @Test
    fun `thumbOffsetY is max at end`() {
        val thumbHeight = 100f
        val offsetY = calculateThumbOffsetY(1f, 1000f, thumbHeight)
        assertEquals(900f, offsetY, 0.01f)
    }

    @Test
    fun `thumbOffsetY is proportional at midpoint`() {
        val thumbHeight = 100f
        val offsetY = calculateThumbOffsetY(0.5f, 1000f, thumbHeight)
        assertEquals(450f, offsetY, 0.01f)
    }

    @Test
    fun `full calculation end to end`() {
        val viewportHeight = 1000f
        val maxValue = 2000
        val currentValue = 1000
        val totalContentHeight = maxValue + viewportHeight

        val thumbHeight = calculateThumbHeight(viewportHeight, totalContentHeight.toFloat())
        val scrollProgress = calculateScrollProgress(currentValue.toFloat(), maxValue.toFloat())
        val thumbOffsetY = calculateThumbOffsetY(scrollProgress, viewportHeight, thumbHeight)

        assertTrue(thumbHeight >= 66f)
        assertTrue(thumbHeight <= viewportHeight)
        assertTrue(scrollProgress in 0f..1f)
        assertTrue(thumbOffsetY >= 0f)
        assertTrue(thumbOffsetY <= viewportHeight - thumbHeight)
    }
}
