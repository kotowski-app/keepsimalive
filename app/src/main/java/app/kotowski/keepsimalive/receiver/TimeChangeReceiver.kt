package app.kotowski.keepsimalive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import dagger.hilt.android.AndroidEntryPoint

// A clock change moves the wall-clock time of every armed nextSendAtMillis (the absolute
// instant is unchanged, its local time is not). Two system broadcasts are handled:
// - ACTION_TIMEZONE_CHANGED: the zone ID changed, so it always enqueues the flagged
//   recompute.
// - ACTION_TIME_CHANGED: the catch-all for UTC-offset moves (DST transitions,
//   carrier-driven zone changes) but noisy (NTP resyncs, manual clock jumps), so it is
//   delta-gated: the recompute is enqueued only when the recorded anchor (zone ID or
//   offset) differs from the current one.
// Manifest-declared so it fires even when the app process is dead; the one-shot worker
// clears and recomputes every armed schedule in the new zone/offset.
@AndroidEntryPoint
class TimeChangeReceiver : BroadcastReceiver() {
    private val tag = "TimeChangeReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // Unconditional: a zone-ID change to a zone with the same current offset can
                // still have divergent future DST rules (e.g. London winter GMT ->
                // permanent-UTC+0 Iceland), which a today-offset diff cannot see.
                Logger.i(tag, "Timezone changed, recomputing armed schedules in the new zone")
                try {
                    ScheduleSafetyWorker.enqueueOnce(context, afterTimezoneChange = true)
                } catch (e: Exception) {
                    Logger.e(tag, "Failed to enqueue timezone reconcile: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }

            Intent.ACTION_TIME_CHANGED -> {
                // Act only when the anchor (zone ID or offset) is stale — the broadcast
                // is noisy (NTP resyncs, manual clock jumps). The flag is passed
                // explicitly so the recompute runs even if the anchor is re-recorded
                // (e.g. by a concurrent doWork) before the work runs.
                if (ScheduleSafetyWorker.anchorStale(context)) {
                    Logger.i(tag, "Clock changed (zone or offset), recomputing armed schedules")
                    try {
                        ScheduleSafetyWorker.enqueueOnce(context, afterTimezoneChange = true)
                    } catch (e: Exception) {
                        Logger.e(tag, "Failed to enqueue clock reconcile: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }
            }
        }
    }
}
