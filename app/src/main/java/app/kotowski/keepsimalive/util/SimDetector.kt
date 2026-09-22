package app.kotowski.keepsimalive.util

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

// Hardware modem count, not active subscriptions, decides single vs multi SIM: a dual-SIM
// device with one tray empty still has two modems, and the slot badge/number stays
// meaningful for the inserted SIM.
object SimDetector {
    // internal: unit tests reset the cache between runs (no other reset seam exists)
    internal var cachedModemCount: Int? = null

    fun getSupportedModemCount(context: Context): Int {
        cachedModemCount?.let { return it }
        val count =
            try {
                val tm = context.getSystemService(TelephonyManager::class.java)
                if (tm == null) {
                    0
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tm.supportedModemCount
                } else {
                    // deprecated, but the only option below R
                    @Suppress("DEPRECATION")
                    tm.phoneCount
                }
            } catch (e: Exception) {
                Logger.w("SimDetector", "Failed to get supported modem count: ${e.message}", e)
                0
            }
        cachedModemCount = if (count > 0) count else 1
        return cachedModemCount!!
    }

    fun isMultiSimDevice(context: Context): Boolean = isMultiSimDevice(getSupportedModemCount(context))

    fun isMultiSimDevice(modemCount: Int): Boolean = modemCount > 1
}
