package app.kotowski.keepsimalive.ui.simsettings

import android.content.Context
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// retryNow must never REPLACE-cancel a live send: the in-flight gate and the re-arm run under
// the SIM's send lock (the worker holds it from the claim through the radio and the result
// write), so a tap landing in the state-flow lag after a claim waits for the in-flight
// attempt instead of cancelling it. A fresh SENDING row is refused (the attempt's outcome is
// undetermined, only the stale check can resolve the row); a stale SENDING row, a retry row
// (PENDING, retryCount > 0) and no active row proceed to the arm.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelRetryTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository: KeepaliveRepository = mock()
    private val armer: ScheduleArmer = mock()
    private val mockReconciler: ScheduleReconciler = mock()
    private val prefs: AppPrefs = mock()

    init {
        // The mocked prefs carry the default late-send grace: the VM reads it for the end
        // state and the state (an unstubbed 0 would skip every past occurrence).
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
    }

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.observeNewest(any(), any())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(any())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        SimDetailViewModel(context, repository, mockReconciler, prefs, armer, SimSendLock(), OffSchedulePendingRegistry())

    private suspend fun loadSim(
        vm: SimDetailViewModel,
        simId: Int = 11,
        config: SimKeepaliveConfig?,
    ) {
        wheneverBlocking { repository.getConfig(simId) }.thenReturn(config)
        vm.loadSimData(context, simId)
        val deadline = System.currentTimeMillis() + 5000
        while (vm.state.value.isLoading && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private fun sendingRow(ageMillis: Long): SendHistoryEntity =
        SendHistoryEntity(
            id = 1,
            simId = 11,
            scheduledForMillis = System.currentTimeMillis(),
            occurrenceBaseMillis = System.currentTimeMillis(),
            lastAttemptAtMillis = System.currentTimeMillis() - ageMillis,
            outcome = SendOutcome.SENDING.name,
            recipient = "+15550100",
            message = "keep alive",
        )

    private fun activeRow(
        outcome: SendOutcome,
        retryCount: Int = 0,
    ): SendHistoryEntity =
        SendHistoryEntity(
            id = 1,
            simId = 11,
            scheduledForMillis = System.currentTimeMillis(),
            occurrenceBaseMillis = System.currentTimeMillis(),
            lastAttemptAtMillis = System.currentTimeMillis(),
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
            retryCount = retryCount,
        )

    @Test
    fun `retryNow proceeds when the active row is retrying`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            // A retry row (PENDING, retryCount > 0) owns the occurrence: the tap re-arms it.
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(activeRow(SendOutcome.PENDING, retryCount = 1))
            vm.retryNow(11)
            verify(armer).armSendNow(11)
        }

    @Test
    fun `retryNow is refused while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            vm.retryNow(11)
            verify(armer, never()).armSendNow(any())
        }

    @Test
    fun `retryNow proceeds when the in-flight send is stale`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(sendingRow(2 * AppConfig.SENDING_STALE_MS))
            vm.retryNow(11)
            verify(armer).armSendNow(11)
        }

    @Test
    fun `retryNow proceeds when there is no active row`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            vm.retryNow(11)
            verify(armer).armSendNow(11)
        }

    @Test
    fun `retryNow waits out a lock-held in-flight attempt before re-checking and arming`() =
        runBlocking {
            // The tap lands in the state-flow lag after the worker's claim: the row is a
            // fresh SENDING and the worker's claim-to-result section holds the SIM's lock
            // (the same SimSendLock instance the VM uses).
            val lock = SimSendLock()
            val vm = SimDetailViewModel(context, repository, mockReconciler, prefs, armer, lock, OffSchedulePendingRegistry())
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            val worker =
                launch(testDispatcher) {
                    lock.withLock(11) {
                        delay(50) // the radio call and result write, inside the lock section
                    }
                }
            vm.retryNow(11)
            // The tap must wait for the in-flight attempt instead of cancelling it: no arm
            // while the worker still holds the lock.
            verify(armer, never()).armSendNow(any())
            // The attempt finishes inside its lock section: the result write lands (the row
            // goes to a pending retry: PENDING, retryCount > 0) and the lock is released.
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(activeRow(SendOutcome.PENDING, retryCount = 1))
            // The worker's resumption is due exactly at the advanced-to time, which
            // advanceTimeBy leaves in the queue; run it: the section ends, the lock is
            // released and the waiting tap resumes with its re-check and the arm.
            testDispatcher.scheduler.advanceTimeBy(50)
            testDispatcher.scheduler.runCurrent()
            verify(armer).armSendNow(11)
        }
}
