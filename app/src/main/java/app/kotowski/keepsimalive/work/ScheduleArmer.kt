package app.kotowski.keepsimalive.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.receiver.SendAlarmReceiver
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleArmer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: KeepaliveRepository,
        private val prefs: AppPrefs,
        private val permissionManager: PermissionManager,
    ) {
        fun sendWorkName(simId: Int): String = "keepalive_send_$simId"

        suspend fun armSend(simId: Int): Boolean {
            val config = repository.getConfig(simId)
            if (config == null || !config.enabled || config.nextSendAtMillis == null) {
                cancelSend(simId)
                return false
            }
            enqueueSendWork(simId, (config.nextSendAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
            // The exact wake is an additional token, never a replacement: the WorkManager
            // work and the safety sweep stay as backup, and the engine's idempotency
            // absorbs both wakes for the same occurrence.
            armExactAlarm(simId, config.nextSendAtMillis)
            return true
        }

        fun armSendNow(simId: Int) {
            enqueueSendWork(simId)
        }

        // The delay defaults to 0: setInitialDelay(0) is the builder's own default
        // (Duration.ZERO), so it is identical to the no-delay armSendNow.
        private fun enqueueSendWork(
            simId: Int,
            delayMillis: Long = 0,
        ) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    sendWorkName(simId),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SendWorker>()
                        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(SendWorker.SIM_ID_KEY to simId))
                        .build(),
                )
        }

        // The exact alarm and the WorkManager wake fire at the same instant: if the work
        // is live (ENQUEUED or RUNNING) when the alarm fires, a REPLACE enqueue would
        // cancel it and the engine would record the interrupted occurrence as skipped. A
        // state check alone does not close the race — an ENQUEUED work can start between
        // the read and the REPLACE — so REPLACE happens only when there is no live work
        // at all (the SendWorkState condition). The ENQUEUED work is left alone: the
        // alarm's only job is to wake the process.
        fun armSendNowUnlessRunning(simId: Int) {
            // The blocking read is safe in the receiver: a local in-memory/SQLite read
            // (no IPC) with a 2 s bound, well inside onReceive's 10-second budget. A
            // failed read degrades to "live work" (logged by SendWorkState): a wrong
            // re-arm REPLACEs a possibly RUNNING send, while a missed wake self-heals
            // (the WorkManager work is the primary wake, see armSend).
            val noLiveWork =
                SendWorkState.hasNoLiveSendWork(
                    WorkManager.getInstance(context),
                    sendWorkName(simId),
                    simId,
                )
            if (noLiveWork == null) {
                return
            }
            if (!noLiveWork) {
                Logger.i("ScheduleArmer", "simId=$simId send work live, exact-alarm wake skipped")
                return
            }
            // Only an absent or terminal work reaches here: the REPLACE can never cancel
            // a live send, and it re-arms a lost one with no delay.
            armSendNow(simId)
        }

        fun cancelSend(simId: Int) {
            WorkManager.getInstance(context).cancelUniqueWork(sendWorkName(simId))
            // Ungated on purpose: an alarm set while the exact wake was on must die with
            // the schedule even if the feature (or permission) is off by now — cancelling
            // a never-set alarm is a no-op.
            cancelExactAlarm(simId)
        }

        // Public so the reconciler can re-arm after a reboot: WorkManager work survives
        // a reboot, alarms do not.
        fun armExactAlarm(
            simId: Int,
            atMillis: Long,
        ) {
            if (!exactWakeActive()) return
            // Clamp past times to now — stock AOSP fires them immediately, but a ROM
            // that drops past alarms loses the wake token.
            val triggeredTime = atMillis.coerceAtLeast(System.currentTimeMillis())
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggeredTime, sendAlarmPendingIntent(simId))
            Logger.i("ScheduleArmer", "simId=$simId exact alarm armed for $triggeredTime")
        }

        fun cancelExactAlarm(simId: Int) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(sendAlarmPendingIntent(simId))
        }

        // Cancels every exact-alarm wake that can be pending: turning the exact wake off
        // must not leave alarms armed from the enabled time.
        suspend fun cancelAllExactAlarms() {
            repository.getAllConfigs().forEach { config ->
                cancelExactAlarm(config.simId)
            }
        }

        suspend fun rearmAllExactAlarms() {
            repository.getAllConfigs().forEach { config ->
                if (config.enabled) {
                    config.nextSendAtMillis?.let { atMillis ->
                        armExactAlarm(config.simId, atMillis)
                    }
                }
            }
        }

        // The single gate of the exact wake: the feature is on AND the "Alarms &
        // reminders" permission is granted (PermissionManager guards against throwing
        // ROMs).
        fun exactWakeActive(): Boolean = prefs.exactWakeEnabled && permissionManager.canScheduleExactAlarms()

        private fun sendAlarmPendingIntent(simId: Int): PendingIntent {
            val intent = Intent(context, SendAlarmReceiver::class.java).putExtra(SendAlarmReceiver.SIM_ID_KEY, simId)
            return PendingIntent.getBroadcast(
                context,
                SEND_ALARM_REQUEST_CODE_BASE + simId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            // One request code per SIM, offset into a range reserved for these intents.
            private const val SEND_ALARM_REQUEST_CODE_BASE = 1000
        }
    }
