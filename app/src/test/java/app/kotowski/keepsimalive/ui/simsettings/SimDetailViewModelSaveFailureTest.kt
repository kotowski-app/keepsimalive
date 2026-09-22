package app.kotowski.keepsimalive.ui.simsettings

import android.app.Application
import android.os.Looper
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

// A save whose repository write throws (the lock section of commit() catches it and logs)
// surfaces as the error_save_failed toast, mirroring the in-flight and ended-schedule
// refusals; the state keeps the pre-save values either way.
//
// Threading: the test body must stay on the Robolectric main (Looper) thread. A runBlocking
// + delay(10) polling loop would resume the test body on a Dispatchers.Default pool thread,
// and the unconfined commit coroutine inherits that thread for its toast — a toast from a
// non-Looper thread throws and leaks out of the save job as an uncaught exception into
// whatever test class runs next.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelSaveFailureTest {
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

    // Waits for the (IO-dispatched) load to finish while staying on the main Looper thread:
    // idleFor advances the main clock and runs due main-thread tasks; the load's state write
    // lands off-Looper, so the wait is a poll.
    private fun loadSim(
        vm: SimDetailViewModel,
        simId: Int = 11,
        config: SimKeepaliveConfig?,
    ) {
        wheneverBlocking { repository.getConfig(simId) }.thenReturn(config)
        vm.loadSimData(context, simId)
        val mainLooper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5000
        while (vm.state.value.isLoading && System.currentTimeMillis() < deadline) {
            mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `saveConfig shows the failure toast when the repository write throws`() {
        val vm = viewModel()
        val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
        loadSim(vm, config = config)
        wheneverBlocking { repository.saveUserColumns(any()) }.thenThrow(RuntimeException("boom"))
        vm.saveConfig(config)
        verifyBlocking(repository) { saveUserColumns(config) }
        verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
        assertEquals(config, vm.state.value.config)
        assertFalse(vm.state.value.justSaved)
        assertEquals(context.getString(R.string.error_save_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `saveConfig shows the failure toast on the first-save path too`() {
        val vm = viewModel()
        val config = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
        loadSim(vm, config = null)
        // The write throws before any post-save re-read, so the null stub covers the
        // in-lock dbConfig read as well.
        wheneverBlocking { repository.getConfig(11) }.thenReturn(null, null)
        wheneverBlocking { repository.saveConfig(any()) }.thenThrow(RuntimeException("boom"))
        vm.saveConfig(config)
        verifyBlocking(repository) {
            saveConfig(config.copy(nextSendAtMillis = null, lastSentAtMillis = null, sendCount = 0))
        }
        verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
        assertNull(vm.state.value.config)
        assertFalse(vm.state.value.justSaved)
        assertEquals(context.getString(R.string.error_save_failed), ShadowToast.getTextOfLatestToast())
    }
}
