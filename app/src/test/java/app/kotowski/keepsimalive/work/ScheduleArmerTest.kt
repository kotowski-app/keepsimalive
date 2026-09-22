package app.kotowski.keepsimalive.work

import android.app.AlarmManager
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.receiver.SendAlarmReceiver
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.PermissionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ScheduleArmerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var repository: KeepaliveRepository
    private lateinit var armer: ScheduleArmer

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        repository = mock()
        armer = ScheduleArmer(context, repository, AppPrefs(context), PermissionManager(context))
    }

    private fun enabledConfig(nextSendAtMillis: Long): SimKeepaliveConfig =
        SimKeepaliveConfig(
            simId = 1,
            enabled = true,
            recipientPhone = "+15550100",
            message = "keep alive",
            nextSendAtMillis = nextSendAtMillis,
        )

    private fun workInfo(uniqueName: String): WorkInfo? =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get()
            .firstOrNull()

    @Test
    fun `armSend enqueues work scheduled at next send`() =
        runBlocking {
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))

            assertTrue(armer.armSend(1))

            val info = workInfo(armer.sendWorkName(1))
            assertNotNull(info)
            assertEquals(WorkInfo.State.ENQUEUED, info!!.state)
            assertTrue(
                "runAfter ${info.nextScheduleTimeMillis} not within 5s of $nextSend",
                Math.abs(info.nextScheduleTimeMillis - nextSend) < 5_000,
            )
        }

    @Test
    fun `armSend with disabled config cancels existing work`() =
        runBlocking {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    armer.sendWorkName(1),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SendWorker>()
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build(),
                )
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend).copy(enabled = false))

            assertFalse(armer.armSend(1))

            val info = workInfo(armer.sendWorkName(1))
            assertEquals(WorkInfo.State.CANCELLED, info?.state)
        }

    @Test
    fun `armSendNow enqueues work with no delay`() =
        runBlocking {
            armer.armSendNow(1)

            val info = workInfo(armer.sendWorkName(1))
            assertNotNull(info)
            assertEquals(0L, info!!.initialDelayMillis)
        }

    // Shared with HangingSendWorker (the test WorkManager instantiates it on its own
    // thread): the worker hangs until the test releases it, so the work can be observed
    // in RUNNING.
    companion object {
        @Volatile
        var releaseGate: CountDownLatch = CountDownLatch(1)
    }

    // Hangs until released, so the work holding the send slot can be observed in RUNNING
    // (the synchronous test WorkManager can never expose that state).
    class HangingSendWorker(
        context: Context,
        params: WorkerParameters,
    ) : Worker(context, params) {
        override fun doWork(): Result {
            releaseGate.await()
            return Result.success()
        }
    }

    private fun awaitWorkState(
        uniqueName: String,
        state: WorkInfo.State,
        timeoutMillis: Long = 10_000,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val info =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueName)
                    .get()
                    .firstOrNull { it.state == state }
            if (info != null) return info
            Thread.sleep(50)
        }
        return null
    }

    @Test
    fun `armSendNowUnlessRunning enqueues the send work while no work is present`() =
        runBlocking {
            armer.armSendNowUnlessRunning(1)

            val info = workInfo(armer.sendWorkName(1))
            assertNotNull(info)
            assertEquals(0L, info!!.initialDelayMillis)
        }

    // The suite-default test WorkManager runs every work synchronously, so a work can
    // never be observed in RUNNING; this test re-initializes it with the default
    // (background-thread) executors instead. The helper swaps the static WorkManager
    // instance and @Before re-initializes it for every test, so the mode does not leak
    // into other tests.
    @Test
    fun `armSendNowUnlessRunning skips the wake while the send work is running`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        releaseGate = CountDownLatch(1)
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                armer.sendWorkName(1),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HangingSendWorker>()
                    .build(),
            )
        val running = awaitWorkState(armer.sendWorkName(1), WorkInfo.State.RUNNING)
        assertNotNull("the send work did not reach RUNNING in time", running)
        val alarmsBefore = shadowAlarms()

        armer.armSendNowUnlessRunning(1)

        val after =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(armer.sendWorkName(1))
                .get()
                .filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, after.size)
        assertEquals(running!!.id, after.first().id)
        assertEquals(WorkInfo.State.RUNNING, after.first().state)
        assertEquals(alarmsBefore.size, shadowAlarms().size)

        releaseGate.countDown()
        awaitWorkState(armer.sendWorkName(1), WorkInfo.State.SUCCEEDED)
    }

    @Test
    fun `armSendNowUnlessRunning leaves a queued work alone instead of replacing it`() =
        runBlocking {
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))
            assertTrue(armer.armSend(1))
            val before = workInfo(armer.sendWorkName(1))!!
            assertEquals(WorkInfo.State.ENQUEUED, before.state)
            assertTrue(before.initialDelayMillis > 0)
            val beforeCount =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(armer.sendWorkName(1))
                    .get()
                    .size

            armer.armSendNowUnlessRunning(1)

            // The ENQUEUED work is already owned by the WorkManager executor (the alarm's
            // wake only has to wake the process), so it is left alone: same id, same
            // original delay, no new work enqueued.
            val infos =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(armer.sendWorkName(1))
                    .get()
            assertEquals(beforeCount, infos.size)
            val after = infos.filter { it.state != WorkInfo.State.CANCELLED }
            assertEquals(1, after.size)
            assertEquals(before.id, after.first().id)
            assertEquals(before.initialDelayMillis, after.first().initialDelayMillis)
        }

    // A terminal work cannot be running, so the wake's REPLACE is safe here: the unique
    // work resolves to a fresh no-delay spec. WorkManager drops the old terminal spec
    // with the REPLACE (it is no longer listed for the name), and the test WorkManager
    // cannot instantiate the Hilt-injected send worker, so the fresh spec is asserted by
    // presence and delay, not by end state.
    @Test
    fun `armSendNowUnlessRunning re-arms a fresh no-delay work while the send work is cancelled`() =
        runBlocking {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    armer.sendWorkName(1),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<HangingSendWorker>()
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build(),
                )
            WorkManager.getInstance(context).cancelUniqueWork(armer.sendWorkName(1))
            val before = workInfo(armer.sendWorkName(1))!!
            assertEquals(WorkInfo.State.CANCELLED, before.state)

            armer.armSendNowUnlessRunning(1)

            val after = workInfo(armer.sendWorkName(1))!!
            assertTrue(after.id != before.id)
            assertEquals(0L, after.initialDelayMillis)
        }

    @Test
    fun `armSendNowUnlessRunning re-arms a fresh no-delay work while the send work is succeeded`() =
        runBlocking {
            // A queued no-delay work runs immediately on the synchronous test WorkManager
            // and ends SUCCEEDED (the gate is pre-released so the hang is skipped).
            releaseGate = CountDownLatch(0)
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    armer.sendWorkName(1),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<HangingSendWorker>()
                        .build(),
                )
            val before = workInfo(armer.sendWorkName(1))!!
            assertEquals(WorkInfo.State.SUCCEEDED, before.state)

            armer.armSendNowUnlessRunning(1)

            val after = workInfo(armer.sendWorkName(1))!!
            assertTrue(after.id != before.id)
            assertEquals(0L, after.initialDelayMillis)
        }

    // A failed work-state read must NOT arm (fail-closed): a wrong re-arm would REPLACE a
    // possibly RUNNING send and cancel the in-flight keepalive (the occurrence is then lost
    // via the stale check), while a missed wake self-heals — the WorkManager work is the
    // primary wake and a truly lost work is re-armed by the next reconcile or the safety
    // sweep. The mock throws on the read (setDelegate uses the same slot
    // WorkManagerTestInitHelper does, so getInstance() returns the mock); the wake must skip
    // the enqueue instead of arming anyway.
    @Test
    fun `armSendNowUnlessRunning skips the wake while the work state read fails`() {
        val failingWorkManager =
            mock<WorkManagerImpl> {
                on {
                    getWorkInfosForUniqueWork(anyString())
                } doThrow IllegalStateException("state read down")
            }
        WorkManagerImpl.setDelegate(failingWorkManager)
        try {
            armer.armSendNowUnlessRunning(1)
        } finally {
            WorkManagerImpl.setDelegate(null)
        }

        // No re-arm was attempted: the failed read must not turn into a REPLACE.
        verify(failingWorkManager, never()).enqueueUniqueWork(
            anyString(),
            any<ExistingWorkPolicy>(),
            any<OneTimeWorkRequest>(),
        )
    }

    // Only the exact-alarm wakes: the WorkManager wake above also parks a PendingIntent alarm
    // of its own, so count alarms whose intent targets the receiver, not every scheduled alarm.
    private fun shadowAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        (
            shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .getScheduledAlarms()
                .filter { alarm ->
                    val operation = alarm.operation ?: return@filter false
                    shadowOf(operation).getSavedIntent().component?.className == SendAlarmReceiver::class.java.name
                }
        )

    private fun prefs() = AppPrefs(context)

    // The SCHEDULE_EXACT_ALARM gate only exists from API 31 (the suite default is 29, where exact
    // alarms need no permission), so the permission-gated paths run on 33.
    @Config(sdk = [33])
    @Test
    fun `armSend arms the exact alarm at the scheduled time while the exact wake is on`() =
        runBlocking {
            prefs().exactWakeEnabled = true
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))

            assertTrue(armer.armSend(1))

            val alarms = shadowAlarms()
            assertEquals(1, alarms.size)
            assertEquals(nextSend, alarms[0].triggerAtTime)
            assertTrue(alarms[0].allowWhileIdle)
        }

    @Config(sdk = [33])
    @Test
    fun `armExactAlarm coerces past trigger time to now`() {
        prefs().exactWakeEnabled = true
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val pastTime = System.currentTimeMillis() - 10_000L

        armer.armExactAlarm(1, pastTime)

        val alarms = shadowAlarms()
        assertEquals(1, alarms.size)
        assertTrue("alarm triggered at ${alarms[0].triggerAtTime} should be >= now, not $pastTime", alarms[0].triggerAtTime >= pastTime)
    }

    @Test
    fun `armSend does not arm the exact alarm while the exact wake is off`() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))

            assertTrue(armer.armSend(1))

            assertTrue(shadowAlarms().isEmpty())
        }

    @Config(sdk = [33])
    @Test
    fun `armSend does not arm the exact alarm while the exact alarm permission is missing`() =
        runBlocking {
            prefs().exactWakeEnabled = true
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))

            assertTrue(armer.armSend(1))

            assertTrue(shadowAlarms().isEmpty())
        }

    @Test
    fun `armSendNow does not arm the exact alarm`() =
        runBlocking {
            prefs().exactWakeEnabled = true
            ShadowAlarmManager.setCanScheduleExactAlarms(true)

            armer.armSendNow(1)

            assertTrue(shadowAlarms().isEmpty())
        }

    @Test
    fun `cancelSend cancels the pending exact alarm even while the exact wake is off`() =
        runBlocking {
            prefs().exactWakeEnabled = true
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))
            assertTrue(armer.armSend(1))
            assertEquals(1, shadowAlarms().size)

            prefs().exactWakeEnabled = false
            armer.cancelSend(1)

            assertTrue(shadowAlarms().isEmpty())
        }

    @Test
    fun `cancelAllExactAlarms cancels the pending alarms of every configured sim`() =
        runBlocking {
            prefs().exactWakeEnabled = true
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(enabledConfig(nextSend))
            whenever(repository.getConfig(2)).thenReturn(enabledConfig(nextSend).copy(simId = 2))
            whenever(repository.getAllConfigs())
                .thenReturn(listOf(enabledConfig(nextSend), enabledConfig(nextSend).copy(simId = 2)))
            assertTrue(armer.armSend(1))
            assertTrue(armer.armSend(2))
            assertEquals(2, shadowAlarms().size)

            armer.cancelAllExactAlarms()

            assertTrue(shadowAlarms().isEmpty())
        }
}
