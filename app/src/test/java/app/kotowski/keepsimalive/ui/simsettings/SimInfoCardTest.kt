package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.SimPhoneStateData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimInfoCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()
    private val data =
        SimPhoneStateData(
            simId = 1,
            displayName = "Vodafone",
            carrierName = "Vodafone",
            slotIndex = 1,
            networkOperatorName = "AIS",
            simOperatorName = "Vodafone",
            isNetworkRoaming = false,
        )

    private fun config(nextSendAtMillis: Long?) =
        SimKeepaliveConfig(
            simId = 1,
            enabled = true,
            nextSendAtMillis = nextSendAtMillis,
            lastSentAtMillis = now - 86_400_000L,
            sendCount = 3,
        )

    private fun activeRow(
        outcome: SendOutcome,
        retryCount: Int = 0,
        failureReason: String? = null,
    ) = SendHistoryEntity(
        simId = 1,
        scheduledForMillis = now - 5_000,
        occurrenceBaseMillis = now - 5_000,
        outcome = outcome.name,
        lastAttemptAtMillis = now - 5_000,
        failureReason = failureReason,
        retryCount = retryCount,
        recipient = "+15550100",
        message = "keep alive",
    )

    private fun render(
        config: SimKeepaliveConfig?,
        inFlight: SendHistoryEntity?,
        simMissing: Boolean = false,
        lastSeenAtMillis: Long? = null,
        lastSeenSlotIndex: Int? = null,
        multiSimDevice: Boolean = false,
        offSchedulePending: Boolean = false,
        onSendOffScheduleClick: (() -> Unit)? = null,
        onCancelOffScheduleClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimInfoCard(
                    context = context,
                    data = data,
                    config = config,
                    currentTime = now,
                    inFlight = inFlight,
                    offSchedulePending = offSchedulePending,
                    onSendOffScheduleClick = onSendOffScheduleClick,
                    onCancelOffScheduleClick = onCancelOffScheduleClick,
                    simMissing = simMissing,
                    lastSeenAtMillis = lastSeenAtMillis,
                    lastSeenSlotIndex = lastSeenSlotIndex,
                    multiSimDevice = multiSimDevice,
                )
            }
        }
    }

    @Test
    fun retryInFlightShowsStatusRetryInWithRetryLabel() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING, retryCount = 1))
        composeTestRule.onNodeWithText("Next retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry in 1h 00m 00s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next send").assertDoesNotExist()
    }

    @Test
    fun retryingWithAnEnabledConfigShowsTheRetryButton() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING, retryCount = 1))
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun retryingWithADisabledConfigHidesTheRetryButton() {
        render(config(now + 3_600_000L).copy(enabled = false), activeRow(SendOutcome.PENDING, retryCount = 1))
        // The worker early-returns on a disabled config, so the button would be a no-op;
        // the retry state itself (label/countdown) stays visible.
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Next retry").assertIsDisplayed()
    }

    @Test
    fun retryingWithoutAConfigHidesTheRetryButton() {
        render(null, activeRow(SendOutcome.PENDING, retryCount = 1))
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // A plain PENDING row (no retry) owns no retry to run: no Retry button, and the status
    // counts down with the "Send in" prefix, not "Retry in".
    @Test
    fun plainPendingRowShowsNoRetryButtonAndSendInStatus() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING))
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send in 1h 00m 00s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry in 1h 00m 00s").assertDoesNotExist()
        composeTestRule.onNodeWithText("Next send").assertIsDisplayed()
    }

    // A retry attempt in flight (SENDING, retryCount > 0) shows "Trying now", not the Retry
    // button: the attempt owns the occurrence to its result write.
    @Test
    fun retryAttemptInFlightShowsNoRetryButtonAndTryingNow() {
        render(config(now - 5_000), activeRow(SendOutcome.SENDING, retryCount = 1))
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Trying now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next retry").assertIsDisplayed()
    }

    // A stale SENDING retry row (the process died mid-retry) is not a live attempt: the
    // status falls through to the countdown with the "Send in" prefix, not "Retry in".
    @Test
    fun staleRetryAttemptFallsThroughToSendInStatus() {
        val staleRow =
            SendHistoryEntity(
                simId = 1,
                scheduledForMillis = now - 5_000,
                occurrenceBaseMillis = now - 5_000,
                outcome = SendOutcome.SENDING.name,
                lastAttemptAtMillis = now - AppConfig.SENDING_STALE_MS - 1_000,
                retryCount = 1,
                recipient = "+15550100",
                message = "keep alive",
            )
        render(config(now + 3_600_000L), staleRow)
        composeTestRule.onNodeWithText("Trying now").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send in 1h 00m 00s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry in 1h 00m 00s").assertDoesNotExist()
    }

    // A confirmed off-schedule send is pending: the card's bottom slot (the Retry's slot)
    // is the Cancel for it.
    @Test
    fun pendingOffScheduleSendShowsTheCancelButtonInTheRetrySlot() {
        var clicks = 0
        render(config(now + 3_600_000L), null, offSchedulePending = true, onCancelOffScheduleClick = { clicks++ })
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send off schedule").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, clicks)
    }

    // The pending Cancel wins the slot over the Retry (defensive: the engine never leaves
    // both states open, but the slot must pick one).
    @Test
    fun pendingOffScheduleSendTakesPrecedenceOverTheRetryButton() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING, retryCount = 1), offSchedulePending = true)
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // No pending send and no retry: the off-schedule offer is shown in the Retry's slot;
    // tapping it opens the confirmation dialog.
    @Test
    fun sendOffScheduleOfferShownInTheRetrySlot() {
        var clicks = 0
        render(config(now + 3_600_000L), null, onSendOffScheduleClick = { clicks++ })
        composeTestRule.onNodeWithText("Send off schedule").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send off schedule").performClick()
        assertEquals(1, clicks)
    }

    // A pending retry wins the slot over the offer: arming the off-schedule send on top
    // of a retry is refused by the engine, so the offer must not be tappable there.
    @Test
    fun pendingRetryTakesPrecedenceOverTheSendOffScheduleOffer() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING, retryCount = 1), onSendOffScheduleClick = {})
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send off schedule").assertDoesNotExist()
    }

    @Test
    fun pendingShowsStatusSendInWithSendLabel() {
        render(config(now + 3_600_000L), null)
        composeTestRule.onNodeWithText("Next send").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send in 1h 00m 00s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Error").assertDoesNotExist()
    }

    @Test
    fun notScheduledShowsNextSendWithoutStatusRow() {
        render(config(null), null)
        composeTestRule.onNodeWithText("Next send").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not scheduled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertDoesNotExist()
    }

    @Test
    fun nearFireTimeCountsDownToZeroSeconds() {
        render(config(now + 3_000), null)
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send in 3s").assertIsDisplayed()
    }

    @Test
    fun pastFireTimeWithinGraceShowsZeroSeconds() {
        render(config(now - 3_000), null)
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send in 0s").assertIsDisplayed()
    }

    @Test
    fun pastFireTimeAfterGraceShowsOverdue() {
        render(config(now - 6_000), null)
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overdue").assertIsDisplayed()
    }

    @Test
    fun firstAttemptSendingShowsTryingNowWithNextSendLabel() {
        render(config(now - 5_000), activeRow(SendOutcome.SENDING))
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trying now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next send").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next retry").assertDoesNotExist()
    }

    @Test
    fun retryAttemptSendingKeepsRetryLabelAndShowsTryingNow() {
        render(config(now - 5_000), activeRow(SendOutcome.SENDING, retryCount = 1))
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trying now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next send").assertDoesNotExist()
    }

    @Test
    fun retryingShowsErrorRowWithLastFailureReason() {
        render(config(now + 3_600_000L), activeRow(SendOutcome.PENDING, retryCount = 1, failureReason = "No service"))
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("No service").assertIsDisplayed()
    }

    @Test
    fun retryAttemptSendingShowsErrorRowWithPreviousReason() {
        render(config(now - 5_000), activeRow(SendOutcome.SENDING, retryCount = 1, failureReason = "No service"))
        composeTestRule.onNodeWithText("Trying now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("No service").assertIsDisplayed()
    }

    @Test
    fun firstAttemptSendingHasNoErrorRow() {
        render(config(now - 5_000), activeRow(SendOutcome.SENDING))
        composeTestRule.onNodeWithText("Trying now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error").assertDoesNotExist()
    }

    // The missing-SIM warning and the Forget button live in the banner directly on the
    // screen above the card (covered in SimDetailScreenTest); the card itself carries
    // only the last-known data rows.
    @Test
    fun missingSimShowsLastSeenBeforeOperatorWithoutTheWarning() {
        render(config(null), null, simMissing = true, lastSeenAtMillis = now - 86_400_000L)
        composeTestRule.onNodeWithText("SIM disabled or unplugged").assertDoesNotExist()
        composeTestRule.onNodeWithText("Forget").assertDoesNotExist()
        composeTestRule.onNodeWithText("Last seen").assertIsDisplayed()
        // "Last seen" must be the card's first data row (before Operator).
        val nodes =
            composeTestRule
                .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
        val index =
            { text: String ->
                nodes.indexOfFirst {
                    it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { t -> t.text == text }
                }
            }
        assertTrue(index("Last seen") in 0 until index("Operator"))
    }

    @Test
    fun missingSimShowsLastSeenWithTheSlotItWasLastSeenIn() {
        // Two days ago: distinct from the "Last sent" value (one day ago in config(null)),
        // so the date/time string appears in exactly one row.
        val lastSeen = now - 2 * 86_400_000L
        render(config(null), null, simMissing = true, lastSeenAtMillis = lastSeen, lastSeenSlotIndex = 2, multiSimDevice = true)
        val dateTime = DateUtil.formatDateTime(context, lastSeen)
        val expected = "$dateTime (slot 2)"
        // The unmerged tree holds the exact leaf texts: the value is the date/time with the
        // slot suffix, and no node carries the plain date/time alone.
        val texts =
            composeTestRule
                .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.config.contains(SemanticsProperties.Text) }
                .flatMap { it.config[SemanticsProperties.Text].map { t -> t.text } }
        assertTrue(texts.contains(expected))
        assertFalse(texts.contains(dateTime))
    }

    @Test
    fun missingSimWithoutASlotShowsThePlainLastSeenValue() {
        // Two days ago: distinct from the "Last sent" value (one day ago in config(null)),
        // so the date/time string appears in exactly one row.
        val lastSeen = now - 2 * 86_400_000L
        render(config(null), null, simMissing = true, lastSeenAtMillis = lastSeen, multiSimDevice = true)
        composeTestRule.onNodeWithText(DateUtil.formatDateTime(context, lastSeen), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun singleSimDeviceHidesTheLastSeenSlotSuffix() {
        // Two days ago: distinct from the "Last sent" value (one day ago in config(null)).
        // A single-SIM device has one slot, so the suffix must not be shown even though a
        // slot is remembered.
        val lastSeen = now - 2 * 86_400_000L
        render(config(null), null, simMissing = true, lastSeenAtMillis = lastSeen, lastSeenSlotIndex = 2, multiSimDevice = false)
        val dateTime = DateUtil.formatDateTime(context, lastSeen)
        composeTestRule.onNodeWithText(dateTime, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("$dateTime (slot 2)", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun presentSimHasNoWarningForgetOrLastSeen() {
        render(config(null), null)
        composeTestRule.onNodeWithText("SIM disabled or unplugged").assertDoesNotExist()
        composeTestRule.onNodeWithText("Forget").assertDoesNotExist()
        composeTestRule.onNodeWithText("Last seen").assertDoesNotExist()
        composeTestRule.onNodeWithText("Operator").assertIsDisplayed()
    }
}
