package app.kotowski.keepsimalive.work

import android.content.Context
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.appString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

// The occurrence-time derivation shared by the orchestrator and the reconciler.
@Singleton
class OccurrenceAdvancer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: KeepaliveRepository,
        private val armer: ScheduleArmer,
    ) {
        // An attempt was interrupted (the process died mid-send): sending is expensive, so the
        // occurrence is skipped instead of re-sent. Silent by design - no notification.
        // The flip and the advance are one transaction (finalizeOutcome): only the flip
        // winner advances the schedule — a loser's second advance would re-jitter the next
        // occurrence and desync nextSendAtMillis from the pending row.
        //
        // @return true when this call resolved the attempt and advanced the schedule.
        suspend fun skipInterrupted(
            simId: Int,
            config: SimKeepaliveConfig,
            row: SendHistoryEntity,
            occurrence: ZonedDateTime,
        ): Boolean {
            if (
                !repository.finalizeOutcome(
                    row.id,
                    SendOutcome.SENDING,
                    SendOutcome.SKIPPED,
                    row.lastAttemptAtMillis,
                    context.appString(R.string.error_send_interrupted),
                    row.retryCount,
                    row.firstAttemptAtMillis,
                    System.currentTimeMillis() - AppConfig.SENDING_STALE_MS,
                    FinalizeAdvance.AdvanceToNext(
                        config.simId,
                        nextOccurrenceMillis(config, occurrence),
                        nextOccurrenceBaseMillis(config, occurrence),
                        config.recipientPhone,
                        config.message,
                    ),
                )
            ) {
                Logger.i(
                    "OccurrenceAdvancer",
                    "simId=$simId interrupted-skip not applied, the row was concurrently resolved, deferring to the concurrent owner",
                )
                return false
            }
            // The radio may have answered before the death (the SMS then went out): the
            // attempt and occurrence times let a misrecorded skip be matched against the
            // engine's "radio answered" log and the carrier/recipient records.
            val attemptAt = row.lastAttemptAtMillis ?: row.scheduledForMillis
            Logger.w(
                "OccurrenceAdvancer",
                "simId=$simId attempt interrupted (attempt at $attemptAt, occurrence ${occurrence.toInstant().toEpochMilli()}), " +
                    "skipping occurrence; the radio may have answered, check the 'radio answered' log",
            )
            armer.armSend(simId)
            return true
        }

        // One SKIPPED row per missed occurrence, no schedule advance: the caller owns the
        // re-arm. One reason covers both causes: at the scheduled time the process was alive
        // (the work ran late) or it was not. Guarded (see insertSkippedIfNotTerminal): a
        // terminal row for this occurrence's base means it is already consumed.
        //
        // `scheduledForMillis` is the walk element's time and `occurrenceBaseMillis` its
        // true pre-jitter base: the guarded insert keys on it, so a terminal row written by
        // a different arm of the same occurrence is seen by its base.
        suspend fun recordMissedOccurrence(
            simId: Int,
            scheduledForMillis: Long,
            occurrenceBaseMillis: Long,
            config: SimKeepaliveConfig,
        ) {
            val reason = context.appString(R.string.error_missed_occurrence)
            repository.insertSkippedIfNotTerminal(
                simId,
                scheduledForMillis,
                occurrenceBaseMillis,
                reason,
                config.recipientPhone,
                config.message,
            )
        }

        // The pre-jitter base of the next occurrence: the occurrence's identity — the row's
        // occurrenceBaseMillis and the consumed-occurrence guards key on it, never on the
        // delivery instant (the jitter is re-rolled on every arm).
        fun nextOccurrenceBaseMillis(
            config: SimKeepaliveConfig,
            lastOccurrence: ZonedDateTime,
        ): Long = nextOccurrenceBase(config, lastOccurrence).toInstant().toEpochMilli()

        fun nextOccurrenceMillis(
            config: SimKeepaliveConfig,
            lastOccurrence: ZonedDateTime,
        ): Long {
            // Same end-date cap as the first arm: an armed time past the end date would be
            // ended by the next end check before it ever sends (the last send would be lost).
            val next = ScheduleCalculator.withWindow(nextOccurrenceBase(config, lastOccurrence), config.timeWindowMinutes, config.endDate)
            return next.toInstant().toEpochMilli()
        }

        // The shared derivation: base and jittered value come from one computation, so a
        // caller passing both to alignPendingRow can never desync them. The caller's
        // occurrence may have been captured before a clock change, so re-anchor in the
        // current zone so the next occurrence lands on the wall-clock time the schedule
        // means after the change (the same anchoring the recompute pass uses).
        private fun nextOccurrenceBase(
            config: SimKeepaliveConfig,
            lastOccurrence: ZonedDateTime,
        ): ZonedDateTime {
            val last = lastOccurrence.withZoneSameInstant(ZoneId.systemDefault())
            return ScheduleCalculator.nextOccurrence(config, last)
        }
    }
