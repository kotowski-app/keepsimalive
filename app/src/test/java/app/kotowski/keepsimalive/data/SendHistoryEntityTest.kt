package app.kotowski.keepsimalive.data

import app.kotowski.keepsimalive.util.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendHistoryEntityTest {
    private fun entity(
        scheduledForMillis: Long,
        lastAttemptAtMillis: Long?,
    ): SendHistoryEntity =
        SendHistoryEntity(
            simId = 1,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            recipient = "+15550100",
            message = "keep alive",
        )

    @Test
    fun `an attempt at the stale threshold is not stale`() {
        val now = 100_000L
        assertFalse(isSendingAttemptStale(now - AppConfig.SENDING_STALE_MS, now, now))
    }

    @Test
    fun `an attempt past the stale threshold is stale`() {
        val now = 100_000L
        assertTrue(isSendingAttemptStale(now - AppConfig.SENDING_STALE_MS - 1, now, now))
    }

    @Test
    fun `a row without an attempt time is judged by the scheduled time`() {
        val now = 100_000L
        assertFalse(isSendingAttemptStale(null, now, now))
        assertTrue(isSendingAttemptStale(null, now - AppConfig.SENDING_STALE_MS - 1, now))
    }

    @Test
    fun `isTerminal is true exactly for the state machine's terminal outcomes`() {
        assertTrue(entity(0, null).copy(outcome = SendOutcome.SENT.name).isTerminal)
        assertTrue(entity(0, null).copy(outcome = SendOutcome.FAILED.name).isTerminal)
        assertTrue(entity(0, null).copy(outcome = SendOutcome.SKIPPED.name).isTerminal)
        assertFalse(entity(0, null).copy(outcome = SendOutcome.PENDING.name).isTerminal)
        // A retry row (PENDING, retryCount > 0) is open, not terminal.
        assertFalse(entity(0, null).copy(outcome = SendOutcome.PENDING.name, retryCount = 2).isTerminal)
        assertFalse(entity(0, null).copy(outcome = SendOutcome.SENDING.name).isTerminal)
    }

    @Test
    fun `the entity extension delegates to the shared predicate`() {
        val now = 100_000L
        assertFalse(entity(now, now).isSendingStale(now))
        // Judged by the attempt time, not the scheduled one: a stale attempt with a fresh
        // scheduled time is stale, a fresh attempt judged from a stale scheduled time is not.
        assertTrue(entity(now - AppConfig.SENDING_STALE_MS - 1, null).isSendingStale(now))
        assertTrue(entity(0, now - AppConfig.SENDING_STALE_MS - 1).isSendingStale(now))
        assertFalse(entity(now - AppConfig.SENDING_STALE_MS - 1, now).isSendingStale(now))
    }
}
