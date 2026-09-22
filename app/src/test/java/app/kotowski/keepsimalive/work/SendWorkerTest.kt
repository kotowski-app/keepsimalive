package app.kotowski.keepsimalive.work

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.kotowski.keepsimalive.util.LogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

// doWork pins the in-process premise of the per-SIM send lock through SendProcessGate,
// which resolves the name via Process.myProcessName when the framework has it — the global
// Robolectric SDK 29 pin does not, so this test class runs at SDK 34 where the shadow
// implements it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendWorkerTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        LogBuffer.clear()
        ShadowProcess.setProcessName(context.packageName)
    }

    private fun worker(orchestrator: SendOrchestrator): SendWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SendWorker(appContext, workerParameters, orchestrator)
            }
        return TestListenableWorkerBuilder
            .from(context, SendWorker::class.java)
            .setInputData(workDataOf(SendWorker.SIM_ID_KEY to 11))
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `a cancelled run is not reported as a failure`() {
        val orchestrator = mock<SendOrchestrator>()
        // A disable cancels the work mid-send: the reconciler's disabled branch
        // resolves the row, so the worker must report success, not failure.
        wheneverBlocking { orchestrator.process(anyInt()) }
            .thenThrow(CancellationException("cancelled"))

        val result = runBlocking { worker(orchestrator).doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `an unexpected failure whose recovery fails is reported as a failure`() {
        val orchestrator = mock<SendOrchestrator>()
        wheneverBlocking { orchestrator.process(anyInt()) }
            .thenThrow(IllegalStateException("boom"))
        // The recovery could not run (e.g. storage down): the work still fails and the
        // next reconcile trigger recovers the schedule.
        wheneverBlocking { orchestrator.recoverFromException(anyInt()) }.thenReturn(false)

        val result = runBlocking { worker(orchestrator).doWork() }
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `an unexpected failure with a recovered occurrence is reported as a success`() {
        val orchestrator = mock<SendOrchestrator>()
        wheneverBlocking { orchestrator.process(anyInt()) }
            .thenThrow(IllegalStateException("boom"))
        // The recovery handed the occurrence to a new owner (retry engine or stale
        // check): the work that just failed owns nothing anymore, so success keeps it
        // out of the terminal FAILED state WorkManager would never re-run.
        wheneverBlocking { orchestrator.recoverFromException(anyInt()) }.thenReturn(true)

        val result = runBlocking { worker(orchestrator).doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `a run in a foreign process fails loudly and lands in the app log`() {
        ShadowProcess.setProcessName("${context.packageName}:worker")
        val orchestrator = mock<SendOrchestrator>()

        val result = runBlocking { worker(orchestrator).doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
        verifyBlocking(orchestrator, never()) { process(anyInt()) }
        assertTrue(
            LogBuffer.entries.any { it.level == "E" && it.message.contains("runs in process") },
        )
    }
}
