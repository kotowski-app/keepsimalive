package app.kotowski.keepsimalive.receiver

import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class TimeChangeReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun onceWorkInfos() =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(ScheduleSafetyWorker.WORK_NAME_ONCE)
            .get()

    // WorkInfo does not expose the input data, so read the flag from the persisted
    // work spec.
    private fun enqueuedInputData(): Data {
        val impl = WorkManager.getInstance(context) as WorkManagerImpl
        val dao = impl.workDatabase.workSpecDao()
        val id = dao.getWorkSpecIdAndStatesForName(ScheduleSafetyWorker.WORK_NAME_ONCE).single().id
        return dao.getWorkSpec(id)!!.input
    }

    @Test
    fun `timezone change enqueues the one-shot recompute work`() {
        TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        // The test WorkManager cannot run the Hilt-injected worker (it ends FAILED), so the
        // enqueued work is asserted by presence only; the flag routing is covered by
        // ScheduleSafetyWorkerTest.
        assertEquals(1, onceWorkInfos().size)
    }

    @Test
    fun `time change with a fresh anchor is ignored like other broadcasts`() {
        // A fresh anchor means no real offset delta: TIME_CHANGED must not enqueue, and
        // the other broadcasts stay ignored.
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_DATE_CHANGED))

        assertTrue(onceWorkInfos().isEmpty())
    }

    @Test
    fun `time change with a stale anchor enqueues the one-shot work`() {
        // Seed an anchor an hour off the current offset so the offset delta reads as real.
        context
            .getSharedPreferences(ScheduleSafetyWorker.CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                ScheduleSafetyWorker.KEY_ANCHORED_OFFSET_SECONDS,
                ScheduleSafetyWorker.currentOffsetSeconds(context) + 3600,
            ).apply()
        TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        // The test WorkManager cannot run the Hilt-injected worker, so assert presence and
        // that the enqueued work carries the recompute flag explicitly (not via the
        // stale-anchor self-carry).
        val infos = onceWorkInfos()
        assertEquals(1, infos.size)
        assertTrue(enqueuedInputData().getBoolean(ScheduleSafetyWorker.KEY_TIMEZONE_CHANGED, false))
    }

    // Same-offset zone change: the gate must fire on a zone-ID delta too, not only an
    // offset delta.
    @Test
    fun `time change with a same-offset zone change enqueues the one-shot work`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Karachi"))) // permanent UTC+5
            ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
            // The zone changes with the current offset unchanged.
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Etc/GMT-5"))) // permanent UTC+5
            TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        } finally {
            TimeZone.setDefault(original)
        }

        // Presence only, as above: the test WorkManager cannot run the Hilt-injected worker.
        assertEquals(1, onceWorkInfos().size)
    }
}
