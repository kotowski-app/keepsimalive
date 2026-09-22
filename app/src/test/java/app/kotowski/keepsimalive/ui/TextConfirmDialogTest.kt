package app.kotowski.keepsimalive.ui

import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TextConfirmDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders title, detail, cancel and confirm`() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Delete this SIM?",
                    detail = "The detail",
                    confirmLabel = "Remove",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Delete this SIM?").assertIsDisplayed()
        composeTestRule.onNodeWithText("The detail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun `renders the title alone when detail is null`() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Wipe history?",
                    detail = null,
                    confirmLabel = "Clear",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Wipe history?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
    }

    @Test
    fun `confirm invokes onConfirm`() {
        var confirmed = false
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Delete this SIM?",
                    detail = null,
                    confirmLabel = "Remove",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Remove").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `the default cancel button invokes onDismiss`() {
        var dismissed = false
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Delete this SIM?",
                    detail = null,
                    confirmLabel = "Remove",
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `a custom cancel label replaces the default`() {
        var dismissed = false
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Turn off auto reset",
                    detail = null,
                    confirmLabel = "Open settings",
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                    cancelLabel = "Not now",
                )
            }
        }

        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        assertTrue(dismissed)
    }

    @Test
    fun `showCancel false renders the confirm button alone`() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Cleaned up",
                    detail = "3 sends deleted",
                    confirmLabel = "OK",
                    onConfirm = {},
                    onDismiss = {},
                    showCancel = false,
                )
            }
        }

        composeTestRule.onNodeWithText("Cleaned up").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `a back press invokes onDismiss`() {
        var dismissed = false
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Delete this SIM?",
                    detail = null,
                    confirmLabel = "Remove",
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        // The dialog window owns its back handling (Compose dialogs are ComponentDialogs
        // with their own back dispatcher), so route the press through the shown dialog.
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        dialog.onBackPressedDispatcher.onBackPressed()

        assertTrue(dismissed)
    }

    @Test
    fun `confirmTestTag tags the confirm button`() {
        var confirmed = false
        composeTestRule.setContent {
            KeepSimAliveTheme {
                TextConfirmDialog(
                    title = "Delete this SIM?",
                    detail = null,
                    confirmLabel = "Remove",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                    confirmTestTag = "confirm_delete",
                )
            }
        }

        composeTestRule.onNodeWithTag("confirm_delete").performClick()
        assertTrue(confirmed)
    }
}
