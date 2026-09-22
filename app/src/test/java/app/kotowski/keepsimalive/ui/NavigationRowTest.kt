package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        leadingIcon: ImageVector? = null,
        onClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    NavigationRow(
                        label = "History (3)",
                        onClick = onClick,
                        leadingIcon = leadingIcon,
                        testTag = "nav_row",
                    )
                }
            }
        }
    }

    @Test
    fun labelIsShownAndTagApplied() {
        render()
        composeTestRule.onNodeWithText("History (3)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_row").assertIsDisplayed()
    }

    @Test
    fun rowClickInvokesOnClickOnce() {
        var clicks = 0
        render(onClick = { clicks++ })
        composeTestRule.onNodeWithTag("nav_row").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun worksWithALeadingIcon() {
        var clicks = 0
        render(
            leadingIcon = Icons.Filled.ChevronRight,
            onClick = { clicks++ },
        )
        composeTestRule.onNodeWithText("History (3)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_row").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun leadingIconPushesTheLabelRight() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    Column {
                        NavigationRow(
                            label = "Plain row",
                            onClick = {},
                            testTag = "nav_plain",
                        )
                        NavigationRow(
                            label = "Icon row",
                            onClick = {},
                            leadingIcon = Icons.Filled.ChevronRight,
                            testTag = "nav_icon",
                        )
                    }
                }
            }
        }
        val plain = composeTestRule.onNode(hasText("Plain row"), true).getBoundsInRoot()
        val withIcon = composeTestRule.onNode(hasText("Icon row"), true).getBoundsInRoot()

        // The label sits after the 24dp icon plus the 8dp gap, so it must start further
        // right than the row's 16dp padding alone.
        assertTrue("label with a leading icon must start further right", withIcon.left > plain.left)
    }
}
