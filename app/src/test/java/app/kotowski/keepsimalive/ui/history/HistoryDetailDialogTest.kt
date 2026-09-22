package app.kotowski.keepsimalive.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDetailDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(item: SendHistoryEntity) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                HistoryDetailDialog(item = item, onDismiss = {})
            }
        }
    }

    private fun item(
        outcome: SendOutcome,
        lastAttemptAtMillis: Long? = null,
        failureReason: String? = null,
        retryCount: Int = 0,
    ) = SendHistoryEntity(
        simId = 1,
        scheduledForMillis = 1234567890L,
        occurrenceBaseMillis = 1234567890L,
        lastAttemptAtMillis = lastAttemptAtMillis,
        outcome = outcome.name,
        failureReason = failureReason,
        recipient = "+15550100",
        message = "m",
        firstAttemptAtMillis = lastAttemptAtMillis,
        retryCount = retryCount,
    )

    @Test
    fun noRetriesRowWhenRetryCountIsZero() {
        render(item(SendOutcome.SENT, lastAttemptAtMillis = 1234567890L))
        composeTestRule.onNodeWithText("Retries").assertDoesNotExist()
    }

    @Test
    fun retriesRowShownWhenRetryCountIsPositive() {
        render(item(SendOutcome.FAILED, lastAttemptAtMillis = 1234567890L, failureReason = "No service", retryCount = 2))
        composeTestRule.onNodeWithText("Retries").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun longMessageIsShownWithoutTruncation() {
        val message = "line one\nline two\nline three\nline four\nline five\nline six"
        render(item(SendOutcome.SENT, lastAttemptAtMillis = 1234567890L).copy(message = message))
        composeTestRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun unknownOutcomeShowsBlankStatus() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                HistoryDetailDialog(
                    item =
                        SendHistoryEntity(
                            simId = 1,
                            scheduledForMillis = 1234567890L,
                            occurrenceBaseMillis = 1234567890L,
                            outcome = "UNKNOWN",
                            recipient = "+15550100",
                            message = "m",
                        ),
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("\u2014").assertDoesNotExist()
        composeTestRule.onNodeWithText("").assertIsDisplayed()
    }

    @Test
    fun dialogTitleIsMessageDetails() {
        render(item(SendOutcome.SENT, lastAttemptAtMillis = 1234567890L))
        composeTestRule.onNodeWithText("Message details").assertIsDisplayed()
    }

    @Test
    fun failedItemShowsTriedAtAndReasonWithoutSentAt() {
        render(item(SendOutcome.FAILED, lastAttemptAtMillis = 1234567890L, failureReason = "No service", retryCount = 1))
        composeTestRule.onNodeWithText("Tried at").assertIsDisplayed()
        composeTestRule.onNodeWithText("No service").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
    }

    @Test
    fun sentItemShowsSentAtWithoutReason() {
        render(item(SendOutcome.SENT, lastAttemptAtMillis = 1234567890L))
        composeTestRule.onNodeWithText("Sent at").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tried at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reason").assertDoesNotExist()
    }

    @Test
    fun retryRowShowsTriedAtAndReason() {
        render(item(SendOutcome.PENDING, lastAttemptAtMillis = 1234567890L, failureReason = "No service", retryCount = 1))
        composeTestRule.onNodeWithText("Tried at").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
        composeTestRule.onNodeWithText("No service").assertIsDisplayed()
    }

    @Test
    fun sendingItemShowsTriedAtWithoutReason() {
        render(item(SendOutcome.SENDING, lastAttemptAtMillis = 1234567890L))
        composeTestRule.onNodeWithText("Tried at").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reason").assertDoesNotExist()
    }

    @Test
    fun pendingItemShowsNoAttemptRow() {
        render(item(SendOutcome.PENDING))
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tried at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reason").assertDoesNotExist()
    }

    @Test
    fun skippedItemShowsNoAttemptRowAndNoReason() {
        render(item(SendOutcome.SKIPPED))
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tried at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reason").assertDoesNotExist()
    }

    @Test
    fun skippedInterruptedItemShowsTriedAtAndReason() {
        render(item(SendOutcome.SKIPPED, lastAttemptAtMillis = 1234567890L, failureReason = "Send interrupted"))
        composeTestRule.onNodeWithText("Tried at").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent at").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send interrupted").assertIsDisplayed()
    }
}
