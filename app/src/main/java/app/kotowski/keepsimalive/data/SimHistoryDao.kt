package app.kotowski.keepsimalive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// A row that still owns an occurrence: not yet attempted (PENDING — including a pending
// retry) or attempt in flight (SENDING). retryCount tells the UI whether the row is a
// retry (the "Next retry" label) even while SENDING; the attempt timestamps let the UI
// tell a live attempt from a dead one (a SENDING row older than SENDING_STALE_MS is no
// longer "trying now").
data class InFlightOccurrence(
    val simId: Int,
    val outcome: String,
    val retryCount: Int,
    val scheduledForMillis: Long,
    val lastAttemptAtMillis: Long?,
)

@Dao
interface SimHistoryDao {
    @Insert
    fun insert(history: SendHistoryEntity): Long

    @Query(
        "UPDATE send_history SET outcome = :outcome, lastAttemptAtMillis = :lastAttemptAtMillis, failureReason = :failureReason, retryCount = :retryCount, firstAttemptAtMillis = :firstAttemptAtMillis WHERE id = :id",
    )
    fun updateOutcome(
        id: Long,
        outcome: String,
        lastAttemptAtMillis: Long?,
        failureReason: String?,
        retryCount: Int,
        firstAttemptAtMillis: Long?,
    )

    // The newest rows for the SIM, capped by `limit`, observed live. Two consumers share the
    // contract: the SIM details' load window (kept unfiltered over all outcomes, so the
    // screen's in-memory terminal filter and the in-flight row lookup keep working) and the
    // history screen's growing live window. The id tiebreaker keeps the order deterministic
    // when two rows share a timestamp (the (simId, scheduledForMillis) index is non-unique).
    @Query("SELECT * FROM send_history WHERE simId = :simId ORDER BY scheduledForMillis DESC, id DESC LIMIT :limit")
    fun observeNewest(
        simId: Int,
        limit: Int,
    ): Flow<List<SendHistoryEntity>>

    // Exact terminal-row count for the history header — the loaded list is capped, so its
    // in-memory size cannot be the total.
    @Query("SELECT COUNT(*) FROM send_history WHERE simId = :simId AND outcome IN ('SENT', 'FAILED', 'SKIPPED')")
    fun observeTerminalCount(simId: Int): Flow<Int>

    // The rows the in-flight flow must surface: the live attempt (SENDING) and the pending
    // retries (PENDING with retryCount > 0 — a retry row carries the occurrence time, while
    // a fresh PENDING is only "next", not "in flight").
    @Query(
        "SELECT simId, outcome, retryCount, scheduledForMillis, lastAttemptAtMillis FROM send_history WHERE outcome = 'SENDING' OR (outcome = 'PENDING' AND retryCount > 0)",
    )
    fun observeInFlight(): Flow<List<InFlightOccurrence>>

    // The row that owns the current occurrence. One open row per SIM is the invariant (the
    // partial unique index enforces it); the outcome priority makes the anchor explicit
    // regardless: SENDING wins over PENDING (a retry row is a PENDING), then the newest.
    @Query(
        "SELECT * FROM send_history WHERE simId = :simId AND outcome IN ('PENDING', 'SENDING') ORDER BY CASE outcome WHEN 'SENDING' THEN 0 ELSE 1 END, scheduledForMillis DESC LIMIT 1",
    )
    suspend fun getActive(simId: Int): SendHistoryEntity?

    // Terminal rows only: an in-flight row (PENDING — including a retry row — or SENDING)
    // is owned by the send engine to completion and must survive retention (deleting a
    // retry row would silently restart its 14-day retry window).
    @Query(
        "DELETE FROM send_history WHERE scheduledForMillis < :cutoffMillis AND outcome IN ('SENT', 'FAILED', 'SKIPPED')",
    )
    fun deleteOlderThan(cutoffMillis: Long): Int

    // Terminal rows only: the in-flight occurrence is owned by the send engine to completion
    // and must survive a Clear (deleting it would lose the occurrence's record and retry chain).
    @Query("DELETE FROM send_history WHERE simId = :simId AND outcome IN ('SENT', 'FAILED', 'SKIPPED')")
    fun deleteBySimId(simId: Int)

    // Forget a SIM: unlike deleteBySimId (Clear) this also drops open rows - the caller
    // deletes under the send lock, so no live attempt outlives them (a late fire bails on
    // the missing config).
    @Query("DELETE FROM send_history WHERE simId = :simId")
    fun deleteAllBySimId(simId: Int)

    // Fresh PENDING rows only: a SENDING row and a retry row (PENDING, retryCount > 0) are
    // owned by the send engine and must survive a save — deleting the retry row mid-flight
    // loses the occurrence's bookkeeping (and its retry chain).
    @Query("DELETE FROM send_history WHERE simId = :simId AND outcome = 'PENDING' AND retryCount = 0")
    fun deletePending(simId: Int)

    // Aligns the SIM's pending row to one occurrence: drops the stale fresh PENDING rows
    // (other times, never SENDING or retry rows) and inserts the row only if no open row
    // remains, so an in-flight occurrence can never be shadowed by a second active row.
    @Transaction
    fun alignPendingRow(
        simId: Int,
        scheduledForMillis: Long,
        occurrenceBaseMillis: Long,
        recipient: String,
        message: String,
    ) {
        deletePendingExcept(simId, scheduledForMillis)
        insertPendingIfNoOpenRow(simId, scheduledForMillis, occurrenceBaseMillis, recipient, message)
    }

    // The drop half of alignPendingRow: a retry row (PENDING, retryCount > 0) carries the
    // occurrence time while the retry time lives in nextSendAtMillis, so keying on the
    // armed time alone would drop it — exclude it here.
    @Query(
        "DELETE FROM send_history WHERE simId = :simId AND outcome = 'PENDING' AND retryCount = 0 AND scheduledForMillis != :keepMillis",
    )
    fun deletePendingExcept(
        simId: Int,
        keepMillis: Long,
    )

    // The columns without a SQL default (outcome, retryCount) get explicit values: the
    // Kotlin defaults on SendHistoryEntity are not visible to Room for a raw query. Three
    // guards: (1) no open row for the SIM; (2) no terminal row (SENT/FAILED/SKIPPED) for
    // this occurrence's BASE — a terminal row is proof the occurrence is already consumed,
    // and the base (never the exact delivery instant) is the key, because a re-arm
    // re-rolls the time-window jitter, so an instant-keyed guard would miss the existing
    // terminal row and re-send a consumed occurrence; and (3) a live enabled config exists —
    // a forget/delete/disable that wins the race against a concurrent send can never be
    // resurrected by a late insert (every legitimate caller runs enabled, so the guard only
    // bites for a SIM the user just removed or turned off).
    @Query(
        "INSERT INTO send_history (simId, scheduledForMillis, occurrenceBaseMillis, outcome, recipient, message, retryCount) SELECT :simId, :scheduledForMillis, :occurrenceBaseMillis, 'PENDING', :recipient, :message, 0 WHERE NOT EXISTS (SELECT 1 FROM send_history WHERE simId = :simId AND outcome IN ('PENDING', 'SENDING')) AND NOT EXISTS (SELECT 1 FROM send_history WHERE simId = :simId AND occurrenceBaseMillis = :occurrenceBaseMillis AND outcome IN ('SENT', 'FAILED', 'SKIPPED')) AND EXISTS (SELECT 1 FROM sim_config WHERE simId = :simId AND enabled = 1)",
    )
    fun insertPendingIfNoOpenRow(
        simId: Int,
        scheduledForMillis: Long,
        occurrenceBaseMillis: Long,
        recipient: String,
        message: String,
    )

    // The skip twin of insertPendingIfNoOpenRow: records one SKIPPED row for a missed
    // occurrence only when no terminal row exists for this occurrence's BASE and a live
    // enabled config exists (the same invariant as the PENDING twin: no skip ghost for a SIM
    // the user just removed or turned off). Any terminal row is proof the occurrence is
    // already consumed — a skip on top of it would show it twice in the history. The base,
    // never the exact delivery instant: a re-arm re-rolls the jitter, so a missed occurrence
    // re-derived past the grace must still see a terminal row carrying the old jittered
    // instant.
    @Query(
        "INSERT INTO send_history (simId, scheduledForMillis, occurrenceBaseMillis, outcome, failureReason, recipient, message, retryCount) SELECT :simId, :scheduledForMillis, :occurrenceBaseMillis, 'SKIPPED', :failureReason, :recipient, :message, 0 WHERE NOT EXISTS (SELECT 1 FROM send_history WHERE simId = :simId AND occurrenceBaseMillis = :occurrenceBaseMillis AND outcome IN ('SENT', 'FAILED', 'SKIPPED')) AND EXISTS (SELECT 1 FROM sim_config WHERE simId = :simId AND enabled = 1)",
    )
    fun insertSkippedIfNotTerminal(
        simId: Int,
        scheduledForMillis: Long,
        occurrenceBaseMillis: Long,
        failureReason: String,
        recipient: String,
        message: String,
    )

    // Atomic claim of an occurrence: flips a still-open row to SENDING and reports how many
    // rows were updated. Concurrent send runs for the same SIM race this update; exactly one
    // can win (1 row), the others see 0 and must not touch the radio.
    @Query(
        "UPDATE send_history SET outcome = 'SENDING', lastAttemptAtMillis = :lastAttemptAtMillis, firstAttemptAtMillis = IFNULL(firstAttemptAtMillis, :firstAttemptAtMillis) WHERE id = :id AND outcome = 'PENDING'",
    )
    fun claimOccurrence(
        id: Long,
        lastAttemptAtMillis: Long,
        firstAttemptAtMillis: Long,
    ): Int

    // Finalizes an occurrence only while it is still open (a PENDING row, including a
    // retry row): a concurrent run may have finalized it first, and a stale write must not
    // clobber the winner's outcome (e.g. a SENT row turned "Skipped (Disabled before
    // send)"). Returns the number of rows updated (0 = already finalized by someone else).
    @Query(
        "UPDATE send_history SET outcome = :outcome, lastAttemptAtMillis = :lastAttemptAtMillis, failureReason = :failureReason, retryCount = :retryCount, firstAttemptAtMillis = :firstAttemptAtMillis WHERE id = :id AND outcome = 'PENDING'",
    )
    fun finalizeOccurrence(
        id: Long,
        outcome: String,
        lastAttemptAtMillis: Long?,
        failureReason: String?,
        retryCount: Int,
        firstAttemptAtMillis: Long?,
    ): Int

    // Finalizes a live in-flight attempt: flips the row to `outcome` only while it is still
    // SENDING — a concurrent finalizer (the stale check, a late send worker, a disable) must
    // not be clobbered, and its schedule advance must not be repeated. `staleBeforeMillis`
    // (null = plain flip) additionally requires the attempt to already be past the staleness
    // bound (a NULL attempt time counts as stale): the stale-check worker and a late send
    // worker race to resolve the same dead attempt, and exactly one flip wins. Returns the
    // number of rows updated (0 = the row is no longer (a stale) SENDING; the schedule must
    // not be advanced either).
    @Query(
        "UPDATE send_history SET outcome = :outcome, lastAttemptAtMillis = :lastAttemptAtMillis, failureReason = :failureReason, retryCount = :retryCount, firstAttemptAtMillis = :firstAttemptAtMillis WHERE id = :id AND outcome = 'SENDING' AND (:staleBeforeMillis IS NULL OR lastAttemptAtMillis IS NULL OR lastAttemptAtMillis < :staleBeforeMillis)",
    )
    fun finalizeSending(
        id: Long,
        outcome: String,
        lastAttemptAtMillis: Long?,
        failureReason: String?,
        retryCount: Int,
        firstAttemptAtMillis: Long?,
        staleBeforeMillis: Long?,
    ): Int

    // The newest occurrence proven delivered, as its BASE (the pre-jitter configured time),
    // not its delivery instant: callers feed it to ScheduleCalculator.nextOccurrence, which
    // re-derives from the date/month plus the configured time, so a jittered value would be
    // wrong here.
    @Query("SELECT MAX(occurrenceBaseMillis) FROM send_history WHERE simId = :simId AND outcome = 'SENT'")
    fun latestSentMillis(simId: Int): Long?

    // Non-zero means a terminal row already exists for this occurrence, whatever jittered
    // delivery instant its row carries. The fresh-arm re-anchor walks from it, so a
    // consumed occurrence must be skipped to the next one instead of being re-armed (and
    // re-sent) under a freshly rolled jitter.
    @Query(
        "SELECT COUNT(*) FROM send_history WHERE simId = :simId AND occurrenceBaseMillis = :occurrenceBaseMillis AND outcome IN ('SENT', 'FAILED', 'SKIPPED')",
    )
    fun countFinalizedByBase(
        simId: Int,
        occurrenceBaseMillis: Long,
    ): Int
}
