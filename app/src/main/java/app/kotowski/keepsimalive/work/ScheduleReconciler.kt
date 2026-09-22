package app.kotowski.keepsimalive.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.data.isSendingStale
import app.kotowski.keepsimalive.schedule.CatchUpResult
import app.kotowski.keepsimalive.schedule.RetryPolicy
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.KeepaliveNotification
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.appLocaleContext
import app.kotowski.keepsimalive.util.appString
import app.kotowski.keepsimalive.util.endConditionReason
import app.kotowski.keepsimalive.util.lateSendGraceMillis
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleReconciler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: KeepaliveRepository,
        private val armer: ScheduleArmer,
        private val prefs: AppPrefs,
        private val advancer: OccurrenceAdvancer,
        private val sendLock: SimSendLock,
    ) {
        // `catchUpAnchors` carries, per SIM, the armed occurrence a clock-change recompute
        // just deleted: the re-anchor anchors its catch-up on the deleted row so the
        // occurrence is re-sent or recorded instead of vanishing without a record. It is
        // the in-memory copy of the anchor the clear half persists at clear time (the
        // persisted one is the crash-survival fallback armFreshSchedule reads when the map
        // is gone).
        // Every SIM is reconciled from a FRESH per-SIM read under that SIM's send lock: a
        // user save landing mid-pass takes the same lock, so its state wins instead of
        // being reverted by the pass's end/align writes, and a row deleted mid-pass (a
        // forget) is simply skipped. No nesting: reconcile itself never takes a send lock,
        // so the pass and the commit path cannot deadlock.
        suspend fun reconcileAll(catchUpAnchors: Map<Int, Long> = emptyMap()) {
            val simIds = repository.getAllConfigs().map { it.simId }
            for (simId in simIds) {
                runCatching {
                    sendLock.withLock(simId) {
                        repository.getConfig(simId)?.let { reconcile(it, catchUpAnchors[simId]) }
                    }
                }.onFailure { e ->
                    Logger.e(
                        "ScheduleReconciler",
                        "simId=$simId reconcile failed: ${e.message}",
                        e,
                    )
                }
            }
            // Exact-alarm wake synced with its gate after the per-SIM pass: re-arm while
            // active (alarms do not survive a reboot, WorkManager work does), cancel every
            // pending alarm while inactive, so a revoke or a turn-off is cleaned even if
            // Settings is never opened.
            syncExactAlarms()
            // Persistent hint: reconcile may have ended a schedule; this pass is also the
            // self-healing backstop (app start, boot, update, dashboard resume, sweep) for
            // a hint that was lost or never shown.
            syncPersistentHint()
            if (prefs.historyRetentionDays > 0) {
                val now = System.currentTimeMillis()
                val cutoff = now - prefs.historyRetentionDays.toLong() * AppConfig.DAY_MS
                val deleted = repository.deleteOldHistory(cutoff)
                if (deleted > 0) {
                    Logger.i("ScheduleReconciler", "deleted $deleted history rows older than ${prefs.historyRetentionDays}d")
                }
            }
        }

        // A timezone change invalidates every armed nextSendAtMillis: the epoch encodes
        // "hour:minute in the zone that was active when it was armed", so the system would
        // keep firing at the same absolute instant — a different wall-clock time in the new
        // zone.
        suspend fun recomputeAfterTimezoneChange() {
            reconcileAll(clearArmedSchedulesForRecompute())
        }

        // The clear half of the recompute: for every enabled armed SIM, persist the armed
        // occurrence it is about to delete (the durable catch-up anchor), then cancel the
        // armed work and clear the schedule state. Returns the in-memory copy of the
        // persisted anchors (the no-crash fast path for the re-arm below).
        //
        // The anchor is written BEFORE the clear and survives a process death up to the
        // re-arm: without it the next trigger would arm only the next future occurrence
        // and the deleted one would vanish without a record (a fresh SIM has no SENT
        // anchor to re-derive it). A crash before the clear is safe: the still-armed state
        // and the stale clock anchor make the next trigger re-run this same recompute.
        internal suspend fun clearArmedSchedulesForRecompute(): Map<Int, Long> {
            val configs = repository.getAllConfigs()
            var cleared = 0
            val catchUpAnchors = mutableMapOf<Int, Long>()
            for (config in configs) {
                if (config.enabled && config.nextSendAtMillis != null) {
                    val active = repository.getActiveHistory(config.simId)
                    if (active != null && active.outcome == SendOutcome.SENDING.name) {
                        // A live attempt owns the row to completion and its result write
                        // re-anchors in the new zone: cancelling would record a possibly
                        // delivered SMS as skipped.
                        Logger.i(
                            "ScheduleReconciler",
                            "simId=${config.simId} in-flight send, recompute deferred to the attempt's result write",
                        )
                        continue
                    }
                    // A retry row is PENDING now: without the retryCount guard it would get
                    // a catch-up anchor at its occurrence time and be deleted below, and the
                    // recompute would re-derive the occurrence as a fresh catch-up (losing
                    // the retry chain). With it, the row survives the clear (deletePending
                    // excludes it) and armFreshSchedule's retry arm restores its time.
                    active
                        ?.takeIf { it.outcome == SendOutcome.PENDING.name && it.retryCount == 0 }
                        ?.let { pending ->
                            prefs.setCatchUpAnchor(config.simId, pending.scheduledForMillis)
                            catchUpAnchors[config.simId] = pending.scheduledForMillis
                        }
                    // Cancel before clearing: the null-next-send branch may arm nothing
                    // (the end condition is reached), and the stale armed work
                    // must not outlive the cleared epoch.
                    armer.cancelSend(config.simId)
                    repository.updateNextSend(config.simId, null)
                    repository.clearPendingHistory(config.simId)
                    cleared++
                }
            }
            if (cleared > 0) {
                Logger.i("ScheduleReconciler", "clock changed (zone ${ZoneId.systemDefault()}), recomputing $cleared armed schedule(s)")
            }
            return catchUpAnchors
        }

        // Arm this SIM's delayed reconcile due the moment a fresh SENDING attempt goes
        // stale, so a dead attempt is resolved then instead of ghosting until an unrelated
        // trigger.
        fun armStaleCheck(
            simId: Int,
            dueAtMillis: Long,
        ) {
            ScheduleSafetyWorker.enqueueStaleCheck(context, simId, dueAtMillis)
        }

        // The persistent "keepalive is running" hint is derived state, shown while any SIM
        // keepalive is enabled (and the "sticky notification" toggle is on). Every call
        // re-reads the flags, so the path that just changed them can refresh it right away
        // instead of waiting for the next reconcileAll pass.
        suspend fun syncPersistentHint() {
            if (prefs.showStickyNotification && repository.getAllConfigs().any { it.enabled }) {
                KeepaliveNotification.show(context.appLocaleContext())
            } else {
                KeepaliveNotification.dismiss(context)
            }
        }

        // Ends the schedule now: end and cancel first so no send run can start. With
        // notify (the default — the engine disabled the schedule in the background) the
        // persistent hint is refreshed right away. The reconciler's own end sites pass
        // notify = false: their caller syncs the hint right after, so an unconditional
        // sync here would be redundant on every path.
        suspend fun endScheduleNow(
            simId: Int,
            notify: Boolean = true,
        ) {
            repository.endSchedule(simId)
            armer.cancelSend(simId)
            if (notify) {
                syncPersistentHint()
            }
        }

        private suspend fun syncExactAlarms() {
            if (armer.exactWakeActive()) {
                armer.rearmAllExactAlarms()
            } else {
                armer.cancelAllExactAlarms()
            }
        }

        // Reconciles one SIM's schedule: computes the next send when it is missing (arming
        // nothing past the end date), aligns the pending history row, and re-arms lost work.
        // The UI runs it under the SIM's send lock (the disabled-branch cleanup and work
        // cancellation must be excluded from a racing send); the persistent-hint sync is a
        // separate call, outside that lock.
        suspend fun reconcileSim(simId: Int) {
            repository.getConfig(simId)?.let { reconcile(it) }
        }

        // `catchUpAnchorMillis` (a clock-change recompute only): the armed occurrence the
        // recompute deleted for this SIM. When present it anchors the catch-up below, so
        // the deleted occurrence is re-sent (within grace) or recorded SKIPPED (past it)
        // instead of vanishing — a fresh SIM (no SENT anchor) has no other path that would
        // re-derive it. It is the in-memory copy of the anchor the clear half persists;
        // armFreshSchedule falls back to the persisted one.
        private suspend fun reconcile(
            config: SimKeepaliveConfig,
            catchUpAnchorMillis: Long? = null,
        ) {
            if (!config.enabled) {
                armer.cancelSend(config.simId)
                // A SENDING row is closed with the guarded in-flight flip, not an
                // unguarded update: this branch also runs lockless (the safety passes),
                // where a fresh row may still have a live owner — on 0 its result write
                // wins and the skip is dropped. A fresh row whose owner the cancel above
                // killed is an attempt cut off mid-flight by the disable, resolved at
                // once — left open it ghosts as "Trying now". The flip, the fresh-row
                // clear and the next-send clear land in one transaction (finalizeOutcome);
                // the SIM is off, a re-enable recomputes from the last send.
                val active = repository.getActiveHistory(config.simId)
                when {
                    active != null && active.outcome == SendOutcome.SENDING.name -> {
                        if (
                            !repository.finalizeOutcome(
                                active.id,
                                SendOutcome.SENDING,
                                SendOutcome.SKIPPED,
                                active.lastAttemptAtMillis ?: active.scheduledForMillis,
                                context.appString(R.string.error_disabled_before_send),
                                active.retryCount,
                                active.firstAttemptAtMillis ?: active.lastAttemptAtMillis ?: active.scheduledForMillis,
                                null,
                                FinalizeAdvance.Disable(config.simId),
                            )
                        ) {
                            Logger.i("ScheduleReconciler", "simId=${config.simId} in-flight row not skipped, row already owned")
                        } else {
                            Logger.w("ScheduleReconciler", "simId=${config.simId} in-flight row while disabled, marked skipped")
                        }
                    }

                    active != null && active.outcome == SendOutcome.PENDING.name -> {
                        // A disabled SIM owns no armed send work, so an open row (a fresh
                        // occurrence or an orphaned retry) ghosts: left open it is a no-op
                        // Retry button or a phantom "send in" countdown. An unconsumed
                        // occurrence keeps its record (a skip is never unexplained).
                        if (
                            !repository.finalizeOutcome(
                                active.id,
                                SendOutcome.PENDING,
                                SendOutcome.SKIPPED,
                                active.lastAttemptAtMillis,
                                context.appString(R.string.error_disabled_before_send),
                                active.retryCount,
                                active.firstAttemptAtMillis,
                                null,
                                FinalizeAdvance.Disable(config.simId),
                            )
                        ) {
                            Logger.i("ScheduleReconciler", "simId=${config.simId} open row not finalized, row already owned")
                        } else {
                            Logger.w("ScheduleReconciler", "simId=${config.simId} open row while disabled, marked skipped")
                        }
                    }

                    else -> {
                        Unit
                    }
                }
                return
            }
            if (config.nextSendAtMillis == null) {
                armFreshSchedule(config, catchUpAnchorMillis)
            } else {
                val nextMillis = config.nextSendAtMillis ?: return
                if (
                    ScheduleCalculator.isEndConditionReached(
                        config,
                        Instant.ofEpochMilli(nextMillis).atZone(ZoneId.systemDefault()),
                    )
                ) {
                    endArmedPastEnd(config, nextMillis)
                    return
                }
                alignArmedSchedule(config)
            }
        }

        // The nextSendAtMillis == null core: resolves a leftover open row (a live SENDING
        // defers to its stale check, a retry row restores its retry time), recomputes the
        // catch-up from the deleted-row or last-send anchor, and re-arms — or ends the
        // schedule when the end condition is already reached. The deleted-row anchor is
        // consumed (and its persisted twin cleared) on every path below, once that path's
        // own writes have landed — a leftover anchor would re-derive the occurrence on a
        // later re-arm.
        private suspend fun armFreshSchedule(
            config: SimKeepaliveConfig,
            catchUpAnchorMillis: Long?,
        ) {
            val zone = ZoneId.systemDefault()
            val nowZdt = ZonedDateTime.now(zone)
            // The crash-survival twin of the in-memory parameter: when the process died
            // between the recompute's clear and this re-arm, the persisted anchor
            // re-derives the deleted occurrence. 0 = unset (the getter's safe default).
            val anchorMillis = (catchUpAnchorMillis ?: prefs.getCatchUpAnchor(config.simId)).takeIf { it > 0L }
            // A save during an in-flight occurrence keeps its row and next-send
            // value, so this branch normally sees no open row; the cases below resolve
            // the reachable leftovers.
            val active = repository.getActiveHistory(config.simId)
            when {
                active != null && active.outcome == SendOutcome.SENDING.name -> {
                    resolveSendingRow(config, active)
                    // The attempt owns the occurrence to completion: its row, not the
                    // anchor, is the record now. Drop the catch-up anchor like the
                    // in-memory path does. The last-sent anchor stays: the attempt's
                    // outcome writes no SENT row on failure, so the rhythm must stay
                    // re-derivable from it after a later history clear.
                    prefs.clearCatchUpAnchor(config.simId)
                    return
                }

                active != null && active.outcome == SendOutcome.PENDING.name && active.retryCount > 0 -> {
                    // The retry owns the occurrence: restore its retry time (a save may
                    // have dropped it).
                    val retryAt =
                        RetryPolicy.computeNextRetryTime(
                            active.retryCount,
                            active.lastAttemptAtMillis ?: active.firstAttemptAtMillis ?: System.currentTimeMillis(),
                        )
                    repository.updateNextSend(config.simId, retryAt)
                    ensureRetryWork(config)
                    // The retry time is durably written and the retry row owns the
                    // occurrence: drop the catch-up anchor like the in-memory path does.
                    // The last-sent anchor stays: the retry never clears it, so a later
                    // history-cleared recompute must still re-derive the rhythm from it.
                    prefs.clearCatchUpAnchor(config.simId)
                    return
                }

                else -> {
                    Unit
                }
            }
            // The anchor is the newest SENT row's occurrence — the same occurrence the
            // engine advances from after a success — so a re-anchor (save, re-enable,
            // clock change) lands on the rhythm the engine already armed. It must never be
            // the actual delivery time: a late retry or late wake would push the next send
            // by the delivery delay. The row is also proof the occurrence went out even
            // when the stored last-send column is stale, so a sent occurrence is never
            // re-armed.
            val lastSentMillis = repository.latestSentMillis(config.simId) ?: 0L
            // History-clear survival anchor: when the user clears history (or retention
            // removes the SENT rows) latestSentMillis is null, but this config column
            // still holds the last sent occurrence's base. Using it re-derives the rhythm
            // the engine already armed — the catch-up starts at the NEXT occurrence, never
            // the anchor itself — instead of firstOccurrence(now) silently dropping
            // elapsed occurrences. 0L stands in for the null and keeps the `> 0` branch
            // reading as unset.
            val lastOccurrenceMillis = config.lastSentOccurrenceMillis ?: 0L
            val catchUp =
                when {
                    // Deleted-row anchor of a clock-change recompute: it wins over the
                    // last-send anchor — walking forward from it re-derives the same
                    // rhythm the last-send anchor would.
                    anchorMillis != null -> {
                        ScheduleCalculator.resolveCatchUp(
                            config,
                            Instant.ofEpochMilli(anchorMillis).atZone(zone),
                            nowZdt,
                            prefs.lateSendGraceMillis,
                        )
                    }

                    // Only an active SIM that sent after its last delete re-anchors on the
                    // last send: a delete newer than the newest SENT row means the SIM was
                    // unconfigured since its last send (the off period is not a miss), so it
                    // falls through to a fresh anchor. A post-delete send outdates the marker
                    // and re-enables this branch.
                    lastSentMillis > 0 && lastSentMillis >= prefs.getRhythmResetAtMillis(config.simId) -> {
                        val anchor =
                            ScheduleCalculator.nextOccurrence(
                                config,
                                ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastSentMillis), zone),
                            )
                        ScheduleCalculator.resolveCatchUp(config, anchor, nowZdt, prefs.lateSendGraceMillis)
                    }

                    lastOccurrenceMillis > 0 -> {
                        // History was cleared but the last-sent-occurrence anchor column
                        // still holds the last sent occurrence's base — re-anchor at the
                        // NEXT occurrence to keep the rhythm (re-arming the base itself
                        // would re-send an occurrence that already went out).
                        val anchor =
                            ScheduleCalculator.nextOccurrence(
                                config,
                                ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastOccurrenceMillis), zone),
                            )
                        ScheduleCalculator.resolveCatchUp(config, anchor, nowZdt, prefs.lateSendGraceMillis)
                    }

                    else -> {
                        CatchUpResult(ScheduleCalculator.firstOccurrence(config, nowZdt))
                    }
                }
            // The missed-occurrence records land before the end check: an end reached at
            // the catch-up's sendAt must not swallow them (a deleted occurrence of a
            // schedule that is ending here is a real miss and gets its row like any
            // other).
            catchUp.skipped.forEach { skipped ->
                // The walk element's true pre-jitter base (ScheduleCalculator.baseOf of
                // the same config the walk ran on) — the guarded insert keys on it.
                advancer.recordMissedOccurrence(
                    config.simId,
                    skipped.toInstant().toEpochMilli(),
                    ScheduleCalculator.baseOf(config, skipped).toInstant().toEpochMilli(),
                    config,
                )
            }
            // The catch-up's sendAt can still point at an already-consumed occurrence — a
            // terminal row for its base exists (an interrupted skip, a disable before the
            // send). Re-arming it would send an occurrence the engine consumed (the row
            // carries a possibly re-rolled jittered instant, so only the base-keyed check
            // sees it). Walk past every consumed occurrence to the first unconsumed one:
            // each step advances at least one period, so the loop terminates. No record is
            // written for the steps: the occurrence is already recorded by its terminal row.
            var sendAt = catchUp.sendAt
            while (
                repository.countFinalizedByBase(
                    config.simId,
                    ScheduleCalculator.baseOf(config, sendAt).toInstant().toEpochMilli(),
                ) > 0
            ) {
                sendAt = ScheduleCalculator.nextOccurrence(config, sendAt)
            }
            if (ScheduleCalculator.isEndConditionReached(config, sendAt)) {
                // No occurrence left (end date passed or send limit reached): end the
                // schedule instead of leaving enabled = 1 with nothing armed. The UI can
                // no longer save an already-ended enabled schedule, so a row reaching
                // here is hand-edited / legacy.
                endScheduleNow(config.simId, notify = false)
                Logger.i("ScheduleReconciler", "simId=${config.simId} end condition reached, disabling")
                // The miss records above carry the deleted occurrence and the schedule is
                // ended: the anchor is fully consumed.
                prefs.clearCatchUpAnchor(config.simId)
                repository.clearLastSentOccurrence(config.simId)
                return
            }
            // The window is capped at the end date: the armed time must never land on a
            // day after it, or the end check would drop the last intended send. The
            // deleted-row anchor resolved to its own armed instant (the walk did not
            // advance) already carries its window jitter, so re-applying withWindow would
            // double-shift it; a walk-advanced sendAt is a clean base.
            val reJitter =
                anchorMillis == null ||
                    sendAt.toInstant().toEpochMilli() != anchorMillis
            val next =
                if (reJitter) {
                    ScheduleCalculator.withWindow(sendAt, config.timeWindowMinutes, config.endDate)
                } else {
                    sendAt
                }
            val nextMillis = next.toInstant().toEpochMilli()
            repository.updateNextSend(config.simId, nextMillis)
            // The base is the armed instant's true pre-jitter base (ScheduleCalculator.baseOf
            // of the same config): exact even when the walk did not advance.
            repository.alignPendingRow(
                config.simId,
                nextMillis,
                ScheduleCalculator.baseOf(config, sendAt).toInstant().toEpochMilli(),
                config.recipientPhone,
                config.message,
            )
            armer.armSend(config.simId)
            // The re-arm is durably written (the Room commit above): only now is the
            // catch-up anchor cleared, so a death in between leaves it and the next trigger
            // re-derives the occurrence from it. The last-sent anchor (the config row) is
            // kept: the re-arm writes a PENDING row, not a SENT one, so clearing it would
            // leave a later history-cleared recompute without a rhythm origin.
            prefs.clearCatchUpAnchor(config.simId)
        }

        // The armed occurrence is past the end condition: the schedule ends
        // here, one-shot. A later re-enable lands in the no-next-send branch,
        // which arms nothing, so the toggle is never fought.
        private suspend fun endArmedPastEnd(
            config: SimKeepaliveConfig,
            nextMillis: Long,
        ) {
            val row = repository.getActiveHistory(config.simId)
            if (row != null && row.outcome == SendOutcome.SENDING.name) {
                val attemptAt = row.lastAttemptAtMillis ?: row.scheduledForMillis
                if (!row.isSendingStale(System.currentTimeMillis())) {
                    // A live attempt owns the row to completion: its result write ends the
                    // schedule atomically with the SENT row when the end condition still
                    // holds — the sent SMS is recorded honestly instead of skipped. A crash
                    // in the meantime is resolved by the stale check armed below.
                    armStaleCheck(config.simId, attemptAt + AppConfig.SENDING_STALE_MS)
                    Logger.i(
                        "ScheduleReconciler",
                        "simId=${config.simId} armed send past end condition with a live attempt, " +
                            "deferring the end to its result write",
                    )
                    return
                }
                // A stale SENDING row has no live owner (the attempt died): close the row
                // and end the schedule in one transaction (a concurrent resolver wins on
                // 0, the skip is dropped and the end lands on its own). The radio call may
                // have gone out already: the occurrence is consumed with the end reason,
                // never re-sent.
                if (
                    !repository.finalizeOutcome(
                        row.id,
                        SendOutcome.SENDING,
                        SendOutcome.SKIPPED,
                        attemptAt,
                        endConditionReason(context.appLocaleContext(), config),
                        row.retryCount,
                        row.firstAttemptAtMillis ?: attemptAt,
                        null,
                        FinalizeAdvance.End(config.simId),
                    )
                ) {
                    Logger.i("ScheduleReconciler", "simId=${config.simId} in-flight row while ending not skipped, row already owned")
                    repository.endSchedule(config.simId)
                } else {
                    Logger.w("ScheduleReconciler", "simId=${config.simId} in-flight row while ending, marked skipped")
                }
                armer.cancelSend(config.simId)
            } else {
                // End and close the row in one transaction when the row belongs to the
                // armed occurrence (a fresh PENDING row carries the armed time, a retry
                // row the original occurrence). Left open, the row ghosts a "trying now"
                // indicator and a re-enable would re-arm it without the end check. The
                // skip carries the end reason: a skip is never unexplained.
                val closeRow = row != null && (row.retryCount > 0 || row.scheduledForMillis == nextMillis)
                val closed =
                    if (!closeRow) {
                        false
                    } else {
                        if (
                            repository.finalizeOutcome(
                                row.id,
                                SendOutcome.PENDING,
                                SendOutcome.SKIPPED,
                                null,
                                endConditionReason(context.appLocaleContext(), config),
                                row.retryCount,
                                row.firstAttemptAtMillis,
                                null,
                                FinalizeAdvance.End(config.simId),
                            )
                        ) {
                            true
                        } else {
                            // 0 rows: the row left PENDING between the read and the write
                            // (a concurrent claim or finalization). Report its actual
                            // state instead of assuming "already finalized".
                            val current = repository.getActiveHistory(config.simId)
                            if (current != null && current.outcome == SendOutcome.SENDING.name) {
                                // A concurrent run claimed it just now: close it with the
                                // guarded in-flight flip like the stale case above — the
                                // guard protects a live owner's result write.
                                if (
                                    repository.finalizeOutcome(
                                        current.id,
                                        SendOutcome.SENDING,
                                        SendOutcome.SKIPPED,
                                        current.lastAttemptAtMillis ?: current.scheduledForMillis,
                                        endConditionReason(context.appLocaleContext(), config),
                                        current.retryCount,
                                        current.firstAttemptAtMillis ?: current.lastAttemptAtMillis ?: current.scheduledForMillis,
                                        null,
                                        FinalizeAdvance.End(config.simId),
                                    )
                                ) {
                                    Logger.w(
                                        "ScheduleReconciler",
                                        "simId=${config.simId} concurrent claim while ending, row marked skipped",
                                    )
                                    true
                                } else {
                                    Logger.i("ScheduleReconciler", "simId=${config.simId} concurrent claim not skipped, row already owned")
                                    false
                                }
                            } else {
                                Logger.i(
                                    "ScheduleReconciler",
                                    "simId=${config.simId} end-condition skip not applied, " +
                                        "row is now ${current?.outcome ?: "closed by a concurrent run"}",
                                )
                                false
                            }
                        }
                    }
                if (!closed) {
                    // No row to close, or the flip lost the race: the end still lands on
                    // its own.
                    repository.endSchedule(config.simId)
                }
                armer.cancelSend(config.simId)
            }
            Logger.i("ScheduleReconciler", "simId=${config.simId} armed send past end condition, disabling")
        }

        // The armed-schedule tail, dispatched by the open row's state: a live SENDING
        // defers to its stale check, a retry row keeps its retry work (aligning would
        // fight it), everything else aligns and re-arms the missing send work.
        private suspend fun alignArmedSchedule(config: SimKeepaliveConfig) {
            val active = repository.getActiveHistory(config.simId)
            when {
                active != null && active.outcome == SendOutcome.SENDING.name -> {
                    resolveSendingRow(config, active)
                    return
                }

                active != null && active.outcome == SendOutcome.PENDING.name && active.retryCount > 0 -> {
                    // A retry in flight: its row is the occurrence anchor,
                    // nextSendAtMillis the retry time. Aligning would fight the retry.
                    ensureRetryWork(config)
                    return
                }

                else -> {
                    alignPendingRow(config)
                    if (hasNoLiveSendWork(config)) {
                        Logger.i("ScheduleReconciler", "simId=${config.simId} send work missing, re-arming")
                        armer.armSend(config.simId)
                    }
                }
            }
        }

        // A SENDING row: a fresh attempt is live — it owns the row to completion (its result
        // writes the next value), so only make sure its stale check is armed; a stale one
        // has no live owner (the process died mid-send) — skip the occurrence, never re-send.
        private suspend fun resolveSendingRow(
            config: SimKeepaliveConfig,
            active: SendHistoryEntity,
        ) {
            val now = System.currentTimeMillis()
            if (active.isSendingStale(now)) {
                advancer.skipInterrupted(
                    config.simId,
                    config,
                    active,
                    Instant.ofEpochMilli(active.scheduledForMillis).atZone(ZoneId.systemDefault()),
                )
            } else {
                armStaleCheck(config.simId, (active.lastAttemptAtMillis ?: active.scheduledForMillis) + AppConfig.SENDING_STALE_MS)
            }
        }

        // The retry work may be gone (a disable/enable cycle cancels it): re-arm it when
        // there is no live work, so a pending retry is never lost.
        private suspend fun ensureRetryWork(config: SimKeepaliveConfig) {
            if (hasNoLiveSendWork(config)) {
                Logger.i("ScheduleReconciler", "simId=${config.simId} retry work missing, re-arming")
                armer.armSend(config.simId)
            }
        }

        private suspend fun alignPendingRow(config: SimKeepaliveConfig) {
            val nextMillis = config.nextSendAtMillis ?: return
            // The base is the armed instant's true pre-jitter base (ScheduleCalculator.baseOf):
            // this only re-aligns the row an earlier engine write already created at that
            // instant (the guarded insert is a no-op on top of it).
            repository.alignPendingRow(
                config.simId,
                nextMillis,
                ScheduleCalculator
                    .baseOf(config, Instant.ofEpochMilli(nextMillis).atZone(ZoneId.systemDefault()))
                    .toInstant()
                    .toEpochMilli(),
                config.recipientPhone,
                config.message,
            )
        }

        // The re-arm gate (the exact-alarm wake's twin, see SendWorkState): a live
        // (ENQUEUED/RUNNING) send work must never be REPLACE-cancelled by a re-arm. The
        // read degrades to "live work" on a failed read (logged by SendWorkState): a wrong
        // re-arm cancels the in-flight send, while a missed re-arm self-heals on the next
        // reconcile. The 2 s bound keeps a stuck work DB from stalling this reconcile.
        private suspend fun hasNoLiveSendWork(config: SimKeepaliveConfig): Boolean =
            withContext(Dispatchers.IO) {
                SendWorkState.hasNoLiveSendWork(
                    WorkManager.getInstance(context),
                    armer.sendWorkName(config.simId),
                    config.simId,
                )
            } ?: false
    }

data class ClockSnapshot(
    val offsetSeconds: Int,
    val zoneId: String,
)

@HiltWorker
class ScheduleSafetyWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val reconciler: ScheduleReconciler,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            try {
                // The flag alone must force the recompute (a same-offset zone change can
                // still have divergent future DST rules). The anchor diff is authoritative
                // on top of it: it catches transitions that happened while the app was
                // closed and no broadcast arrived (launch, boot, update, dashboard resume,
                // sweep) and a second transition between enqueue and run; it holds the
                // zone ID too, so a same-offset change reads stale as well.
                if (inputData.getBoolean(KEY_TIMEZONE_CHANGED, false) || anchorStale(applicationContext)) {
                    // Capture the clock ONCE, before the recompute: the anchor must record the
                    // clock the re-anchored schedules actually use, not whatever the clock
                    // reads when the write happens to land.
                    val snapshot = captureClock()
                    reconciler.recomputeAfterTimezoneChange()
                    // The anchor is written ONLY after a successful recompute, never
                    // optimistically at enqueue time (that would re-introduce the flag-loss
                    // race). A clock change landing after the capture keeps the anchor
                    // stale, so the next sweep re-runs the corrective recompute.
                    recordAnchor(applicationContext, snapshot)
                } else {
                    // A per-SIM stale check carries its SIM id: scoping the run to that SIM
                    // keeps N SIMs going stale at once from enqueueing N global passes (each
                    // re-reads every config, re-arms every exact alarm and re-posts the
                    // hint). The hint sync follows the reconcileSim contract (the caller
                    // syncs after the commit): the per-SIM path may have ended the schedule.
                    val staleCheckSimId = inputData.getInt(KEY_STALE_CHECK_SIM_ID, -1)
                    if (staleCheckSimId >= 0) {
                        reconciler.reconcileSim(staleCheckSimId)
                        reconciler.syncPersistentHint()
                    } else {
                        reconciler.reconcileAll()
                    }
                }
                Result.success()
            } catch (e: CancellationException) {
                // A REPLACE from another enqueueOnce cancels a running instance; the
                // replacing work re-runs the reconcile and the anchor is only recorded on
                // success, so the recompute self-heals. Same pattern as SendWorker.doWork.
                Logger.i("ScheduleSafetyWorker", "reconcile cancelled by a superseding enqueue")
                Result.success()
            } catch (e: Exception) {
                Logger.e("ScheduleSafetyWorker", "reconcile failed: ${e.message}", e)
                Result.retry()
            }

        companion object {
            const val WORK_NAME_PERIODIC = "keepalive_safety_periodic"
            const val WORK_NAME_ONCE = "keepalive_reconcile"

            // Base for a separate unique name: REPLACE must not fight the immediate
            // once-reconcile over one slot, and a delayed stale-check must survive it. The
            // enqueue site appends the per-SIM suffix, so each SIM's check keeps its own
            // due time and one SIM's fresh SENDING row can no longer delay another SIM's
            // stale resolution.
            const val WORK_NAME_STALE_CHECK = "keepalive_stale_check"

            // The flag means "recompute armed schedules after a clock change: zone ID
            // or offset".
            const val KEY_TIMEZONE_CHANGED = "timezone_changed"

            // The stale check's SIM id: at run time doWork scopes a stale-check work to
            // its own SIM (reconcileSim + hint sync) instead of the global reconcileAll.
            const val KEY_STALE_CHECK_SIM_ID = "stale_check_sim_id"

            // Plain (not encrypted) SharedPreferences: the anchor must be readable from
            // static contexts (this companion and the receiver), which cannot reach the DI
            // singleton. ANCHOR_UNSET means "no successful recompute since install/update";
            // real UTC offsets are whole minutes, so -1 can never be a real value.
            internal const val CLOCK_ANCHOR_PREFS = "keepalive_clock_anchor"
            internal const val KEY_ANCHORED_OFFSET_SECONDS = "anchored_offset_seconds"
            internal const val KEY_ANCHORED_ZONE_ID = "anchored_zone_id"
            internal const val ANCHOR_UNSET = -1

            fun currentOffsetSeconds(context: Context): Int =
                ZoneId
                    .systemDefault()
                    .rules
                    .getOffset(Instant.now())
                    .totalSeconds

            fun captureClock(): ClockSnapshot =
                ClockSnapshot(
                    ZoneId
                        .systemDefault()
                        .rules
                        .getOffset(Instant.now())
                        .totalSeconds,
                    ZoneId.systemDefault().id,
                )

            fun anchorStale(context: Context): Boolean {
                val prefs =
                    context
                        .getSharedPreferences(CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
                val storedOffset = prefs.getInt(KEY_ANCHORED_OFFSET_SECONDS, ANCHOR_UNSET)
                val storedZone = prefs.getString(KEY_ANCHORED_ZONE_ID, null)
                return storedOffset == ANCHOR_UNSET ||
                    storedZone == null ||
                    storedOffset != currentOffsetSeconds(context) ||
                    storedZone != ZoneId.systemDefault().id
            }

            // Records the given captured clock as the anchor — the clock the recompute
            // that preceded it actually anchored the schedules in, never a fresh re-read
            // (which would mask a clock change that landed after the recompute).
            fun recordAnchor(
                context: Context,
                snapshot: ClockSnapshot,
            ) {
                context
                    .getSharedPreferences(CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_ANCHORED_OFFSET_SECONDS, snapshot.offsetSeconds)
                    .putString(KEY_ANCHORED_ZONE_ID, snapshot.zoneId)
                    .apply()
            }

            fun enqueuePeriodic(context: Context) {
                val workRequest =
                    PeriodicWorkRequestBuilder<ScheduleSafetyWorker>(AppConfig.SAFETY_SWEEP_INTERVAL_HOURS, TimeUnit.HOURS)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, workRequest)
            }

            fun enqueueOnce(
                context: Context,
                afterTimezoneChange: Boolean = false,
            ) {
                val builder = OneTimeWorkRequestBuilder<ScheduleSafetyWorker>()
                // A stale anchor self-carries the flag: a flag-less enqueue (app start,
                // boot, update, dashboard resume) must not replace a pending flagged work
                // and drop the recompute (the REPLACE policy shares one unique name). The
                // anchor holds the zone ID too, so any clock change keeps it stale.
                if (afterTimezoneChange || anchorStale(context)) {
                    builder.setInputData(workDataOf(KEY_TIMEZONE_CHANGED to true))
                }
                val workRequest = builder.build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, workRequest)
            }

            // Delayed reconcile due when a fresh SENDING attempt goes stale, one unique
            // work per SIM (the per-SIM name suffix): REPLACE refreshes only this SIM's own
            // due time, so one SIM's fresh SENDING row can no longer delay another SIM's
            // stale resolution. A clock change landing before it runs stays a global
            // recompute — the anchor self-carry in doWork.
            fun enqueueStaleCheck(
                context: Context,
                simId: Int,
                dueAtMillis: Long,
            ) {
                val delay = (dueAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        "${WORK_NAME_STALE_CHECK}_$simId",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<ScheduleSafetyWorker>()
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf(KEY_STALE_CHECK_SIM_ID to simId))
                            .build(),
                    )
            }
        }
    }
