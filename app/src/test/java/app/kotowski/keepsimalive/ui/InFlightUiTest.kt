package app.kotowski.keepsimalive.ui

import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.util.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightUiTest {
    @Test
    fun `a missing in-flight row is neither sending nor a retry`() {
        val state = InFlightUiState.from(null, 0)
        assertFalse(state.isSending)
        assertFalse(state.isRetry)
    }

    @Test
    fun `a pending row is neither sending nor a retry`() {
        val state = InFlightUiState.from(SendOutcome.PENDING.name, 0)
        assertFalse(state.isSending)
        assertFalse(state.isRetry)
    }

    @Test
    fun `a fresh sending row is sending and not a retry`() {
        val state = InFlightUiState.from(SendOutcome.SENDING.name, 0)
        assertTrue(state.isSending)
        assertFalse(state.isRetry)
    }

    @Test
    fun `a sending row with a consumed retry inherits the retry label`() {
        val state = InFlightUiState.from(SendOutcome.SENDING.name, 1)
        assertTrue(state.isSending)
        assertTrue(state.isRetry)
    }

    @Test
    fun `the retry label keys on the retry count, whatever the open outcome`() {
        // A retry row is PENDING with retryCount > 0; a retry attempt in flight is SENDING
        // with retryCount > 0; fresh open rows have retryCount 0.
        val pendingRetry = InFlightUiState.from(SendOutcome.PENDING.name, 1)
        assertFalse(pendingRetry.isSending)
        assertTrue(pendingRetry.isRetry)
        val plainPending = InFlightUiState.from(SendOutcome.PENDING.name, 0)
        assertFalse(plainPending.isSending)
        assertFalse(plainPending.isRetry)
        val freshSending = InFlightUiState.from(SendOutcome.SENDING.name, 0)
        assertTrue(freshSending.isSending)
        assertFalse(freshSending.isRetry)
        val sendingRetry = InFlightUiState.from(SendOutcome.SENDING.name, 1)
        assertTrue(sendingRetry.isSending)
        assertTrue(sendingRetry.isRetry)
    }

    @Test
    fun `a terminal row is neither sending nor a retry`() {
        // The in-flight UI is fed open rows only (the in-flight queries filter them); the
        // label keys sending on the outcome and the retry flag on the retry count, so a
        // terminal row that never retried reports neither.
        assertFalse(InFlightUiState.from(SendOutcome.SENT.name, 2).isSending)
        assertFalse(InFlightUiState.from(SendOutcome.FAILED.name, 0).isRetry)
        assertFalse(InFlightUiState.from(SendOutcome.SKIPPED.name, 0).isRetry)
    }

    @Test
    fun `a sending attempt at the stale threshold is still live`() {
        val now = 100_000L
        assertTrue(isLiveSendingAttempt(now - AppConfig.SENDING_STALE_MS, now, now))
    }

    @Test
    fun `a sending attempt past the stale threshold is not live`() {
        val now = 100_000L
        assertFalse(isLiveSendingAttempt(now - AppConfig.SENDING_STALE_MS - 1, now, now))
    }

    @Test
    fun `a sending row without an attempt time is judged by the scheduled time`() {
        val now = 100_000L
        assertTrue(isLiveSendingAttempt(null, now, now))
        assertFalse(isLiveSendingAttempt(null, now - AppConfig.SENDING_STALE_MS - 1, now))
    }
}
