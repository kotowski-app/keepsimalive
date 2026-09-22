package app.kotowski.keepsimalive.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

// The LazyColumn counterpart of SystemScrollStateScrollIndicator: the LazyListState has
// no exact content size, so the total height is estimated from the average visible item
// height — close enough for the thumb even with rows of varying heights. The style
// matches the ScrollState indicator.
@Composable
fun SystemLazyColumnScrollIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val isScrolling = listState.isScrollInProgress
    val alpha by animateFloatAsState(
        targetValue = scrollbarAlphaTarget(isScrolling),
        animationSpec = tween(scrollbarFadeDurationMs(isScrolling)),
        label = "scrollbar_alpha",
    )
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    val viewportHeight = layoutInfo.viewportSize.height.toFloat()

    // Nothing to scroll, or fully faded out while idle: no indicator at all.
    if (visibleItems.isEmpty() || totalItems == 0 || viewportHeight <= 0f || alpha <= 0f) return

    val averageItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentHeight = totalItems * averageItemHeight

    if (totalContentHeight <= viewportHeight) return

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f * alpha)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * alpha)

    Canvas(
        modifier =
            modifier
                .fillMaxHeight()
                .width(5.dp),
    ) {
        val minThumbHeight = 66f
        val thumbHeight =
            (viewportHeight / totalContentHeight * viewportHeight)
                .coerceIn(minThumbHeight, viewportHeight)
        val scrollOffset =
            (listState.firstVisibleItemIndex * averageItemHeight) +
                listState.firstVisibleItemScrollOffset.toFloat()
        val maxScrollOffset = totalContentHeight - viewportHeight
        val scrollProgress =
            if (maxScrollOffset > 0) (scrollOffset / maxScrollOffset).coerceIn(0f, 1f) else 0f
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
