package app.kotowski.keepsimalive.ui.dashboard

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.InFlightOccurrence
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.ui.icons.Timer1Icon
import app.kotowski.keepsimalive.ui.icons.Timer2Icon
import app.kotowski.keepsimalive.ui.icons.Timer3Icon
import app.kotowski.keepsimalive.ui.isLiveSendingAttempt
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.SimInfo
import app.kotowski.keepsimalive.util.toRelativeDisplay
import java.time.Instant

enum class SimStatus {
    ACTIVE,
    NO_SERVICE,

    // A configured SIM that is not in the active subscription list (unplugged or switched
    // off): the card is shown from the last known identity.
    REMOVED,
}

// The in-flight window of the "Next send/Next retry" field: the attempt is marked in flight
// (SENDING, live per isLiveSendingAttempt) or the scheduled time has arrived and the worker
// still has its grace to start. "Trying now" covers both, so the UI does not flip to "just
// now" between the two.
fun nextSendShowsTryingNow(
    inFlight: InFlightOccurrence?,
    nextSendAtMillis: Long?,
    now: Long,
): Boolean {
    if (inFlight?.outcome == SendOutcome.SENDING.name) {
        return isLiveSendingAttempt(inFlight.lastAttemptAtMillis, inFlight.scheduledForMillis, now)
    }
    val scheduledFor = nextSendAtMillis ?: return false
    return now - scheduledFor in 0L..AppConfig.IN_FLIGHT_GRACE_MS
}

fun slotIcon(
    slot: Int,
    multiSimDevice: Boolean,
): ImageVector? =
    if (!multiSimDevice) {
        null
    } else {
        when (slot) {
            1 -> Timer1Icon
            2 -> Timer2Icon
            3 -> Timer3Icon
            else -> null
        }
    }

// The right-hand indicator of the card is always the keepalive state, independent of the
// SIM's service status (a no-service or removed SIM still shows its keepalive on/off).
fun SimCardStub.keepaliveStringRes(): Int = if (keepaliveEnabled) R.string.dashboard_sim_status_on else R.string.dashboard_sim_status_off

data class SimCardStub(
    val simId: Int,
    val label: String,
    val slot: Int,
    val carrier: String,
    val status: SimStatus,
    val lastSentAtMillis: Long?,
    val nextSendAtMillis: Long?,
    val sendCount: Int,
    val keepaliveEnabled: Boolean = false,
) {
    fun getLastSentRelative(
        context: Context,
        now: Instant = Instant.now(),
        neverText: String = context.getString(R.string.interval_never),
        justNowText: String = context.getString(R.string.interval_just_now),
        inAMomentText: String = context.getString(R.string.interval_in_a_moment),
    ): String = relative(context, lastSentAtMillis, neverText, now, justNowText, inAMomentText)

    fun getNextSendRelative(
        context: Context,
        now: Instant = Instant.now(),
        neverText: String = context.getString(R.string.interval_not_scheduled),
        justNowText: String = context.getString(R.string.interval_just_now),
        inAMomentText: String = context.getString(R.string.interval_in_a_moment),
    ): String = relative(context, nextSendAtMillis, neverText, now, justNowText, inAMomentText)
}

private fun relative(
    context: Context,
    millis: Long?,
    neverText: String,
    now: Instant,
    justNowText: String,
    inAMomentText: String,
): String =
    millis?.let {
        Instant.ofEpochMilli(it).toRelativeDisplay(
            context,
            it,
            now,
            justNowText = justNowText,
            inAMomentText = inAMomentText,
            neverText = neverText,
        )
    } ?: neverText

fun SimInfo.toSimCardStub(): SimCardStub =
    SimCardStub(
        simId = simId,
        label = displayName,
        slot = slotIndex + 1,
        carrier = carrierName,
        status = if (isInService) SimStatus.ACTIVE else SimStatus.NO_SERVICE,
        lastSentAtMillis = null,
        nextSendAtMillis = null,
        sendCount = 0,
    )

// The card of a configured SIM that is no longer in the device: last known identity,
// REMOVED status, no live stats (the config overlay in refreshCards fills them in).
fun CachedSimIdentity.toMissingSimCardStub(context: Context): SimCardStub =
    SimCardStub(
        simId = simId,
        // User-facing (Dashboard card title): the fallback names the subscription id, not a
        // tray slot, and comes from strings.xml.
        label = displayName.ifEmpty { context.getString(R.string.sim_name_fallback, simId) },
        slot = slotIndex,
        carrier = carrierName,
        status = SimStatus.REMOVED,
        lastSentAtMillis = null,
        nextSendAtMillis = null,
        sendCount = 0,
    )

// The empty-dashboard card: shown once the live SIM list has been read and not a single
// card would render (no real SIM, no remembered missing SIM). The missing Phone permission
// lands here too — the SIM list is unreadable then. Which of the two texts the card names
// (missing SIM vs missing permission) the screen picks from the granted state.
fun noSimsCardVisible(
    simCardsRead: Boolean,
    simCards: List<SimCardStub>,
): Boolean = simCardsRead && simCards.isEmpty()
