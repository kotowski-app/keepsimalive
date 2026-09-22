package app.kotowski.keepsimalive.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.kotowski.keepsimalive.R

data class SimInfo(
    val simId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val isInService: Boolean,
    val networkOperatorName: String = "",
    val simOperatorName: String = "",
    val isNetworkRoaming: Boolean = false,
)

// No caching: the only caller (the dashboard poll) wants a fresh read on every pass, and the
// resolver is called from both the main thread and Dispatchers.IO, so a shared cache would
// need synchronization for no benefit.
object SimNameResolver {
    fun getActiveSims(context: Context): List<SimInfo> {
        // The single READ_PHONE_STATE gate, shared with the detail screen and the presence
        // check (SimInfoFetcher.hasPhoneStatePermission): without it the active list is
        // unreadable, so there are no SIMs to report.
        if (!SimInfoFetcher.hasPhoneStatePermission(context)) {
            return emptyList()
        }

        val simInfo =
            try {
                val subscriptionManager =
                    try {
                        context.getSystemService(SubscriptionManager::class.java)
                    } catch (e: Exception) {
                        Logger.e("SimNameResolver", "getSystemService(SubscriptionManager) threw: ${e.message}", e)
                        null
                    }

                val telephonyManager =
                    try {
                        context.getSystemService(TelephonyManager::class.java)
                    } catch (e: Exception) {
                        Logger.e("SimNameResolver", "getSystemService(TelephonyManager) threw: ${e.message}", e)
                        null
                    }

                val activeSubscriptions =
                    try {
                        subscriptionManager?.activeSubscriptionInfoList
                    } catch (e: Exception) {
                        Logger.e("SimNameResolver", "activeSubscriptionInfoList threw: ${e.message}", e)
                        null
                    }

                if (!activeSubscriptions.isNullOrEmpty()) {
                    activeSubscriptions.map { info ->
                        val subId = info.subscriptionId

                        // User-facing (Dashboard card carrier): the fallback comes from
                        // strings.xml, for a missing (null or empty) name or a throwing read.
                        val carrierName =
                            try {
                                info.carrierName?.toString()?.ifEmpty { null }
                                    ?: context.getString(R.string.sim_carrier_unknown)
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  info.carrierName threw: ${e.message}", e)
                                context.getString(R.string.sim_carrier_unknown)
                            }

                        val displayName =
                            try {
                                info.displayName?.toString() ?: carrierName
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  info.displayName threw: ${e.message}", e)
                                carrierName
                            }

                        val slotIndex =
                            try {
                                info.simSlotIndex
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  info.simSlotIndex threw: ${e.message}", e)
                                0
                            }

                        // createForSubscriptionId exists from API 24, but 7.0/7.1 (API 24/25)
                        // OEM telephony implementations are unstable, so the per-SIM radio
                        // read is restricted to API 26+ (the stable Multi-SIM baseline).
                        // Below 26 the fields degrade to "unknown" (a null tmForSub) instead
                        // of serving the default SIM's data as this SIM's.
                        val tmForSub =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    telephonyManager?.createForSubscriptionId(subId)
                                } catch (e: Exception) {
                                    Logger.e("SimNameResolver", "  createForSubscriptionId($subId) threw: ${e.message}", e)
                                    null
                                }
                            } else {
                                null
                            }

                        // serviceState is API 26+ (tmForSub is only non-null there anyway) and
                        // needs permissions the user may have revoked; any failure degrades to
                        // false, so no explicit gate is added.
                        @SuppressLint("MissingPermission")
                        val isInService =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    val state = tmForSub?.serviceState
                                    state?.state == android.telephony.ServiceState.STATE_IN_SERVICE
                                } catch (e: SecurityException) {
                                    Logger.e("SimNameResolver", "  serviceState SecurityException: ${e.message}", e)
                                    false
                                } catch (e: Exception) {
                                    Logger.e("SimNameResolver", "  serviceState threw: ${e.message}", e)
                                    false
                                }
                            } else {
                                false
                            }

                        val networkOperatorName =
                            try {
                                tmForSub?.networkOperatorName ?: ""
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  networkOperatorName threw: ${e.message}", e)
                                ""
                            }

                        val simOperatorName =
                            try {
                                tmForSub?.simOperatorName ?: ""
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  simOperatorName threw: ${e.message}", e)
                                ""
                            }

                        val isNetworkRoaming =
                            try {
                                tmForSub?.isNetworkRoaming ?: false
                            } catch (e: Exception) {
                                Logger.e("SimNameResolver", "  isNetworkRoaming threw: ${e.message}", e)
                                false
                            }

                        SimInfo(
                            simId = subId,
                            slotIndex = slotIndex,
                            displayName = displayName,
                            carrierName = carrierName,
                            isInService = isInService,
                            networkOperatorName = networkOperatorName,
                            simOperatorName = simOperatorName,
                            isNetworkRoaming = isNetworkRoaming,
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: SecurityException) {
                Logger.e("SimNameResolver", "SecurityException during SIM resolution: ${e.message}", e)
                emptyList()
            } catch (e: Exception) {
                Logger.e("SimNameResolver", "Unexpected exception during SIM resolution: ${e.message}", e)
                emptyList()
            }

        return simInfo
    }

    // Per-SIM display name for a title that must survive the SIM leaving the tray: live
    // telephony first (routed through SimInfoFetcher), then the last known identity (the SIM
    // is out of the tray), then the i18n subscription-id fallback. Exception-safe by
    // construction (both reads degrade to null internally). Synchronous: the caller decides
    // the dispatcher.
    fun resolveDisplayName(
        context: Context,
        simId: Int,
    ): String {
        // getSimPhoneState declares READ_PHONE_STATE but degrades to null internally on
        // SecurityException, so a read without the permission is a miss, not a crash.
        @SuppressLint("MissingPermission")
        val live = SimInfoFetcher.getSimPhoneState(context, simId)
        return live?.displayName?.ifEmpty { null }
            ?: SimIdentityCache.get(context, simId)?.displayName?.ifEmpty { null }
            ?: context.appString(R.string.sim_name_fallback, simId)
    }
}
