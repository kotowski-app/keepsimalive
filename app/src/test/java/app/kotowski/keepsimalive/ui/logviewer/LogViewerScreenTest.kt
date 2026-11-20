package app.kotowski.keepsimalive.ui.logviewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.LogFileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class LogViewerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        LogBuffer.clear()
    }

    @After
    fun tearDown() {
        LogBuffer.resetForTests()
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

    // The Copy action is an icon-only button (the content_copy icon, no text label), so
    // it is addressed by its test tag.
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
    fun copyCopiesAllEntriesInNewestFirstOrder() {
        LogBuffer.addInfo("Test", "msg1")
        LogBuffer.addWarn("Test", "msg2")
        render()
        composeTestRule.onNodeWithTag("log_viewer_copy").performClick()
        val expected = LogBuffer.entries.asReversed().joinToString("\n") { it.toString() }
        assertEquals(expected, clipboardText())
    }

    @Test
    fun newestEntryRendersFirst() {
        LogBuffer.addInfo("Test", "older entry")
        LogBuffer.addInfo("Test", "newer entry")
        render()
        val olderTop = composeTestRule.onNodeWithText("older entry", substring = true).getUnclippedBoundsInRoot().top
        val newerTop = composeTestRule.onNodeWithText("newer entry", substring = true).getUnclippedBoundsInRoot().top
        assertTrue(newerTop < olderTop)
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
        // Newlines are escaped to the literal "\n", so the entry stays a single line.
        assertTrue(entry.contains("\\n"))
        assertTrue(!entry.contains("\n"))
        composeTestRule.onNodeWithText(entry).performClick()
        assertEquals(entry, clipboardText())
    }

    @Test
    fun clearButtonIsDisabledWhileTheBufferIsEmpty() {
        render()
        composeTestRule.onNodeWithTag("log_viewer_clear").assertIsNotEnabled()
    }

    // The direct executor makes the file effects synchronous (production uses a daemon thread).
    @Test
    fun `clear waits for the confirm dialog, and confirming clears the buffer and the file`() {
        val file = File.createTempFile("logviewer-clear", ".log").apply { delete() }
        LogBuffer.init(LogFileStore(file), Executor { it.run() })
        LogBuffer.addInfo("Test", "to be cleared")
        render()
        composeTestRule.onNodeWithTag("log_viewer_clear").performClick()
        composeTestRule.onNodeWithText("Clear logs?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete all app logs? This cannot be undone.").assertIsDisplayed()
        assertTrue(LogBuffer.entries.isNotEmpty())
        composeTestRule
            .onAllNodesWithText("Cancel", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.onNodeWithText("Clear logs?").assertDoesNotExist()
        assertTrue(LogBuffer.entries.isNotEmpty())
        assertTrue(file.exists())
        composeTestRule.onNodeWithTag("log_viewer_clear").performClick()
        composeTestRule.onNodeWithTag("log_viewer_clear_confirm").performClick()
        assertTrue(LogBuffer.entries.isEmpty())
        assertTrue("the clear must delete the log file", !file.exists())
        composeTestRule.onNodeWithText("No logs captured yet").assertIsDisplayed()
    }
}
