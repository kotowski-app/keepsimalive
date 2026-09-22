package app.kotowski.keepsimalive.ui.simsettings

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveDatabase
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.work.OccurrenceAdvancer
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelLoadTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository: KeepaliveRepository = mock()
    private val armer: ScheduleArmer = mock()
    private val reconcilerPrefs: AppPrefs = mock()
    private val mockPrefs: AppPrefs = mock()
    private val mockReconciler: ScheduleReconciler = mock()
    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        LogBuffer.clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        whenever(repository.observeNewest(any(), any())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(any())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
        // A real active subscription (id 11): the tests load it like any real SIM.
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(11, tm)
        shadowOf(tm).setSimOperatorName("Test Carrier")
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(11)
                        .setSimSlotIndex(0)
                        .setDisplayName("Test SIM")
                        .setCarrierName("Test Carrier")
                        .buildSubscriptionInfo(),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LogBuffer.clear()
    }

    private suspend fun waitFor(
        timeoutMs: Long = 10_000,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private fun realReconciler(repository: KeepaliveRepository): ScheduleReconciler =
        ScheduleReconciler(
            context,
            repository,
            armer,
            reconcilerPrefs,
            OccurrenceAdvancer(context, repository, armer),
            SimSendLock(),
        )

    @Test
    fun `loadSimData without phone permission shows error`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val vm =
            SimDetailViewModel(
                context,
                repository,
                mockReconciler,
                mockPrefs,
                armer,
                SimSendLock(),
                OffSchedulePendingRegistry(),
            )
        vm.loadSimData(context, 1)
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        assertNull(vm.state.value.data)
        assertFalse(vm.state.value.isLoading)
        // The permission-denied path must be visible in the in-app log (the dashboard's
        // empty-SIM-list path logs nothing by itself).
        assertTrue(
            LogBuffer.entries.any { it.level == "W" && it.tag == "SimDetailVM" && it.message.contains("READ_PHONE_STATE") },
        )
    }

    @Test
    fun `loadSimData without phone permission shows error for a real sim above the old stub range`() {
        // A real SIM with id 19 is not a stub: the READ_PHONE_STATE gate applies to it too.
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val vm =
            SimDetailViewModel(
                context,
                repository,
                mockReconciler,
                mockPrefs,
                armer,
                SimSendLock(),
                OffSchedulePendingRegistry(),
            )
        vm.loadSimData(context, 19)
        assertEquals(context.getString(R.string.sim_detail_permission_required), vm.state.value.error)
        assertNull(vm.state.value.data)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadSimData loads sim data without saved config`() =
        runBlocking {
            wheneverBlocking { repository.getConfig(any()) }.thenReturn(null)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            val data = vm.state.value.data
            assertNotNull(data)
            assertEquals(11, data!!.simId)
            assertNull(vm.state.value.config)
            assertNull(vm.state.value.error)
        }

    @Test
    @Config(sdk = [29])
    fun `loadSimData exposes multi sim flag from modem count`() =
        runBlocking {
            wheneverBlocking { repository.getConfig(any()) }.thenReturn(null)
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            SimDetector.cachedModemCount = null
            shadowOf(tm).setPhoneCount(2)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            assertNotNull(vm.state.value.data)
            assertTrue(vm.state.value.multiSimDevice)
        }

    @Test
    fun `loadSimData prefills saved config`() =
        runBlocking {
            val saved = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100", hour = 8)
            wheneverBlocking { repository.getConfig(11) }.thenReturn(saved)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            assertEquals(saved, vm.state.value.config)
            assertEquals(11, requireNotNull(vm.state.value.data).simId)
        }

    @Test
    fun `loadSimData shows error when sim not found`() =
        runBlocking {
            wheneverBlocking { repository.getConfig(any()) }.thenReturn(null)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 99)
            waitForLoad(vm)
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.data)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `loadSimData shows the last known info when the sim is absent but cached`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
            shadowOf(tm).setSimOperatorName("Test Carrier")
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java)).apply {
                setActiveSubscriptionInfoList(
                    listOf(
                        ShadowSubscriptionManager.SubscriptionInfoBuilder
                            .newBuilder()
                            .setId(1)
                            .setSimSlotIndex(1)
                            .setDisplayName("SIM A")
                            .setCarrierName("Carrier A")
                            .buildSubscriptionInfo(),
                    ),
                )
            }
            wheneverBlocking { repository.getConfig(any()) }.thenReturn(null)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 1)
            waitForLoad(vm)
            assertEquals(
                "SIM A",
                vm.state.value.data!!
                    .displayName,
            )
            assertFalse(vm.state.value.simMissing)

            // SIM unplugged: it leaves the active list. A fresh screen load must fall back
            // to the last known identity (remembered by the first load) instead of an error.
            shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(emptyList())
            val vm2 =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm2.loadSimData(context, 1)
            waitForLoad(vm2)
            val data = vm2.state.value.data
            assertNotNull(data)
            assertTrue(vm2.state.value.simMissing)
            assertEquals("SIM A", data!!.displayName)
            assertEquals("Carrier A", data.carrierName)
            assertEquals("Test Carrier", data.simOperatorName)
            assertNull(vm2.state.value.error)
            assertTrue(vm2.state.value.lastSeenAtMillis!! > 0)
            // The slot is remembered 1-based (the shadow's 0-based index 1 -> slot 2).
            assertEquals(2, vm2.state.value.lastSeenSlotIndex)
        }

    @Test
    fun `forgetSim deletes the config, the history and the cached identity`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.saveConfig(SimKeepaliveConfig(simId = 11, enabled = true, lastSentOccurrenceMillis = 1_690_000_000_000L))
                // An open retry row (PENDING, retryCount > 0): the forget drops it with the rest.
                realRepository.insertHistory(historyRow(11, 1_700_000_000_000L, SendOutcome.PENDING, retryCount = 1))
                SimIdentityCache.save(
                    context,
                    CachedSimIdentity(11, "Vodafone", "Vodafone", 1, "AIS", "Vodafone", false, 1_700_000_000_000L),
                )
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        mockReconciler,
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.forgetSim(11)
                waitFor { realRepository.getConfig(11) == null }
                assertNull(realRepository.getConfig(11))
                assertEquals(0, realRepository.observeNewest(11, Int.MAX_VALUE).first().size)
                assertNull(SimIdentityCache.get(context, 11))
                verify(armer).cancelSend(11)
                // The one-shot navigation signal lands with the committed forget (the
                // screen leaves with it, so the ViewModel outlives its own delete).
                assertTrue(vm.state.value.justForgot)
                // The config row is gone: the clock-change recompute's persisted
                // catch-up anchor for this SIM goes with it (a re-added SIM must not
                // inherit it). The last-sent-occurrence anchor is a column of that same
                // row: it dies with the row — a re-added SIM starts fresh.
                verify(mockPrefs).clearCatchUpAnchor(11)
                // Forgetting the (last) enabled SIM refreshes the persistent hint right away.
                verifyBlocking(mockReconciler) { syncPersistentHint() }
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `deleteSchedule removes the config, finalizes the open retry and keeps the history`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.saveConfig(SimKeepaliveConfig(simId = 11, enabled = true, lastSentOccurrenceMillis = 1_690_000_000_000L))
                realRepository.insertHistory(historyRow(11, 1_700_000_000_000L, SendOutcome.SENT))
                // A retry row (PENDING, retryCount > 0) is the open row the delete finalizes
                // as a skipped occurrence through the guarded open-row write.
                realRepository.insertHistory(historyRow(11, 1_700_100_000_000L, SendOutcome.PENDING, retryCount = 1))
                SimIdentityCache.save(
                    context,
                    CachedSimIdentity(11, "Vodafone", "Vodafone", 1, "AIS", "Vodafone", false, 1_700_000_000_000L),
                )
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        mockReconciler,
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.deleteSchedule(11)
                waitFor { realRepository.getConfig(11) == null }
                assertNull(realRepository.getConfig(11))
                // The screen follows the config observer: the summary goes away.
                waitFor { vm.state.value.config == null }
                assertNull(vm.state.value.config)
                verify(armer).cancelSend(11)
                // The config row is gone: the clock-change recompute's persisted
                // catch-up anchor for this SIM goes with it (a re-added SIM must not
                // inherit it). The last-sent-occurrence anchor is a column of that same
                // row: it dies with the row — a re-added SIM starts fresh.
                verify(mockPrefs).clearCatchUpAnchor(11)
                // The SENT history rows outlive the config row: the rhythm-reset marker
                // keeps a re-enable from re-anchoring the rhythm on the pre-delete send
                // (and recording the whole off period as missed occurrences).
                verify(mockPrefs).setRhythmResetAtMillis(eq(11), any())
                // Deleting the (last) enabled SIM refreshes the persistent hint right away.
                verifyBlocking(mockReconciler) { syncPersistentHint() }
                // History is kept: the terminal row survives untouched, the open retry is
                // finalized as a skipped occurrence with the user-facing reason.
                val rows = realRepository.observeNewest(11, Int.MAX_VALUE).first()
                assertEquals(2, rows.size)
                assertEquals(SendOutcome.SENT.name, rows.single { it.scheduledForMillis == 1_700_000_000_000L }.outcome)
                val skipped = rows.single { it.scheduledForMillis == 1_700_100_000_000L }
                assertEquals(SendOutcome.SKIPPED.name, skipped.outcome)
                assertEquals(context.getString(R.string.error_schedule_deleted), skipped.failureReason)
                // The identity cache is Forget's domain: unconfigure keeps it.
                assertNotNull(SimIdentityCache.get(context, 11))
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `deleteSchedule finalizes a sending occurrence with the direct update`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.saveConfig(SimKeepaliveConfig(simId = 11, enabled = true))
                realRepository.insertHistory(historyRow(11, 1_700_100_000_000L, SendOutcome.SENDING))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        mockReconciler,
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.deleteSchedule(11)
                waitFor { realRepository.getConfig(11) == null }
                // finalizeOccurrence guards PENDING only (including a retry row): a SENDING
                // attempt takes the direct update and still ends up as an explained skip,
                // never left open to ghost as "Trying now".
                val row = realRepository.observeNewest(11, Int.MAX_VALUE).first().single()
                assertEquals(SendOutcome.SKIPPED.name, row.outcome)
                assertEquals(context.getString(R.string.error_schedule_deleted), row.failureReason)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `deleteSchedule without an open occurrence only removes the config`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.saveConfig(SimKeepaliveConfig(simId = 11, enabled = true))
                realRepository.insertHistory(historyRow(11, 1_700_000_000_000L, SendOutcome.SENT))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        mockReconciler,
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.deleteSchedule(11)
                waitFor { realRepository.getConfig(11) == null }
                assertNull(realRepository.getConfig(11))
                val rows = realRepository.observeNewest(11, Int.MAX_VALUE).first()
                assertEquals(1, rows.size)
                assertEquals(SendOutcome.SENT.name, rows.single().outcome)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `loadSimData collects history rows into state`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.insertHistory(historyRow(11, 1700000000000L, SendOutcome.SENT))
                realRepository.insertHistory(historyRow(11, 1700000001000L, SendOutcome.FAILED))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                // The list and the count are two independent Room flows: wait for both,
                // since the count's first emission can lag the list's under load.
                waitFor { vm.state.value.history.size == 2 && vm.state.value.historyCount == 2 }
                assertEquals(2, vm.state.value.history.size)
                // The header count comes from the DB's terminal-row count flow (both rows
                // are terminal), not from the (capped) loaded list's size.
                assertEquals(2, vm.state.value.historyCount)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `re-saving a config keeps lifetime stats and re-arms the schedule`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val saved =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = true,
                    recipientPhone = "+15550100",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    endType = app.kotowski.keepsimalive.data.EndType.NEVER,
                    lastSentAtMillis = now - 86_400_000L,
                    sendCount = 4,
                    nextSendAtMillis = now + 3_600_000L,
                )
            wheneverBlocking { repository.getConfig(11) }.thenReturn(saved)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            // Re-save with different user fields — the engine-owned columns
            // (nextSendAtMillis, lastSentAtMillis, sendCount) are never written from the UI,
            // so they survive by construction, whatever a concurrent send did in between.
            val editor =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "updated",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                )
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
        }

    @Test
    fun `changing the schedule on save re-anchors the next send to the new schedule`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                val oldNext = now + 3_600_000L
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        message = "keep alive",
                        hour = 12,
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                        lastSentAtMillis = now - 86_400_000L,
                        sendCount = 1,
                        nextSendAtMillis = oldNext,
                    ),
                )
                // The SENT row is the engine's rhythm anchor: seed it at the last send so
                // the re-anchor below is deterministic.
                realRepository.insertHistory(historyRow(11, now - 86_400_000L, SendOutcome.SENT))
                realRepository.insertHistory(historyRow(11, oldNext, SendOutcome.PENDING))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                vm.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        message = "keep alive",
                        hour = 18,
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                    ),
                )
                // The next send is re-anchored to the last sent occurrence (the newest
                // SENT row) under the NEW schedule: last send + 30 days at the new time of
                // day, not the old stored time.
                val zone = ZoneId.systemDefault()
                val expected =
                    Instant
                        .ofEpochMilli(now - 86_400_000L)
                        .atZone(zone)
                        .toLocalDate()
                        .plusDays(30)
                        .atTime(18, 0)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                waitFor { realRepository.getConfig(11)?.nextSendAtMillis == expected }
                assertEquals(expected, realRepository.getConfig(11)?.nextSendAtMillis)
                // Lifetime stats survive the re-anchor.
                assertEquals(1, realRepository.getConfig(11)?.sendCount)
                // One pending row, aligned to the re-anchored time (the seeded SENT row
                // is terminal and not counted).
                val rows = withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(11, Int.MAX_VALUE).first() }
                val pending = rows.filter { it.outcome == SendOutcome.PENDING.name }
                assertEquals(1, pending.size)
                assertEquals(expected, pending[0].scheduledForMillis)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `re-saving an unchanged schedule keeps the stored next send`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                val storedNext = now + 3_600_000L
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        message = "keep alive",
                        hour = 12,
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                        lastSentAtMillis = now - 86_400_000L,
                        sendCount = 1,
                        nextSendAtMillis = storedNext,
                    ),
                )
                realRepository.insertHistory(historyRow(11, storedNext, SendOutcome.PENDING))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                vm.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        message = "updated",
                        hour = 12,
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                    ),
                )
                waitFor { realRepository.getConfig(11)?.message == "updated" }
                // The schedule is unchanged: the engine's own next send survives (no re-anchor,
                // no re-roll), so a concurrent send advance is never reverted by a save.
                assertEquals(storedNext, realRepository.getConfig(11)?.nextSendAtMillis)
                assertEquals(1, realRepository.getConfig(11)?.sendCount)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `disabling a sim via toggle cancels the schedule`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val nextSend = System.currentTimeMillis() + 3_600_000L
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        nextSendAtMillis = nextSend,
                    ),
                )
                realRepository.insertHistory(historyRow(11, nextSend, SendOutcome.PENDING))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                vm.setEnabled(false)
                waitFor { realRepository.getConfig(11)?.enabled == false }
                assertEquals(false, realRepository.getConfig(11)?.enabled)
                // The disable's clearPendingHistory lands a step after the flag flip: wait for
                // it instead of racing the commit coroutine.
                waitFor { realRepository.getActiveHistory(11) == null }
                assertNull(realRepository.getActiveHistory(11))
                assertNull(realRepository.getConfig(11)?.nextSendAtMillis)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `enabling a sim via toggle puts the recomputed next send into the screen state`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                // A disabled SIM: the disable branch left no next send behind (a re-enable
                // must recompute it), and a last send anchors the recompute.
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = false,
                        recipientPhone = "+15550100",
                        message = "keep alive",
                        hour = 12,
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                        lastSentAtMillis = now - 86_400_000L,
                        sendCount = 1,
                    ),
                )
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                vm.setEnabled(true)
                // The commit's own state write must already carry the recomputed next send —
                // not the pre-reconcile read (nextSendAtMillis = null), which would leave the
                // screen "Not scheduled" until a re-navigation. The state write and the
                // reconcile's write are the same lock section, so the state can never lag
                // behind the database it was read from.
                waitFor {
                    val c = vm.state.value.config
                    c != null && c.nextSendAtMillis != null
                }
                val stateConfig = vm.state.value.config
                assertNotNull(stateConfig)
                assertEquals(true, stateConfig?.enabled)
                assertNotNull(stateConfig?.nextSendAtMillis)
                assertEquals(realRepository.getConfig(11)?.nextSendAtMillis, stateConfig?.nextSendAtMillis)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun `save with an in-flight send keeps the in-flight row and its next send`() =
        runBlocking {
            val nextSend = System.currentTimeMillis() + 3_600_000L
            val saved =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = true,
                    recipientPhone = "+15550100",
                    lastSentAtMillis = System.currentTimeMillis() - 86_400_000L,
                    sendCount = 2,
                    nextSendAtMillis = nextSend,
                )
            wheneverBlocking { repository.getConfig(11) }.thenReturn(saved)
            wheneverBlocking { repository.getActiveHistory(11) }
                .thenReturn(
                    SendHistoryEntity(
                        simId = 11,
                        scheduledForMillis = nextSend,
                        occurrenceBaseMillis = nextSend,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            val editor = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100")
            vm.saveConfig(editor)
            // User columns only: the engine-owned next send of the in-flight occurrence is
            // never written from the UI.
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            // PENDING rows are not cleared while an in-flight occurrence exists: the send
            // engine owns the occurrence to completion, and clearing its next-send PENDING
            // row would lose the schedule.
            verifyBlocking(repository, times(0)) { clearPendingHistory(any()) }
        }

    @Test
    fun `saving while a send is in flight keeps the in-flight row`() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                // The occurrence is in flight right now: its row is SENDING, not PENDING.
                val nextSend = now - 5_000
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                        lastSentAtMillis = now - 86_400_000L,
                        sendCount = 4,
                        nextSendAtMillis = nextSend,
                    ),
                )
                realRepository.insertHistory(historyRow(11, nextSend, SendOutcome.SENDING))
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)
                vm.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        freqType = FrequencyType.EVERY_N_DAYS,
                        daysInterval = 30,
                    ),
                )
                // The in-flight row survives the save and the engine's own next-send value
                // is preserved: the live attempt owns the occurrence to completion.
                val active = realRepository.getActiveHistory(11)
                assertEquals(SendOutcome.SENDING.name, active?.outcome)
                assertEquals(nextSend, active?.scheduledForMillis)
                assertEquals(nextSend, realRepository.getConfig(11)?.nextSendAtMillis)
                val rows = withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(11, Int.MAX_VALUE).first() }
                assertEquals(1, rows.size)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    // A real enabled schedule whose next occurrence is the rhythm's true next send (the
    // 12:00 default hour, 30 days after the last send): the off-schedule tests arm a
    // one-off on top of it and the cancel test must restore exactly this next send.
    private suspend fun saveRegularSchedule(
        realRepository: KeepaliveRepository,
        now: Long,
    ): Long {
        val lastSent = now - 86_400_000L
        val regularNext =
            Instant
                .ofEpochMilli(lastSent)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .plusDays(30)
                .atTime(12, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        realRepository.saveConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = true,
                recipientPhone = "+15550100",
                message = "keep alive",
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                lastSentAtMillis = lastSent,
                sendCount = 1,
                nextSendAtMillis = regularNext,
            ),
        )
        // The SENT row is the engine's rhythm anchor: seed it at the last send so a
        // recompute (e.g. the one-off cancel restore) lands on the same next send.
        realRepository.insertHistory(historyRow(11, lastSent, SendOutcome.SENT))
        return regularNext
    }

    @Test
    fun `sendOffSchedule arms the one-off and the pending state clears on the engine claim`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                saveRegularSchedule(realRepository, now)
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                // The arm lands: the next send is the one-off's fire time, its PENDING row
                // replaces the regular one (the stored next send is superseded), and the
                // state carries the pending fire time for the dialog's countdown.
                vm.sendOffSchedule(11)
                waitFor { vm.state.value.offScheduleSendPendingAtMillis != null }
                val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
                assertTrue(
                    "the one-off must be armed with the configured delay",
                    t in (now + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS)..(now + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS + 5_000L),
                )
                assertEquals(t, realRepository.getConfig(11)?.nextSendAtMillis)
                val pendingRow = requireNotNull(realRepository.getActiveHistory(11))
                assertEquals(SendOutcome.PENDING.name, pendingRow.outcome)
                assertEquals(t, pendingRow.scheduledForMillis)
                assertEquals("+15550100", pendingRow.recipient)
                assertEquals("keep alive", pendingRow.message)

                // The engine claims the occurrence at the fire time: the history flow must
                // drop the pending state (the confirm dialog auto-closes through it).
                realRepository.updateHistory(pendingRow.id, SendOutcome.SENDING, now, null, 0, now)
                waitFor { vm.state.value.offScheduleSendPendingAtMillis == null }
                assertNull(vm.state.value.offScheduleSendPendingAtMillis)
            } finally {
                // In-memory DB: no close() (it throws); the builder tears the database
                // down with the test.
            }
        }

    @Test
    fun `cancelOffSchedule finalizes the one-off and restores the regular next send`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                val regularNext = saveRegularSchedule(realRepository, now)
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.sendOffSchedule(11)
                waitFor { vm.state.value.offScheduleSendPendingAtMillis != null }
                val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
                vm.cancelOffSchedule(11)

                waitFor { realRepository.getActiveHistory(11) == null }
                val rows = withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(11, Int.MAX_VALUE).first() }
                val skipped = rows.single { it.scheduledForMillis == t }
                assertEquals(SendOutcome.SKIPPED.name, skipped.outcome)
                assertEquals(context.getString(R.string.error_send_cancelled), skipped.failureReason)
                verify(armer).cancelSend(11)
                // The regular schedule is restored: the anchor (the last regular
                // send) never moved, so the recompute lands on the same next send the
                // schedule had before the one-off was armed. The restore is an async
                // recompute that follows the row finalization, so wait for it (an
                // immediate assertion races the full-suite load and flakes).
                waitFor { realRepository.getConfig(11)?.nextSendAtMillis == regularNext }
                assertEquals(regularNext, realRepository.getConfig(11)?.nextSendAtMillis)
                waitFor { vm.state.value.offScheduleSendPendingAtMillis == null }
                assertNull(vm.state.value.offScheduleSendPendingAtMillis)
            } finally {
                // In-memory DB: no close() (it throws); the builder tears the database
                // down with the test.
            }
        }

    @Test
    fun `sendOffSchedule is refused while the schedule is ended`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.saveConfig(
                    SimKeepaliveConfig(
                        simId = 11,
                        enabled = true,
                        recipientPhone = "+15550100",
                        message = "keep alive",
                        endType = EndType.AFTER_N_SENDS,
                        maxSends = 1,
                        sendCount = 1,
                        lastSentAtMillis = System.currentTimeMillis() - 86_400_000L,
                    ),
                )
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        SimSendLock(),
                        OffSchedulePendingRegistry(),
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                // The ended state is derived; the gate refuses before anything is armed
                // (the engine would consume the one-off as an explained skip — no SMS).
                assertNotNull(vm.state.value.endedReason)
                vm.sendOffSchedule(11)
                assertNull(vm.state.value.offScheduleSendPendingAtMillis)
                assertNull(realRepository.getConfig(11)?.nextSendAtMillis)
                assertNull(realRepository.getActiveHistory(11))
            } finally {
                // In-memory DB: no close() (it throws); the builder tears the database
                // down with the test.
            }
        }

    // A disable that lands after the state read (engine auto-disable, schedule end): the
    // pre-lock checks pass on the stale enabled state, but the in-lock config re-read must
    // see the disable and back off silently — no write, no arm, no registry mark, no
    // pending state (the config observer hides the button when the disabled config
    // arrives).
    @Test
    fun `sendOffSchedule backs off when the config is disabled under the lock`() =
        runBlocking {
            val enabled = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val configCalls = AtomicInteger(0)
            // The load reads the enabled config (the pre-lock checks pass on it); the
            // in-lock re-read is the next call and must see the disable that landed after
            // the state read.
            wheneverBlocking { repository.getConfig(11) }
                .thenAnswer {
                    configCalls.incrementAndGet()
                    enabled
                }.thenAnswer {
                    configCalls.incrementAndGet()
                    enabled.copy(enabled = false)
                }
            // No open row: without the config re-read the arm would have run.
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            val registry = OffSchedulePendingRegistry()
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    registry,
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            assertEquals(enabled, vm.state.value.config)

            vm.sendOffSchedule(11)
            // Wait for the in-lock re-read (the second getConfig call): the back-off
            // decision is the synchronous statement right after it.
            waitFor { configCalls.get() >= 2 }
            assertEquals(2, configCalls.get())
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
            assertNull(registry.getPending(11))
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { alignPendingRow(any(), any(), any(), any(), any()) }
            verifyBlocking(armer, never()) { armSend(anyInt()) }
        }

    // The deleted-row twin of the back-off: the config row is gone under the lock (a
    // delete/forget racing the tap) — the same silent back-off, nothing armed or marked.
    @Test
    fun `sendOffSchedule backs off when the config row is deleted under the lock`() =
        runBlocking {
            val enabled = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val configCalls = AtomicInteger(0)
            wheneverBlocking { repository.getConfig(11) }
                .thenAnswer {
                    configCalls.incrementAndGet()
                    enabled
                }.thenAnswer {
                    configCalls.incrementAndGet()
                    null
                }
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            val registry = OffSchedulePendingRegistry()
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    registry,
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            assertEquals(enabled, vm.state.value.config)

            vm.sendOffSchedule(11)
            waitFor { configCalls.get() >= 2 }
            assertEquals(2, configCalls.get())
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
            assertNull(registry.getPending(11))
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { alignPendingRow(any(), any(), any(), any(), any()) }
            verifyBlocking(armer, never()) { armSend(anyInt()) }
        }

    // A save that changed the recipient/message between the state read and the lock must
    // be honored by the arm: the in-lock re-read carries the live fields to the pending
    // row (the engine's pre-send checks still guard structural defects at send time).
    @Test
    fun `sendOffSchedule arms with the live config fields when a save landed in flight`() =
        runBlocking {
            val stale = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val live = SimKeepaliveConfig(simId = 11, enabled = true, recipientPhone = "+15550199", message = "live message")
            // The load reads the state config; the in-lock re-read is the next call and
            // must see the save that landed after the state read.
            wheneverBlocking { repository.getConfig(11) }.thenReturn(stale, live)
            wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
            val registry = OffSchedulePendingRegistry()
            val vm =
                SimDetailViewModel(
                    context,
                    repository,
                    mockReconciler,
                    mockPrefs,
                    armer,
                    SimSendLock(),
                    registry,
                )
            vm.loadSimData(context, 11)
            waitForLoad(vm)
            assertEquals(stale, vm.state.value.config)

            val now = System.currentTimeMillis()
            vm.sendOffSchedule(11)
            waitFor { vm.state.value.offScheduleSendPendingAtMillis != null }
            val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
            assertTrue(
                "the one-off must be armed with the configured delay",
                t in (now + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS)..(now + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS + 5_000L),
            )
            verifyBlocking(repository) { alignPendingRow(11, t, t, "+15550199", "live message") }
            verifyBlocking(repository) { updateNextSend(11, t) }
            verifyBlocking(armer) { armSend(11) }
            assertEquals(t, registry.getPending(11))
            assertEquals(t, vm.state.value.offScheduleSendPendingAtMillis)
        }

    // The process-level registry is the copy of the pending state that survives the
    // ViewModel's death: leaving the screen and re-visiting within the delay window must
    // restore the card's Cancel (and the cancel from the re-visit must finalize the
    // one-off and drop the entry).
    @Test
    fun `a re-visit restores the pending one-off from the registry and cancels it`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                saveRegularSchedule(realRepository, now)
                val registry = OffSchedulePendingRegistry()
                val lock = SimSendLock()
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        lock,
                        registry,
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                vm.sendOffSchedule(11)
                waitFor { vm.state.value.offScheduleSendPendingAtMillis != null }
                val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
                // The arm must reach the process-level copy (the state copy alone dies
                // with the ViewModel).
                assertEquals(t, registry.getPending(11))

                val revisited =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        lock,
                        registry,
                    )
                revisited.loadSimData(context, 11)
                waitForLoad(revisited)
                assertEquals(t, revisited.state.value.offScheduleSendPendingAtMillis)

                // The cancel from the re-visit finalizes the one-off and drops the
                // registry entry with it. The row clearing happens before the state/registry
                // update (the recompute in between takes real time), so wait for the state —
                // the actual completion condition — not just the row.
                revisited.cancelOffSchedule(11)
                waitFor { realRepository.getActiveHistory(11) == null }
                waitFor { revisited.state.value.offScheduleSendPendingAtMillis == null }
                assertNull(revisited.state.value.offScheduleSendPendingAtMillis)
                assertNull(registry.getPending(11))
            } finally {
                // In-memory DB: no close() (it throws); the builder tears the database
                // down with the test.
            }
        }

    // The registry entry can be stale: the one-off resolved or was replaced while no
    // ViewModel was alive to clear it. The re-visit must not keep the Cancel for a
    // resolved one-off: the history flow validates the remembered time against the open
    // PENDING rows and drops the state and the entry on its first emission.
    @Test
    fun `a re-visit after the one-off resolved restores no pending state`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                val now = System.currentTimeMillis()
                saveRegularSchedule(realRepository, now)
                val registry = OffSchedulePendingRegistry()
                val lock = SimSendLock()
                val vm =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        lock,
                        registry,
                    )
                vm.loadSimData(context, 11)
                waitForLoad(vm)

                // The stale entry: a remembered fire time whose PENDING row no longer
                // exists (the one-off resolved while the screen was away).
                registry.markPending(11, now - 60_000L)

                val revisited =
                    SimDetailViewModel(
                        context,
                        realRepository,
                        realReconciler(realRepository),
                        mockPrefs,
                        armer,
                        lock,
                        registry,
                    )
                revisited.loadSimData(context, 11)
                waitForLoad(revisited)
                // The first history emission drops the stale entry: no Cancel for a
                // resolved one-off, and the entry is gone for the next re-visit.
                waitFor {
                    revisited.state.value.offScheduleSendPendingAtMillis == null && registry.getPending(11) == null
                }
                assertNull(revisited.state.value.offScheduleSendPendingAtMillis)
                assertNull(registry.getPending(11))
            } finally {
                // In-memory DB: no close() (it throws); the builder tears the database
                // down with the test.
            }
        }

    private fun historyRow(
        simId: Int,
        scheduledForMillis: Long,
        outcome: SendOutcome,
        retryCount: Int = 0,
    ): SendHistoryEntity =
        SendHistoryEntity(
            simId = simId,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
            retryCount = retryCount,
        )

    private suspend fun waitForLoad(vm: SimDetailViewModel) {
        val deadline = System.currentTimeMillis() + 5000
        while (vm.state.value.isLoading && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }
}
