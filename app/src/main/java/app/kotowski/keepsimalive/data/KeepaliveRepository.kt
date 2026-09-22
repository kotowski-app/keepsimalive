package app.kotowski.keepsimalive.data

import androidx.room.withTransaction
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// The schedule consequence that must land in the same transaction as the terminal flip.
// Data layer only: no work arming, no prefs, no notifications (the caller does those
// after the commit).
sealed interface FinalizeAdvance {
    // null `nextSendAtMillis` = final send: the end lands instead of a next row.
    data class Success(
        val simId: Int,
        val lastSentOccurrenceMillis: Long,
        val sendCount: Int,
        val nextSendAtMillis: Long?,
        val nextBaseMillis: Long?,
        val recipient: String,
        val message: String,
    ) : FinalizeAdvance

    data class AdvanceToNext(
        val simId: Int,
        val nextSendAtMillis: Long,
        val nextBaseMillis: Long,
        val recipient: String,
        val message: String,
    ) : FinalizeAdvance

    // The schedule is disabled and the next send cleared; no history row is written.
    data class End(
        val simId: Int,
    ) : FinalizeAdvance

    // User disable: the fresh PENDING rows are dropped and the next send cleared.
    data class Disable(
        val simId: Int,
    ) : FinalizeAdvance

    // Engine auto-disable: a targeted write of enabled = 0 with the in-lock config's
    // user columns, the fresh PENDING rows dropped and the next send cleared.
    data class AutoDisable(
        val config: SimKeepaliveConfig,
    ) : FinalizeAdvance
}

@Singleton
class KeepaliveRepository
    @Inject
    constructor(
        private val database: KeepaliveDatabase,
        private val simConfigDao: SimConfigDao,
        private val simHistoryDao: SimHistoryDao,
    ) {
        suspend fun saveConfig(config: SimKeepaliveConfig) {
            withContext(Dispatchers.IO) {
                simConfigDao.upsert(config.toEntity())
            }
        }

        // UI save: writes only the user-controlled columns, so the engine-owned schedule
        // state (nextSendAtMillis, lastSentAtMillis, sendCount) survives. The row must
        // already exist (saveConfig creates it on first enable).
        suspend fun saveUserColumns(config: SimKeepaliveConfig) {
            withContext(Dispatchers.IO) {
                applyUserColumns(config.toEntity(), null)
            }
        }

        // The single unpack of the user columns into the targeted update: a new user
        // column means the DAO, toEntity/fromEntity and this one place. `enabled`
        // overrides the entity's flag when non-null (the engine auto-disable forces off).
        // Plain and DAO-only on purpose: finalizeOutcome calls it inline on the
        // transaction thread.
        private fun applyUserColumns(
            entity: SimConfigEntity,
            enabled: Boolean?,
        ) {
            simConfigDao.updateUserColumns(
                entity.simId,
                enabled ?: entity.enabled,
                entity.recipientPhone,
                entity.message,
                entity.hour,
                entity.minute,
                entity.freqType,
                entity.daysInterval,
                entity.monthsInterval,
                entity.dayOfMonth,
                entity.selectedMonths,
                entity.endType,
                entity.maxSends,
                entity.endDate,
                entity.timeWindowMinutes,
            )
        }

        // Targeted engine writes: only the schedule-state columns are touched, so a user edit
        // (full-row upsert) that lands in the same window is never clobbered.

        suspend fun updateNextSend(
            simId: Int,
            nextSendAtMillis: Long?,
        ) {
            withContext(Dispatchers.IO) {
                simConfigDao.updateNextSend(simId, nextSendAtMillis)
            }
        }

        // Clears the history-clear survival anchor (the base of the last sent occurrence):
        // the terminal schedule end — never a plain re-arm, which writes a PENDING row,
        // not a SENT one, and must keep the rhythm re-derivable after a later history clear.
        suspend fun clearLastSentOccurrence(simId: Int) {
            withContext(Dispatchers.IO) {
                simConfigDao.clearLastSentOccurrence(simId)
            }
        }

        // One transactional finalize: the terminal flip (a CAS guarded on `expected`) and
        // the schedule consequence (`advance`) land together, or nothing lands — the
        // "terminal row written, schedule not advanced" crash state is unproducible.
        //
        // `expected` selects the CAS guard: a PENDING row takes the open-row flip, a
        // SENDING row the in-flight flip; `staleBeforeMillis` (SENDING only, null = plain
        // flip) additionally requires the attempt to be past the staleness bound. Returns
        // true when the flip won and the consequence landed; false when a concurrent
        // finalizer already owns the row (nothing was written — the caller must not
        // advance or arm either).
        //
        // The body calls DAO methods only: the transaction's connection is thread-affine,
        // so no suspend hop (delay, withContext, repository wrappers) inside it. The
        // synchronous @Transaction `alignPendingRow` nests as a savepoint.
        suspend fun finalizeOutcome(
            rowId: Long,
            expected: SendOutcome,
            outcome: SendOutcome,
            lastAttemptAtMillis: Long?,
            failureReason: String?,
            retryCount: Int,
            firstAttemptAtMillis: Long?,
            staleBeforeMillis: Long?,
            advance: FinalizeAdvance,
        ): Boolean =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val applied =
                        if (expected == SendOutcome.SENDING) {
                            simHistoryDao.finalizeSending(
                                rowId,
                                outcome.name,
                                lastAttemptAtMillis,
                                failureReason,
                                retryCount,
                                firstAttemptAtMillis,
                                staleBeforeMillis,
                            )
                        } else {
                            simHistoryDao.finalizeOccurrence(
                                rowId,
                                outcome.name,
                                lastAttemptAtMillis,
                                failureReason,
                                retryCount,
                                firstAttemptAtMillis,
                            )
                        }
                    if (applied == 1) {
                        when (advance) {
                            is FinalizeAdvance.Success -> {
                                val sentAt = checkNotNull(lastAttemptAtMillis) { "a success finalize carries the sent-at time" }
                                simConfigDao.recordSuccess(
                                    advance.simId,
                                    sentAt,
                                    advance.lastSentOccurrenceMillis,
                                    advance.sendCount,
                                    advance.nextSendAtMillis,
                                )
                                if (advance.nextSendAtMillis == null) {
                                    check(advance.nextBaseMillis == null) { "an ending success carries no next base" }
                                    simConfigDao.endSchedule(advance.simId)
                                } else {
                                    val nextSend = checkNotNull(advance.nextSendAtMillis)
                                    val nextBase = checkNotNull(advance.nextBaseMillis) { "a continuing success carries the next base" }
                                    simHistoryDao.alignPendingRow(
                                        advance.simId,
                                        nextSend,
                                        nextBase,
                                        advance.recipient,
                                        advance.message,
                                    )
                                }
                            }

                            is FinalizeAdvance.AdvanceToNext -> {
                                simConfigDao.updateNextSend(advance.simId, advance.nextSendAtMillis)
                                simHistoryDao.alignPendingRow(
                                    advance.simId,
                                    advance.nextSendAtMillis,
                                    advance.nextBaseMillis,
                                    advance.recipient,
                                    advance.message,
                                )
                            }

                            is FinalizeAdvance.End -> {
                                simConfigDao.endSchedule(advance.simId)
                            }

                            is FinalizeAdvance.Disable -> {
                                simHistoryDao.deletePending(advance.simId)
                                simConfigDao.updateNextSend(advance.simId, null)
                            }

                            is FinalizeAdvance.AutoDisable -> {
                                val entity = advance.config.toEntity()
                                applyUserColumns(entity, false)
                                simHistoryDao.deletePending(entity.simId)
                                simConfigDao.updateNextSend(entity.simId, null)
                            }
                        }
                    }
                    applied == 1
                }
            }

        // The grace-skip finalize: the anchor open row flips to SKIPPED in place (no
        // attempt happened, so it keeps lastAttemptAtMillis = null and its existing
        // base), every other missed occurrence gets its guarded SKIPPED record, and the
        // re-armed occurrence lands — one transaction, or nothing.
        //
        // `config` is the caller's in-lock read: a re-read inside the transaction could
        // diverge on freqType/time and derive wrong bases for the consumed guards.
        // `missed` are the missed occurrence times (epoch millis); the anchor is excluded
        // from them by the caller. Returns false when the anchor flip lost the race
        // (a concurrent finalizer owns the row): the caller logs and does not arm.
        suspend fun finalizeGraceSkips(
            config: SimKeepaliveConfig,
            anchor: SendHistoryEntity?,
            missed: List<Long>,
            reason: String,
            sendAtMillis: Long,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val simId = config.simId
                val recipient = config.recipientPhone
                val message = config.message
                database.withTransaction {
                    val applied =
                        if (anchor != null) {
                            simHistoryDao.finalizeOccurrence(
                                anchor.id,
                                SendOutcome.SKIPPED.name,
                                null,
                                reason,
                                anchor.retryCount,
                                anchor.firstAttemptAtMillis,
                            )
                        } else {
                            1
                        }
                    if (applied == 1) {
                        for (millis in missed) {
                            simHistoryDao.insertSkippedIfNotTerminal(
                                simId,
                                millis,
                                baseOfMillis(config, millis),
                                reason,
                                recipient,
                                message,
                            )
                        }
                        simConfigDao.updateNextSend(simId, sendAtMillis)
                        simHistoryDao.alignPendingRow(
                            simId,
                            sendAtMillis,
                            baseOfMillis(config, sendAtMillis),
                            recipient,
                            message,
                        )
                    }
                    applied == 1
                }
            }

        // The pre-jitter base of the occurrence at `millis` (the consumed guards key on
        // it). Pure, so the DAO-calls-only transaction body above may call it.
        private fun baseOfMillis(
            config: SimKeepaliveConfig,
            millis: Long,
        ): Long =
            ScheduleCalculator
                .baseOf(config, Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
                .toInstant()
                .toEpochMilli()

        suspend fun endSchedule(simId: Int) {
            withContext(Dispatchers.IO) {
                simConfigDao.endSchedule(simId)
            }
        }

        suspend fun getConfig(simId: Int): SimKeepaliveConfig? =
            withContext(Dispatchers.IO) {
                simConfigDao.getBySimId(simId)?.let { SimKeepaliveConfig.fromEntity(it).sanitize() }
            }

        suspend fun getAllConfigs(): List<SimKeepaliveConfig> =
            withContext(Dispatchers.IO) {
                simConfigDao.getAll().map { SimKeepaliveConfig.fromEntity(it).sanitize() }
            }

        fun observeConfigs(): Flow<List<SimKeepaliveConfig>> =
            simConfigDao
                .observeAll()
                .map { entities -> entities.map { SimKeepaliveConfig.fromEntity(it).sanitize() } }

        suspend fun insertHistory(history: SendHistoryEntity): Long =
            withContext(Dispatchers.IO) {
                simHistoryDao.insert(history)
            }

        // One skip record per missed occurrence, unless a terminal row already proves it
        // was consumed (by base): a skip on top of a terminal row is a ghost of a crash
        // state, not a real miss.
        suspend fun insertSkippedIfNotTerminal(
            simId: Int,
            scheduledForMillis: Long,
            occurrenceBaseMillis: Long,
            failureReason: String,
            recipient: String,
            message: String,
        ) {
            withContext(Dispatchers.IO) {
                simHistoryDao.insertSkippedIfNotTerminal(
                    simId,
                    scheduledForMillis,
                    occurrenceBaseMillis,
                    failureReason,
                    recipient,
                    message,
                )
            }
        }

        suspend fun updateHistory(
            id: Long,
            outcome: SendOutcome,
            lastAttemptAtMillis: Long?,
            failureReason: String?,
            retryCount: Int,
            firstAttemptAtMillis: Long?,
        ) {
            withContext(Dispatchers.IO) {
                simHistoryDao.updateOutcome(id, outcome.name, lastAttemptAtMillis, failureReason, retryCount, firstAttemptAtMillis)
            }
        }

        fun observeNewest(
            simId: Int,
            limit: Int,
        ): Flow<List<SendHistoryEntity>> = simHistoryDao.observeNewest(simId, limit)

        fun observeHistoryCount(simId: Int): Flow<Int> = simHistoryDao.observeTerminalCount(simId)

        // A SENDING row wins over a retry row when both exist: Map.plus prefers the right
        // operand, so the sending map goes last.
        fun observeInFlightStates(): Flow<Map<Int, InFlightOccurrence>> =
            simHistoryDao.observeInFlight().map { rows ->
                val retrying = rows.filter { it.retryCount > 0 }.associateBy { it.simId }
                val sending = rows.filter { it.outcome == SendOutcome.SENDING.name }.associateBy { it.simId }
                retrying + sending
            }

        suspend fun getActiveHistory(simId: Int): SendHistoryEntity? =
            withContext(Dispatchers.IO) {
                simHistoryDao.getActive(simId)
            }

        // 1 if this call atomically claimed the occurrence (row flipped to SENDING), 0 if a
        // concurrent run already owns it.
        suspend fun claimOccurrence(
            id: Long,
            now: Long,
            firstAttempt: Long,
        ): Int =
            withContext(Dispatchers.IO) {
                simHistoryDao.claimOccurrence(id, now, firstAttempt)
            }

        // 1 if this call finalized the row, 0 if a concurrent run already did: only open
        // (PENDING) rows are touched, so a stale write never clobbers the winner's outcome.
        suspend fun finalizeOccurrence(
            id: Long,
            outcome: SendOutcome,
            lastAttemptAtMillis: Long?,
            failureReason: String?,
            retryCount: Int,
            firstAttemptAtMillis: Long?,
        ): Int =
            withContext(Dispatchers.IO) {
                simHistoryDao.finalizeOccurrence(id, outcome.name, lastAttemptAtMillis, failureReason, retryCount, firstAttemptAtMillis)
            }

        // 1 if this call flipped the in-flight attempt to `outcome`, 0 if the row is no
        // longer SENDING (a concurrent finalizer owns it): on 0 the caller must not
        // advance or re-arm. `staleBeforeMillis` (null = plain flip) additionally requires
        // the attempt to be past the staleness bound.
        suspend fun finalizeSending(
            id: Long,
            outcome: SendOutcome,
            lastAttemptAtMillis: Long?,
            failureReason: String?,
            retryCount: Int,
            firstAttemptAtMillis: Long?,
            staleBeforeMillis: Long?,
        ): Int =
            withContext(Dispatchers.IO) {
                simHistoryDao.finalizeSending(
                    id,
                    outcome.name,
                    lastAttemptAtMillis,
                    failureReason,
                    retryCount,
                    firstAttemptAtMillis,
                    staleBeforeMillis,
                )
            }

        // Non-zero when a terminal row exists for this occurrence, whatever jittered
        // instant its row carries: the re-anchor walk keys on the base, so a consumed
        // occurrence is never re-armed (and re-sent) under a freshly rolled jitter.
        suspend fun countFinalizedByBase(
            simId: Int,
            occurrenceBaseMillis: Long,
        ): Int =
            withContext(Dispatchers.IO) {
                simHistoryDao.countFinalizedByBase(simId, occurrenceBaseMillis)
            }

        // The latest SENT row's base (the pre-jitter occurrence time the recompute anchors
        // on), not its delivery instant.
        suspend fun latestSentMillis(simId: Int): Long? =
            withContext(Dispatchers.IO) {
                simHistoryDao.latestSentMillis(simId)
            }

        suspend fun deleteOldHistory(cutoffMillis: Long): Int =
            withContext(Dispatchers.IO) {
                simHistoryDao.deleteOlderThan(cutoffMillis)
            }

        suspend fun clearHistory(simId: Int) {
            withContext(Dispatchers.IO) {
                simHistoryDao.deleteBySimId(simId)
            }
        }

        // Delete the config row only: the schedule and the stats live in this row, so the
        // SIM returns to its new-SIM state. History stays (the audit trail; an open row is
        // finalized by the caller first), unlike deleteSim.
        suspend fun deleteConfig(simId: Int) {
            withContext(Dispatchers.IO) {
                simConfigDao.deleteBySimId(simId)
            }
        }

        // Forget a SIM: the config row plus every history row (open rows included). The
        // caller runs this under the send lock and disarms the armed work right after.
        // One transaction: a crash between the two deletes must not leave the config row
        // armed — the reconciler would re-arm the forgotten SIM and keep sending to the
        // recipient.
        suspend fun deleteSim(simId: Int) {
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    simHistoryDao.deleteAllBySimId(simId)
                    simConfigDao.deleteBySimId(simId)
                }
            }
        }

        // Fresh PENDING rows only: an in-flight occurrence (SENDING, or a retry row —
        // PENDING, retryCount > 0 — owned by the send engine) must survive a save.
        suspend fun clearPendingHistory(simId: Int) {
            withContext(Dispatchers.IO) {
                simHistoryDao.deletePending(simId)
            }
        }

        // Aligns the SIM's pending row to one occurrence: drops stale fresh PENDING rows
        // (never SENDING or a retry row) and inserts only when no open row remains, so a
        // save can never shadow an in-flight occurrence. `occurrenceBaseMillis` is the
        // pre-jitter base the terminal-row guard keys on (see SimHistoryDao).
        suspend fun alignPendingRow(
            simId: Int,
            scheduledForMillis: Long,
            occurrenceBaseMillis: Long,
            recipient: String,
            message: String,
        ) {
            withContext(Dispatchers.IO) {
                simHistoryDao.alignPendingRow(simId, scheduledForMillis, occurrenceBaseMillis, recipient, message)
            }
        }
    }
