package app.kotowski.keepsimalive.ui.simsettings

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.ui.isLiveSendingAttempt
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.util.SimInfoFetcher
import app.kotowski.keepsimalive.util.SimPhoneStateData
import app.kotowski.keepsimalive.util.ToastUtil
import app.kotowski.keepsimalive.util.appLocaleContext
import app.kotowski.keepsimalive.util.appString
import app.kotowski.keepsimalive.util.isValidE164
import app.kotowski.keepsimalive.util.lateSendGraceMillis
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

data class SimDetailUiState(
    val isLoading: Boolean = false,
    val data: SimPhoneStateData? = null,
    val config: SimKeepaliveConfig? = null,
    val justSaved: Boolean = false,
    // One-shot forget-completed signal: the screen navigates away with it (never set on
    // the refused path — the user stays on the screen with the toast).
    val justForgot: Boolean = false,
    val error: String? = null,
    // The SIM's history rows (all outcomes): the info card derives in-flight status from
    // them and the off-schedule pending state clears with its PENDING row. Finished sends
    // live on the history screen (its own view model).
    val history: List<SendHistoryEntity> = emptyList(),
    // Exact terminal-row count from the DB: the "History (N)" button label (the history
    // screen's top bar shows the same value).
    val historyCount: Int = 0,
    val multiSimDevice: Boolean = false,
    // True while the SIM is not in the device: data then carries the last known identity
    // (from the cache) instead of the live radio state.
    val simMissing: Boolean = false,
    // When the SIM was last seen by the app (simMissing only).
    val lastSeenAtMillis: Long? = null,
    // The 1-based slot the SIM was in when it was last seen (simMissing only).
    val lastSeenSlotIndex: Int? = null,
    // The schedule-ended banner text (null while the saved schedule can still send); the
    // toggle is disabled while non-null and the schedule is off.
    val endedReason: String? = null,
    // The engine anchor (the newest SENT row's occurrence, null when nothing was ever
    // sent): the editor, the engine and the save backstop all judge the end state
    // with it.
    val lastSentAnchorMillis: Long? = null,
    // The user-configurable late-send grace (Settings): the editor's pending end state runs
    // the same catch-up the engine runs, so it needs the same grace.
    val lateSendGraceMinutes: Int = AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES,
    // Armed fire time of the confirmed off-schedule send, non-null while its PENDING row
    // exists: the info card's button becomes the Cancel for it, and the history flow clears
    // it the moment the row leaves PENDING (engine claim, user cancel, or a save that drops
    // the row) — which restores the offer.
    val offScheduleSendPendingAtMillis: Long? = null,
)

@HiltViewModel
class SimDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val keepaliveRepository: KeepaliveRepository,
        private val scheduleReconciler: ScheduleReconciler,
        private val prefs: AppPrefs,
        private val armer: ScheduleArmer,
        private val sendLock: SimSendLock,
        private val offSchedulePending: OffSchedulePendingRegistry,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SimDetailUiState())
        val state: StateFlow<SimDetailUiState> = _state.asStateFlow()

        private var loadedSimId: Int? = null
        private var historyJob: Job? = null
        private var countJob: Job? = null
        private var configJob: Job? = null

        fun loadSimData(
            context: Context,
            simId: Int,
        ) {
            _state.value = SimDetailUiState(isLoading = true)

            if (!SimInfoFetcher.hasPhoneStatePermission(context)) {
                // subscriptionId, not slot number: log readers must not mistake it for a tray slot.
                Logger.w("SimDetailVM", "READ_PHONE_STATE missing, subscriptionId=$simId details unavailable")
                _state.value =
                    SimDetailUiState(
                        isLoading = false,
                        error = appContext.appString(R.string.sim_detail_permission_required),
                    )
                return
            }

            viewModelScope.launch {
                // READ_PHONE_STATE is gated above (hasPhoneStatePermission); lint can't see
                // through the helper, and the fetch degrades to null on SecurityException.
                @SuppressLint("MissingPermission")
                val data =
                    try {
                        withContext(Dispatchers.IO) {
                            SimIdentityCache.prewarm(context)
                            SimInfoFetcher.getSimPhoneState(context, simId)
                        }
                    } catch (e: Exception) {
                        Logger.e("SimDetailVM", "Failed to fetch SIM data: ${e.message}", e)
                        null
                    }
                val config =
                    try {
                        keepaliveRepository.getConfig(simId)
                    } catch (e: Exception) {
                        Logger.e("SimDetailVM", "Failed to load keepalive config for SIM $simId: ${e.message}", e)
                        null
                    }
                val (endedReason, lastSentAnchorMillis) = endedStateAndAnchor(config)

                if (data != null) {
                    loadedSimId = simId
                    _state.value =
                        SimDetailUiState(
                            data = data,
                            config = config,
                            multiSimDevice = SimDetector.isMultiSimDevice(context),
                            endedReason = endedReason,
                            lastSentAnchorMillis = lastSentAnchorMillis,
                            lateSendGraceMinutes = prefs.lateSendGraceMinutes,
                            // Restored from the process-level registry (it outlived this
                            // ViewModel): a re-visit within the delay window must still show
                            // the Cancel. Stale entries (the row resolved while the screen
                            // was away) are dropped by the history flow on its first emission.
                            offScheduleSendPendingAtMillis = offSchedulePending.getPending(simId),
                        )
                    startHistoryCollection(simId)
                    startConfigCollection(simId)
                } else {
                    // The SIM is not in the device: fall back to the last known identity so
                    // the keepalive (config, history, stats) stays visible and editable.
                    val cached = SimIdentityCache.get(context, simId)
                    if (cached != null) {
                        loadedSimId = simId
                        _state.value =
                            SimDetailUiState(
                                data = cached.toSimPhoneStateData(),
                                simMissing = true,
                                lastSeenAtMillis = cached.lastSeenAtMillis,
                                lastSeenSlotIndex = cached.slotIndex,
                                config = config,
                                multiSimDevice = SimDetector.isMultiSimDevice(context),
                                endedReason = endedReason,
                                lastSentAnchorMillis = lastSentAnchorMillis,
                                lateSendGraceMinutes = prefs.lateSendGraceMinutes,
                                // Same registry restore as the live-data branch above; the
                                // history flow drops a stale entry on its first emission.
                                offScheduleSendPendingAtMillis = offSchedulePending.getPending(simId),
                            )
                        startHistoryCollection(simId)
                        startConfigCollection(simId)
                    } else {
                        _state.value =
                            SimDetailUiState(
                                isLoading = false,
                                error = appContext.appString(R.string.sim_detail_removed_error),
                            )
                    }
                }
            }
        }

        // Re-reads the live SIM state while the screen is open (the initial load is a one-
        // shot, so a SIM toggled off/on in settings would otherwise stay stale). Quiet by
        // design: a full reload would flip isLoading and re-open an editor from saved
        // values, losing the draft. Only the no-cache error state goes through the full
        // reload: the collectors never started there. The screen's `editing` flag holds
        // the permission-lost swap off while a draft is open (see the guard below).
        fun refreshSimData(
            simId: Int,
            editing: Boolean = false,
        ) {
            if (!SimInfoFetcher.hasPhoneStatePermission(appContext)) {
                // Permission lost while the screen shows a real SIM: switch to the error
                // state a fresh load would set, else the stale data stays on screen.
                val currentState = _state.value
                if (currentState.data != null) {
                    if (editing) {
                        // The editor's draft is internal remember state seeded from the
                        // saved values on re-entry, so the swap would silently destroy
                        // it — and the save path never reads phone state, so the edit
                        // stays completable. Keep the current state (log only); the swap
                        // lands on the next tick once the edit ends.
                        Logger.w("SimDetailVM", "READ_PHONE_STATE missing while editing, keeping the current state, subscriptionId=$simId")
                    } else {
                        _state.value =
                            SimDetailUiState(
                                isLoading = false,
                                error = appContext.appString(R.string.sim_detail_permission_required),
                            )
                    }
                }
                return
            }
            viewModelScope.launch {
                // Same gate as loadSimData: READ_PHONE_STATE is checked above, and the fetch
                // degrades to null on any failure (runCatching).
                @SuppressLint("MissingPermission")
                val data =
                    withContext(Dispatchers.IO) {
                        runCatching { SimInfoFetcher.getSimPhoneState(appContext.appLocaleContext(), simId) }
                            .getOrNull()
                    }
                val currentState = _state.value
                when {
                    // simMissing is only ever set by a load/refresh that also started the
                    // history/config collectors, so no collector restart is needed here.
                    data != null && currentState.simMissing -> {
                        _state.update {
                            it.copy(
                                data = data,
                                simMissing = false,
                                lastSeenAtMillis = null,
                                lastSeenSlotIndex = null,
                            )
                        }
                    }

                    data != null && currentState.error != null -> {
                        loadSimData(appContext, simId)
                    }

                    data != null && currentState.data != data -> {
                        _state.update { it.copy(data = data) }
                    }

                    data == null && currentState.data != null && !currentState.simMissing -> {
                        val cached = SimIdentityCache.get(appContext, simId)
                        if (cached != null) {
                            _state.value =
                                currentState.copy(
                                    data = cached.toSimPhoneStateData(),
                                    simMissing = true,
                                    lastSeenAtMillis = cached.lastSeenAtMillis,
                                    lastSeenSlotIndex = cached.slotIndex,
                                    isLoading = false,
                                    error = null,
                                )
                        } else {
                            _state.value =
                                SimDetailUiState(
                                    isLoading = false,
                                    error = appContext.appString(R.string.sim_detail_removed_error),
                                )
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        private fun startHistoryCollection(simId: Int) {
            historyJob?.cancel()
            countJob?.cancel()
            historyJob =
                viewModelScope.launch {
                    keepaliveRepository.observeNewest(simId, AppConfig.HISTORY_LOAD_LIMIT).collect { rows ->
                        val previousPending = _state.value.offScheduleSendPendingAtMillis
                        _state.update { st ->
                            // The off-schedule pending state dies with its PENDING row
                            // (engine claim, user cancel, or a save that drops it) — all
                            // through this flow, no dedicated callback; the card's button
                            // goes back to the offer with it.
                            val pending = st.offScheduleSendPendingAtMillis
                            val stillPending =
                                pending != null &&
                                    rows.any { it.outcome == SendOutcome.PENDING.name && it.scheduledForMillis == pending }
                            st.copy(
                                history = rows,
                                offScheduleSendPendingAtMillis = if (stillPending) pending else null,
                            )
                        }
                        // The registry copy dies with the same row, outside the state update:
                        // a re-visit must not restore a Cancel for a resolved one-off.
                        // clearPendingIf spares a racing newer arm that remembered a
                        // different time.
                        if (
                            previousPending != null &&
                            rows.none { it.outcome == SendOutcome.PENDING.name && it.scheduledForMillis == previousPending }
                        ) {
                            offSchedulePending.clearPendingIf(simId, previousPending)
                        }
                    }
                }
            countJob =
                viewModelScope.launch {
                    keepaliveRepository.observeHistoryCount(simId).collect { count ->
                        _state.update { it.copy(historyCount = count) }
                    }
                }
        }

        // Keeps the screen in sync with background writes (a send flipping the toggle OFF,
        // stats updates). The editor holds its own state, so this never clobbers an in-
        // progress edit; ended reason and anchor are re-derived on every emission.
        private fun startConfigCollection(simId: Int) {
            configJob?.cancel()
            configJob =
                viewModelScope.launch {
                    keepaliveRepository.observeConfigs().collect { configs ->
                        val current = configs.firstOrNull { it.simId == simId }
                        val (endedReason, lastSentAnchorMillis) = endedStateAndAnchor(current)
                        _state.update { st ->
                            if (
                                st.config != current ||
                                st.endedReason != endedReason ||
                                st.lastSentAnchorMillis != lastSentAnchorMillis
                            ) {
                                st.copy(
                                    config = current,
                                    endedReason = endedReason,
                                    lastSentAnchorMillis = lastSentAnchorMillis,
                                )
                            } else {
                                st
                            }
                        }
                    }
                }
        }

        // The saved schedule's end state under the engine anchor (the newest SENT row's
        // occurrence, never the delivery time — a late send would drift it): the banner
        // text (null while the schedule can still send) and the anchor the editor judges
        // pending fields with.
        private suspend fun endedStateAndAnchor(config: SimKeepaliveConfig?): Pair<String?, Long?> {
            if (config == null) return null to null
            val anchor = keepaliveRepository.latestSentMillis(config.simId)
            val end =
                ScheduleCalculator.endState(
                    config,
                    anchor,
                    ZonedDateTime.now(ZoneId.systemDefault()),
                    prefs.lateSendGraceMillis,
                )
            return endedReason(appContext.appLocaleContext(), end) to anchor
        }

        // Unconfigures the SIM: the config row goes (schedule, last sent and send count with
        // it), but the history rows stay — terminal ones are the audit trail, the open one
        // is finalized as skipped first. The identity cache and not-present counter are
        // Forget's domain, not touched. A save in flight is cancelled (like commit), so it
        // cannot resurrect the row. Gate check, finalize and delete run under the send lock;
        // refused while a fresh send is in flight (like disable).
        fun deleteSchedule(simId: Int) {
            saveJob?.cancel()
            saveJob =
                viewModelScope.launch {
                    val deleted =
                        sendLock.withLock(simId) {
                            if (isSendInFlight(simId)) {
                                false
                            } else {
                                finalizeActiveAsSkipped(simId, appContext.appString(R.string.error_schedule_deleted))
                                keepaliveRepository.deleteConfig(simId)
                                // The config row goes: the clock-change recompute's persisted
                                // catch-up anchor for this SIM goes with it, or a re-added
                                // SIM would inherit it and re-derive (record as skipped)
                                // occurrences from before the delete. The last-sent-occurrence
                                // anchor (a column of that same row) goes with the row — a
                                // re-added SIM starts fresh, with no inherited rhythm.
                                prefs.clearCatchUpAnchor(simId)
                                // The SENT history rows outlive the config row: without
                                // this marker a re-enable would re-anchor the rhythm on
                                // the pre-delete send and record the whole off period as
                                // missed occurrences.
                                prefs.setRhythmResetAtMillis(simId, System.currentTimeMillis())
                                true
                            }
                        }
                    if (!deleted) {
                        ToastUtil.show(appContext, appContext.appString(R.string.error_send_in_progress))
                        return@launch
                    }
                    armer.cancelSend(simId)
                    // Deleting the (possibly last) enabled SIM leaves no keepalive running:
                    // refresh the persistent hint right away.
                    scheduleReconciler.syncPersistentHint()
                }
        }

        // Forgets the SIM entirely: config row, history (open rows included), identity
        // cache, not-present counter. Gate check and delete run under the send lock, so no
        // SMS and no ghost row outlive the forget. Refused while a fresh send is in flight
        // (like disable/delete): under the lock a SENDING row has no live owner.
        fun forgetSim(simId: Int) {
            // Like delete: a save in flight is cancelled, so it cannot re-create the row
            // after the forget. A save started after the forget is an explicit re-enable
            // and may create the row as for a new SIM.
            saveJob?.cancel()
            viewModelScope.launch {
                val forgotten =
                    sendLock.withLock(simId) {
                        if (isSendInFlight(simId)) {
                            false
                        } else {
                            keepaliveRepository.deleteSim(simId)
                            true
                        }
                    }
                if (!forgotten) {
                    ToastUtil.show(appContext, appContext.appString(R.string.error_send_in_progress))
                    return@launch
                }
                armer.cancelSend(simId)
                SimIdentityCache.forget(appContext, simId)
                prefs.setSimNotPresentFailures(simId, 0)
                // The config row is gone: the clock-change recompute's persisted
                // catch-up anchor for this SIM goes with it, or a re-added SIM would
                // inherit it and re-derive (record as skipped) occurrences from before
                // the forget. The last-sent-occurrence anchor (a column of that same row)
                // goes with the row — a re-added SIM starts fresh, with no inherited
                // rhythm.
                prefs.clearCatchUpAnchor(simId)
                // Forgetting the last enabled SIM leaves no keepalive running: refresh the
                // persistent hint right away.
                scheduleReconciler.syncPersistentHint()
                // The last step, after everything committed: the screen leaves with this
                // flag — a navigation before that would pop the destination and cancel
                // this coroutine, and the delete would never run. The refused path never
                // sets it (its toast keeps the user here).
                _state.update { it.copy(justForgot = true) }
            }
        }

        // Runs the pending retry immediately (the Retry button only exists while a retry row
        // (PENDING, retryCount > 0) owns the occurrence, so this is never a no-op). Gate
        // check and re-arm run under the send lock, like disable/delete/forget: the worker
        // holds the same lock from the claim through the result write, so a tap landing in
        // the state-flow lag after a claim blocks here instead of REPLACE-cancelling the
        // running worker. The enqueue stays the last action, like the engine's own re-arms.
        fun retryNow(simId: Int) {
            viewModelScope.launch {
                sendLock.withLock(simId) {
                    if (isSendInFlight(simId)) {
                        // A fresh SENDING row under the lock has no live owner (the owner
                        // would hold this lock): the outcome is undetermined, and the stale
                        // check is what resolves the row.
                        return@withLock
                    }
                    armer.armSendNow(simId)
                }
            }
        }

        // Off-schedule one-off send: arms the configured message as a single occurrence
        // [AppConfig.OFF_SCHEDULE_SEND_DELAY_MS] ahead so the user can still cancel it; the
        // regular engine then runs it like a scheduled send. It counts as one: the send
        // count grows on success and the next occurrence re-anchors from it — a stored
        // pending send moved off its occurrence by that re-anchor is recorded in History as
        // skipped instead of vanishing. Refused (toast) while a SENDING or retry row is
        // engine-owned: the one-active-row invariant would drop it silently. Gate check,
        // writes and arm run under the send lock.
        fun sendOffSchedule(simId: Int) {
            val config = _state.value.config
            // The button is only visible in this state; defensive re-reads for a tap that
            // lands on a stale composition (the state re-derives on every config emission).
            if (config == null || !config.enabled || _state.value.endedReason != null) return
            if (!isValidE164(config.recipientPhone) || config.message.isBlank()) return
            // A confirmed send is already pending (the card's button is the Cancel): a
            // second arm must not replace the row — the user expected a cancel, not a
            // re-arm.
            if (_state.value.offScheduleSendPendingAtMillis != null) return
            viewModelScope.launch {
                var armedAt: Long? = null
                var refusedInFlight = false
                sendLock.withLock(simId) {
                    // Re-read the config under the lock, like commit: a disable or a save
                    // may have landed after the state read, and arming against a disabled
                    // config would write a ghost next send and a ghost Cancel for an off
                    // SIM. Silent back-off: the config observer hides the button when the
                    // disabled config arrives.
                    val liveConfig = keepaliveRepository.getConfig(simId)
                    if (liveConfig == null || !liveConfig.enabled) return@withLock
                    // Re-read under the lock, like delete/forget: a SENDING or retry
                    // occurrence is engine-owned to its result write.
                    val active = keepaliveRepository.getActiveHistory(simId)
                    if (
                        active != null &&
                        (
                            active.outcome == SendOutcome.SENDING.name ||
                                (active.outcome == SendOutcome.PENDING.name && active.retryCount > 0)
                        )
                    ) {
                        refusedInFlight = true
                    } else {
                        val now = System.currentTimeMillis()
                        val t = now + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS
                        // The align drops the stored next send, replaced by the one-off's
                        // row. Within the delay it's simply superseded (one send, no
                        // duplicate); otherwise a next occurrence moved off by the
                        // re-anchor is finalized SKIPPED before the align deletes it, so a
                        // skip is never unexplained (the reconciler's pattern).
                        recordDroppedPending(liveConfig, active, now, t)
                        keepaliveRepository.updateNextSend(simId, t)
                        // The live config's fields, not the state's: a save that changed
                        // the recipient/message in the same window is honored. The
                        // one-off's own time doubles as its base: it is a one-shot send,
                        // not a scheduled occurrence.
                        keepaliveRepository.alignPendingRow(simId, t, t, liveConfig.recipientPhone, liveConfig.message)
                        armer.armSend(simId)
                        armedAt = t
                    }
                }
                if (refusedInFlight) {
                    ToastUtil.show(appContext, appContext.appString(R.string.error_send_in_progress))
                    return@launch
                }
                armedAt?.let { t ->
                    // The registry copy outlives this ViewModel (a re-visit within the delay
                    // window restores the card's Cancel from it); the state copy drives the
                    // card for this lifetime.
                    offSchedulePending.markPending(simId, t)
                    _state.update { it.copy(offScheduleSendPendingAtMillis = t) }
                }
            }
        }

        // Records the occurrence the off-schedule arm is about to drop, so it gets a
        // SKIPPED row with the user-facing reason instead of vanishing (a skip is never
        // unexplained, the reconciler's invariant). No record where nothing is lost: the
        // pending falls within the delay (the one-off supersedes it), or the re-anchor
        // lands on the same next occurrence — judged against the pending's own base, never
        // the last SENT row, which a catch-up may sit several intervals ahead of.
        private suspend fun recordDroppedPending(
            config: SimKeepaliveConfig,
            active: SendHistoryEntity?,
            now: Long,
            oneOffAtMillis: Long,
        ) {
            if (active == null) return
            // Superseded, not dropped: the planned fire is within the delay, so the
            // one-off IS this send (one SMS, a few seconds off its time).
            if (active.scheduledForMillis - now <= AppConfig.OFF_SCHEDULE_SEND_DELAY_MS) return
            val zone = ZoneId.systemDefault()
            val nextAfterOneOff =
                ScheduleCalculator.nextOccurrence(
                    config,
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(oneOffAtMillis), zone),
                )
            if (
                nextAfterOneOff !=
                ScheduleCalculator.baseOf(
                    config,
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(active.scheduledForMillis), zone),
                )
            ) {
                keepaliveRepository.finalizeOccurrence(
                    active.id,
                    SendOutcome.SKIPPED,
                    null,
                    appContext.appString(R.string.error_replaced_by_off_schedule),
                    active.retryCount,
                    active.firstAttemptAtMillis,
                )
            }
        }

        // Cancels the pending off-schedule send: finalizes its occurrence as SKIPPED,
        // disarms the armed work and restores the regular schedule (the anchor never moved,
        // so the recompute lands on the same next send). Refused (toast) while SENDING,
        // like disable/delete: the SMS may already be out. A no-op once the occurrence
        // resolved itself. Disarm precedes the restore's re-arm: both arm the same unique
        // work name, so the reverse order would kill the restored schedule's work. Gate
        // check, finalize, disarm and restore run under the send lock; toast and state
        // update stay outside it.
        fun cancelOffSchedule(simId: Int) {
            val pendingAt = _state.value.offScheduleSendPendingAtMillis ?: return
            viewModelScope.launch {
                var refusedInFlight = false
                sendLock.withLock(simId) {
                    val active = keepaliveRepository.getActiveHistory(simId)
                    when {
                        // The occurrence resolved on its own (the engine claimed it, a save
                        // dropped the row): the pending state clears with it, nothing to
                        // disarm here.
                        active == null || active.scheduledForMillis != pendingAt -> {
                            Unit
                        }

                        active.outcome == SendOutcome.SENDING.name -> {
                            refusedInFlight = true
                        }

                        // PENDING (the delay window) or a retry row (a failed attempt
                        // waiting for its backoff): the user's cancel wins — no SMS left
                        // the radio for PENDING, and a retry row never reached a
                        // successful send.
                        else -> {
                            keepaliveRepository.finalizeOccurrence(
                                active.id,
                                SendOutcome.SKIPPED,
                                active.lastAttemptAtMillis,
                                appContext.appString(R.string.error_send_cancelled),
                                active.retryCount,
                                active.firstAttemptAtMillis,
                            )
                            armer.cancelSend(simId)
                            keepaliveRepository.updateNextSend(simId, null)
                            keepaliveRepository.clearPendingHistory(simId)
                            // Restores the regular schedule: the anchor (last regular send)
                            // is untouched, so the recompute lands on the same next send as
                            // before the one-off was armed.
                            scheduleReconciler.reconcileSim(simId)
                        }
                    }
                }
                // The state clears in every outcome (the dialog closes with the row),
                // independent of the refusal toast (only feedback). The registry copy dies
                // with it: a re-visit must not restore a Cancel for a resolved one-off.
                offSchedulePending.clearPending(simId)
                _state.update { it.copy(offScheduleSendPendingAtMillis = null) }
                if (refusedInFlight) {
                    ToastUtil.show(appContext, appContext.appString(R.string.error_send_in_progress))
                }
            }
        }

        // Single in-flight write per config row: a new commit cancels the previous one, so
        // rapid Save/toggle sequences keep the last intent (no races between concurrent
        // upserts of the same row).
        private var saveJob: Job? = null

        // True while a fresh attempt is in flight (a SENDING row younger than
        // SENDING_STALE_MS): the attempt owns its occurrence, so a disable/delete/forget
        // landing in that window would race the worker's result write. A stale SENDING row
        // has no live owner and never blocks.
        private suspend fun isSendInFlight(simId: Int): Boolean {
            val active = keepaliveRepository.getActiveHistory(simId) ?: return false
            if (active.outcome != SendOutcome.SENDING.name) return false
            return isLiveSendingAttempt(active.lastAttemptAtMillis, active.scheduledForMillis, System.currentTimeMillis())
        }

        // Closes the SIM's open occurrence as SKIPPED (delete/reset, after the in-flight
        // gate refused a fresh send): under the send lock a SENDING row has no live owner
        // (a dead mid-send attempt), so it takes the direct update, while a PENDING row
        // (including a retry) goes through the guarded finalizeOccurrence. The radio call
        // may have gone out already: the occurrence is consumed with the given reason,
        // never re-sent.
        private suspend fun finalizeActiveAsSkipped(
            simId: Int,
            reason: String,
        ) {
            val active = keepaliveRepository.getActiveHistory(simId) ?: return
            if (active.outcome == SendOutcome.SENDING.name) {
                keepaliveRepository.updateHistory(
                    active.id,
                    SendOutcome.SKIPPED,
                    active.lastAttemptAtMillis,
                    reason,
                    active.retryCount,
                    active.firstAttemptAtMillis,
                )
            } else {
                keepaliveRepository.finalizeOccurrence(
                    active.id,
                    SendOutcome.SKIPPED,
                    active.lastAttemptAtMillis,
                    reason,
                    active.retryCount,
                    active.firstAttemptAtMillis,
                )
            }
        }

        private fun commit(
            config: SimKeepaliveConfig,
            notifySaved: Boolean,
        ) {
            saveJob?.cancel()
            saveJob =
                viewModelScope.launch {
                    // In-flight gate, write and per-SIM reconcile run under the send lock
                    // (see SimSendLock): a racing send and this commit are mutually
                    // exclusive. Toasts, state update and the global hint sync stay
                    // outside.
                    var refusedInFlight = false
                    var endedRefusal: String? = null
                    var saveFailed = false
                    val result: SimKeepaliveConfig? =
                        sendLock.withLock(config.simId) {
                            // A disable must not land mid-attempt: the SMS is already
                            // committed to the radio, and killing the worker would lose the
                            // result and leave the send uncounted (N+1 under "After N
                            // sends"). A fresh SENDING row under the lock has no live owner
                            // — say so, keep the keepalive on.
                            if (!config.enabled && isSendInFlight(config.simId)) {
                                refusedInFlight = true
                                return@withLock null
                            }
                            // The engine-owned schedule state (nextSendAtMillis,
                            // lastSentAtMillis, sendCount) is never written from the UI: a
                            // full-row upsert from a read would clobber a concurrent send
                            // that advanced the schedule in between. The row is only created
                            // on first enable; afterwards only user-controlled columns
                            // update. When the save changes when sends happen, the stored
                            // next send belongs to the previous schedule and is dropped so
                            // the reconcile re-anchors to the last send — unless an
                            // occurrence is in flight (SENDING or a retry row): then it is
                            // the armed work's fire time, and dropping it would recompute a
                            // retry with fresh jitter no work is armed for. Fresh PENDING
                            // rows are only dropped with no in-flight occurrence. Disabling
                            // a previously-enabled SIM drops the stored next send even on an
                            // unchanged schedule: with no open row left the disabled
                            // reconcile branch never clears it, and the stale value would
                            // ghost a countdown (the in-flight exception is unchanged — the
                            // open row's finalizer clears it with the flip).
                            try {
                                val dbConfig = keepaliveRepository.getConfig(config.simId)
                                // Enabling an already-ended schedule can never arm a send
                                // (the limit is consumed or the next send is past the end
                                // date): refuse it instead of persisting an enabled state
                                // that does nothing. The end state is read from the database
                                // (the editor's pending config lacks send count and last
                                // send) and anchored like the engine, so the guard and the
                                // reconciler always agree.
                                if (config.enabled) {
                                    val end =
                                        ScheduleCalculator.endState(
                                            config.copy(sendCount = dbConfig?.sendCount ?: config.sendCount),
                                            keepaliveRepository.latestSentMillis(config.simId),
                                            ZonedDateTime.now(ZoneId.systemDefault()),
                                            prefs.lateSendGraceMillis,
                                        )
                                    // Backstop with the banner/editor warning text: the UI
                                    // normally makes this save unreachable (the toggle
                                    // cannot be enabled while ended).
                                    val refusal = endedReason(appContext.appLocaleContext(), end)
                                    if (refusal != null) {
                                        endedRefusal = refusal
                                        return@withLock null
                                    }
                                }
                                val inFlight =
                                    keepaliveRepository
                                        .getActiveHistory(config.simId)
                                        ?.takeIf {
                                            it.outcome == SendOutcome.SENDING.name ||
                                                (it.outcome == SendOutcome.PENDING.name && it.retryCount > 0)
                                        }
                                if (dbConfig == null) {
                                    keepaliveRepository.saveConfig(
                                        config.copy(
                                            nextSendAtMillis = null,
                                            lastSentAtMillis = null,
                                            sendCount = 0,
                                        ),
                                    )
                                } else {
                                    keepaliveRepository.saveUserColumns(config)
                                    if (inFlight == null && (!config.sameSchedule(dbConfig) || (!config.enabled && dbConfig.enabled))) {
                                        keepaliveRepository.updateNextSend(config.simId, null)
                                    }
                                }
                                if (inFlight == null) {
                                    keepaliveRepository.clearPendingHistory(config.simId)
                                }
                                val saved = keepaliveRepository.getConfig(config.simId)
                                if (saved == null) return@withLock null
                                scheduleReconciler.reconcileSim(saved.simId)
                                // Re-read after the reconcile: `saved` predates it (on a
                                // re-enable its nextSendAtMillis is still null), and the
                                // pre-reconcile copy must not be the last word the screen
                                // hears ("not scheduled" until a re-navigation).
                                keepaliveRepository.getConfig(config.simId)
                            } catch (e: CancellationException) {
                                // A cancelled save (superseded by a newer commit/delete/forget)
                                // must stop as a proper cancellation, not continue from a
                                // dead job inside the lock section.
                                throw e
                            } catch (e: Exception) {
                                Logger.e("SimDetailVM", "Failed to save keepalive config: ${e.message}", e)
                                saveFailed = true
                                null
                            }
                        }
                    if (refusedInFlight) {
                        ToastUtil.show(appContext, appContext.appString(R.string.error_send_in_progress))
                        return@launch
                    }
                    endedRefusal?.let { reason ->
                        ToastUtil.show(appContext, reason)
                        return@launch
                    }
                    if (saveFailed) {
                        ToastUtil.show(appContext, appContext.appString(R.string.error_save_failed))
                        return@launch
                    }
                    val effective = result ?: return@launch
                    _state.update { it.copy(config = effective, justSaved = notifySaved) }
                    // The per-SIM reconcile already ran under the lock; the global hint
                    // sync stays outside (it must not wait for the next dashboard visit).
                    scheduleReconciler.syncPersistentHint()
                }
        }

        fun setEnabled(enabled: Boolean) {
            val current = _state.value.config ?: return
            // No same-value early return: state may be stale while a save is in flight,
            // and dropping a repeat toggle would lose the user's last intent.
            commit(current.copy(enabled = enabled), notifySaved = false)
        }

        fun saveConfig(config: SimKeepaliveConfig) {
            commit(config, notifySaved = true)
        }

        fun clearSavedFlag() {
            _state.update { it.copy(justSaved = false) }
        }
    }
