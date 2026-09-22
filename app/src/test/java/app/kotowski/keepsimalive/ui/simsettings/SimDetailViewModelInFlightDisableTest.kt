package app.kotowski.keepsimalive.ui.simsettings

import android.app.Application
import app.kotowski.keepsimalive.R
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowToast

// Disabling, deleting or forgetting the keepalive while a fresh SENDING attempt is in flight
// is refused: the SMS is already committed to the radio, and cancelling the worker would lose
// the result and leave the send uncounted, while a delete/forget racing the worker's result
// write would resurrect the state the action just cleared. The guard covers the toggle and
// editor Save entry points of commit() plus deleteSchedule and forgetSim; a stale SENDING
// row, a retry row (PENDING, retryCount > 0), a plain PENDING row, no active row and any
// enable proceed as usual.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelInFlightDisableTest {
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

    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.observeNewest(any(), any())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(any())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
        // A real active subscription (id 11): the tests load it like any real SIM.
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(11, tm)
        shadowOf(tm).setSimOperatorName("Vodafone")
        shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(11)
                        .setSimSlotIndex(0)
                        .setDisplayName("Vodafone")
                        .setCarrierName("Vodafone")
                        .buildSubscriptionInfo(),
                ),
            )
        }
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

    // The commit() flow re-reads the config as its effective result after the write: re-stub
    // with the saved value second so the state ends up holding the post-save config.
    private suspend fun primeEffectiveConfig(
        simId: Int,
        dbConfig: SimKeepaliveConfig,
        effective: SimKeepaliveConfig,
    ) {
        wheneverBlocking { repository.getConfig(simId) }.thenReturn(dbConfig, effective)
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
    fun `setEnabled false is refused while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            vm.setEnabled(false)
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
            assertEquals(context.getString(R.string.error_send_in_progress), ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled false proceeds when the in-flight send is stale`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(sendingRow(2 * AppConfig.SENDING_STALE_MS))
            val disabled = config.copy(enabled = false)
            primeEffectiveConfig(11, config, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(disabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled false proceeds when the active row is a retry row`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(activeRow(SendOutcome.PENDING, retryCount = 1))
            val disabled = config.copy(enabled = false)
            primeEffectiveConfig(11, config, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(disabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled false proceeds when the active row is pending`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(activeRow(SendOutcome.PENDING))
            val disabled = config.copy(enabled = false)
            primeEffectiveConfig(11, config, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(disabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled false proceeds when there is no active row`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            val disabled = config.copy(enabled = false)
            primeEffectiveConfig(11, config, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(disabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled true proceeds while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = false, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            val enabled = config.copy(enabled = true)
            primeEffectiveConfig(11, config, enabled)
            vm.setEnabled(true)
            verifyBlocking(repository) { saveUserColumns(enabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(enabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `saveConfig with a disabled config is refused while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            vm.saveConfig(config.copy(enabled = false))
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
            assertEquals(context.getString(R.string.error_send_in_progress), ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `deleteSchedule is refused while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            vm.deleteSchedule(11)
            verify(armer, never()).cancelSend(11)
            verifyBlocking(repository, never()) { deleteConfig(11) }
            assertEquals(config, vm.state.value.config)
            assertEquals(context.getString(R.string.error_send_in_progress), ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `deleteSchedule proceeds when the in-flight send is stale`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(sendingRow(2 * AppConfig.SENDING_STALE_MS))
            vm.deleteSchedule(11)
            verify(armer).cancelSend(11)
            verifyBlocking(repository) { deleteConfig(11) }
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `deleteSchedule proceeds while a retry is pending`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(activeRow(SendOutcome.PENDING, retryCount = 1))
            wheneverBlocking { repository.finalizeOccurrence(any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull()) }
                .thenReturn(1)
            vm.deleteSchedule(11)
            verify(armer).cancelSend(11)
            verifyBlocking(repository) { deleteConfig(11) }
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `forgetSim is refused while a fresh send is in flight`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(sendingRow(0L))
            vm.forgetSim(11)
            verify(armer, never()).cancelSend(11)
            verifyBlocking(repository, never()) { deleteSim(11) }
            // The refused forget must not signal: the screen stays, the toast explains.
            assertFalse(vm.state.value.justForgot)
            assertEquals(context.getString(R.string.error_send_in_progress), ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `forgetSim proceeds when the in-flight send is stale`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(sendingRow(2 * AppConfig.SENDING_STALE_MS))
            vm.forgetSim(11)
            verify(armer).cancelSend(11)
            verifyBlocking(repository) { deleteSim(11) }
            assertTrue(vm.state.value.justForgot)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    // The screen leaves with the flag, so the flag must land only once the delete AND the
    // post-delete cleanup have committed — a flag set earlier would let the navigation
    // cancel a forget that is not done yet.
    @Test
    fun `forgetSim sets the forgotten flag only after the delete and the cleanup have committed`() =
        runBlocking {
            val vm = viewModel()
            val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            loadSim(vm, config = config)
            val gate = CompletableDeferred<Unit>()
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            wheneverBlocking { repository.deleteSim(11) }
                .doSuspendableAnswer { gate.await() }
            vm.forgetSim(11)
            // The delete is still in flight: no cleanup, no flag — the screen must stay.
            assertFalse(vm.state.value.justForgot)
            verify(armer, never()).cancelSend(11)
            verify(prefs, never()).setSimNotPresentFailures(anyInt(), anyInt())
            verify(prefs, never()).clearCatchUpAnchor(anyInt())
            verify(mockReconciler, never()).syncPersistentHint()
            gate.complete(Unit)
            verifyBlocking(repository) { deleteSim(11) }
            verify(armer).cancelSend(11)
            verify(prefs).setSimNotPresentFailures(11, 0)
            verify(prefs).clearCatchUpAnchor(11)
            verifyBlocking(mockReconciler) { syncPersistentHint() }
            assertTrue(vm.state.value.justForgot)
        }
}
