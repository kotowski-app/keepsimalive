package app.kotowski.keepsimalive.ui.simsettings

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager

// The periodic re-read of the live SIM state (refreshSimData) that keeps a SIM switched
// off/on in system settings from staying stale on the detail screen.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelRefreshTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val repository: KeepaliveRepository = mock()
    private val armer: ScheduleArmer = mock()
    private val prefs: AppPrefs = mock()

    init {
        // The mocked prefs carry the default late-send grace: the VM reads it for the end
        // state and the state (an unstubbed 0 would skip every past occurrence).
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
    }

    private val reconciler: ScheduleReconciler = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        SimDetector.cachedModemCount = null
        whenever(repository.observeNewest(anyInt(), anyInt())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SimDetector.cachedModemCount = null
    }

    private suspend fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!check() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private fun simInfo(
        id: Int,
        carrier: String,
        display: String,
        slot: Int = 0,
    ): SubscriptionInfo =
        ShadowSubscriptionManager.SubscriptionInfoBuilder
            .newBuilder()
            .setId(id)
            .setSimSlotIndex(slot)
            .setDisplayName(display)
            .setCarrierName(carrier)
            .buildSubscriptionInfo()

    private fun setActiveSims(vararg sims: SubscriptionInfo) {
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(sims.toList())
    }

    // The Robolectric radio reports a null simOperatorName by default, which the non-null
    // SimPhoneStateData field turns into a swallowed NPE: register a per-sub radio with a
    // name, like a real device with an inserted SIM.
    private fun registerSimRadio() {
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        shadowOf(tm).setSimOperatorName("Test Carrier")
    }

    private fun viewModel(): SimDetailViewModel =
        SimDetailViewModel(context, repository, reconciler, prefs, armer, SimSendLock(), OffSchedulePendingRegistry())

    private suspend fun loadRealSim(vm: SimDetailViewModel) {
        vm.loadSimData(context, 1)
        waitFor { vm.state.value.data != null && !vm.state.value.isLoading }
    }

    @Test
    fun `refreshSimData updates changed sim data without touching the rest of the state`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            assertEquals(
                "Carrier A",
                vm.state.value.data!!
                    .carrierName,
            )

            // SIM switched off and back on with a new carrier: the re-read picks it up.
            setActiveSims(simInfo(1, "Carrier B", "SIM B"))
            vm.refreshSimData(1)
            waitFor {
                vm.state.value.data
                    ?.carrierName == "Carrier B"
            }
            assertEquals(
                "SIM B",
                vm.state.value.data!!
                    .displayName,
            )
            assertEquals(
                "Carrier B",
                vm.state.value.data!!
                    .carrierName,
            )
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData switches to the last known info when the sim disappears`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A", slot = 1))

            val vm = viewModel()
            loadRealSim(vm)

            // SIM unplugged: it leaves the active subscription list. The screen keeps the
            // last known identity (cached by the load) and flags the SIM as missing.
            setActiveSims()
            vm.refreshSimData(1)
            waitFor { vm.state.value.simMissing }
            assertTrue(vm.state.value.simMissing)
            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertEquals(
                "Carrier A",
                vm.state.value.data!!
                    .carrierName,
            )
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.lastSeenAtMillis!! > 0)
            // The slot is remembered 1-based (the shadow's 0-based index 1 -> slot 2).
            assertEquals(2, vm.state.value.lastSeenSlotIndex)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData reloads the sim when it comes back`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            setActiveSims()
            vm.refreshSimData(1)
            waitFor { vm.state.value.simMissing }
            assertTrue(vm.state.value.simMissing)

            // Switched back on: the next re-read restores the live screen (missing flag gone).
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))
            vm.refreshSimData(1)
            waitFor { vm.state.value.data != null && !vm.state.value.simMissing }
            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData swaps in live data without a full reload when the sim comes back`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            // A saved config and a history row the original load must have picked up: the
            // re-arrival swap has to keep both instead of reloading them from scratch.
            val saved = SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100")
            wheneverBlocking { repository.getConfig(1) }.thenReturn(saved)
            whenever(repository.observeConfigs()).thenReturn(flowOf(listOf(saved)))
            val row =
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                )
            whenever(repository.observeNewest(anyInt(), anyInt())).thenReturn(flowOf(listOf(row)))
            whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(1))

            val vm = viewModel()
            loadRealSim(vm)
            assertEquals(saved, vm.state.value.config)
            waitFor { vm.state.value.history == listOf(row) }

            setActiveSims()
            vm.refreshSimData(1)
            waitFor { vm.state.value.simMissing }

            // The sim comes back: the live data must land without a full reload — no spinner
            // emission (the editor stays composed and keeps its draft) and no clobbering of
            // the state the collectors already keep fresh.
            val emissions = mutableListOf<SimDetailUiState>()
            val collector = launch { vm.state.collect { emissions += it } }
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))
            vm.refreshSimData(1)
            waitFor { vm.state.value.data != null && !vm.state.value.simMissing }
            collector.cancel()

            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertFalse(vm.state.value.simMissing)
            assertNull(vm.state.value.lastSeenAtMillis)
            assertNull(vm.state.value.lastSeenSlotIndex)
            assertNull(vm.state.value.error)
            assertTrue("the in-place swap must never flip isLoading", emissions.none { it.isLoading })
            assertEquals(saved, vm.state.value.config)
            assertEquals(listOf(row), vm.state.value.history)
        }

    @Test
    fun `refreshSimData carries the history count and grace into the missing state`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            whenever(prefs.lateSendGraceMinutes).thenReturn(30)
            whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(7))

            val vm = viewModel()
            loadRealSim(vm)
            waitFor { vm.state.value.historyCount == 7 }
            assertEquals(30, vm.state.value.lateSendGraceMinutes)

            setActiveSims()
            vm.refreshSimData(1)
            waitFor { vm.state.value.simMissing }

            assertEquals(7, vm.state.value.historyCount)
            assertEquals(30, vm.state.value.lateSendGraceMinutes)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `refreshSimData switches to the removed error when the sim disappears without cache`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            // Load a SIM the fetcher cannot cache (not in the active list from the start is
            // impossible to load live), so emulate an un-cached disappearance directly:
            // seed no cache, load via the present path, wipe the cache, then drop the SIM.
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            SimIdentityCache.forget(context, 1)
            setActiveSims()
            vm.refreshSimData(1)
            waitFor { vm.state.value.data == null && vm.state.value.error != null }
            assertEquals(context.getString(R.string.sim_detail_removed_error), vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData is a no-op without phone permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val vm = viewModel()
        vm.loadSimData(context, 1)
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        vm.refreshSimData(1)
        // The guard bails before reading the radio: the permission error stays, no crash.
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        assertNull(vm.state.value.data)
    }

    @Test
    fun `refreshSimData is a no-op without phone permission for a real sim above the old stub range`() {
        // The guard is the stub allowlist, not a numeric range: a real SIM with id 19 bails
        // on the missing permission exactly like a low-id SIM.
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val vm = viewModel()
        vm.loadSimData(context, 19)
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        vm.refreshSimData(19)
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        assertNull(vm.state.value.data)
    }

    @Test
    fun `refreshSimData keeps the same data while the sim is unchanged`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            val before = vm.state.value.data
            vm.refreshSimData(1)
            delay(200)
            assertEquals(before, vm.state.value.data)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `refreshSimData switches to the permission error when the permission is revoked`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            assertNotNull(vm.state.value.data)

            // The permission is revoked while the screen shows the live data: the next
            // refresh (a 5 s tick in production) must switch to the same error state a
            // fresh load without permission sets — otherwise the stale data stays on
            // screen forever (nothing else re-reads it while the permission is missing).
            shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1)
            waitFor { vm.state.value.data == null && vm.state.value.error != null }
            assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
            assertFalse(vm.state.value.isLoading)

            // A second refresh keeps the error state (idempotent, no churn).
            vm.refreshSimData(1)
            assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
            assertNull(vm.state.value.data)
        }

    @Test
    fun `refreshSimData keeps the data while the editor is open when the permission is revoked`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            assertNotNull(vm.state.value.data)

            // The permission is revoked while the editor is open: the refresh must keep
            // the current state (log only) — the swap would destroy the editor's draft,
            // and the save path never reads phone state, so the edit stays completable.
            // The guard bails before reading the radio, so the state check is immediate.
            shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1, editing = true)
            assertNotNull(vm.state.value.data)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isLoading)

            // The edit ends (Save or Cancel): the next refresh swaps to the permission
            // error, exactly as for a screen without an open editor.
            vm.refreshSimData(1)
            waitFor { vm.state.value.data == null && vm.state.value.error != null }
            assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData keeps the data while the editor is open and recovers when the permission is granted back`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            assertNotNull(vm.state.value.data)

            // Revoked while the editor is open: the state is kept, the draft survives.
            shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1, editing = true)
            assertNotNull(vm.state.value.data)
            assertNull(vm.state.value.error)

            // Granted back while the edit is still open: the normal refresh path updates
            // the data in place (no full reload), so the editor stays mounted.
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1, editing = true)
            waitFor { vm.state.value.data != null && vm.state.value.error == null }
            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `refreshSimData recovers from the permission error when the permission is granted back`() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            registerSimRadio()
            setActiveSims(simInfo(1, "Carrier A", "SIM A"))

            val vm = viewModel()
            loadRealSim(vm)
            assertNotNull(vm.state.value.data)

            // Revoke: the next refresh switches to the permission error.
            shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1)
            waitFor { vm.state.value.error != null && vm.state.value.data == null }
            assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)

            // Grant back: the next refresh must restore the live screen without a
            // re-navigation.
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            vm.refreshSimData(1)
            waitFor { vm.state.value.data != null && vm.state.value.error == null }
            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertFalse(vm.state.value.simMissing)
            assertFalse(vm.state.value.isLoading)
        }
}
