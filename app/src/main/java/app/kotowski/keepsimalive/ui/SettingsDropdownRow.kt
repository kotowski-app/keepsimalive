package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

// The shared read-only dropdown row (a pick from a fixed list): the ExposedDropdownMenuBox
// wiring (the read-only field with the chevron, the menu, the close-on-pick) lives here
// once. The caller owns the expanded state: the rows must reset it externally too (the
// SIM editor closes the end-condition menu when the schedule is turned off).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdownRow(
    value: String,
    label: String,
    // The menu options: a label with the pick action.
    items: List<Pair<String, () -> Unit>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    // The field's tag (the screen tests click through it); null leaves it untagged.
    testTag: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = leadingIcon,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors =
                if (isError) {
                    ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                        focusedTextColor = MaterialTheme.colorScheme.error,
                        cursorColor = MaterialTheme.colorScheme.error,
                        focusedBorderColor = MaterialTheme.colorScheme.error,
                        focusedLabelColor = MaterialTheme.colorScheme.error,
                        focusedTrailingIconColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                },
            isError = isError,
            supportingText =
                if (supportingText != null) {
                    { Text(text = supportingText, color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            items.forEach { (itemLabel, onSelect) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    // The pick action runs first, then the menu closes, so a state reset
                    // inside the action is not clobbered by the close.
                    onClick = {
                        onSelect()
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}
