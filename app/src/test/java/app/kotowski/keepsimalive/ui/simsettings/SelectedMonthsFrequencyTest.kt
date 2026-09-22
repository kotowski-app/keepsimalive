package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectedMonthsFrequencyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private var published: SimKeepaliveConfig? = null

    private val monthNames =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )

    private fun render(initial: SimKeepaliveConfig?) {
        published = null
        composeTestRule.setContent {
            KeepSimAliveTheme {
                KeepaliveConfigEditor(
                    context = context,
                    simId = 1,
                    initial = initial,
                    onConfigChanged = { published = it },
                    onValidationChanged = { _, _ -> },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun renderValid() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "test",
            ),
        )
    }

    private fun selectSelectedMonths() {
        composeTestRule.onNodeWithText("Selected months").performClick()
    }

    @Test
    fun `the frequency row has months, days and selected months segments`() {
        renderValid()
        composeTestRule.onNodeWithText("Months").assertIsSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        composeTestRule.onNodeWithText("Selected months").assertIsNotSelected()
        composeTestRule.onNodeWithText("January").assertDoesNotExist()
    }

    @Test
    fun `selecting selected months shows all 12 month chips in order`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("Selected months").assertIsSelected()
        composeTestRule.onNodeWithText("Months").assertIsNotSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        // assertExists, not IsDisplayed: the last chips can sit past the small test
        // viewport.
        monthNames.forEach { name -> composeTestRule.onNodeWithText(name).assertExists() }
    }

    @Test
    fun `selecting selected months shows the month grid and the shared on day row`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("Every").assertDoesNotExist()
        // The "On day" row is shared by both month cadences (the month grid replaces the
        // monthly interval row, not the send day).
        composeTestRule.onNodeWithText("On day").assertExists()
        composeTestRule.onNodeWithText("Selected months is not available yet.").assertDoesNotExist()
    }

    @Test
    fun `day 30 composes the short month hint for selected months`() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "test",
                dayOfMonth = 30,
            ),
        )
        selectSelectedMonths()
        composeTestRule
            .onNodeWithText("If day 30 doesn't exist in a month, the message sends on the last day.")
            .assertExists()
    }

    @Test
    fun `the pick at least one month hint shows while no month is picked and disappears with the first pick`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("Select at least one month.").assertExists()
        composeTestRule.onNodeWithText("January").performClick()
        composeTestRule.onNodeWithText("Select at least one month.").assertDoesNotExist()
    }

    @Test
    fun `selected months with no picked month keeps the published config null`() {
        // An empty month set is unschedulable: the draft must stay out of the engine until
        // the user picks a month.
        renderValid()
        selectSelectedMonths()
        assertNull(published)
    }

    @Test
    fun `selected months with a picked month produces a saved config`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("January").performClick()
        composeTestRule.onNodeWithText("February").performClick()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.SELECTED_MONTHS, pending?.freqType)
        assertEquals(setOf(1, 2), pending?.selectedMonths)
    }

    @Test
    fun `month chips toggle their selection on and off`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("January").assertIsNotSelected()
        composeTestRule.onNodeWithText("January").performClick()
        composeTestRule.onNodeWithText("January").assertIsSelected()
        composeTestRule.onNodeWithText("February").performClick()
        composeTestRule.onNodeWithText("February").assertIsSelected()
        composeTestRule.onNodeWithText("March").performClick()
        composeTestRule.onNodeWithText("March").performClick()
        composeTestRule.onNodeWithText("March").assertIsNotSelected()
        composeTestRule.onNodeWithText("January").assertIsSelected()
        composeTestRule.onNodeWithText("February").assertIsSelected()
    }

    @Test
    fun `switching back to months hides the picker and restores the published config`() {
        renderValid()
        selectSelectedMonths()
        composeTestRule.onNodeWithText("January").performClick()
        assertEquals(FrequencyType.SELECTED_MONTHS, published?.freqType)
        composeTestRule.onNodeWithText("Months").performClick()
        composeTestRule.onNodeWithText("Months").assertIsSelected()
        composeTestRule.onNodeWithText("January").assertDoesNotExist()
        composeTestRule.onNodeWithText("Select at least one month.").assertDoesNotExist()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.MONTHLY, pending?.freqType)
    }

    @Test
    fun `tapping months from days restores the interval fields`() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "test",
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 14,
            ),
        )
        composeTestRule.onNodeWithText("Days").assertIsSelected()
        composeTestRule.onNodeWithText("Months").performClick()
        composeTestRule.onNodeWithText("Months").assertIsSelected()
        composeTestRule.onNodeWithText("Selected months").assertIsNotSelected()
        composeTestRule.onNodeWithText("January").assertDoesNotExist()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.MONTHLY, pending?.freqType)
    }

    @Test
    fun `month chips sit in equal rows of the same size`() {
        // The test viewport leaves 288dp for the grid, which monthGridColumns maps to 2
        // columns: January/February share the first row, March/April the second, and the
        // chips weight-fill their slots so all of them are the same width (a pixel of
        // rounding is allowed in the split).
        renderValid()
        selectSelectedMonths()
        val january = composeTestRule.onNodeWithText("January").getBoundsInRoot()
        val february = composeTestRule.onNodeWithText("February").getBoundsInRoot()
        val march = composeTestRule.onNodeWithText("March").getBoundsInRoot()
        val april = composeTestRule.onNodeWithText("April").getBoundsInRoot()
        assertEquals(january.top, february.top)
        assertEquals(march.top, april.top)
        assertTrue(march.top > january.top)
        assertTrue(february.left > january.left)
        val januaryWidth = january.right - january.left
        val februaryWidth = february.right - february.left
        val marchWidth = march.right - march.left
        assertTrue(kotlin.math.abs(januaryWidth.value - februaryWidth.value) <= 1)
        assertTrue(kotlin.math.abs(januaryWidth.value - marchWidth.value) <= 1)
    }
}
