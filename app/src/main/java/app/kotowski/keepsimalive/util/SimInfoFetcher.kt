package app.kotowski.keepsimalive.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import app.kotowski.keepsimalive.R

data class SimPhoneStateData(
    val simId: Int,
    val displayName: String,
    val carrierName: String,
    val slotIndex: Int,
    val networkOperatorName: String,
    val simOperatorName: String,
    val isNetworkRoaming: Boolean,
)

object SimInfoFetcher {
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    fun getSimPhoneState(
        context: Context,
        subId: Int,
    ): SimPhoneStateData? {
        // A SIM that is switched off or removed is not in the active subscription list: report
        // it as absent instead of serving defaults for a dead subscription id. The list is
        // read once and serves both the presence check and the identity: a SIM that
        // disappears in between must not be served (and cached) with a fake identity. A
        // read that throws degrades to absent, like a SIM not in the list.
        val activeSubscriptions =
            runCatching {
                context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            }.getOrNull()
        val subInfo = activeSubscriptions?.find { it.subscriptionId == subId } ?: return null

        // createForSubscriptionId exists from API 24, but 7.0/7.1 (API 24/25) OEM telephony
        // implementations are unstable, so the per-SIM radio read is restricted to API 26+
        // (the stable Multi-SIM baseline). The SIM is still present (it is in the active
        // list above), so below 26 it is reported with "unknown" (empty) radio fields
        // instead of absent. On API 26+ the call is still exception-guarded: a throwing or
        // missing per-SIM instance degrades to "unknown" the same way.
        val tmForSub =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context
                        .getSystemService(TelephonyManager::class.java)
                        ?.createForSubscriptionId(subId)
                } catch (e: Exception) {
                    Logger.e("SimInfoFetcher", "createForSubscriptionId($subId) threw: ${e.message}", e)
                    null
                }
            } else {
                null
            }

        return try {
            val data =
                SimPhoneStateData(
                    simId = subId,
                    // Both fallbacks are user-facing, so they come from strings.xml; the name
                    // names the subscription id, not a tray slot. Null and empty system names
                    // fall back the same way.
                    displayName =
                        subInfo.displayName?.toString()?.ifEmpty { null }
                            ?: context.getString(R.string.sim_name_fallback, subId),
                    carrierName =
                        subInfo.carrierName?.toString()?.ifEmpty { null }
                            ?: context.getString(R.string.sim_carrier_unknown),
                    slotIndex = subInfo.simSlotIndex + 1,
                    // Null below API 26 (no per-SIM radio read) or when the per-SIM
                    // instance is unavailable: the fields degrade to "unknown" instead
                    // of reporting the present SIM as absent.
                    networkOperatorName = tmForSub?.networkOperatorName ?: "",
                    simOperatorName = tmForSub?.simOperatorName ?: "",
                    isNetworkRoaming = tmForSub?.isNetworkRoaming ?: false,
                )
            // Remember the identity so the SIM can still be shown while it is out of the tray.
            SimIdentityCache.save(
                context,
                CachedSimIdentity(
                    simId = subId,
                    displayName = data.displayName,
                    carrierName = data.carrierName,
                    slotIndex = data.slotIndex,
                    networkOperatorName = data.networkOperatorName,
                    simOperatorName = data.simOperatorName,
                    isNetworkRoaming = data.isNetworkRoaming,
                    lastSeenAtMillis = System.currentTimeMillis(),
                ),
            )
            data
        } catch (e: SecurityException) {
            Logger.e("SimInfoFetcher", "SecurityException fetching SIM $subId data: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("SimInfoFetcher", "Exception fetching SIM $subId data: ${e.message}", e)
            null
        }
    }

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

    // Whether a SIM with this subscription id is currently in the active subscription list.
    // Without READ_PHONE_STATE the list is not readable, so the SIM counts as present (the
    // caller decides).
    fun isSimPresent(
        context: Context,
        subId: Int,
    ): Boolean {
        if (!hasPhoneStatePermission(context)) return true
        val activeSubscriptions =
            try {
                context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                Logger.e("SimInfoFetcher", "SecurityException reading active subscriptions: ${e.message}", e)
                null
            } catch (e: Exception) {
                Logger.e("SimInfoFetcher", "Exception reading active subscriptions: ${e.message}", e)
                null
            }
        // null (query failed) counts as present: let the radio decide instead of failing the
        // occurrence.
        return activeSubscriptions?.any { it.subscriptionId == subId } ?: true
    }
}
