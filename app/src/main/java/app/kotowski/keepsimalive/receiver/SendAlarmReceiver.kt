package app.kotowski.keepsimalive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.work.ScheduleArmer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// The exact-alarm wake (experimental, Settings "Exact wake-up"): fires at the scheduled time
// and re-wakes the same send work the WorkManager wake would run, unless that work is
// live (ENQUEUED or RUNNING: a same-instant REPLACE would cancel it and lose the
// occurrence; the check lives in ScheduleArmer). It does nothing else: no engine logic
// here (the 10-second receiver budget is never at risk), and the WorkManager work and
// the safety sweep keep running as the backup — the engine's idempotency absorbs both
// wakes arriving for the same occurrence.
@AndroidEntryPoint
class SendAlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var armer: ScheduleArmer

    private val tag = "SendAlarmReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val simId = intent.getIntExtra(SIM_ID_KEY, -1)
        if (simId < 0) {
            Logger.w(tag, "alarm fired without a sim id, ignoring")
            return
        }
        Logger.i(tag, "exact alarm fired simId=$simId")
        try {
            armer.armSendNowUnlessRunning(simId)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to wake the send work: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    companion object {
        const val SIM_ID_KEY = "send_alarm_sim_id"
    }
}
