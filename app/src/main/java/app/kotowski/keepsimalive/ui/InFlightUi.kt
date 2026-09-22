package app.kotowski.keepsimalive.ui

import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.isSendingAttemptStale

// The "in-flight attempt" facts the dashboard SIM card and the SIM details card derive
// from the same send-history row: whether the attempt is currently sending, and whether
// the row is a retry — the "Next retry" label, which is a row with retryCount > 0 (a
// waiting retry or a retry attempt in flight).
data class InFlightUiState(
    val isSending: Boolean,
    val isRetry: Boolean,
) {
    companion object {
        fun from(
            outcome: String?,
            retryCount: Int,
        ): InFlightUiState {
            val isSending = outcome == SendOutcome.SENDING.name
            return InFlightUiState(
                isSending = isSending,
                isRetry = retryCount > 0,
            )
        }
    }
}

// A SENDING row younger than SENDING_STALE_MS is a live attempt (the attempt owns its
// occurrence): the card keeps "Trying now" instead of flipping to "just now", and the SIM
// details commit gate refuses a disable landing mid-send. A stale SENDING row (the process
// died mid-send) is not live: the engine resolves it on its next run, and the UI falls
// through to its stale rendering (the relative time / the overdue state).
fun isLiveSendingAttempt(
    lastAttemptAtMillis: Long?,
    scheduledForMillis: Long,
    now: Long,
): Boolean = !isSendingAttemptStale(lastAttemptAtMillis, scheduledForMillis, now)
