package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailRowLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun valueColumnIsWiderThanLabelColumn() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    DetailRow(label = "Operator", value = "Vodafone (roaming on AIS)")
                }
            }
        }
        val label = composeTestRule.onNodeWithText("Operator")
        val value = composeTestRule.onNodeWithText("Vodafone (roaming on AIS)")
        label.assertIsDisplayed()
        value.assertIsDisplayed()
        val labelBounds = label.getBoundsInRoot()
        val valueBounds = value.getBoundsInRoot()
        val labelWidth = labelBounds.right - labelBounds.left
        val valueWidth = valueBounds.right - valueBounds.left
        assertTrue("value should take more space than the label", valueWidth > labelWidth)
    }

    @Test
    fun roamingValueFitsOnOneLine() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    DetailRow(label = "Operator", value = "Vodafone (roaming on AIS)")
                    Text("x", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val value = composeTestRule.onNodeWithText("Vodafone (roaming on AIS)")
        val singleLineReference = composeTestRule.onNodeWithText("x")
        val valueBounds = value.getBoundsInRoot()
        val referenceBounds = singleLineReference.getBoundsInRoot()
        assertEquals(
            "roaming value must stay on one line",
            referenceBounds.bottom - referenceBounds.top,
            valueBounds.bottom - valueBounds.top,
        )
    }
}
