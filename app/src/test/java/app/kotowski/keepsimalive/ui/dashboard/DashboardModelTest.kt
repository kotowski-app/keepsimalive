package app.kotowski.keepsimalive.ui.dashboard

import androidx.room.Room
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.InFlightOccurrence
import app.kotowski.keepsimalive.data.KeepaliveDatabase
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.icons.Timer1Icon
import app.kotowski.keepsimalive.ui.icons.Timer2Icon
import app.kotowski.keepsimalive.ui.icons.Timer3Icon
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.PermissionsAutoResetStatus
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.util.SimInfo
import app.kotowski.keepsimalive.work.ScheduleReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

// The relative text with fixed test phrases instead of the app strings (the app's
// default quantity-string formatters come from the resources).
private fun lastSentRelative(
    stub: SimCardStub,
    context: android.content.Context,
    now: Instant = Instant.now(),
): String =
    stub.getLastSentRelative(
        context,
        now,
        neverText = "never",
        justNowText = "just now",
        inAMomentText = "in a moment",
    )

private fun nextSendRelative(
    stub: SimCardStub,
    context: android.content.Context,
    now: Instant = Instant.now(),
): String =
    stub.getNextSendRelative(
        context,
        now,
        neverText = "not scheduled",
        justNowText = "just now",
        inAMomentText = "in a moment",
    )

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: KeepaliveDatabase
    private lateinit var repository: KeepaliveRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
        repository = KeepaliveRepository(database, database.simConfigDao(), database.simHistoryDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Deliberately no database.close() here: Room 2.6.1's close() holds the
        // InvalidationTracker write lock while taking the static ":memory:" process lock,
        // while a still-collecting observeConfigs() flow (the ViewModel scope is never
        // cancelled in these tests) takes them in the opposite order on its first open —
        // a lock-order inversion that deadlocks the whole test JVM under load. The
        // in-memory database is garbage-collected instead (the app never closes its DB).
    }

    private suspend fun waitFor(
        timeoutMs: Long = 5000,
        check: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    @Test
    fun `nextSendShowsTryingNow true while a fresh attempt is sending`() {
        val now = 1_000_000L
        val freshSending = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, now - 10_000L, now - 10_000L)
        assertTrue(nextSendShowsTryingNow(freshSending, now + 3_600_000L, now))
        assertTrue(nextSendShowsTryingNow(freshSending, now, now))
        assertTrue(nextSendShowsTryingNow(freshSending, null, now))
    }

    @Test
    fun `nextSendShowsTryingNow false when the sending row is stale`() {
        val now = 1_000_000L
        val staleSending = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, now - 120_000L, now - 120_000L)
        assertFalse(nextSendShowsTryingNow(staleSending, now - 120_000L, now))
        assertFalse(nextSendShowsTryingNow(staleSending, null, now))
        // The staleness threshold is exclusive: an attempt exactly SENDING_STALE_MS old still
        // counts as live, one ms past it does not.
        val atThreshold = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, now - AppConfig.SENDING_STALE_MS, null)
        val pastThreshold = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, now - AppConfig.SENDING_STALE_MS - 1, null)
        assertTrue(nextSendShowsTryingNow(atThreshold, null, now))
        assertFalse(nextSendShowsTryingNow(pastThreshold, null, now))
    }

    @Test
    fun `nextSendShowsTryingNow judges staleness by the attempt time with the scheduled time as fallback`() {
        val now = 1_000_000L
        // A fresh attempt wins over a stale scheduled time (a retry re-writes nextSend).
        val freshAttemptStaleSchedule = InFlightOccurrence(1, SendOutcome.SENDING.name, 1, now - 2 * 3_600_000L, now - 10_000L)
        assertTrue(nextSendShowsTryingNow(freshAttemptStaleSchedule, now - 2 * 3_600_000L, now))
        // No attempt time (legacy row): the scheduled time decides.
        val noAttemptStaleSchedule = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, now - 120_000L, null)
        assertFalse(nextSendShowsTryingNow(noAttemptStaleSchedule, now - 120_000L, now))
    }

    @Test
    fun `nextSendShowsTryingNow false before the scheduled time`() {
        val now = 1_000_000L
        assertFalse(nextSendShowsTryingNow(null, now + 1, now))
        assertFalse(nextSendShowsTryingNow(null, now + 3_600_000L, now))
    }

    @Test
    fun `nextSendShowsTryingNow true from the scheduled time within grace`() {
        val now = 1_000_000L
        val scheduled = now - 10_000L
        assertTrue(nextSendShowsTryingNow(null, scheduled, now - 10_000L))
        assertTrue(nextSendShowsTryingNow(null, scheduled, scheduled + AppConfig.IN_FLIGHT_GRACE_MS))
    }

    @Test
    fun `nextSendShowsTryingNow false after grace expired`() {
        val now = 1_000_000L
        val scheduled = now - 10_000L
        assertFalse(nextSendShowsTryingNow(null, scheduled, scheduled + AppConfig.IN_FLIGHT_GRACE_MS + 1))
    }

    @Test
    fun `nextSendShowsTryingNow false when nothing scheduled`() {
        assertFalse(nextSendShowsTryingNow(null, null, 1_000_000L))
    }

    @Test
    fun `DashboardUiState defaults permissionsAutoResetStatus to NOT_AVAILABLE`() {
        val state = DashboardUiState()
        assertEquals(PermissionsAutoResetStatus.NOT_AVAILABLE, state.permissionsAutoResetStatus)
    }

    @Test
    fun `DashboardViewModel updatePermissionsAutoResetStatus updates state`() =
        runBlocking {
            val pm = mock<PermissionManager>()
            whenever(pm.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updatePermissionsAutoResetStatus()
            assertEquals(PermissionsAutoResetStatus.DISABLED, vm.state.value.permissionsAutoResetStatus)
        }

    @Test
    fun `noSimsCardVisible is true only after a read that yields no cards at all`() {
        val card =
            SimCardStub(
                simId = 1,
                label = "SIM A",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
            )

        assertFalse(noSimsCardVisible(false, emptyList()))
        assertTrue(noSimsCardVisible(true, emptyList()))
        assertFalse(noSimsCardVisible(true, listOf(card)))
        assertFalse(noSimsCardVisible(true, listOf(card, card)))
    }

    @Test
    fun `slotIcon maps slots 1 to 3 to their timer icons on multi sim devices`() {
        assertSame(Timer1Icon, slotIcon(1, true))
        assertSame(Timer2Icon, slotIcon(2, true))
        assertSame(Timer3Icon, slotIcon(3, true))
    }

    @Test
    fun `slotIcon returns null for unknown slots`() {
        assertNull(slotIcon(0, true))
        assertNull(slotIcon(4, true))
        assertNull(slotIcon(101, true))
        assertNull(slotIcon(104, true))
    }

    @Test
    fun `slotIcon returns null on single sim devices`() {
        assertNull(slotIcon(1, false))
        assertNull(slotIcon(2, false))
        assertNull(slotIcon(3, false))
        assertNull(slotIcon(103, false))
    }

    @Test
    @Config(sdk = [29])
    fun `updateSimCards exposes multi sim flag based on modem count`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            SimDetector.cachedModemCount = null
            shadowOf(tm).setPhoneCount(2)
            val pm = mock<PermissionManager>()
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updateSimCards(context)
            assertTrue(vm.state.value.multiSimDevice)

            SimDetector.cachedModemCount = null
            shadowOf(tm).setPhoneCount(1)
            vm.updateSimCards(context)
            assertFalse(vm.state.value.multiSimDevice)
        }

    @Test
    fun `updateSimCards reflects a sim switched off and on on the next call`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val simA =
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("SIM A")
                    .setCarrierName("Carrier A")
                    .buildSubscriptionInfo()
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(simA))

            val pm = mock<PermissionManager>()
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updateSimCards(context)
            assertEquals(
                listOf("SIM A"),
                vm.state.value.realSimCards
                    .map { it.label },
            )

            // SIM switched off in system settings: it leaves the active list. The next read
            // must see the change (no stale cache).
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.realSimCards
                    .isEmpty(),
            )

            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(simA))
            vm.updateSimCards(context)
            assertEquals(
                listOf("SIM A"),
                vm.state.value.realSimCards
                    .map { it.label },
            )
        }

    private fun activeSimA() =
        ShadowSubscriptionManager.SubscriptionInfoBuilder
            .newBuilder()
            .setId(1)
            .setDisplayName("SIM A")
            .setCarrierName("Carrier A")
            .buildSubscriptionInfo()

    @Test
    fun `updateSimCards marks the sim list read even while it yields no cards`() =
        runBlocking {
            // No READ_PHONE_STATE on the test context: the SIM list is unreadable and the
            // read yields no cards, but the read itself is recorded (no "no SIMs" flash
            // before the first pass finishes).
            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            assertFalse(vm.state.value.simCardsRead)
            vm.updateSimCards(context)
            assertTrue(vm.state.value.simCardsRead)
            assertTrue(
                vm.state.value.realSimCards
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `updateSimCards moves a configured sim to the missing list when it leaves the active list`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(activeSimA()))
            repository.saveConfig(SimKeepaliveConfig(simId = 1, enabled = true))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            // The missing-card logic reads stats from the config flow, whose first emission
            // is asynchronous: wait for it before unplugging the SIM.
            waitFor {
                vm.state.value.realSimCards
                    .any { it.keepaliveEnabled }
            }
            assertEquals(
                listOf("SIM A"),
                vm.state.value.realSimCards
                    .map { it.label },
            )
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )

            // SIM unplugged: the card stays, from the last known identity, in the missing list.
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.realSimCards
                    .isEmpty(),
            )
            val missing = vm.state.value.missingSimCards
            assertEquals(1, missing.size)
            assertEquals(SimStatus.REMOVED, missing[0].status)
            assertEquals("SIM A", missing[0].label)
            assertEquals("Carrier A", missing[0].carrier)

            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(activeSimA()))
            vm.updateSimCards(context)
            assertEquals(
                listOf("SIM A"),
                vm.state.value.realSimCards
                    .map { it.label },
            )
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `updateSimCards caches the live sim identity with a 1-based slot`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val sim =
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setSimSlotIndex(1)
                    .setDisplayName("SIM A")
                    .setCarrierName("Carrier A")
                    .buildSubscriptionInfo()
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(listOf(sim))

            val pm = mock<PermissionManager>()
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updateSimCards(context)

            // The cached slot is 1-based (the 0-based index 1 -> slot 2), like
            // SimPhoneStateData.slotIndex: it feeds the "SIM N" header and the
            // "Last seen (Slot N)" row of a removed SIM.
            assertEquals(2, SimIdentityCache.get(context, 1)?.slotIndex)
        }

    @Test
    fun `missing card appears as soon as its config arrives after the first sim poll`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            SimIdentityCache.save(
                context,
                CachedSimIdentity(
                    simId = 1,
                    displayName = "SIM A",
                    carrierName = "Carrier A",
                    slotIndex = 0,
                    networkOperatorName = "",
                    simOperatorName = "",
                    isNetworkRoaming = false,
                    lastSeenAtMillis = 1_700_000_000_000L,
                ),
            )

            val pm = mock<PermissionManager>()
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )

            // The config lands after the poll (as the config flow's first emission does at
            // app start): the missing card derives right away, without another sim poll.
            repository.saveConfig(SimKeepaliveConfig(simId = 1, enabled = true))
            waitFor {
                vm.state.value.missingSimCards
                    .any { it.simId == 1 }
            }
            val missing = vm.state.value.missingSimCards
            assertEquals(1, missing.size)
            assertEquals("SIM A", missing[0].label)
            assertEquals(SimStatus.REMOVED, missing[0].status)
        }

    @Test
    fun `updateSimCards shows no missing cards without phone permission`() =
        runBlocking {
            // Without READ_PHONE_STATE the active list is unreadable, so presence cannot be
            // verified and a configured SIM must not be presented as removed.
            shadowOf(context).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(emptyList())
            SimIdentityCache.save(
                context,
                CachedSimIdentity(1, "SIM A", "Carrier A", 1, "", "", false, System.currentTimeMillis()),
            )
            repository.saveConfig(SimKeepaliveConfig(simId = 1, enabled = true))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.realSimCards
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `updateSimCards logs the phone permission loss exactly once`() =
        runBlocking {
            LogBuffer.clear()
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(activeSimA()))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            assertEquals(1, vm.state.value.realSimCards.size)
            assertEquals(0, LogBuffer.entries.count { it.tag == "DashVM" })

            // The permission is revoked: the next poll must log the granted -> lost
            // transition exactly once (the in-app log is otherwise silent about the
            // vanished SIM cards).
            shadowOf(context).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.realSimCards
                    .isEmpty(),
            )
            val permissionLossLogs =
                LogBuffer.entries.count {
                    it.level == "W" && it.tag == "DashVM" && it.message.contains("Phone permission")
                }
            assertEquals(1, permissionLossLogs)
            vm.updateSimCards(context)
            assertEquals(
                1,
                LogBuffer.entries.count { it.level == "W" && it.tag == "DashVM" && it.message.contains("Phone permission") },
            )
            LogBuffer.clear()
        }

    @Test
    fun `updateSimCards shows no missing card for a configured sim that was never seen`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(emptyList())
            repository.saveConfig(SimKeepaliveConfig(simId = 5, enabled = true))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `updateSimCards moves a configured real sim above the old stub range to the missing list`() =
        runBlocking {
            // Subscription ids are not bounded by 10: a real SIM with id 19 must get the same
            // "Missing SIM" protection as any other real SIM (the old `id <= 10` filter
            // excluded it forever).
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val simHigh =
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(19)
                    .setDisplayName("SIM HIGH")
                    .setCarrierName("Carrier HIGH")
                    .buildSubscriptionInfo()
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(simHigh))
            repository.saveConfig(SimKeepaliveConfig(simId = 19, enabled = true))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            waitFor {
                vm.state.value.realSimCards
                    .any { it.keepaliveEnabled }
            }
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )

            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            vm.updateSimCards(context)
            val missing = vm.state.value.missingSimCards
            assertEquals(1, missing.size)
            assertEquals(19, missing[0].simId)
            assertEquals(SimStatus.REMOVED, missing[0].status)
            assertEquals("SIM HIGH", missing[0].label)

            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(simHigh))
            vm.updateSimCards(context)
            assertEquals(
                19,
                vm.state.value.realSimCards
                    .single()
                    .simId,
            )
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `missing card carries the config stats`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(activeSimA()))
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 1,
                    enabled = true,
                    lastSentAtMillis = 1_000L,
                    nextSendAtMillis = 2_000L,
                    sendCount = 3,
                ),
            )
            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            // The missing-card logic reads stats from the config flow, whose first emission
            // is asynchronous: wait for it before unplugging the SIM.
            waitFor {
                vm.state.value.realSimCards
                    .any { it.keepaliveEnabled }
            }

            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            vm.updateSimCards(context)
            val missing =
                vm.state.value.missingSimCards
                    .single()
            assertEquals(1_000L, missing.lastSentAtMillis)
            assertEquals(2_000L, missing.nextSendAtMillis)
            assertEquals(3, missing.sendCount)
        }

    @Test
    fun `forgetting a sim removes its missing card`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(activeSimA()))
            repository.saveConfig(SimKeepaliveConfig(simId = 1, enabled = true))

            val vm =
                DashboardViewModel(
                    context,
                    mock<PermissionManager>(),
                    repository,
                    mock<ScheduleReconciler>(),
                )
            vm.updateSimCards(context)
            // The missing-card logic reads stats from the config flow, whose first emission
            // is asynchronous: wait for it before unplugging the SIM.
            waitFor {
                vm.state.value.realSimCards
                    .any { it.keepaliveEnabled }
            }
            shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())
            vm.updateSimCards(context)
            assertEquals(1, vm.state.value.missingSimCards.size)

            // The detail screen forgets the SIM: the config row is gone, so the card is.
            repository.deleteSim(1)
            waitFor {
                vm.state.value.missingSimCards
                    .isEmpty()
            }
            assertTrue(
                vm.state.value.missingSimCards
                    .isEmpty(),
            )
        }

    @Test
    fun `SimCardStub has all required fields`() {
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "TestCarrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = 1704067200000L,
                nextSendAtMillis = 1706745600000L,
                sendCount = 5,
            )
        assertEquals(1, stub.simId)
        assertEquals("Test", stub.label)
        assertEquals(1, stub.slot)
        assertEquals("TestCarrier", stub.carrier)
        assertEquals(SimStatus.ACTIVE, stub.status)
        assertEquals(1704067200000L, stub.lastSentAtMillis)
        assertEquals(1706745600000L, stub.nextSendAtMillis)
        assertEquals(5, stub.sendCount)
        assertFalse(stub.keepaliveEnabled)
    }

    @Test
    fun `SimInfo converts to SimCardStub`() {
        val simInfo =
            SimInfo(
                simId = 2,
                slotIndex = 1,
                displayName = "My SIM",
                carrierName = "Carrier",
                isInService = true,
            )
        val stub = simInfo.toSimCardStub()
        assertEquals(2, stub.simId)
        assertEquals("My SIM", stub.label)
        assertEquals(2, stub.slot)
        assertEquals("Carrier", stub.carrier)
        assertEquals(SimStatus.ACTIVE, stub.status)
        assertNull(stub.lastSentAtMillis)
        assertNull(stub.nextSendAtMillis)
        assertEquals(0, stub.sendCount)
    }

    @Test
    fun `SimInfo with no service converts to NO_SERVICE status`() {
        val simInfo =
            SimInfo(
                simId = 3,
                slotIndex = 2,
                displayName = "No Service SIM",
                carrierName = "Carrier",
                isInService = false,
            )
        val stub = simInfo.toSimCardStub()
        assertEquals(SimStatus.NO_SERVICE, stub.status)
    }

    @Test
    fun `SimCardStub null timestamps display never and not scheduled`() {
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
            )
        assertEquals(
            "never",
            stub.getLastSentRelative(context, neverText = "never", justNowText = "just now", inAMomentText = "in a moment"),
        )
        assertEquals(
            "not scheduled",
            stub.getNextSendRelative(context, neverText = "not scheduled", justNowText = "just now", inAMomentText = "in a moment"),
        )
    }

    @Test
    fun `SimCardStub recent timestamp displays relative interval`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = fixedNow.minus(5, ChronoUnit.MINUTES).toEpochMilli(),
                nextSendAtMillis = fixedNow.plus(2, ChronoUnit.HOURS).toEpochMilli(),
                sendCount = 3,
            )
        val lastSent = lastSentRelative(stub, context, fixedNow)
        assertNotNull(lastSent)
        assertNotEquals("never", lastSent)
        assertTrue("Expected '5 minutes ago' but got: $lastSent", lastSent.contains("5"))

        val nextSend = nextSendRelative(stub, context, fixedNow)
        assertNotNull(nextSend)
        assertNotEquals("not scheduled", nextSend)
        assertTrue("Expected hours in result but got: $nextSend", nextSend.contains("hour"))
    }

    @Test
    fun `SimCardStub Future SIM shows In a moment for next send`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = fixedNow.plus(30, ChronoUnit.SECONDS).toEpochMilli(),
                sendCount = 0,
            )
        val nextSendRelative =
            stub.getNextSendRelative(
                context,
                now = fixedNow,
                neverText = "not scheduled",
                justNowText = "just now",
                inAMomentText = "in a moment",
            )
        assertEquals("in a moment", nextSendRelative)
    }

    @Test
    fun `getLastSentRelative uses provided now parameter`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = fixedNow.minus(5, ChronoUnit.MINUTES).toEpochMilli(),
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val result = lastSentRelative(stub, context, fixedNow)
        assertTrue("Expected '5 minutes ago' but got: $result", result.contains("5"))
    }

    @Test
    fun `getNextSendRelative uses provided now parameter`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = fixedNow.plus(3, ChronoUnit.HOURS).toEpochMilli(),
                sendCount = 0,
            )
        val result = nextSendRelative(stub, context, fixedNow)
        assertTrue("Expected hours in result but got: $result", result.contains("hour"))
        assertTrue("Expected future direction but got: $result", result.contains("in"))
    }

    @Test
    fun `getLastSentRelative with stale now shows older interval`() {
        val baseTime = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = baseTime.minus(1, ChronoUnit.MINUTES).toEpochMilli(),
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val freshResult = lastSentRelative(stub, context, baseTime)
        val staleNow = baseTime.plus(10, ChronoUnit.MINUTES)
        val staleResult = lastSentRelative(stub, context, staleNow)
        assertTrue("Fresh result should contain '1 minute': $freshResult", freshResult.contains("1 minute"))
        assertTrue("Stale result should contain '11 minutes': $staleResult", staleResult.contains("11 minutes"))
    }

    @Test
    fun `getNextSendRelative with past now shows just now`() {
        val baseTime = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = baseTime.minus(29, ChronoUnit.SECONDS).toEpochMilli(),
                sendCount = 0,
            )
        val result = nextSendRelative(stub, context, baseTime)
        assertEquals("just now", result)
    }

    @Test
    fun `SimCardStub defaults now to current time`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = fixedNow.minus(3, ChronoUnit.HOURS).toEpochMilli(),
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val result = lastSentRelative(stub, context, fixedNow)
        assertTrue("Expected '3 hours ago' but got: $result", result.contains("3"))
    }

    @Test
    fun `SimStatus has three values`() {
        assertEquals(3, SimStatus.values().size)
        assertNotNull(SimStatus.ACTIVE)
        assertNotNull(SimStatus.NO_SERVICE)
        assertNotNull(SimStatus.REMOVED)
    }

    @Test
    fun `keepaliveStringRes maps on and off independently of the service status`() {
        val on =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
                keepaliveEnabled = true,
            )
        assertEquals(R.string.dashboard_sim_status_on, on.keepaliveStringRes())
        assertEquals(R.string.dashboard_sim_status_off, on.copy(keepaliveEnabled = false).keepaliveStringRes())
        // A no-service or removed SIM still shows its keepalive state
        assertEquals(R.string.dashboard_sim_status_on, on.copy(status = SimStatus.NO_SERVICE).keepaliveStringRes())
        assertEquals(R.string.dashboard_sim_status_off, on.copy(status = SimStatus.REMOVED, keepaliveEnabled = false).keepaliveStringRes())
    }

    @Test
    fun `CachedSimIdentity converts to a REMOVED SimCardStub with last known fields`() {
        val cached =
            CachedSimIdentity(
                simId = 1,
                displayName = "Vodafone",
                carrierName = "Vodafone",
                slotIndex = 1,
                networkOperatorName = "AIS",
                simOperatorName = "Vodafone",
                isNetworkRoaming = true,
                lastSeenAtMillis = 1_700_000_000_000L,
            )
        val stub = cached.toMissingSimCardStub(context)
        assertEquals(1, stub.simId)
        assertEquals("Vodafone", stub.label)
        assertEquals(1, stub.slot)
        assertEquals("Vodafone", stub.carrier)
        assertEquals(SimStatus.REMOVED, stub.status)
        assertNull(stub.lastSentAtMillis)
        assertNull(stub.nextSendAtMillis)
        assertEquals(0, stub.sendCount)
    }

    @Test
    fun `CachedSimIdentity with an empty name falls back to the i18n subscription id label`() {
        val cached =
            CachedSimIdentity(
                simId = 2,
                displayName = "",
                carrierName = "Carrier",
                slotIndex = 2,
                networkOperatorName = "",
                simOperatorName = "",
                isNetworkRoaming = false,
                lastSeenAtMillis = 1_700_000_000_000L,
            )
        // The fallback is user-facing (Dashboard card title) and must come from strings.xml,
        // naming the subscription id rather than a tray slot.
        assertEquals(context.getString(R.string.sim_name_fallback, 2), cached.toMissingSimCardStub(context).label)
    }

    @Test
    fun `SimCardStub keepaliveEnabled defaults to false`() {
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
            )
        assertFalse(stub.keepaliveEnabled)
    }

    @Test
    fun `nextSendRelative past timestamp uses the past plural`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = fixedNow.minus(5, ChronoUnit.MINUTES).toEpochMilli(),
                sendCount = 0,
            )
        val result = nextSendRelative(stub, context, fixedNow)
        assertTrue("Expected '5 minutes ago' but got: $result", result.contains("5"))
        assertTrue("Expected past direction but got: $result", result.contains("ago"))
    }

    @Test
    fun `nextSendRelative future timestamp uses the future plural`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = fixedNow.plus(5, ChronoUnit.MINUTES).toEpochMilli(),
                sendCount = 0,
            )
        val result = nextSendRelative(stub, context, fixedNow)
        assertTrue("Expected 'in 5 minutes' but got: $result", result.contains("5"))
        assertTrue("Expected future direction but got: $result", result.contains("in"))
    }

    @Test
    fun `lastSentRelative days past uses the day plural`() {
        val fixedNow = Instant.now()
        val stub =
            SimCardStub(
                simId = 1,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = fixedNow.minus(3, ChronoUnit.DAYS).toEpochMilli(),
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val result = lastSentRelative(stub, context, fixedNow)
        assertTrue("Expected '3 days ago' but got: $result", result.contains("3"))
        assertTrue("Expected 'day' but got: $result", result.contains("day"))
    }

    @Test
    fun `DashboardUiState defaults notificationsGranted false`() {
        val state = DashboardUiState()
        assertFalse(state.notificationsGranted)
        assertFalse(state.isAndroid13Plus)
    }

    @Test
    fun `applyStats applies config stats to matching cards`() {
        val card =
            SimCardStub(
                simId = 11,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val config =
            SimKeepaliveConfig(
                simId = 11,
                enabled = true,
                lastSentAtMillis = 1000L,
                nextSendAtMillis = 2000L,
                sendCount = 7,
            )
        val result = applyStats(listOf(card), mapOf(11 to config))
        assertEquals(1, result.size)
        assertEquals(1000L, result[0].lastSentAtMillis)
        assertEquals(2000L, result[0].nextSendAtMillis)
        assertEquals(7, result[0].sendCount)
        assertEquals(card.label, result[0].label)
        assertEquals(card.status, result[0].status)
        assertTrue(result[0].keepaliveEnabled)
    }

    @Test
    fun `applyStats overlays a disabled keepalive state`() {
        val card =
            SimCardStub(
                simId = 11,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
                keepaliveEnabled = true,
            )
        val config = SimKeepaliveConfig(simId = 11, enabled = false)
        val result = applyStats(listOf(card), mapOf(11 to config))
        assertFalse(result[0].keepaliveEnabled)
    }

    @Test
    fun `applyStats leaves cards without a config unchanged`() {
        val card =
            SimCardStub(
                simId = 12,
                label = "Test",
                slot = 1,
                carrier = "Carrier",
                status = SimStatus.ACTIVE,
                lastSentAtMillis = null,
                nextSendAtMillis = null,
                sendCount = 0,
            )
        val otherConfig = SimKeepaliveConfig(simId = 99, sendCount = 3)
        val result = applyStats(listOf(card), mapOf(99 to otherConfig))
        assertEquals(listOf(card), result)
    }

    @Test
    fun `observeConfigs emission updates card stats from config`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            // The SubscriptionInfo no-arg constructor is package-private in the compile stub
            // but public in Robolectric's android-all; build it reflectively.
            val subInfo =
                org.robolectric.util.ReflectionHelpers
                    .callConstructor(android.telephony.SubscriptionInfo::class.java)
            org.robolectric.util.ReflectionHelpers
                .setField(subInfo, "mId", 21)
            org.robolectric.util.ReflectionHelpers
                .setField(subInfo, "mSimSlotIndex", 0)
            org.robolectric.util.ReflectionHelpers
                .setField(subInfo, "mDisplayName", "Test SIM")
            org.robolectric.util.ReflectionHelpers
                .setField(subInfo, "mCarrierName", "Test Carrier")
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(listOf(subInfo))

            val pm = mock<PermissionManager>()
            val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
            vm.updateSimCards(context)
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 21,
                    enabled = true,
                    lastSentAtMillis = 1000L,
                    nextSendAtMillis = 2000L,
                    sendCount = 4,
                ),
            )
            waitFor {
                vm.state.value.realSimCards
                    .firstOrNull { it.simId == 21 }
                    ?.sendCount == 4
            }
            val card =
                vm.state.value.realSimCards
                    .first { it.simId == 21 }
            assertEquals(4, card.sendCount)
            assertEquals(1000L, card.lastSentAtMillis)
            assertEquals(2000L, card.nextSendAtMillis)
        }

    // The real manager (not a mock): the row states read the framework permission
    // through the manager's single isGranted path, so the shadow grant/deny must flow
    // through it to be asserted.
    @Test
    @Config(sdk = [33])
    fun `updatePermissions reflects granted notifications permission`() {
        val pm = PermissionManager(context)
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
        vm.updatePermissions(context)
        assertTrue(vm.state.value.notificationsGranted)
        assertTrue(vm.state.value.isAndroid13Plus)
    }

    @Test
    @Config(sdk = [33])
    fun `updatePermissions reflects denied notifications permission`() {
        val pm = PermissionManager(context)
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val vm = DashboardViewModel(context, pm, repository, mock<ScheduleReconciler>())
        vm.updatePermissions(context)
        assertFalse(vm.state.value.notificationsGranted)
        assertTrue(vm.state.value.isAndroid13Plus)
    }
}
