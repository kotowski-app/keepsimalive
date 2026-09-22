package app.kotowski.keepsimalive.receiver

import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The merged receiver handles both resume broadcasts (see SafetyResumeReceiver), so the
// success cases run once per action; the shared failure path is verified once (the action
// only picks the log line, not the enqueue).
@RunWith(RobolectricTestRunner::class)
class SafetyResumeReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun enqueuedCount(workName: String): Int =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(workName)
            .get()
            .size

    @Test
    fun `boot completed enqueues the one-shot and periodic safety work`() {
        SafetyResumeReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // The test WorkManager cannot run the Hilt-injected worker, so the enqueue is
        // asserted by presence only; the work routing is covered by
        // ScheduleSafetyWorkerTest.
        assertEquals(1, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_ONCE))
        assertEquals(1, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_PERIODIC))
    }

    @Test
    fun `app update enqueues the one-shot and periodic safety work`() {
        SafetyResumeReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(1, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_ONCE))
        assertEquals(1, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_PERIODIC))
    }

    @Test
    fun `unrelated broadcasts enqueue nothing`() {
        SafetyResumeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        SafetyResumeReceiver().onReceive(context, Intent("com.example.other"))
        // The removed vendor quickboot action (Android 2.x-4.x HTC/Samsung ROMs; no
        // device that can run this app sends it) must enqueue nothing now.
        SafetyResumeReceiver().onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))

        assertEquals(0, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_ONCE))
        assertEquals(0, enqueuedCount(ScheduleSafetyWorker.WORK_NAME_PERIODIC))
    }

    @Test
    fun `an enqueue failure is swallowed instead of crashing the receiver`() {
        // A throwing WorkManager must be caught: a crash here would drop the safety work
        // silently. setDelegate uses the same slot WorkManagerTestInitHelper does, so the
        // throwing instance is what getInstance() returns.
        val failingWorkManager =
            mock<WorkManagerImpl> {
                on {
                    enqueueUniqueWork(anyString(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
                } doThrow IllegalStateException("work manager down")
            }
        WorkManagerImpl.setDelegate(failingWorkManager)
        try {
            SafetyResumeReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        } finally {
            WorkManagerImpl.setDelegate(null)
        }

        // The one-shot enqueue was attempted (and threw); the periodic one must not run.
        verify(failingWorkManager).enqueueUniqueWork(
            eq(ScheduleSafetyWorker.WORK_NAME_ONCE),
            any<ExistingWorkPolicy>(),
            any<OneTimeWorkRequest>(),
        )
        verify(failingWorkManager, never()).enqueueUniquePeriodicWork(
            anyString(),
            any<ExistingPeriodicWorkPolicy>(),
            any<PeriodicWorkRequest>(),
        )
    }
}
