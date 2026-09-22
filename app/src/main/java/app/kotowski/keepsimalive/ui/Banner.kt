package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// The subtle tint of the banner: onSurface at low alpha over the screen background —
// the same theme, just a bit colored (black/white themes read as gray, Material You
// palettes as a faint neutral lift).
private const val BANNER_TINT_ALPHA: Float = 0.06f

// The shared notice band: icon + title on one row, an optional action button on the right
// of the row, an optional description under it. Full-width with the subtle tint, shown
// directly on the screen (not inside a card).
@Composable
fun Banner(
    icon: ImageVector,
    title: String,
    description: String? = null,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    buttonTestTag: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = BANNER_TINT_ALPHA))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    // The same size as the text next to it (the default 24 dp reads too
                    // large and pushes the row's text off the button's text level).
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // A button without a handler would be a dead control: render it only when
            // both are given.
            if (buttonText != null && onButtonClick != null) {
                TextButton(
                    onClick = onButtonClick,
                    modifier = buttonTestTag?.let { Modifier.testTag(it) } ?: Modifier,
                ) {
                    Text(buttonText)
                }
            }
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
