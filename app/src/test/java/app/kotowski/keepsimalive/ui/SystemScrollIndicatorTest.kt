package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SystemScrollIndicatorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .testTag("scroll_host"),
                    ) {
                        repeat(100) {
                            Text("item $it", modifier = Modifier.height(50.dp))
                        }
                    }
                    SystemScrollStateScrollIndicator(
                        scrollState = scrollState,
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .testTag("scroll_indicator"),
                    )
                }
            }
        }
    }

    @Test
    fun `the indicator is hidden while idle`() {
        render()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scroll_indicator").assertDoesNotExist()
    }

    @Test
    fun `the indicator fades in while scrolling and out after the scroll stops`() {
        render()
        composeTestRule.waitForIdle()
        // A held drag: the pointer stays down (no up), so the scroll is in progress while
        // the frames below run the fade-in.
        composeTestRule.onNodeWithTag("scroll_host").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - 100f), delayMillis = 50)
            moveBy(Offset(0f, -100f), delayMillis = 50)
            moveBy(Offset(0f, -100f), delayMillis = 50)
        }
        composeTestRule.mainClock.advanceTimeBy(16)
        composeTestRule.onNodeWithTag("scroll_indicator").assertExists()
        // The lift ends the scroll, starting the fade-out.
        composeTestRule.onNodeWithTag("scroll_host").performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.onNodeWithTag("scroll_indicator").assertDoesNotExist()
    }

    @Test
    fun `the indicator is hidden while the content is not scrollable`() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .testTag("scroll_host"),
                    ) {
                        Text("single item")
                    }
                    SystemScrollStateScrollIndicator(
                        scrollState = scrollState,
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .testTag("scroll_indicator"),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scroll_indicator").assertDoesNotExist()
    }
}
