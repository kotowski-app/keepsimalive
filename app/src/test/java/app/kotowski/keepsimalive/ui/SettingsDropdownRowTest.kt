package app.kotowski.keepsimalive.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The shared read-only dropdown row (the Settings' language / theme / grace / retention
// rows and the SIM details' end-condition / debug pickers): this covers its wiring — the
// pick, the close-on-pick, the tag, the optional icon, the invalid-state look.
@RunWith(RobolectricTestRunner::class)
class SettingsDropdownRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val expandedEvents = mutableListOf<Boolean>()
    private val picks = mutableListOf<String>()

    private fun render(
        value: String = "Picked",
        label: String = "Row label",
        items: List<Pair<String, () -> Unit>> =
            listOf(
                "First" to { picks.add("First") },
                "Second" to { picks.add("Second") },
            ),
        expanded: Boolean = false,
        testTag: String? = "row_tag",
        leadingIcon: (@Composable () -> Unit)? = null,
        isError: Boolean = false,
        supportingText: String? = null,
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                // The row's expanded state lives here the way the screens hold theirs.
                var open by remember { mutableStateOf(expanded) }
                SettingsDropdownRow(
                    value = value,
                    label = label,
                    items = items,
                    expanded = open,
                    onExpandedChange = {
                        open = it
                        expandedEvents.add(it)
                    },
                    testTag = testTag,
                    leadingIcon = leadingIcon,
                    isError = isError,
                    supportingText = supportingText,
                )
            }
        }
    }

    @Test
    fun showsTheValueAndLabel() {
        render()
        composeTestRule.onNodeWithText("Picked").assertIsDisplayed()
        composeTestRule.onNodeWithText("Row label").assertIsDisplayed()
    }

    @Test
    fun theFieldCarriesTheTestTag() {
        render()
        composeTestRule.onNodeWithTag("row_tag").assertIsDisplayed()
    }

    @Test
    fun anUntaggedRowHasNoTag() {
        render(testTag = null)
        composeTestRule.onNodeWithTag("row_tag").assertDoesNotExist()
    }

    @Test
    fun tappingTheFieldOpensTheMenu() {
        render()
        composeTestRule.onNodeWithTag("row_tag").performClick()
        composeTestRule.onNodeWithText("First").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second").assertIsDisplayed()
        assertEquals(listOf(true), expandedEvents)
    }

    @Test
    fun pickingInvokesTheActionOnceAndClosesTheMenu() {
        render()
        composeTestRule.onNodeWithTag("row_tag").performClick()
        composeTestRule.onNodeWithText("Second").performClick()
        assertEquals(listOf("Second"), picks)
        composeTestRule.onNodeWithText("First").assertDoesNotExist()
        composeTestRule.onNodeWithText("Second").assertDoesNotExist()
        assertEquals(listOf(true, false), expandedEvents)
    }

    @Test
    fun aLeadingIconIsShownWhileProvided() {
        render(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.testTag("icon_tag"),
                )
            },
        )
        // The icon's tag fuses with the field's tag in the merged tree, so address it in
        // the unmerged tree and check it occupies space.
        val iconNodes =
            composeTestRule.onAllNodes(hasTestTag("icon_tag"), useUnmergedTree = true)
        assertEquals(1, iconNodes.fetchSemanticsNodes().size)
        val iconBounds = iconNodes.onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue(iconBounds.width > 0)
        assertTrue(iconBounds.height > 0)
    }

    @Test
    fun aRowWithoutLeadingIconHasNoIcon() {
        render()
        val iconNodes =
            composeTestRule.onAllNodes(hasTestTag("icon_tag"), useUnmergedTree = true)
        assertTrue(iconNodes.fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theErrorLookShowsTheSupportingText() {
        render(isError = true, supportingText = "Pick a date first")
        composeTestRule.onNodeWithText("Pick a date first").assertIsDisplayed()
    }

    @Test
    fun aValidRowHasNoSupportingText() {
        render()
        composeTestRule.onNodeWithText("Pick a date first").assertDoesNotExist()
    }
}
