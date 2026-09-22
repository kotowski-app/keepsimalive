package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FrequencySectionTest {
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

    @Test
    fun `new schedule defaults to selected months with every month picked`() {
        render(null)
        composeTestRule.onNodeWithText("Selected months").assertIsSelected()
        composeTestRule.onNodeWithText("Months").assertIsNotSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        // Every month starts picked (the new rhythm sends on all of them until the user
        // narrows the set down), so no pick hint is needed.
        monthNames.forEach { name -> composeTestRule.onNodeWithText(name).assertIsSelected() }
        composeTestRule.onNodeWithText("Select at least one month.").assertDoesNotExist()
    }

    @Test
    fun `editing a default config keeps the stored monthly rhythm`() {
        render(SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100", message = "test"))
        composeTestRule.onNodeWithText("Months").assertIsSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        composeTestRule.onNodeWithText("Every").assertIsDisplayed()
        composeTestRule.onNodeWithText("On day").assertIsDisplayed()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.MONTHLY, pending?.freqType)
        assertEquals(1, pending?.monthsInterval)
        assertEquals(1, pending?.dayOfMonth)
    }

    @Test
    fun `null initial produces null pending due to invalid empty fields`() {
        render(null)
        composeTestRule.onNodeWithText("Selected months").assertExists()
        // Validation always runs (not skipped when disabled), so empty recipient
        // makes the pending config null.
        assertEquals(null, published)
    }

    @Test
    fun `selected months leads the frequency row`() {
        render(null)
        val selected = composeTestRule.onNodeWithText("Selected months")
        val months = composeTestRule.onNodeWithText("Months")
        val days = composeTestRule.onNodeWithText("Days")
        assertTrue(
            "Selected months must be the first segment",
            selected.getBoundsInRoot().left < months.getBoundsInRoot().left,
        )
        assertTrue(
            "Months must sit before Days",
            months.getBoundsInRoot().left < days.getBoundsInRoot().left,
        )
    }

    @Test
    fun `editing an existing days schedule populates values`() {
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
        composeTestRule.onNodeWithText("Months").assertIsNotSelected()
        composeTestRule.onNodeWithText("14").assertIsDisplayed()
        composeTestRule.onNodeWithText("On day").assertDoesNotExist()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.EVERY_N_DAYS, pending?.freqType)
        assertEquals(14, pending?.daysInterval)
    }

    @Test
    fun `editing an existing monthly schedule populates values`() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "test",
                freqType = FrequencyType.MONTHLY,
                monthsInterval = 2,
                dayOfMonth = 15,
            ),
        )
        composeTestRule.onNodeWithText("Months").assertIsSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText("15").assertIsDisplayed()
        val pending = published
        assertNotNull(pending)
        assertEquals(FrequencyType.MONTHLY, pending?.freqType)
        assertEquals(2, pending?.monthsInterval)
        assertEquals(15, pending?.dayOfMonth)
    }

    @Test
    fun `day 29 composes the short month hint`() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 29,
            ),
        )
        composeTestRule
            .onNodeWithText("If day 29 doesn't exist in a month, the message sends on the last day.")
            .assertExists()
    }

    @Test
    fun `day 28 does not compose the short month hint`() {
        render(
            SimKeepaliveConfig(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 28,
            ),
        )
        composeTestRule
            .onNodeWithText("If day 28 doesn't exist in a month, the message sends on the last day.")
            .assertDoesNotExist()
    }
}
