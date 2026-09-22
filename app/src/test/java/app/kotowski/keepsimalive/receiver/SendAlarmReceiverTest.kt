package app.kotowski.keepsimalive.receiver

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.work.ScheduleArmer
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The receiver re-wakes the per-SIM send work (ScheduleArmer.armSendNow), so the effect is
// asserted on the WorkManager queue the way SafetyResumeReceiverTest asserts its enqueues: the test
// WorkManager cannot run the Hilt-injected SendWorker, so presence/state of the unique work is
// the observable (the work routing itself is covered by ScheduleArmerTest).
@RunWith(RobolectricTestRunner::class)
class SendAlarmReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private lateinit var armer: ScheduleArmer
    private lateinit var receiver: SendAlarmReceiver

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        armer = ScheduleArmer(context, mock<KeepaliveRepository>(), AppPrefs(context), PermissionManager(context))
        receiver = SendAlarmReceiver().also { it.armer = armer }
    }

    // The test WorkManager attempts the Hilt-injected SendWorker and it ends FAILED (there is
    // no Hilt harness here), so the enqueue is asserted by presence, like SafetyResumeReceiverTest;
    // the work routing is covered by ScheduleArmerTest.
    private fun sendWorkPresent(simId: Int): Boolean =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(armer.sendWorkName(simId))
            .get()
            .isNotEmpty()

    @Test
    fun `a fired alarm re-wakes the send work of its sim`() {
        receiver.onReceive(
            context,
            Intent(context, SendAlarmReceiver::class.java).putExtra(SendAlarmReceiver.SIM_ID_KEY, 3),
        )

        assertTrue(sendWorkPresent(3))
    }

    @Test
    fun `an alarm for sim id zero is accepted (the sentinel is negative)`() {
        receiver.onReceive(
            context,
            Intent(context, SendAlarmReceiver::class.java).putExtra(SendAlarmReceiver.SIM_ID_KEY, 0),
        )

        assertTrue(sendWorkPresent(0))
    }

    @Test
    fun `an alarm without a sim id is ignored`() {
        receiver.onReceive(context, Intent(context, SendAlarmReceiver::class.java))

        assertFalse(sendWorkPresent(1))
    }

    @Test
    fun `an enqueue failure is swallowed instead of crashing the receiver`() {
        // A throwing WorkManager must be caught: a crash here would drop the one-shot exact
        // alarm with the process. setDelegate uses the same slot WorkManagerTestInitHelper
        // does, so the throwing instance is what getInstance() returns. The state read must
        // SUCCEED (empty = no live work) for the wake to reach the enqueue: the armer fails
        // closed on a failed state read (pinned in ScheduleArmerTest) and would skip the
        // enqueue this test exists to cover.
        val failingWorkManager =
            mock<WorkManagerImpl> {
                on { getWorkInfosForUniqueWork(anyString()) }
                    .thenReturn(Futures.immediateFuture(emptyList()))
                on {
                    enqueueUniqueWork(anyString(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
                } doThrow IllegalStateException("work manager down")
            }
        WorkManagerImpl.setDelegate(failingWorkManager)
        try {
            receiver.onReceive(
                context,
                Intent(context, SendAlarmReceiver::class.java).putExtra(SendAlarmReceiver.SIM_ID_KEY, 3),
            )
        } finally {
            WorkManagerImpl.setDelegate(null)
        }

        // The wake was attempted (and threw); the receiver must not crash on it.
        verify(failingWorkManager).enqueueUniqueWork(
            eq(armer.sendWorkName(3)),
            any<ExistingWorkPolicy>(),
            any<OneTimeWorkRequest>(),
        )
    }
}
