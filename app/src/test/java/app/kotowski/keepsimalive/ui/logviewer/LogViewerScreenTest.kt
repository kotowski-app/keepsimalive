package app.kotowski.keepsimalive.ui.logviewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class LogViewerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        LogBuffer.clear()
    }

    private fun render() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                LogViewerScreen(onNavigateBack = {})
            }
        }
    }

    private fun clipboardText(): CharSequence? {
        val rule = composeTestRule as AndroidComposeTestRule<*, *>
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text
    }

    // The Copy action is an icon-only button (the content_copy icon, no text label), so it
    // is addressed by its test tag. The top bar has no Clear: the log buffer is never
    // cleared from the UI.
    @Test
    fun copyDisabledWhenBufferEmpty() {
        render()
        composeTestRule.onNodeWithText("No logs captured yet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("log_viewer_copy").assertIsNotEnabled()
    }

    @Test
    fun copyDisabledWhenFilterMatchesNothing() {
        LogBuffer.addInfo("Test", "hello world")
        render()
        composeTestRule.onNodeWithText("Search").performTextInput("zzz")
        composeTestRule.onNodeWithText("No logs match the search query.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("log_viewer_copy").assertIsNotEnabled()
    }

    @Test
    fun copyCopiesAllEntries() {
        LogBuffer.addInfo("Test", "msg1")
        LogBuffer.addWarn("Test", "msg2")
        render()
        composeTestRule.onNodeWithTag("log_viewer_copy").performClick()
        val expected = LogBuffer.entries.joinToString("\n") { it.toString() }
        assertEquals(expected, clipboardText())
    }

    @Test
    fun copyCopiesOnlyMatchingEntries() {
        LogBuffer.addInfo("Test", "alpha one")
        LogBuffer.addInfo("Test", "beta two")
        render()
        composeTestRule.onNodeWithText("Search").performTextInput("alpha")
        composeTestRule.onNodeWithTag("log_viewer_copy").performClick()
        val copied = clipboardText()
        assertTrue(copied.toString().contains("alpha one"))
        assertTrue(!copied.toString().contains("beta two"))
    }

    @Test
    fun rowTapCopiesWholeEntry() {
        LogBuffer.addInfo("Test", "single line")
        render()
        val entry = LogBuffer.entries[0].toString()
        composeTestRule.onNodeWithText(entry).performClick()
        assertEquals(entry, clipboardText())
    }

    @Test
    fun multilineEntryRendersAsOneRow() {
        LogBuffer.addInfo("Test", "line one\nline two")
        render()
        val entry = LogBuffer.entries[0].toString()
        composeTestRule.onNodeWithText(entry).assertIsDisplayed()
        composeTestRule.onNodeWithText("line two").assertDoesNotExist()
    }

    @Test
    fun multilineEntryTapCopiesWholeEntry() {
        LogBuffer.addInfo("Test", "line one\nline two")
        render()
        val entry = LogBuffer.entries[0].toString()
        assertTrue(entry.contains("\n"))
        composeTestRule.onNodeWithText(entry).performClick()
        assertEquals(entry, clipboardText())
    }
}
