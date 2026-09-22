package app.kotowski.keepsimalive.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

// Idle scrollbars fade out to save screen real estate (the Scrollbar guide).
internal fun scrollbarAlphaTarget(isScrollInProgress: Boolean): Float = if (isScrollInProgress) 1f else 0f

// Short ramp-in while the finger is down; slower ramp-out so the thumb stays readable for a moment.
internal fun scrollbarFadeDurationMs(isScrollInProgress: Boolean): Int = if (isScrollInProgress) 150 else 1000

@Composable
fun SystemScrollStateScrollIndicator(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val isScrolling = scrollState.isScrollInProgress
    val alpha by animateFloatAsState(
        targetValue = scrollbarAlphaTarget(isScrolling),
        animationSpec = tween(scrollbarFadeDurationMs(isScrolling)),
        label = "scrollbar_alpha",
    )
    // Nothing to scroll, or fully faded out while idle: no indicator at all.
    if (scrollState.maxValue <= 0 || alpha <= 0f) return

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f * alpha)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * alpha)

    Canvas(
        modifier =
            modifier
                .fillMaxHeight()
                .width(5.dp),
    ) {
        val viewportHeight = size.height
        val totalContentHeight = scrollState.maxValue + viewportHeight

        if (scrollState.maxValue <= 0) return@Canvas

        val minThumbHeight = 66f
        val thumbHeight =
            (viewportHeight / totalContentHeight * viewportHeight)
                .coerceIn(minThumbHeight, viewportHeight)
        val scrollProgress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
        val maxThumbOffset = viewportHeight - thumbHeight
        val thumbOffsetY = scrollProgress * maxThumbOffset

        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(size.width / 2, size.width / 2),
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbOffsetY),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2, size.width / 2),
        )
    }
}
