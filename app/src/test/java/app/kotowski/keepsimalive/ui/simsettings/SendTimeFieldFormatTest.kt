package app.kotowski.keepsimalive.ui.simsettings

import android.text.format.DateFormat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.DateUtil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The editor's send-time field and the time picker dial follow the system 12/24-hour
// setting (DateUtil.formatTime + DateFormat.is24HourFormat) instead of a hardcoded
// 24-hour layout. Robolectric's default locale is en-US 12-hour, so the picker renders
// its AM/PM period toggle and the 01..12 hour circle here.
@RunWith(RobolectricTestRunner::class)
class SendTimeFieldFormatTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun renderEditor(
        hour: Int,
        minute: Int,
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                KeepaliveConfigEditor(
                    context = context,
                    simId = 1,
                    initial =
                        SimKeepaliveConfig(
                            simId = 1,
                            enabled = true,
                            recipientPhone = "+15550100",
                            message = "test",
                            hour = hour,
                            minute = minute,
                        ),
                    onConfigChanged = {},
                    onValidationChanged = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun sendTimeFieldShowsTheLocaleFormattedTime() {
        renderEditor(hour = 14, minute = 30)
        val expected = DateUtil.formatTime(context, 14, 30)
        composeTestRule.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        // The old hardcoded 24-h zero-padded layout must not come back (the system here
        // uses the 12-h format, where the field reads "2:30 PM", not "14:30").
        if (!DateFormat.is24HourFormat(context)) {
            composeTestRule.onNodeWithText("14:30", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun timePickerDialFollowsTheSystem12HourSetting() {
        renderEditor(hour = 14, minute = 30)
        composeTestRule
            .onNodeWithText(DateUtil.formatTime(context, 14, 30), useUnmergedTree = true)
            .performClick()
        // The period toggle is composed only in 12-hour mode...
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("AM", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("AM", useUnmergedTree = true).assertExists()
        // ...and the hour circle holds the 12-h numbers (01..12), never the 24-h ones.
        composeTestRule.onNodeWithText("13", useUnmergedTree = true).assertDoesNotExist()
    }
}
