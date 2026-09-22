package app.kotowski.keepsimalive.work

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kotowski.keepsimalive.util.Logger
import java.util.concurrent.TimeUnit

// The "no live send work" check shared by the armer's exact-alarm wake and the
// reconciler's re-arm gate: only an absent or terminal work can be
// REPLACE-re-enqueued — a live (ENQUEUED or RUNNING) send work must never be
// replaced, or the in-flight keepalive is cancelled.
object SendWorkState {
    // Reads the unique send work's state with a 2 s bound: true = absent or terminal,
    // false = live (ENQUEUED/RUNNING), null = the read itself failed (stuck work DB /
    // timeout) — the caller must treat null as live work (never re-arm): a wrong re-arm
    // REPLACEs a possibly RUNNING send and loses the occurrence, while a missed re-arm
    // self-heals on the next reconcile. Blocking: the caller decides the dispatcher —
    // the alarm receiver calls it on its own thread (a local read, inside onReceive's
    // budget), the reconciler wraps it in Dispatchers.IO.
    fun hasNoLiveSendWork(
        workManager: WorkManager,
        workName: String,
        simId: Int,
    ): Boolean? =
        try {
            val infos = workManager.getWorkInfosForUniqueWork(workName).get(2, TimeUnit.SECONDS)
            val state = infos.firstOrNull()?.state
            infos.isEmpty() ||
                state == WorkInfo.State.CANCELLED ||
                state == WorkInfo.State.FAILED ||
                state == WorkInfo.State.SUCCEEDED
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Logger.w(
                "SendWorkState",
                "simId=$simId failed to read the send work state, treating it as live work: ${e.message}",
                e,
            )
            null
        }
}
