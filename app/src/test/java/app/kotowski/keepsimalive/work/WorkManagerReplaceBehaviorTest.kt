package app.kotowski.keepsimalive.work

import android.content.Context
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch

// Library-behavior guard for the exact WorkManager version in the build (2.9.0):
// a REPLACE of a unique work whose current work is RUNNING cancels that worker
// (doWork sees a CancellationException), drops the old spec and enqueues the
// replacement. The engine's self re-arms (the SendOrchestrator armSend call sites)
// lean on this: the cancelled run has already written every state change, and
// SendWorker.doWork swallows the CancellationException.
@RunWith(RobolectricTestRunner::class)
class WorkManagerReplaceBehaviorTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val uniqueName = "replace_behavior_probe"

    @Before
    fun setup() {
        // The suite-default synchronous test WorkManager can never expose RUNNING;
        // the background-thread executors let the probe be observed mid-run.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
    }

    // Hangs in a suspend-friendly loop until the shared gate is released and records
    // a cancellation of its doWork coroutine. A CoroutineWorker like the app's
    // SendWorker: a plain blocking await would outrun the library's cancellation
    // (no suspension point left to throw in).
    class ReplaceProbeWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            try {
                while (releaseGate.getCount() > 0) {
                    delay(20)
                }
                return Result.success()
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        }

        companion object {
            @Volatile
            var releaseGate: CountDownLatch = CountDownLatch(1)

            @Volatile
            var cancellationObserved: Boolean = false
        }
    }

    private fun workInfo(): WorkInfo? =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get()
            .firstOrNull()

    // WorkManager posts the worker start-up chain (WorkForegroundRunnable and the
    // startWork listener) to the main-thread executor, which Robolectric leaves
    // paused: idle it while waiting, or a CoroutineWorker's doWork never starts.
    private fun pumpMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitWork(
        timeoutMillis: Long = 10_000,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            pumpMainLooper()
            val info = workInfo()
            if (info != null && predicate(info)) return info
            Thread.sleep(50)
        }
        return null
    }

    private fun awaitTrue(
        message: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            pumpMainLooper()
            Thread.sleep(50)
        }
        fail(message)
    }

    @Test
    fun `REPLACE cancels the running worker and the replacement takes over the slot`() {
        ReplaceProbeWorker.releaseGate = CountDownLatch(1)
        ReplaceProbeWorker.cancellationObserved = false
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReplaceProbeWorker>()
                    .build(),
            )
        val running = awaitWork { it.state == WorkInfo.State.RUNNING }
        assertNotNull("the probe work did not reach RUNNING in time", running)

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReplaceProbeWorker>()
                    .build(),
            )
        val replacement = awaitWork { it.id != running!!.id }
        assertNotNull("the replacement work did not appear in time", replacement)

        awaitTrue("the running worker did not observe the cancellation in time") {
            ReplaceProbeWorker.cancellationObserved
        }

        // Let the replacement finish: release the shared gate.
        ReplaceProbeWorker.releaseGate.countDown()
        val done = awaitWork { it.state == WorkInfo.State.SUCCEEDED }
        assertNotNull("the replacement work did not finish", done)
        assertEquals(replacement!!.id, done!!.id)
    }
}
