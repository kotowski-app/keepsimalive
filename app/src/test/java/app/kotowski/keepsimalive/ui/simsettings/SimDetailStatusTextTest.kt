package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimDetailStatusTextTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()

    private fun row(
        outcome: SendOutcome = SendOutcome.PENDING,
        retryCount: Int = 0,
    ) = SendHistoryEntity(
        simId = 1,
        scheduledForMillis = now - 5_000,
        occurrenceBaseMillis = now - 5_000,
        outcome = outcome.name,
        lastAttemptAtMillis = now - 5_000,
        retryCount = retryCount,
        recipient = "+15550100",
        message = "m",
    )

    @Test
    fun `nextSendText not scheduled shows not scheduled`() {
        assertEquals(context.getString(R.string.interval_not_scheduled), nextSendText(context, null))
    }

    @Test
    fun `nextSendText scheduled shows date time without countdown`() {
        val text = nextSendText(context, now + 18_192_000)
        assertTrue("expected a non-blank date time, got: $text", text.isNotBlank())
        assertFalse("date time must not carry the countdown in parentheses, got: $text", text.contains('('))
    }

    @Test
    fun `statusText no next send is null`() {
        assertNull(statusText(context, null, null, now))
        assertNull(statusText(context, row(SendOutcome.PENDING), null, now))
    }

    @Test
    fun `statusText fresh sending shows trying now regardless of the scheduled time`() {
        // row() carries a 5 s old attempt: fresh by a wide margin.
        assertEquals(
            context.getString(R.string.sim_history_outcome_sending),
            statusText(context, row(SendOutcome.SENDING), now - 5_000, now),
        )
        assertEquals(
            context.getString(R.string.sim_history_outcome_sending),
            statusText(context, row(SendOutcome.SENDING, 1), now + 3_600_000, now),
        )
        // Exactly SENDING_STALE_MS old is still a live attempt (the threshold is exclusive).
        val atThreshold = row(SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 60_000)
        assertEquals(
            context.getString(R.string.sim_history_outcome_sending),
            statusText(context, atThreshold, now - 60_000, now),
        )
    }

    @Test
    fun `statusText stale sending falls through to the overdue state`() {
        // The process died mid-send: the attempt is older than SENDING_STALE_MS.
        val stale = row(SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 70_000, scheduledForMillis = now - 70_000)
        assertEquals(
            context.getString(R.string.status_overdue),
            statusText(context, stale, now - 70_000, now),
        )
    }

    @Test
    fun `statusText stale sending without an attempt time judges by the scheduled time`() {
        val noAttemptStale = row(SendOutcome.SENDING).copy(lastAttemptAtMillis = null, scheduledForMillis = now - 70_000)
        assertEquals(
            context.getString(R.string.status_overdue),
            statusText(context, noAttemptStale, now - 70_000, now),
        )
        val noAttemptFresh = row(SendOutcome.SENDING).copy(lastAttemptAtMillis = null, scheduledForMillis = now - 10_000)
        assertEquals(
            context.getString(R.string.sim_history_outcome_sending),
            statusText(context, noAttemptFresh, now - 10_000, now),
        )
    }

    @Test
    fun `statusText pending hours remaining shows send in duration`() {
        assertEquals("Send in 5h 03m 12s", statusText(context, null, now + 18_192_000, now))
    }

    @Test
    fun `statusText pending minutes remaining shows send in duration`() {
        assertEquals("Send in 1m 30s", statusText(context, null, now + 90_000, now))
    }

    @Test
    fun `statusText pending seconds remaining shows send in duration`() {
        assertEquals("Send in 7s", statusText(context, null, now + 7_000, now))
    }

    @Test
    fun `statusText 3001ms remaining shows send in 3s`() {
        assertEquals("Send in 3s", statusText(context, null, now + 3001, now))
    }

    @Test
    fun `statusText seconds remaining counts down to zero`() {
        assertEquals("Send in 3s", statusText(context, null, now + 3000, now))
        assertEquals("Send in 1s", statusText(context, null, now + 1000, now))
        assertEquals("Send in 0s", statusText(context, null, now + 500, now))
        assertEquals("Send in 0s", statusText(context, null, now, now))
    }

    @Test
    fun `statusText stays at zero seconds within worker grace`() {
        assertEquals("Send in 0s", statusText(context, null, now - 2_999, now))
        assertEquals("Send in 0s", statusText(context, null, now - 5_000, now))
        assertEquals("Retry in 0s", statusText(context, row(SendOutcome.PENDING, 1), now - 5_000, now))
    }

    @Test
    fun `statusText shows overdue after grace expired`() {
        assertEquals(context.getString(R.string.status_overdue), statusText(context, null, now - 5_001, now))
        assertEquals(context.getString(R.string.status_overdue), statusText(context, row(SendOutcome.PENDING, 1), now - 60_000, now))
    }

    @Test
    fun `statusText retrying shows retry in duration`() {
        // A retry row is PENDING with retryCount > 0.
        assertEquals("Retry in 5h 03m 12s", statusText(context, row(SendOutcome.PENDING, 2), now + 18_192_000, now))
        assertEquals("Retry in 1m 30s", statusText(context, row(SendOutcome.PENDING, 2), now + 90_000, now))
        assertEquals("Send in 5h 03m 12s", statusText(context, row(SendOutcome.PENDING), now + 18_192_000, now))
    }
}
