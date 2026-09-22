package app.kotowski.keepsimalive.ui.dashboard

import android.Manifest
import android.app.Application
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.work.ScheduleReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager

// The card-merge invariants of DashboardViewModel: the config-stats feed (Main) and the 5 s
// SIM poll (IO) both read-modify-write the same snapshot, and neither may resurrect the
// other's fields. Robolectric-based because updateSimCards calls the real telephony statics
// (SimNameResolver, SimIdentityCache, SimDetector), which need a real Context. The orders
// are deterministic (no thread races): the stats emission lands before the poll, then the
// poll lands before the stats emission.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelMergeTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val permissionManager: PermissionManager = mock()
    private val repository: KeepaliveRepository = mock()
    private val reconciler: ScheduleReconciler = mock()

    // The observeConfigs feed the collector collects from: the tests drive its emissions.
    private val configsFlow = MutableStateFlow<List<SimKeepaliveConfig>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        SimDetector.cachedModemCount = null
        whenever(repository.observeConfigs()).thenReturn(configsFlow)
        whenever(repository.observeInFlightStates()).thenReturn(MutableStateFlow(emptyMap()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SimDetector.cachedModemCount = null
    }

    private fun viewModel(): DashboardViewModel = DashboardViewModel(context, permissionManager, repository, reconciler)

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

    // The poll's telephony reads need the per-sub radio registered and in service:
    // Robolectric's default radio reports no service state (and a null simOperatorName)
    // for an unregistered subscription id, which the poll would read as NO_SERVICE.
    private fun grantPhonePermission() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        shadowOf(tm).setSimOperatorName("Test Carrier")
        // ServiceState.Builder is not in the compileSdk stubs, so the in-service state is
        // set through the plain setter.
        val serviceState = ServiceState()
        serviceState.setState(ServiceState.STATE_IN_SERVICE)
        shadowOf(tm).setServiceState(serviceState)
    }

    @Test
    fun `stats survive a later sim poll`() {
        val lastSent = 1_700_000_000_000L
        val nextSend = 1_700_003_600_000L
        // The collector's emission lands first (the cold-start order: the Room flow re-emits
        // on its first collection before the first poll has run).
        configsFlow.value =
            listOf(
                SimKeepaliveConfig(simId = 1, enabled = true, sendCount = 3, lastSentAtMillis = lastSent, nextSendAtMillis = nextSend),
                SimKeepaliveConfig(simId = 2, enabled = true, sendCount = 7, lastSentAtMillis = lastSent, nextSendAtMillis = nextSend),
            )
        // SIM 2 is configured but out of the tray: the poll's presence check must surface it
        // from the last known identity (REMOVED).
        SimIdentityCache.save(
            context,
            CachedSimIdentity(2, "SIM B", "Carrier B", 2, "", "", false, lastSent),
        )
        grantPhonePermission()
        setActiveSims(simInfo(1, "Carrier A", "SIM A"))

        val vm = viewModel()
        // The stats are staged in the snapshot, but no SIM list has been read yet, so no
        // real card renders (and SIM 2 cannot look missing: presence is not verifiable).
        assertTrue(
            vm.state.value.realSimCards
                .isEmpty(),
        )
        assertTrue(
            vm.state.value.missingSimCards
                .isEmpty(),
        )
        assertFalse(vm.state.value.simCardsRead)

        // The poll runs (Dispatchers.IO in production): its fields must merge on top of the
        // stats, not resurrect a pre-stats snapshot over them.
        vm.updateSimCards(context)

        val state = vm.state.value
        assertTrue(state.simCardsRead)
        val real1 = state.realSimCards.single { it.simId == 1 }
        assertEquals("SIM A", real1.label)
        assertEquals("Carrier A", real1.carrier)
        assertEquals(SimStatus.ACTIVE, real1.status)
        assertTrue(real1.keepaliveEnabled)
        assertEquals(3, real1.sendCount)
        assertEquals(lastSent, real1.lastSentAtMillis)
        assertEquals(nextSend, real1.nextSendAtMillis)
        val missing2 = state.missingSimCards.single { it.simId == 2 }
        assertEquals(SimStatus.REMOVED, missing2.status)
        assertEquals("SIM B", missing2.label)
        assertTrue(missing2.keepaliveEnabled)
        assertEquals(7, missing2.sendCount)
        assertEquals(nextSend, missing2.nextSendAtMillis)
    }

    @Test
    fun `poll survives a later stats emission`() {
        val lastSent = 1_700_000_000_000L
        val nextSend = 1_700_003_600_000L
        grantPhonePermission()
        setActiveSims(simInfo(1, "Carrier A", "SIM A"))

        // configsFlow is still empty: the collector's emission carries no stats, so the poll
        // (run before any config row exists) lands first.
        val vm = viewModel()
        vm.updateSimCards(context)
        val identityBeforeStats =
            vm.state.value.realSimCards
                .single { it.simId == 1 }
        assertTrue(vm.state.value.simCardsRead)
        assertEquals("SIM A", identityBeforeStats.label)
        assertEquals(SimStatus.ACTIVE, identityBeforeStats.status)
        assertFalse(identityBeforeStats.keepaliveEnabled)
        assertEquals(0, identityBeforeStats.sendCount)
        assertNull(identityBeforeStats.nextSendAtMillis)

        // A config row lands (Room flow re-emits): the stats must be added on top of the
        // poll's identity, not resurrect a pre-poll snapshot over it.
        configsFlow.value =
            listOf(
                SimKeepaliveConfig(simId = 1, enabled = true, sendCount = 3, lastSentAtMillis = lastSent, nextSendAtMillis = nextSend),
            )

        val state = vm.state.value
        val real1 = state.realSimCards.single { it.simId == 1 }
        assertTrue(real1.keepaliveEnabled)
        assertEquals(3, real1.sendCount)
        assertEquals(lastSent, real1.lastSentAtMillis)
        assertEquals(nextSend, real1.nextSendAtMillis)
        // The live SIM list is still the card's basis (a pre-poll snapshot would carry no
        // real SIM list at all).
        assertTrue(state.simCardsRead)
        assertEquals("SIM A", real1.label)
        assertEquals("Carrier A", real1.carrier)
        assertEquals(SimStatus.ACTIVE, real1.status)
        assertEquals(1, real1.slot)
    }
}
