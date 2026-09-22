package app.kotowski.keepsimalive.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The history screen's top bar: the title carries the exact terminal count from the DB
// (the loaded list is capped, so its in-memory size is not the total), and the Clear
// action exists only while there is something to clear.
@RunWith(RobolectricTestRunner::class)
class HistoryTopBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        simDisplayName: String = "",
        historyCount: Int,
        hasRows: Boolean,
        onClearClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                HistoryTopBar(
                    simDisplayName = simDisplayName,
                    historyCount = historyCount,
                    hasRows = hasRows,
                    onNavigateBack = {},
                    onClearClick = onClearClick,
                )
            }
        }
    }

    @Test
    fun titleShowsTheSimNameAndTheExactCount() {
        render(simDisplayName = "Vodafone", historyCount = 1000, hasRows = true)
        composeTestRule
            .onNodeWithText("Vodafone — History (1000)", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    // While the name has not resolved yet (the first frames after entry) the plain count
    // title shows: the title is never blank or half-formatted.
    @Test
    fun titleWithoutTheResolvedNameShowsTheCountOnly() {
        render(simDisplayName = "", historyCount = 1000, hasRows = true)
        composeTestRule.onNodeWithText("History (1000)", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun clearActionShownWhileThereAreRows() {
        render(historyCount = 3, hasRows = true)
        composeTestRule.onNodeWithContentDescription("Clear").assertIsDisplayed()
    }

    @Test
    fun clearActionHiddenWithoutRows() {
        render(historyCount = 0, hasRows = false)
        composeTestRule.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }

    @Test
    fun clearActionInvokesTheCallback() {
        var clicked = false
        render(historyCount = 1, hasRows = true, onClearClick = { clicked = true })
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        assertTrue(clicked)
    }

    @Test
    fun backActionIsPresent() {
        render(historyCount = 0, hasRows = false)
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }
}
