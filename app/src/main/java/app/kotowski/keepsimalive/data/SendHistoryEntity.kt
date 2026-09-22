package app.kotowski.keepsimalive.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kotowski.keepsimalive.util.AppConfig

// One row per scheduled occurrence. The outcome is a state machine driven by
// SendOrchestrator: PENDING (not attempted) -> SENDING (attempt in flight) -> SENT | FAILED |
// SKIPPED, or back to PENDING with retryCount + 1 on a retryable failure (the same row keeps
// the occurrence time stable across retries; the retry time is nextSendAtMillis). PENDING |
// SENDING -> SKIPPED on end condition, disable or an interrupted attempt. A retry is a PENDING
// row with retryCount > 0; SENT, FAILED and SKIPPED are terminal — nothing else in the schema
// distinguishes a retry from a fresh PENDING.
@Entity(tableName = "send_history", indices = [Index(value = ["simId", "scheduledForMillis"])])
data class SendHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val simId: Int,
    val scheduledForMillis: Long,
    // The occurrence's base time (the configured hour:minute on the occurrence's date or
    // month, before the time-window jitter): scheduledForMillis is base + jitter, and the
    // jitter is re-rolled on every arm. The base is the occurrence's identity — the
    // "already consumed?" guards in SimHistoryDao key on it, never on the exact jittered
    // instant (a re-arm with a freshly rolled jitter must still see the terminal row the
    // same occurrence left behind).
    val occurrenceBaseMillis: Long,
    // Set when the attempt starts (SENDING); afterwards the last attempt start time. Null while
    // PENDING and for SKIPPED occurrences that were never attempted.
    val lastAttemptAtMillis: Long? = null,
    val outcome: String = SendOutcome.PENDING.name,
    val failureReason: String? = null,
    val recipient: String,
    val message: String,
    val firstAttemptAtMillis: Long? = null,
    val retryCount: Int = 0,
)

enum class SendOutcome { PENDING, SENDING, SENT, FAILED, SKIPPED }

// The one staleness fact about an in-flight attempt, shared by the send engine (which
// resolves a dead attempt) and the UI (which stops showing "Trying now"): the attempt time
// is the last attempt start, or the scheduled time for a claim that never attempted, and an
// attempt older than SENDING_STALE_MS is presumed dead.
fun isSendingAttemptStale(
    lastAttemptAtMillis: Long?,
    scheduledForMillis: Long,
    now: Long,
): Boolean = now - (lastAttemptAtMillis ?: scheduledForMillis) > AppConfig.SENDING_STALE_MS

fun SendHistoryEntity.isSendingStale(now: Long): Boolean = isSendingAttemptStale(lastAttemptAtMillis, scheduledForMillis, now)

// Terminal per the state machine above: the occurrence is consumed and the row is pure history
// (countable, paged, retention-deletable). The SQL twin is the IN ('SENT', 'FAILED', 'SKIPPED')
// literals in SimHistoryDao — Room can't call Kotlin, so the two must be kept in sync.
val SendHistoryEntity.isTerminal: Boolean
    get() =
        outcome == SendOutcome.SENT.name ||
            outcome == SendOutcome.FAILED.name ||
            outcome == SendOutcome.SKIPPED.name
