package app.kotowski.keepsimalive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import dagger.hilt.android.AndroidEntryPoint

// One receiver for the two "resume background work" broadcasts (boot, app update), the way
// TimeChangeReceiver handles the two clock broadcasts: both run the same recovery, so the
// action only picks the log line before the shared enqueue.
@AndroidEntryPoint
class SafetyResumeReceiver : BroadcastReceiver() {
    private val tag = "SafetyResumeReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val message =
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> "Boot intent received, resuming background work"
                Intent.ACTION_MY_PACKAGE_REPLACED -> "App updated, resuming background work"
                else -> return
            }
        Logger.i(tag, message)
        try {
            ScheduleSafetyWorker.enqueueOnce(context)
            ScheduleSafetyWorker.enqueuePeriodic(context)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to enqueue safety workers: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
}
