package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.R

// The Save/Cancel panel: a real bottom bar. The app is edge-to-edge and the Scaffold
// places the bar at the very bottom of the layout, so the bar must clear the navigation
// bars (and the IME) itself: the background stays full-bleed while the buttons sit above
// them. union = max per edge, so with the keyboard open the IME inset wins, otherwise
// the nav bar inset does. The Save button stays clickable while disabled so the tap can
// still explain what is missing (the explanation lives in onSave).
@Composable
fun SaveCancelBar(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onSave,
                colors =
                    if (saveEnabled) {
                        ButtonDefaults.buttonColors()
                    } else {
                        // The M3 "disabled" look (container onSurface 12%, content onSurface
                        // 38%) while the button stays clickable so the tap still explains
                        // what is missing.
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
