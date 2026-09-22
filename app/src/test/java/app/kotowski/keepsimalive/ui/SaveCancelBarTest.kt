package app.kotowski.keepsimalive.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The editor's bottom bar: Save is the bar's one primary and Cancel the text button —
// the same "primary + cancel" idiom as the confirm dialogs. Save stays clickable while
// "disabled" so the tap can still explain what is missing (the explanation lives in
// onSave).
@RunWith(RobolectricTestRunner::class)
class SaveCancelBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        saveEnabled: Boolean = true,
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SaveCancelBar(
                    onCancel = onCancel,
                    onSave = onSave,
                    saveEnabled = saveEnabled,
                )
            }
        }
    }

    @Test
    fun bothButtonsAreShown() {
        render()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun cancelInvokesOnCancelOnce() {
        var clicks = 0
        render(onCancel = { clicks++ })
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun saveInvokesOnSaveWhileEnabled() {
        var clicks = 0
        render(saveEnabled = true, onSave = { clicks++ })
        composeTestRule.onNodeWithText("Save").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun saveStaysClickableWhileNotYetEnabled() {
        // The tap explains what is missing (the explanation lives in onSave), so the
        // button must still deliver the tap while it shows the "disabled" look.
        var clicks = 0
        render(saveEnabled = false, onSave = { clicks++ })
        composeTestRule.onNodeWithText("Save").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun bothButtonsShareTheBarEqually() {
        render()
        val cancel = composeTestRule.onNodeWithText("Cancel").fetchSemanticsNode()
        val save = composeTestRule.onNodeWithText("Save").fetchSemanticsNode()
        assertEquals(cancel.size.width, save.size.width)
        assertTrue(cancel.size.width > 0)
    }
}
