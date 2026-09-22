package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
class SystemLazyColumnScrollIndicatorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(itemCount: Int) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val listState = rememberLazyListState()
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag("scroll_host"),
                    ) {
                        items(itemCount) {
                            Text("item $it", modifier = Modifier.height(50.dp))
                        }
                    }
                    SystemLazyColumnScrollIndicator(
                        listState = listState,
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
        render(100)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scroll_indicator").assertDoesNotExist()
    }

    @Test
    fun `the indicator fades in while scrolling and out after the scroll stops`() {
        render(100)
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
        render(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scroll_indicator").assertDoesNotExist()
    }
}
