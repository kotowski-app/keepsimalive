package app.kotowski.keepsimalive.work

import android.Manifest
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.SimInfoFetcher
import app.kotowski.keepsimalive.util.SimNameResolver
import app.kotowski.keepsimalive.util.appLocaleContext
import app.kotowski.keepsimalive.util.appQuantity
import app.kotowski.keepsimalive.util.appString
import app.kotowski.keepsimalive.util.endConditionReason
import app.kotowski.keepsimalive.util.isValidE164
import app.kotowski.keepsimalive.util.lateSendGraceMillis
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SendOrchestrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: KeepaliveRepository,
        private val sender: SmsSender,
        private val armer: ScheduleArmer,
        private val advancer: OccurrenceAdvancer,
        @Named("sending_hold_millis") private val sendingHoldMillis: Long,
        private val prefs: AppPrefs,
        private val permissionManager: PermissionManager,
        private val reconciler: ScheduleReconciler,
        private val sendLock: SimSendLock,
    ) {
        init {
            // A live attempt (radio timeout + display hold) must finish before the SENDING
            // row goes stale, or a healthy send reads as "Send interrupted".
            check(AppConfig.SEND_TIMEOUT_MS + sendingHoldMillis < AppConfig.SENDING_STALE_MS) {
                "SEND_TIMEOUT_MS + sending hold ($sendingHoldMillis ms) must stay below SENDING_STALE_MS"
            }
        }

        suspend fun process(simId: Int) {
            var config = repository.getConfig(simId) ?: return
            if (!config.enabled) {
                // Ghost state (the process died between the UI disable commit and its
                // reconcile): bailing would consume the one-shot work with the armed
                // schedule left unsynchronized.
                Logger.w("SendOrchestrator", "simId=$simId send work fired for a disabled config, triggering a reconcile")
                reconciler.reconcileSim(simId)
                return
            }
            val nextSend = config.nextSendAtMillis
            if (nextSend == null) {
                // Unexpected: a cleared next send means a crash or a race left the schedule
                // unarmed. Trigger the reconciler's recompute instead of waiting for the sweep.
                Logger.w("SendOrchestrator", "simId=$simId send work fired with a cleared next send, triggering a reconcile")
                reconciler.reconcileSim(simId)
                return
            }

            val zone = ZoneId.systemDefault()
            val nowZdt = ZonedDateTime.now(zone)
            val now = nowZdt.toInstant().toEpochMilli()

            // Anchor on the active row's scheduledForMillis (stable across retries), never
            // on nextSendAtMillis: the engine rewrites that one to each retry time.
            val active = repository.getActiveHistory(simId)
            val occurrenceMillis = active?.scheduledForMillis ?: nextSend
            val occurrence = Instant.ofEpochMilli(occurrenceMillis).atZone(zone)

            // Re-read: a save may have landed after the top read, and the engine
            // decisions must run on the latest config.
            val hoisted = repository.getConfig(simId) ?: return
            if (!hoisted.enabled) {
                // Same cleanup as the top-level disable path: the reconciler's disabled
                // branch cancels the work, resolves the open rows and clears the next
                // send — covering a disable whose own cleanup was interrupted.
                Logger.i("SendOrchestrator", "simId=$simId disabled before the engine decisions, skipping occurrence")
                reconciler.reconcileSim(simId)
                return
            }
            if (hoisted.nextSendAtMillis == null) {
                // A save or the reconciler cleared the next send after the top read: trigger
                // the recompute now rather than waiting for the next safety sweep.
                Logger.w("SendOrchestrator", "simId=$simId next send cleared after the pre-flight read, triggering a reconcile")
                reconciler.reconcileSim(simId)
                return
            }
            config = hoisted

            if (handleActiveRow(simId, config, active, occurrence, now)) {
                return
            }

            val retryInFlight =
                active != null &&
                    active.outcome == SendOutcome.PENDING.name &&
                    active.retryCount > 0
            val resolved =
                if (retryInFlight) {
                    CatchUpResult(occurrence, emptyList())
                } else {
                    ScheduleCalculator.resolveCatchUp(config, occurrence, nowZdt, prefs.lateSendGraceMillis)
                }
            // Occurrences inside the grace are always attempted (the grace covers a late
            // wake); only ones past it are consumed as missed.
            if (resolved.skipped.isNotEmpty()) {
                Logger.w(
                    "SendOrchestrator",
                    "simId=$simId catch-up: ${resolved.skipped.size} occurrence(s) past the grace, " +
                        "sending ${resolved.sendAt} instead",
                )
                resolved.skipped.forEach { skippedOccurrence ->
                    val millis = skippedOccurrence.toInstant().toEpochMilli()
                    Logger.w(
                        "SendOrchestrator",
                        "simId=$simId skipping occurrence $millis: ${context.appString(R.string.error_missed_occurrence)}",
                    )
                }
            }
            val sendAt = resolved.sendAt
            // The catch-up re-arm carries the time-window jitter like every other arm
            // site: the walk's sendAt is the clean base, and arming it verbatim would
            // send the first occurrence after a miss exactly on the configured time.
            val sendAtMillis =
                if (resolved.skipped.isNotEmpty()) {
                    ScheduleCalculator
                        .withWindow(sendAt, config.timeWindowMinutes, config.endDate)
                        .toInstant()
                        .toEpochMilli()
                } else {
                    sendAt.toInstant().toEpochMilli()
                }

            // Under the per-SIM lock: the skip records, the re-arm and the end decision
            // are check-then-act, and two overlapping runs must not both write them.
            val stopRun =
                sendLock.withLock(simId) {
                    // A retry can't be pending on an early fire — it fires after its
                    // occurrence — so the save-changed backoff orphans nothing.
                    val inLockConfig =
                        freshConfigOrBackOff(
                            simId,
                            config,
                            active,
                            sendAtMillis,
                            if (now < sendAtMillis) "early fire" else "late run",
                        ) ?: return@withLock true
                    // Rebind to the in-lock read: the writes below must carry the latest
                    // save's fields, not the pre-lock ones.
                    config = inLockConfig
                    // The skip records and the re-arm land in one transaction
                    // (finalizeGraceSkips): a crash can no longer leave a recorded skip
                    // with an un-advanced schedule.
                    if (resolved.skipped.isNotEmpty()) {
                        val anchor =
                            active?.takeIf { a ->
                                resolved.skipped.any { s -> s.toInstant().toEpochMilli() == a.scheduledForMillis }
                            }
                        val missed =
                            resolved
                                .skipped
                                .map { it.toInstant().toEpochMilli() }
                                .filterNot { millis -> anchor != null && millis == anchor.scheduledForMillis }
                        if (
                            !repository.finalizeGraceSkips(
                                config,
                                anchor,
                                missed,
                                context.appString(R.string.error_missed_occurrence),
                                sendAtMillis,
                            )
                        ) {
                            Logger.i("SendOrchestrator", "simId=$simId grace skip not applied, row already finalized")
                        }
                    } else if (now < sendAtMillis) {
                        // No terminal write here: a plain re-alignment (the guarded insert
                        // is a no-op on top of the row that already holds that instant).
                        repository.updateNextSend(simId, sendAtMillis)
                        repository.alignPendingRow(
                            simId,
                            sendAtMillis,
                            ScheduleCalculator.baseOf(config, sendAt).toInstant().toEpochMilli(),
                            config.recipientPhone,
                            config.message,
                        )
                    }
                    // Judge retries at the send time, not the original occurrence: a
                    // retry re-armed past the end date must end the schedule, not send
                    // one SMS past it.
                    val endCheckAt = if (retryInFlight) nowZdt else sendAt
                    if (ScheduleCalculator.isEndConditionReached(config, endCheckAt)) {
                        // Re-read under the lock: finalizeGraceSkips may have just created
                        // the row at sendAt, which the pre-lock `active` no longer names.
                        val live = repository.getActiveHistory(simId)
                        if (live != null && live.scheduledForMillis == sendAtMillis) {
                            // The skip carries the end reason (a skip is never
                            // unexplained); the flip and the end land together
                            // (finalizeOutcome).
                            if (
                                !repository.finalizeOutcome(
                                    live.id,
                                    SendOutcome.PENDING,
                                    SendOutcome.SKIPPED,
                                    null,
                                    endConditionReason(context.appLocaleContext(), config),
                                    live.retryCount,
                                    live.firstAttemptAtMillis,
                                    null,
                                    FinalizeAdvance.End(simId),
                                )
                            ) {
                                Logger.i("SendOrchestrator", "simId=$simId end-condition skip not applied, row already finalized")
                                // The flip lost the race: the end still lands on its own.
                                reconciler.endScheduleNow(simId)
                            } else {
                                armer.cancelSend(simId)
                                reconciler.syncPersistentHint()
                            }
                        } else {
                            // Disabled in the background: endScheduleNow refreshes the
                            // persistent hint right away, not on the next dashboard visit.
                            reconciler.endScheduleNow(simId)
                        }
                        Logger.i("SendOrchestrator", "simId=$simId end condition reached, ending without send")
                        return@withLock true
                    }
                    if (now < sendAtMillis) {
                        Logger.i("SendOrchestrator", "simId=$simId work fired early, re-arming for next send")
                        // Last action on purpose: the REPLACE self-cancels this worker,
                        // and anything after it that suspends could be skipped by that
                        // cancellation.
                        armer.armSend(simId)
                        return@withLock true
                    }
                    false
                }
            if (stopRun) {
                return
            }
            attemptOccurrence(simId, config, active, sendAt, sendAtMillis, now)
        }

        // The in-flight row's tri-state gate: a stale SENDING or an expired retry window
        // ends this run; an open retry or no open row falls through to the catch-up.
        private suspend fun handleActiveRow(
            simId: Int,
            config: SimKeepaliveConfig,
            active: SendHistoryEntity?,
            occurrence: ZonedDateTime,
            now: Long,
        ): Boolean {
            when {
                active != null && active.outcome == SendOutcome.SENDING.name -> {
                    // A previous attempt is still in flight: once stale the process died
                    // mid-send, so skip (no re-send — SMS is expensive).
                    if (active.isSendingStale(now)) {
                        advancer.skipInterrupted(simId, config, active, occurrence)
                    } else {
                        // Arm a stale check: without it a dead attempt would ghost
                        // "Trying now" until the next unrelated trigger.
                        val attemptAt = active.lastAttemptAtMillis ?: active.scheduledForMillis
                        reconciler.armStaleCheck(simId, attemptAt + AppConfig.SENDING_STALE_MS)
                    }
                    return true
                }

                active != null && active.outcome == SendOutcome.PENDING.name && active.retryCount > 0 -> {
                    // Retries skip the late-send grace (same occurrence): only the retry
                    // window from the first attempt bounds them.
                    val firstAttempt = active.firstAttemptAtMillis ?: now
                    if (RetryPolicy.isExpiredByFirstAttempt(firstAttempt, now)) {
                        Logger.w("SendOrchestrator", "simId=$simId retry window expired, failing occurrence")
                        // No attempt happened here: keep the last attempt time so the UI can
                        // show "Tried at". The count includes the pending retry that never
                        // runs, hence -1 (a hand-edited 0 floors at 0).
                        if (
                            !repository.finalizeOutcome(
                                active.id,
                                SendOutcome.PENDING,
                                SendOutcome.FAILED,
                                active.lastAttemptAtMillis ?: firstAttempt,
                                context.appString(R.string.error_retry_period_exceeded),
                                (active.retryCount - 1).coerceAtLeast(0),
                                firstAttempt,
                                null,
                                FinalizeAdvance.AdvanceToNext(
                                    simId,
                                    advancer.nextOccurrenceMillis(config, occurrence),
                                    advancer.nextOccurrenceBaseMillis(config, occurrence),
                                    config.recipientPhone,
                                    config.message,
                                ),
                            )
                        ) {
                            Logger.i("SendOrchestrator", "simId=$simId retry-window expiry not applied, row already finalized")
                            return true
                        }
                        // Last action on purpose (see handleSuccess); the prefs write after
                        // it is non-suspending.
                        armer.armSend(simId)
                        prefs.setSimNotPresentFailures(simId, 0)
                        return true
                    }
                }

                else -> {
                    Unit
                }
            }
            return false
        }

        // Final re-read before the radio: the send must reflect the latest fields,
        // and a disable that landed late must stop it.
        private suspend fun attemptOccurrence(
            simId: Int,
            config: SimKeepaliveConfig,
            active: SendHistoryEntity?,
            sendAt: ZonedDateTime,
            sendAtMillis: Long,
            now: Long,
        ) {
            // Shadowed as a var: the re-reads below rebind it to the latest save.
            var config = config
            val fresh = repository.getConfig(simId) ?: return
            if (!fresh.enabled) {
                if (active != null && active.scheduledForMillis == sendAtMillis) {
                    // A concurrent run may have sent right before the disable landed; the
                    // conditional write must not clobber a SENT row.
                    if (
                        repository.finalizeOccurrence(
                            active.id,
                            SendOutcome.SKIPPED,
                            null,
                            context.appString(R.string.error_disabled_before_send),
                            active.retryCount,
                            active.firstAttemptAtMillis,
                        ) == 0
                    ) {
                        Logger.i("SendOrchestrator", "simId=$simId disable-skip not applied, row already finalized")
                    }
                }
                Logger.i("SendOrchestrator", "simId=$simId disabled before the radio call, skipping occurrence")
                armer.cancelSend(simId)
                return
            }
            config = fresh

            // Two runs for one SIM can overlap and must not both reach the radio: the
            // per-SIM lock plus the atomic claim below keep exactly one attempt live (the
            // lock also keeps the UI funnels out — see SimSendLock). A REPLACE cancels a
            // running worker (WorkManager 2.9.0), so the self re-arms stay the last action
            // of a run and doWork swallows the resulting CancellationException.
            sendLock.withLock(simId) {
                // A reset leaves the config enabled, so a guarded insert would re-create
                // the row — the cleared-schedule bail must catch it. Exception to the
                // save-changed backoff: a pending retry of this occurrence is engine-owned,
                // and backing off would strand its retry until the next safety sweep.
                val inLockConfig =
                    freshConfigOrBackOff(simId, config, active, sendAtMillis, "late run") ?: return@withLock
                // Rebind to the in-lock read: everything below must reflect the latest
                // save — a stale send would go to the user's old number otherwise.
                config = inLockConfig
                // A concurrent run may have created or claimed the row in the meantime.
                val live = repository.getActiveHistory(simId)
                val row =
                    when {
                        live != null && live.scheduledForMillis == sendAtMillis -> {
                            // A concurrent run owns the live attempt for this occurrence.
                            if (live.outcome == SendOutcome.SENDING.name) {
                                Logger.i("SendOrchestrator", "simId=$simId concurrent run owns the occurrence, backing off")
                                null
                            } else {
                                live
                            }
                        }

                        else -> {
                            // A terminal row for this occurrence blocks the insert by its
                            // base guard (SimHistoryDao.insertPendingIfNoOpenRow), so a
                            // consumed occurrence is never re-created (and re-sent) by a
                            // stale or concurrent fire: the run ends below without sending
                            // or writing. Otherwise the row is created only when no open
                            // row remains (stale fresh PENDING is dropped, SENDING and
                            // retry rows are never shadowed).
                            repository.alignPendingRow(
                                simId,
                                sendAtMillis,
                                ScheduleCalculator.baseOf(config, sendAt).toInstant().toEpochMilli(),
                                config.recipientPhone,
                                config.message,
                            )
                            repository.getActiveHistory(simId)?.takeIf { it.scheduledForMillis == sendAtMillis }
                        }
                    }
                if (row == null) {
                    // The align insert was refused (the base guard saw a terminal row for
                    // this occurrence) or another open row owns the SIM: a stale or
                    // concurrent fire ends here, no send, no write.
                    Logger.i("SendOrchestrator", "simId=$simId no open row for this occurrence after the align, backing off")
                    return@withLock
                }
                row.let { r ->
                    val firstAttempt = r.firstAttemptAtMillis ?: now
                    // Checked here, not at the top of process: an early fire must still
                    // re-arm, and a permission granted by send time must send.
                    when {
                        !permissionManager.isGranted(Manifest.permission.SEND_SMS) -> {
                            skipBeforeSend(
                                simId,
                                config,
                                r,
                                sendAt,
                                context.appString(R.string.error_no_permission_at_send),
                            )
                        }

                        !permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE) -> {
                            skipBeforeSend(
                                simId,
                                config,
                                r,
                                sendAt,
                                context.appString(R.string.error_no_phone_permission),
                            )
                        }

                        !SimInfoFetcher.isSimPresent(context, simId) -> {
                            val consecutiveFailures = prefs.getSimNotPresentFailures(simId) + 1
                            prefs.setSimNotPresentFailures(simId, consecutiveFailures)

                            if (consecutiveFailures >= AppConfig.MAX_SIM_NOT_PRESENT_FAILURES) {
                                // Threshold reached: auto-disable breaks the infinite
                                // failure loop of a removed SIM. The flip, the disable
                                // and the schedule clear land together (finalizeOutcome).
                                if (
                                    !repository.finalizeOutcome(
                                        r.id,
                                        SendOutcome.PENDING,
                                        SendOutcome.FAILED,
                                        now,
                                        context.appQuantity(
                                            R.plurals.error_sim_not_present_auto_disabled,
                                            AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                                            AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                                        ),
                                        r.retryCount,
                                        firstAttempt,
                                        null,
                                        FinalizeAdvance.AutoDisable(config),
                                    )
                                ) {
                                    Logger.i("SendOrchestrator", "simId=$simId auto-disable not applied, row already finalized")
                                } else {
                                    armer.cancelSend(simId)
                                    prefs.setSimNotPresentFailures(simId, 0)
                                    Logger.w(
                                        "SendOrchestrator",
                                        "simId=$simId auto-disabled after $consecutiveFailures consecutive SIM-not-present failures",
                                    )
                                    // Enabled state changed in the background: refresh
                                    // the persistent hint right away.
                                    reconciler.syncPersistentHint()
                                    // Disabled without the user in the app: post the
                                    // alert so the disable is visible.
                                    KeepaliveNotification.notifyAutoDisabled(context.appLocaleContext(), simLabel(simId))
                                }
                            } else {
                                // "Not present" can be transient (flapping SIM), so retry with
                                // backoff: a flapper recovers, a removed SIM hits the
                                // threshold above.
                                val reason = context.appString(R.string.error_sim_not_present)
                                val newRetryCount = r.retryCount + 1
                                // The row stays open (PENDING, retryCount + 1), not
                                // terminal: the retry is the same occurrence
                                // re-attempted, and the PENDING guard keeps the "already
                                // finalized" refusal semantics.
                                if (
                                    repository.finalizeOccurrence(
                                        r.id,
                                        SendOutcome.PENDING,
                                        now,
                                        reason,
                                        newRetryCount,
                                        firstAttempt,
                                    ) == 0
                                ) {
                                    Logger.i("SendOrchestrator", "simId=$simId sim-not-present retry not applied, row already finalized")
                                } else {
                                    armRetry(simId, newRetryCount, now, reason)
                                }
                            }
                        }

                        !isValidE164(config.recipientPhone) -> {
                            failBeforeSend(
                                simId,
                                config,
                                r,
                                firstAttempt,
                                sendAt,
                                now,
                                context.appString(R.string.error_invalid_recipient),
                            )
                        }

                        config.message.isBlank() -> {
                            // Structural defect: the editor cannot save an empty message, so
                            // only a legacy or tampered row reaches here — fail immediately
                            // instead of burning the retry window.
                            failBeforeSend(
                                simId,
                                config,
                                r,
                                firstAttempt,
                                sendAt,
                                now,
                                context.appString(R.string.error_empty_message),
                            )
                        }

                        config.message.length > AppConfig.SMS_MAX_CHARS -> {
                            // Structural defect: the editor caps the message at the
                            // single-segment limit, so only a legacy or tampered row reaches
                            // here — fail immediately instead of letting the radio truncate
                            // device-dependently.
                            Logger.w(
                                "SendOrchestrator",
                                "simId=$simId stored message is ${config.message.length} chars, over the ${AppConfig.SMS_MAX_CHARS} single-segment limit, failing occurrence",
                            )
                            failBeforeSend(
                                simId,
                                config,
                                r,
                                firstAttempt,
                                sendAt,
                                now,
                                context.appString(R.string.error_message_too_long),
                            )
                        }

                        else -> {
                            // Presence confirmed: a SIM back in the slot breaks the
                            // not-present streak.
                            prefs.setSimNotPresentFailures(simId, 0)
                            // Atomic claim: exactly one concurrent run reaches the radio.
                            // A death in between leaves a SENDING row that the stale check
                            // below resolves (no re-send).
                            // Fresh timestamp at claim time: `now` was captured at the top
                            // of process() and may be seconds old by here, skewing the claim
                            // stamp and all downstream "tried at" / "sent at" display values.
                            val claimAt = System.currentTimeMillis()
                            val firstAttempt = r.firstAttemptAtMillis ?: claimAt
                            if (repository.claimOccurrence(r.id, claimAt, firstAttempt) == 0) {
                                Logger.i("SendOrchestrator", "simId=$simId occurrence already claimed by another run, backing off")
                            } else {
                                // The claim owner arms the stale check itself, before the radio
                                // call: a process that dies mid-send arms nothing otherwise,
                                // and the orphaned row would wait for the next unrelated
                                // trigger. A finished send finds a finalized row, so the check
                                // is a no-op.
                                reconciler.armStaleCheck(simId, claimAt + AppConfig.SENDING_STALE_MS)
                                // Pre-radio re-validation while the lock is held: a stall of
                                // SENDING_STALE_MS after the claim lets the stale check resolve
                                // the row and advance the schedule while this attempt is
                                // parked. Any state other than this occurrence's own fresh
                                // SENDING attempt aborts (no duplicate SMS).
                                val result =
                                    sender.send(
                                        simId,
                                        config.recipientPhone,
                                        config.message,
                                    ) {
                                        val live = repository.getActiveHistory(simId)
                                        live != null &&
                                            live.id == r.id &&
                                            live.outcome == SendOutcome.SENDING.name &&
                                            !live.isSendingStale(System.currentTimeMillis())
                                    }
                                // The row stays SENDING until the result write after the hold:
                                // a death in that window resolves the occurrence as an
                                // interrupted skip, so the radio answer time and result are
                                // logged — a misrecorded skip is then diagnosable against
                                // carrier/recipient records.
                                Logger.i(
                                    "SendOrchestrator",
                                    "simId=$simId radio answered at ${System.currentTimeMillis()} ($result), " +
                                        "writing the result after the $sendingHoldMillis ms display hold",
                                )
                                // Hold so "Trying now" is actually visible; the radio is done
                                // by the time it ends.
                                if (sendingHoldMillis > 0) {
                                    delay(sendingHoldMillis)
                                }

                                when (result) {
                                    is SmsSendResult.Success -> {
                                        handleSuccess(config, r, firstAttempt, sendAt, claimAt, simId)
                                    }

                                    is SmsSendResult.Failure -> {
                                        handleFailure(config, r, firstAttempt, sendAt, claimAt, simId, result)
                                    }

                                    SmsSendResult.Superseded -> {
                                        // The radio was not called: the stale check took
                                        // ownership while this attempt was stalled — no
                                        // write, no re-arm, only the log.
                                        Logger.w(
                                            "SendOrchestrator",
                                            "simId=$simId attempt superseded before the radio call, deferring to the concurrent owner",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Crash recovery, called from [SendWorker] when [process] throws: WorkManager
         * never re-runs a failed one-time work, so the occurrence needs a new owner
         * before the work reports. The owner is chosen by the row state the crash left:
         *
         * - SENDING: the radio call may have gone out — no re-send, the stale check
         *   resolves the row.
         * - PENDING (including a retry row): the attempt never reached the claim — routed
         *   into the retry engine with backoff.
         * - no open row: the reconciler recomputes.
         *
         * @return true when a new owner was established; false when the recovery could
         *   not run (the work fails and the next reconcile trigger recovers it).
         */
        suspend fun recoverFromException(simId: Int): Boolean =
            runCatching {
                val now = System.currentTimeMillis()
                val active = repository.getActiveHistory(simId)
                val reason = context.appString(R.string.error_send_failed)
                when (active?.outcome) {
                    SendOutcome.SENDING.name -> {
                        // The radio call may have gone out — no re-send. A fresh
                        // row is re-checked when it goes stale.
                        val attemptAt = active.lastAttemptAtMillis ?: active.scheduledForMillis
                        reconciler.armStaleCheck(simId, attemptAt + AppConfig.SENDING_STALE_MS)
                        Logger.w(
                            "SendOrchestrator",
                            "simId=$simId crash recovery: in-flight row left to the stale check",
                        )
                        true
                    }

                    SendOutcome.PENDING.name -> {
                        // No SMS left the radio: a plain failed attempt, same
                        // handover as a generic radio failure.
                        val firstAttempt = active.firstAttemptAtMillis ?: now
                        val newRetryCount = active.retryCount + 1
                        if (
                            repository.finalizeOccurrence(
                                active.id,
                                SendOutcome.PENDING,
                                now,
                                reason,
                                newRetryCount,
                                firstAttempt,
                            ) != 0
                        ) {
                            Logger.w(
                                "SendOrchestrator",
                                "simId=$simId crash recovery: attempt crashed before the claim, retry #$newRetryCount armed with backoff",
                            )
                            armRetry(simId, newRetryCount, now, reason)
                        } else {
                            Logger.i(
                                "SendOrchestrator",
                                "simId=$simId crash recovery: row already owned by a concurrent run, deferring to the reconciler",
                            )
                            reconciler.reconcileSim(simId)
                        }
                        true
                    }

                    else -> {
                        // The occurrence was consumed (the advance or re-arm may be
                        // missing) or the row was never aligned.
                        Logger.w(
                            "SendOrchestrator",
                            "simId=$simId crash recovery: no open row, deferring to the reconciler",
                        )
                        reconciler.reconcileSim(simId)
                        true
                    }
                }
            }.getOrElse { e ->
                // The recovery needs the same storage the crash came from.
                Logger.e("SendOrchestrator", "simId=$simId crash recovery failed: ${e.message}", e)
                false
            }

        // True when this run is the pending retry of its own occurrence. The pre-lock
        // read is stable for this purpose: only the engine creates retry rows (PENDING,
        // retryCount > 0), under the same per-SIM lock — a save landing in the window
        // keeps a pending retry's row and time, so backing off would strand the retry.
        private fun retryOccurrenceInFlight(
            active: SendHistoryEntity?,
            sendAtMillis: Long,
        ): Boolean =
            active != null &&
                active.outcome == SendOutcome.PENDING.name &&
                active.retryCount > 0 &&
                active.scheduledForMillis == sendAtMillis

        // The in-lock re-read shared by the early-fire and late-run sections: a cleared
        // schedule (delete/forget/disable/reset) must not be resurrected by a late fire,
        // and a save that changed the schedule pre-lock owns the future — back off and drop
        // the old occurrence (when the new one is now, the save's own re-arm sends it).
        // Exception: a pending retry of this occurrence (engine-owned, see
        // retryOccurrenceInFlight).
        //
        // @return the in-lock config to rebind to, or null when this run must back off.
        private suspend fun freshConfigOrBackOff(
            simId: Int,
            preLock: SimKeepaliveConfig,
            active: SendHistoryEntity?,
            sendAtMillis: Long,
            phase: String,
        ): SimKeepaliveConfig? {
            val liveConfig = repository.getConfig(simId)
            if (liveConfig == null || !liveConfig.enabled || liveConfig.nextSendAtMillis == null) {
                Logger.i("SendOrchestrator", "simId=$simId $phase against a cleared schedule, backing off")
                return null
            }
            if (!liveConfig.sameSchedule(preLock) && !retryOccurrenceInFlight(active, sendAtMillis)) {
                Logger.i("SendOrchestrator", "simId=$simId $phase against a save-changed schedule, backing off")
                return null
            }
            return liveConfig
        }

        private suspend fun handleSuccess(
            config: SimKeepaliveConfig,
            row: SendHistoryEntity,
            firstAttempt: Long,
            sendAt: ZonedDateTime,
            now: Long,
            simId: Int,
        ) {
            // Not-present streak already reset at the claim: a success can only
            // happen through it.
            val newCount = config.sendCount + 1
            val ending = !ScheduleCalculator.hasMoreOccurrence(config, newCount, sendAt)
            // Computed once: nextOccurrenceMillis re-rolls the time-window jitter on
            // every call.
            val nextMillis = if (ending) null else advancer.nextOccurrenceMillis(config, sendAt)
            val nextBase = if (ending) null else advancer.nextOccurrenceBaseMillis(config, sendAt)
            // Atomic (see finalizeOutcome): the SENT row, the success counters, the
            // last-sent-occurrence anchor and the end or next open row land together, so a
            // crash can't leave a SENT row with an un-advanced schedule. The anchor is the
            // row's base, not its delivery instant: the recompute re-derives the rhythm
            // from it, which needs the exact occurrence identity.
            if (
                !repository.finalizeOutcome(
                    row.id,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    now,
                    null,
                    row.retryCount,
                    firstAttempt,
                    null,
                    FinalizeAdvance.Success(
                        simId,
                        row.occurrenceBaseMillis,
                        newCount,
                        nextMillis,
                        nextBase,
                        config.recipientPhone,
                        config.message,
                    ),
                )
            ) {
                // The stale check resolved the stalled attempt and advanced the schedule:
                // defer to the concurrent owner.
                Logger.w(
                    "SendOrchestrator",
                    "simId=$simId success write not applied, the row was concurrently finalized, deferring to the concurrent owner",
                )
                return
            }
            if (ending) {
                Logger.i("SendOrchestrator", "simId=$simId (${simLabel(simId)}) keepalive ended after $newCount sends")
                // The engine just ended this keepalive: refresh the persistent hint here,
                // not on the next dashboard visit.
                reconciler.syncPersistentHint()
                return
            }
            val next = checkNotNull(nextMillis)
            Logger.i("SendOrchestrator", "simId=$simId (${simLabel(simId)}) sent, next in ${next - now}ms")
            // Last action on purpose: the REPLACE self-cancels this worker, and anything
            // after it that suspends could be skipped by that cancellation.
            armer.armSend(simId)
        }

        private suspend fun handleFailure(
            config: SimKeepaliveConfig,
            row: SendHistoryEntity,
            firstAttempt: Long,
            sendAt: ZonedDateTime,
            now: Long,
            simId: Int,
            result: SmsSendResult.Failure,
        ) {
            if (result.permanent) {
                // The flip is guarded like the success write: the stale check of a stalled
                // attempt may have resolved the row and advanced the schedule while the
                // radio result was in flight — deferring keeps its outcome.
                if (
                    !repository.finalizeOutcome(
                        row.id,
                        SendOutcome.SENDING,
                        SendOutcome.FAILED,
                        now,
                        result.reason,
                        row.retryCount,
                        firstAttempt,
                        null,
                        FinalizeAdvance.AdvanceToNext(
                            simId,
                            advancer.nextOccurrenceMillis(config, sendAt),
                            advancer.nextOccurrenceBaseMillis(config, sendAt),
                            config.recipientPhone,
                            config.message,
                        ),
                    )
                ) {
                    Logger.w(
                        "SendOrchestrator",
                        "simId=$simId permanent failure write not applied, the row was concurrently finalized, deferring to the concurrent owner",
                    )
                    return
                }
                Logger.w("SendOrchestrator", "simId=$simId permanent failure: ${result.reason}")
                // Last action on purpose (see handleSuccess).
                armer.armSend(simId)
                return
            }
            // A disable may have landed mid-attempt: the radio ran, but a retry must not
            // be armed for a SIM the user just turned off.
            val freshConfig = repository.getConfig(simId)
            if (freshConfig != null && !freshConfig.enabled) {
                if (
                    repository.finalizeSending(
                        row.id,
                        SendOutcome.SKIPPED,
                        now,
                        context.appString(R.string.error_disabled_before_send),
                        row.retryCount,
                        firstAttempt,
                        null,
                    ) == 0
                ) {
                    Logger.i(
                        "SendOrchestrator",
                        "simId=$simId disable-skip write not applied, the row was concurrently finalized, deferring to the concurrent owner",
                    )
                    return
                }
                Logger.i("SendOrchestrator", "simId=$simId disabled while the attempt was in flight, skipping occurrence")
                return
            }
            Logger.w(
                "SendOrchestrator",
                "simId=$simId send failed: ${result.reason} retryCount=${row.retryCount} firstAttempt=$firstAttempt",
            )
            val newRetryCount = row.retryCount + 1
            // The flip is the guarded SENDING->PENDING one (the row stays open as the
            // retry): a retry armed for a row the stale check already resolved would be
            // re-claimed and re-sent.
            if (
                repository.finalizeSending(row.id, SendOutcome.PENDING, now, result.reason, newRetryCount, firstAttempt, null) == 0
            ) {
                Logger.w(
                    "SendOrchestrator",
                    "simId=$simId retry write not applied, the row was concurrently finalized, deferring to the concurrent owner",
                )
                return
            }
            armRetry(simId, newRetryCount, now, result.reason)
        }

        private suspend fun armRetry(
            simId: Int,
            newRetryCount: Int,
            now: Long,
            reason: String,
        ) {
            val retryAt = RetryPolicy.computeNextRetryTime(newRetryCount, now)
            Logger.i("SendOrchestrator", "simId=$simId retry #$newRetryCount armed for $retryAt (reason: $reason)")
            repository.updateNextSend(simId, retryAt)
            // Last action on purpose: the REPLACE self-cancels this worker, and anything
            // after it that suspends could be skipped by that cancellation.
            armer.armSend(simId)
        }

        // A pre-flight problem that makes sending impossible right now (missing
        // permission): consume the occurrence as SKIPPED so the armed job is not lost
        // silently.
        private suspend fun skipBeforeSend(
            simId: Int,
            config: SimKeepaliveConfig,
            row: SendHistoryEntity,
            lastOccurrence: ZonedDateTime,
            reason: String,
        ) {
            // On a 0-result a concurrent run owns the occurrence: back off without
            // advancing again.
            if (
                !repository.finalizeOutcome(
                    row.id,
                    SendOutcome.PENDING,
                    SendOutcome.SKIPPED,
                    null,
                    reason,
                    row.retryCount,
                    row.firstAttemptAtMillis,
                    null,
                    FinalizeAdvance.AdvanceToNext(
                        simId,
                        advancer.nextOccurrenceMillis(config, lastOccurrence),
                        advancer.nextOccurrenceBaseMillis(config, lastOccurrence),
                        config.recipientPhone,
                        config.message,
                    ),
                )
            ) {
                Logger.i("SendOrchestrator", "simId=$simId pre-flight skip not applied, row already finalized")
                return
            }
            Logger.w("SendOrchestrator", "simId=$simId occurrence skipped before send: $reason")
            // Last action on purpose (see handleSuccess).
            armer.armSend(simId)
        }

        // A pre-flight problem that cannot heal within the retry window (invalid
        // recipient, empty message): fail permanently instead of burning it.
        private suspend fun failBeforeSend(
            simId: Int,
            config: SimKeepaliveConfig,
            row: SendHistoryEntity,
            firstAttempt: Long,
            lastOccurrence: ZonedDateTime,
            now: Long,
            reason: String,
        ) {
            // On a 0-result the concurrent owner advances the schedule, not this run.
            if (
                !repository.finalizeOutcome(
                    row.id,
                    SendOutcome.PENDING,
                    SendOutcome.FAILED,
                    now,
                    reason,
                    row.retryCount,
                    firstAttempt,
                    null,
                    FinalizeAdvance.AdvanceToNext(
                        simId,
                        advancer.nextOccurrenceMillis(config, lastOccurrence),
                        advancer.nextOccurrenceBaseMillis(config, lastOccurrence),
                        config.recipientPhone,
                        config.message,
                    ),
                )
            ) {
                Logger.i("SendOrchestrator", "simId=$simId pre-flight failure not applied, row already finalized")
                return
            }
            Logger.w("SendOrchestrator", "simId=$simId occurrence failed before send (no retry): $reason")
            // Last action on purpose (see handleSuccess).
            armer.armSend(simId)
        }

        // The name for the logs and the auto-disable notification: the same chain as the
        // UI titles, so an absent SIM's notification title stays i18n.
        private fun simLabel(simId: Int): String = SimNameResolver.resolveDisplayName(context.appLocaleContext(), simId)
    }

@HiltWorker
class SendWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val orchestrator: SendOrchestrator,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            // SimSendLock is an in-process Mutex: exclusion against the UI funnels breaks
            // silently if the worker ever runs in a separate process, so pin the
            // default-executor premise. A violation fails the work instead of throwing
            // out of doWork.
            SendProcessGate
                .processViolation(applicationContext.packageName, SendProcessGate.currentProcessName())
                ?.let { refusal ->
                    Logger.e("SendWorker", refusal)
                    return Result.failure()
                }
            val simId = inputData.getInt(SIM_ID_KEY, -1)
            if (simId < 0) {
                Logger.w("SendWorker", "work has no simId in its input data, failing")
                return Result.failure()
            }
            Logger.i("SendWorker", "started simId=$simId")
            return try {
                orchestrator.process(simId)
                Result.success()
            } catch (e: CancellationException) {
                // Expected when a disable cancels the work mid-send: the reconciler's
                // disabled branch resolves the row.
                Result.success()
            } catch (e: Exception) {
                Logger.e("SendWorker", "process failed simId=$simId: ${e.message}", e)
                // WorkManager never re-runs a failed one-time work, so the occurrence
                // needs a new owner before the result is reported. If the recovery can't
                // run (storage down), the work fails and the next reconcile trigger
                // recovers it.
                if (orchestrator.recoverFromException(simId)) {
                    Result.success()
                } else {
                    Result.failure()
                }
            }
        }

        companion object {
            const val SIM_ID_KEY = "sim_id"
        }
    }
