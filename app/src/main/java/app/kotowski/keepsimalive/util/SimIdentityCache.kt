package app.kotowski.keepsimalive.util

import android.content.Context
import android.content.SharedPreferences

// Last known identity of a real SIM, persisted so a removed SIM can still be recognized and
// shown (dashboard "Missing" card, SIM Details with the last known info) until it is
// forgotten. Keyed by subscriptionId: on this device the same physical SIM comes back with
// the same id, while a different SIM in the same slot gets a new one and never inherits this
// data (the keepalive follows the SIM, not the slot).
data class CachedSimIdentity(
    val simId: Int,
    val displayName: String,
    val carrierName: String,
    val slotIndex: Int,
    val networkOperatorName: String,
    val simOperatorName: String,
    val isNetworkRoaming: Boolean,
    val lastSeenAtMillis: Long,
) {
    fun toSimPhoneStateData(): SimPhoneStateData =
        SimPhoneStateData(
            simId = simId,
            displayName = displayName,
            carrierName = carrierName,
            slotIndex = slotIndex,
            networkOperatorName = networkOperatorName,
            simOperatorName = simOperatorName,
            isNetworkRoaming = isNetworkRoaming,
        )
}

object SimIdentityCache {
    private const val PREFS_NAME = "sim_identity_cache"

    // Minimum interval between "seen" refreshes of an unchanged identity: the 5 s poll
    // would otherwise rewrite the file every pass per SIM. "Last seen" renders at minute
    // precision, so the timestamp may lag reality by up to this interval without the
    // difference being visible.
    private const val MIN_SEEN_UPDATE_MS: Long = 60_000L

    // The display name (possibly empty, but always written) marks a complete entry: without
    // it nothing identifiable was ever cached for this SIM.
    fun get(
        context: Context,
        simId: Int,
    ): CachedSimIdentity? {
        val prefs = prefs(context) ?: return null
        val name = prefs.getString(key(simId, "name"), null) ?: return null
        return CachedSimIdentity(
            simId = simId,
            displayName = name,
            carrierName = prefs.getString(key(simId, "carrier"), "") ?: "",
            slotIndex = prefs.getInt(key(simId, "slot"), 0),
            networkOperatorName = prefs.getString(key(simId, "net_op"), "") ?: "",
            simOperatorName = prefs.getString(key(simId, "sim_op"), "") ?: "",
            isNetworkRoaming = prefs.getBoolean(key(simId, "roaming"), false),
            lastSeenAtMillis = prefs.getLong(key(simId, "seen"), 0L),
        )
    }

    fun save(
        context: Context,
        identity: CachedSimIdentity,
    ) {
        val prefs = prefs(context) ?: return
        val existing = get(context, identity.simId)
        // A "seen"-only refresh younger than the minimum interval would rewrite the file
        // for a timestamp the minute-precision "Last seen" display cannot show: skip it.
        if (
            existing != null &&
            existing.copy(lastSeenAtMillis = identity.lastSeenAtMillis) == identity &&
            identity.lastSeenAtMillis - existing.lastSeenAtMillis < MIN_SEEN_UPDATE_MS
        ) {
            return
        }
        val editor = prefs.edit()
        if (existing != null && existing.copy(lastSeenAtMillis = identity.lastSeenAtMillis) == identity) {
            editor.putLong(key(identity.simId, "seen"), identity.lastSeenAtMillis)
        } else {
            editor
                .putString(key(identity.simId, "name"), identity.displayName)
                .putString(key(identity.simId, "carrier"), identity.carrierName)
                .putInt(key(identity.simId, "slot"), identity.slotIndex)
                .putString(key(identity.simId, "net_op"), identity.networkOperatorName)
                .putString(key(identity.simId, "sim_op"), identity.simOperatorName)
                .putBoolean(key(identity.simId, "roaming"), identity.isNetworkRoaming)
                .putLong(key(identity.simId, "seen"), identity.lastSeenAtMillis)
        }
        editor.apply()
    }

    // Prewarms the SharedPreferences file so the first getSharedPreferences call does not
    // happen on the main thread later (cold-start IPC + XML parse). A no-op when the file
    // has already been accessed in this process.
    fun prewarm(context: Context) {
        prefs(context)
    }

    fun forget(
        context: Context,
        simId: Int,
    ) {
        val prefs = prefs(context) ?: return
        prefs
            .edit()
            .remove(key(simId, "name"))
            .remove(key(simId, "carrier"))
            .remove(key(simId, "slot"))
            .remove(key(simId, "net_op"))
            .remove(key(simId, "sim_op"))
            .remove(key(simId, "roaming"))
            .remove(key(simId, "seen"))
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()

    private fun key(
        simId: Int,
        field: String,
    ) = "sim_${simId}_$field"
}
