package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.DetailRow
import app.kotowski.keepsimalive.ui.InFlightUiState
import app.kotowski.keepsimalive.ui.isLiveSendingAttempt
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.SimPhoneStateData

@Composable
internal fun SimInfoCard(
    context: android.content.Context,
    data: SimPhoneStateData,
    config: SimKeepaliveConfig?,
    currentTime: Long,
    inFlight: SendHistoryEntity?,
    onRetryClick: () -> Unit = {},
    // True while the confirmed off-schedule send is pending: the card's bottom button
    // then is the Cancel for it (the send cannot be offered again until it resolves).
    offSchedulePending: Boolean = false,
    // Null hides the button: the offer conditions are the caller's (keepalive on, not
    // ended, sendable message) — the same ones the ViewModel's gate re-checks.
    onSendOffScheduleClick: (() -> Unit)? = null,
    onCancelOffScheduleClick: () -> Unit = {},
    simMissing: Boolean = false,
    lastSeenAtMillis: Long? = null,
    lastSeenSlotIndex: Int? = null,
    multiSimDevice: Boolean = false,
) {
    // While SENDING the "Next send / Next retry" row keeps its previous label (a retry attempt
    // inherits "Next retry") and only updates on success/failure; the Status row becomes
    // "Trying now".
    val inFlightUi = InFlightUiState.from(inFlight?.outcome, inFlight?.retryCount ?: 0)
    val status = statusText(context, inFlight, config?.nextSendAtMillis, currentTime)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sim_config_card_sim_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Shown while the SIM is not in the device: the rows below render the last
            // known identity (the banner above the card says the data is not live and
            // offers to forget the remembered SIM).
            if (simMissing) {
                lastSeenAtMillis?.let {
                    val dateTime = DateUtil.formatDateTime(context, it)
                    // The slot the SIM was last seen in: only meaningful on a multi-SIM
                    // device (a single-SIM device has one slot, so the suffix would be
                    // noise).
                    val slot = if (multiSimDevice) lastSeenSlotIndex else null
                    val value =
                        slot?.let { s ->
                            context.getString(R.string.sim_last_seen_with_slot, dateTime, s)
                        } ?: dateTime
                    DetailRow(
                        label = stringResource(R.string.sim_last_seen),
                        value = value,
                    )
                }
            }
            DetailRow(
                label = stringResource(R.string.sim_detail_operator_name),
                value = operatorText(context, data),
            )
            DetailRow(
                label = stringResource(R.string.last_sent),
                value =
                    config?.lastSentAtMillis?.let { DateUtil.formatDateTime(context, it) }
                        ?: stringResource(R.string.interval_never),
            )
            DetailRow(
                label = stringResource(if (inFlightUi.isRetry) R.string.next_retry else R.string.next_send),
                value = nextSendText(context, config?.nextSendAtMillis),
            )
            status?.let {
                DetailRow(label = stringResource(R.string.sim_history_detail_outcome), value = it)
            }
            // While a retry is pending (or a retry attempt is in flight) the occurrence is not in
            // History yet, so the last failure reason would be visible nowhere else.
            inFlight?.failureReason?.let {
                DetailRow(label = stringResource(R.string.sim_detail_error), value = it)
            }
            DetailRow(
                label = stringResource(R.string.send_count),
                value = sendCountText(context, config?.sendCount ?: 0, config?.maxSends, config?.endType ?: EndType.NEVER),
            )
            // The single action button at the card bottom: the Cancel for the confirmed
            // off-schedule send while it is pending (the only pending-state handle on
            // screen), the immediate retry while a retry is pending (it re-attempts
            // exactly this occurrence — on a disabled config the worker early-returns
            // and the button is hidden there), and otherwise the off-schedule send
            // offer (arming it on top of a pending retry would be refused by the
            // engine, so the retry wins the slot).
            if (offSchedulePending) {
                Spacer(modifier = Modifier.height(8.dp))
                // Filled, like the Retry and the off-schedule Send that share this slot.
                Button(onClick = onCancelOffScheduleClick) {
                    Text(stringResource(android.R.string.cancel))
                }
            } else if (
                inFlight != null &&
                inFlight.outcome == SendOutcome.PENDING.name &&
                inFlight.retryCount > 0 &&
                config?.enabled == true
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetryClick) {
                    Text(stringResource(R.string.sim_info_retry))
                }
            } else if (onSendOffScheduleClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSendOffScheduleClick) {
                    Text(stringResource(R.string.sim_send_off_schedule))
                }
            }
        }
    }
}

internal fun operatorText(
    context: android.content.Context,
    data: SimPhoneStateData,
): String {
    if (!data.isNetworkRoaming) return data.networkOperatorName.ifEmpty { "\u2014" }
    val home = data.simOperatorName
    val visited = data.networkOperatorName
    val roamingSuffix = if (visited.isEmpty()) "" else context.getString(R.string.sim_detail_roaming_on, visited)
    return when {
        home.isEmpty() -> roamingSuffix.ifEmpty { "\u2014" }
        roamingSuffix.isEmpty() -> home
        else -> "$home ($roamingSuffix)"
    }
}

internal fun nextSendText(
    context: android.content.Context,
    nextSendAtMillis: Long?,
): String =
    nextSendAtMillis?.let { DateUtil.formatDateTime(context, it) }
        ?: context.getString(R.string.interval_not_scheduled)

// The single Status row value: "Trying now" while an attempt is in flight, otherwise
// the "Send in"/"Retry in" countdown ticking down to "… in 0s", which stays static
// (clamped) until the worker's grace runs out; after that "overdue" (the attempt has not
// started yet and the send is genuinely late). Null when there is no next send (row
// hidden). A SENDING row older than SENDING_STALE_MS is not a live attempt (the process
// died mid-send): it falls through to overdue instead of ghosting "Trying now" until the
// engine's next run resolves it.
internal fun statusText(
    context: android.content.Context,
    inFlight: SendHistoryEntity?,
    nextSendAtMillis: Long?,
    currentTime: Long,
): String? {
    if (inFlight?.outcome == SendOutcome.SENDING.name) {
        if (isLiveSendingAttempt(inFlight.lastAttemptAtMillis, inFlight.scheduledForMillis, currentTime)) {
            return context.getString(R.string.sim_history_outcome_sending)
        }
    }
    val scheduledFor = nextSendAtMillis ?: return null
    if (currentTime - scheduledFor > AppConfig.IN_FLIGHT_GRACE_MS) {
        return context.getString(R.string.status_overdue)
    }
    val prefix =
        if (inFlight != null && inFlight.outcome == SendOutcome.PENDING.name && inFlight.retryCount > 0) {
            R.string.status_retry_in
        } else {
            R.string.status_send_in
        }
    return context.getString(prefix, DateUtil.formatDurationMillis(context, scheduledFor - currentTime))
}

internal fun sendCountText(
    context: android.content.Context,
    sendCount: Int,
    maxSends: Int?,
    endType: EndType,
): String =
    if (endType == EndType.AFTER_N_SENDS && maxSends != null) {
        context.getString(R.string.send_count_with_limit, sendCount, maxSends)
    } else {
        "$sendCount"
    }
