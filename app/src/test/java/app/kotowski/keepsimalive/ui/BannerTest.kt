package app.kotowski.keepsimalive.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
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

// The shared notice band (icon + title, an optional action button on the row, an optional
// description under it): the History auto-clear banner and the SIM details' missing-SIM
// and schedule-ended banners all render through it. Screen-level journeys (visibility
// conditions, actions) live in the screens' own tests.
@RunWith(RobolectricTestRunner::class)
class BannerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        icon: ImageVector = Icons.Default.Warning,
        title: String,
        description: String? = null,
        buttonText: String? = null,
        onButtonClick: (() -> Unit)? = null,
        buttonTestTag: String? = null,
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Banner(
                    icon = icon,
                    title = title,
                    description = description,
                    buttonText = buttonText,
                    onButtonClick = onButtonClick,
                    buttonTestTag = buttonTestTag,
                )
            }
        }
    }

    @Test
    fun showsTheTitleAndTheDescription() {
        render(title = "SIM disabled or unplugged", description = "The details shown are the last known ones, not live data.")
        composeTestRule.onNodeWithText("SIM disabled or unplugged").assertIsDisplayed()
        composeTestRule.onNodeWithText("The details shown are the last known ones, not live data.").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutADescription() {
        render(title = "Schedule ended.")
        composeTestRule.onNodeWithText("Schedule ended.").assertIsDisplayed()
    }

    @Test
    fun showsTheActionButtonAndInvokesTheCallback() {
        var clicks = 0
        render(title = "Auto-clear is on.", buttonText = "Run cleanup now", onButtonClick = { clicks++ }, buttonTestTag = "run_cleanup_now")
        composeTestRule.onNodeWithText("Run cleanup now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run cleanup now").performClick()
        assertTrue(clicks == 1)
    }

    @Test
    fun theActionButtonCarriesTheTestTag() {
        render(title = "Auto-clear is on.", buttonText = "Run cleanup now", onButtonClick = {}, buttonTestTag = "run_cleanup_now")
        composeTestRule.onNodeWithTag("run_cleanup_now").assertIsDisplayed()
    }

    @Test
    fun showsNoButtonWithoutButtonText() {
        render(title = "Schedule ended.", description = "The reason goes here.")
        composeTestRule.onNodeWithText("Run cleanup now").assertDoesNotExist()
        composeTestRule.onNodeWithText("Forget").assertDoesNotExist()
    }
}
