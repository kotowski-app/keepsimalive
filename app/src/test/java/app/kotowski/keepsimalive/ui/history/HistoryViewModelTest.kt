package app.kotowski.keepsimalive.ui.history

import android.app.Application
import androidx.room.Room
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.KeepaliveDatabase
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.isTerminal
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.SimIdentityCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The per-SIM history view model: one growing live window (the newest `loadedLimit` rows,
// observed live, the limit growing by a page per scroll), the exact terminal count for the
// header, the retention period (drives the auto-clear banner) and "Run cleanup now".
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository: KeepaliveRepository = mock()
    private val prefs: AppPrefs = mock()
    private val context: Application = RuntimeEnvironment.getApplication()

    private val regionFlow = MutableStateFlow<List<SendHistoryEntity>>(emptyList())
    private val countFlow = MutableStateFlow(0)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // One window query for any limit: the test drives the window's contents through
        // regionFlow (a grown window re-emits its larger rows through the same flow).
        whenever(repository.observeNewest(any(), any())).thenReturn(regionFlow)
        whenever(repository.observeHistoryCount(any())).thenReturn(countFlow)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HistoryViewModel(repository, prefs, context)

    // The screen's LaunchedEffect drives the load with the route's simId. Waits for both
    // the list (isLoading) and the SIM name (title): the name resolves on its own IO
    // coroutine and can outlive the list's first emission.
    private suspend fun load(
        vm: HistoryViewModel,
        simId: Int = 11,
    ) {
        vm.loadHistory(simId)
        val deadline = System.currentTimeMillis() + 5000
        while (
            (
                vm.state.value.isLoading ||
                    vm.state.value.simDisplayName
                        .isEmpty()
            ) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(10)
        }
    }

    // A row with an explicit id: the list keys its rows by id, so rows without distinct
    // ids (Room assigns them, mocks do not) would collapse into one.
    private fun row(
        id: Long,
        simId: Int = 11,
        scheduledForMillis: Long = id * HOUR,
        outcome: SendOutcome = SendOutcome.SENT,
    ): SendHistoryEntity =
        SendHistoryEntity(
            id = id,
            simId = simId,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
        )

    // The newest-first window of rows with the given ids (row id n is the nth-newest: its
    // timestamp is n * HOUR, so a higher id is always newer).
    private fun page(ids: List<Long>): List<SendHistoryEntity> = ids.map { row(it) }

    // The base window the screen loads: the newest 20 rows (HISTORY_PAGE_SIZE).
    private val firstPage: List<Long> = (21L..40L).toList().reversed()

    @Test
    fun loadSurfacesTheRetentionDaysFromPrefs() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(365)
            load(vm)
            assertEquals(365, vm.state.value.historyRetentionDays)
        }

    @Test
    fun loadDefaultsToRetentionOffWhenNothingIsStored() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(0)
            load(vm)
            assertEquals(0, vm.state.value.historyRetentionDays)
        }

    @Test
    fun loadPopulatesTheHistoryAndTheCount() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = listOf(row(2), row(1))
            countFlow.value = 2
            waitFor { vm.state.value.history.size == 2 && vm.state.value.historyCount == 2 }
            assertEquals(2, vm.state.value.history.size)
            // The header count comes from the DB's terminal-row count flow, not from the
            // (window-sized) loaded list's size.
            assertEquals(2, vm.state.value.historyCount)
        }

    @Test
    fun loadResolvesTheSimDisplayNameForTheTitle() =
        runBlocking {
            val vm = viewModel()
            // No live subscription for 11 in the test device: the title resolves from the
            // last known identity (the live read misses, the cache hit serves the name).
            SimIdentityCache.save(
                context,
                CachedSimIdentity(
                    simId = 11,
                    displayName = "Vodafone",
                    carrierName = "Vodafone",
                    slotIndex = 1,
                    networkOperatorName = "AIS",
                    simOperatorName = "Vodafone",
                    isNetworkRoaming = false,
                    lastSeenAtMillis = 1_700_000_000_000L,
                ),
            )
            load(vm, simId = 11)
            assertEquals("Vodafone", vm.state.value.simDisplayName)
        }

    @Test
    fun loadFallsBackToTheSubscriptionIdNameWhenTheSimIsUnknown() =
        runBlocking {
            val vm = viewModel()
            // 999 is no subscription in the test device: the live read misses, nothing was
            // ever cached, so the title names the subscription id.
            load(vm, simId = 999)
            assertEquals(
                context.getString(R.string.sim_name_fallback, 999),
                vm.state.value.simDisplayName,
            )
        }

    @Test
    fun hasMoreIsTrueWhileTheLoadedTerminalRowsAreBelowTheCount() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 21
            waitFor { vm.state.value.hasMore }
            assertTrue(vm.state.value.hasMore)
            // The count flow is live: the flag tracks it, not the loaded list.
            countFlow.value = 20
            waitFor { !vm.state.value.hasMore }
            assertFalse(vm.state.value.hasMore)
            countFlow.value = 40
            waitFor { vm.state.value.hasMore }
            assertTrue(vm.state.value.hasMore)
        }

    @Test
    fun hasMoreIsFalseWithNoRowsAndDoesNotCountLiveRowsAsLoaded() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            // No rows loaded and no count: the window is not growable against nothing.
            assertFalse(vm.state.value.hasMore)
            // 19 terminal + 1 live row: only the 19 terminal rows count as loaded.
            regionFlow.value = page(firstPage.dropLast(1)) + row(41, outcome = SendOutcome.PENDING)
            countFlow.value = 20
            waitFor { vm.state.value.history.size == 20 }
            assertTrue(vm.state.value.hasMore)
        }

    @Test
    fun loadMoreGrowsTheWindowAndTheListGrowsInPlace() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 40
            waitFor { vm.state.value.hasMore && vm.state.value.history.size == 20 }
            vm.loadMore()
            // The grow's re-emit: 40 rows newest first (the mock serves the same flow for
            // any limit, so the test drives the larger window through it — the re-emit
            // must land AFTER the grow, or hasMore would already be false).
            regionFlow.value = page((40L downTo 1L).toList())
            // The re-emit is immediate: the list is already at 40 rows while the spinner
            // is still held.
            waitFor { vm.state.value.history.size == 40 }
            assertTrue(vm.state.value.isLoadingMore)
            // The hold is a real (virtual) minimum, pure UX: the spinner is still up 300
            // ms in...
            testDispatcher.scheduler.advanceTimeBy(300)
            assertTrue(vm.state.value.isLoadingMore)
            // ...and it elapses by the minimum delay (400 ms).
            testDispatcher.scheduler.advanceTimeBy(150)
            waitFor { !vm.state.value.isLoadingMore }
            // The grow is a limit bump, not a fetch: observeNewest was called with the
            // base limit on load and the doubled limit by loadMore.
            val captor = argumentCaptor<Int>()
            verifyBlocking(repository, times(2)) { observeNewest(eq(11), captor.capture()) }
            assertEquals(
                listOf(
                    AppConfig.HISTORY_PAGE_SIZE,
                    2 * AppConfig.HISTORY_PAGE_SIZE,
                ),
                captor.allValues,
            )
            assertFalse(vm.state.value.isLoadingMore)
            assertEquals(
                (40L downTo 1L).toList(),
                vm.state.value.history
                    .map { it.id },
            )
        }

    @Test
    fun loadMoreIsANoOpWhileAnotherLoadIsInFlight() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 40
            waitFor { vm.state.value.hasMore }
            vm.loadMore()
            // The grow is in flight (its 400 ms hold is pending on the virtual clock): a
            // second trigger must not grow the window twice.
            assertTrue(vm.state.value.isLoadingMore)
            vm.loadMore()
            // The grow's re-emit: 40 rows.
            regionFlow.value = page((40L downTo 1L).toList())
            waitFor(advanceVirtual = true) { !vm.state.value.isLoadingMore }
            val captor = argumentCaptor<Int>()
            verifyBlocking(repository, times(2)) { observeNewest(eq(11), captor.capture()) }
            assertEquals(
                listOf(
                    AppConfig.HISTORY_PAGE_SIZE,
                    2 * AppConfig.HISTORY_PAGE_SIZE,
                ),
                captor.allValues,
            )
            assertEquals(40, vm.state.value.history.size)
        }

    @Test
    fun loadMoreIsANoOpWhenEverythingIsLoaded() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 20
            waitFor { vm.state.value.history.size == 20 }
            assertFalse(vm.state.value.hasMore)
            vm.loadMore()
            assertFalse(vm.state.value.isLoadingMore)
            assertEquals(20, vm.state.value.history.size)
            val captor = argumentCaptor<Int>()
            verifyBlocking(repository, times(1)) { observeNewest(eq(11), captor.capture()) }
            assertEquals(AppConfig.HISTORY_PAGE_SIZE, captor.firstValue)
        }

    @Test
    fun loadMoreGrowsTheWindowEvenWithoutLoadedRows() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            // The count says rows exist, but the window has not emitted any yet: the grow
            // has no cursor, so the limit still grows (the UI cannot trigger it — the
            // empty state replaces the list — but the call is harmless).
            countFlow.value = 5
            vm.loadMore()
            waitFor(advanceVirtual = true) { !vm.state.value.isLoadingMore }
            val captor = argumentCaptor<Int>()
            verifyBlocking(repository, times(2)) { observeNewest(eq(11), captor.capture()) }
            assertEquals(
                listOf(
                    AppConfig.HISTORY_PAGE_SIZE,
                    2 * AppConfig.HISTORY_PAGE_SIZE,
                ),
                captor.allValues,
            )
            assertTrue(
                vm.state.value.history
                    .isEmpty(),
            )
        }

    @Test
    fun clearHistoryEmptiesTheListOnTheReEmit() =
        runBlocking {
            val vm = viewModel()
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 40
            waitFor { vm.state.value.hasMore }
            vm.loadMore()
            // The grow's re-emit: 40 rows.
            regionFlow.value = page((40L downTo 1L).toList())
            waitFor(advanceVirtual = true) { vm.state.value.history.size == 40 && !vm.state.value.isLoadingMore }
            vm.clearHistory()
            // No immediate reset: the window still holds the loaded rows until the delete
            // commits.
            assertEquals(40, vm.state.value.history.size)
            assertFalse(vm.state.value.isLoadingMore)
            // The delete commits: the window re-emits empty (at most the surviving
            // in-flight row), the list empties with it.
            regionFlow.value = emptyList()
            countFlow.value = 0
            waitFor {
                vm.state.value.history
                    .isEmpty()
            }
            assertTrue(
                vm.state.value.history
                    .isEmpty(),
            )
        }

    @Test
    fun runCleanupNowKeepsTheListUntilTheReEmitThenShowsTheSurvivors() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(365)
            load(vm)
            regionFlow.value = page(firstPage)
            countFlow.value = 40
            waitFor { vm.state.value.hasMore }
            vm.loadMore()
            // The grow's re-emit: 40 rows.
            regionFlow.value = page((40L downTo 1L).toList())
            waitFor(advanceVirtual = true) { vm.state.value.history.size == 40 && !vm.state.value.isLoadingMore }
            wheneverBlocking { repository.deleteOldHistory(any()) }.thenReturn(20)
            vm.runCleanupNow()
            waitFor { vm.state.value.cleanupDeletedCount == 20 }
            // No immediate list reset: the window still holds the loaded rows until the
            // delete commits and the window re-emits with the survivors.
            assertEquals(40, vm.state.value.history.size)
            assertFalse(vm.state.value.isLoadingMore)
            // The sweep deleted the 20 old rows: the re-emit keeps only the survivors,
            // and the window covers them all — nothing more to grow.
            regionFlow.value = page(firstPage)
            countFlow.value = 20
            waitFor { vm.state.value.history.size == 20 && !vm.state.value.hasMore }
            assertEquals(20, vm.state.value.history.size)
            assertFalse(vm.state.value.hasMore)
            assertFalse(vm.state.value.isLoadingMore)
        }

    @Test
    fun runCleanupNowWithTheRetentionOffDoesNothing() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(0)
            load(vm)
            vm.runCleanupNow()
            verifyBlocking(repository, never()) { deleteOldHistory(any()) }
            assertNull(vm.state.value.cleanupDeletedCount)
        }

    @Test
    fun runCleanupNowDeletesRowsOlderThanTheRetentionAndReportsTheCount() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(365)
            load(vm)
            wheneverBlocking { repository.deleteOldHistory(any()) }.thenReturn(7)
            val before = System.currentTimeMillis()
            vm.runCleanupNow()
            val after = System.currentTimeMillis()
            val captor = argumentCaptor<Long>()
            verifyBlocking(repository) { deleteOldHistory(captor.capture()) }
            val cutoff = captor.firstValue
            assertTrue(
                "cutoff $cutoff outside [${before - 365L * AppConfig.DAY_MS - 1000}, ${after - 365L * AppConfig.DAY_MS + 1000}]",
                cutoff in (before - 365L * AppConfig.DAY_MS - 1000)..(after - 365L * AppConfig.DAY_MS + 1000),
            )
            assertEquals(7, vm.state.value.cleanupDeletedCount)
        }

    @Test
    fun clearCleanupResultResetsTheCount() =
        runBlocking {
            val vm = viewModel()
            whenever(prefs.historyRetentionDays).thenReturn(365)
            load(vm)
            wheneverBlocking { repository.deleteOldHistory(any()) }.thenReturn(3)
            vm.runCleanupNow()
            assertEquals(3, vm.state.value.cleanupDeletedCount)
            vm.clearCleanupResult()
            assertNull(vm.state.value.cleanupDeletedCount)
        }

    @Test
    fun scrollingToTheBottomGrowsTheWindowOverEveryRowExactlyOnceFromTheDb() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                repeat(45) { i ->
                    realRepository.insertHistory(historyRow(11, (i + 1).toLong() * HOUR, SendOutcome.SENT))
                }
                val vm = HistoryViewModel(realRepository, prefs, context)
                vm.loadHistory(11)
                // The base window only (20 of the 45 rows): the screen enters cheap.
                waitFor { vm.state.value.history.size == 20 && vm.state.value.hasMore }
                // Each loadMore grows the window by a page (spinner held >= 400 ms each,
                // pure UX).
                vm.loadMore()
                waitFor(
                    advanceVirtual = true,
                ) { vm.state.value.history.size == 40 && !vm.state.value.isLoadingMore }
                vm.loadMore()
                waitFor(
                    advanceVirtual = true,
                ) { vm.state.value.history.size == 45 && !vm.state.value.hasMore && !vm.state.value.isLoadingMore }
                // The grown window covers the history exactly: every row once, newest
                // first.
                assertEquals(
                    (45 downTo 1).map { it * HOUR },
                    vm.state.value.history
                        .map { it.scheduledForMillis },
                )
                // Exhausted: another loadMore is a no-op.
                vm.loadMore()
                delay(600)
                assertEquals(45, vm.state.value.history.size)
                assertFalse(vm.state.value.isLoadingMore)
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    @Test
    fun aSendFinishingWhileTheScreenIsOpenAppearsLiveAtTheTop() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                repeat(25) { i ->
                    realRepository.insertHistory(historyRow(11, (i + 1).toLong() * HOUR, SendOutcome.SENT))
                }
                // The in-flight occurrence: the newest row, still open.
                val inFlightId = realRepository.insertHistory(historyRow(11, 26L * HOUR, SendOutcome.SENDING))
                val vm = HistoryViewModel(realRepository, prefs, context)
                vm.loadHistory(11)
                waitFor { vm.state.value.history.size == 20 && vm.state.value.historyCount == 25 }
                // The window holds the newest 20 rows: the in-flight one plus 19 finished.
                val inFlight =
                    vm.state.value.history
                        .first { it.scheduledForMillis == 26L * HOUR }
                assertEquals(SendOutcome.SENDING.name, inFlight.outcome)
                // The attempt finishes: the flip re-emits the window, no interaction needed.
                realRepository.updateHistory(inFlightId, SendOutcome.SENT, 26L * HOUR, null, 0, 26L * HOUR)
                waitFor {
                    vm.state.value.history
                        .first { it.scheduledForMillis == 26L * HOUR }
                        .outcome == SendOutcome.SENT.name
                }
                // The live count followed the flip too (25 -> 26 terminal rows).
                waitFor { vm.state.value.historyCount == 26 }
                assertEquals(26, vm.state.value.historyCount)
            } finally {
                // The in-memory DB is deliberately not closed (see the paging test above).
            }
        }

    // The verified gap scenario: the old region+append design froze a keyset cursor at the
    // window's bottom row, so a send finishing while the screen was open (the PENDING row
    // flips SENT and the next-occurrence arm inserts a new row) shifted the capped region
    // and stranded the crossed row in a gap covered by neither part. The growing window
    // cannot strand a row: its limit only ever grows, and the list is a pure query result.
    @Test
    fun aSendFinishingAfterTheWindowGrewKeepsEveryLoadedRowVisible() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                // 20 terminal rows, one hour apart.
                repeat(20) { i ->
                    realRepository.insertHistory(historyRow(11, (i + 1).toLong() * HOUR, SendOutcome.SENT))
                }
                // The armed next occurrence (the "schedule in 20s" shape): the newest row,
                // still open.
                val pendingId = realRepository.insertHistory(historyRow(11, 21L * HOUR, SendOutcome.PENDING))
                val vm = HistoryViewModel(realRepository, prefs, context)
                vm.loadHistory(11)
                // The base window: the PENDING row plus the 19 newest terminals.
                waitFor { vm.state.value.history.size == 20 && vm.state.value.historyCount == 20 }
                // Scroll to the bottom: the window grows to cover every row (in the old
                // design the keyset cursor froze at the 19th terminal here, and the send
                // below pushed it out of the region into the gap).
                vm.loadMore()
                waitFor(advanceVirtual = true) { vm.state.value.history.size == 21 && !vm.state.value.hasMore }
                // The send finishes while the screen is open: the PENDING row flips SENT
                // and the next occurrence is armed (a new PENDING row, the newest).
                realRepository.updateHistory(pendingId, SendOutcome.SENT, 21L * HOUR, null, 0, 21L * HOUR)
                realRepository.insertHistory(historyRow(11, 22L * HOUR, SendOutcome.PENDING))
                // The live re-emit, no interaction: the finished row surfaces as a
                // terminal and the new PENDING row appears at the top of the window.
                waitFor {
                    vm.state.value.historyCount == 21 &&
                        vm.state.value.history
                            .first()
                            .scheduledForMillis == 22L * HOUR &&
                        vm.state.value.history
                            .first { it.scheduledForMillis == 21L * HOUR }
                            .outcome == SendOutcome.SENT.name
                }
                // No previously loaded terminal row is missing: the window (a pure query
                // result, limit only ever grows) still holds all 21 terminals, newest
                // first.
                val terminals =
                    vm.state.value.history
                        .filter { it.isTerminal }
                assertEquals(
                    (21 downTo 1).map { it * HOUR },
                    terminals.map { it.scheduledForMillis },
                )
                // The visible terminal count agrees with the header count, and the window
                // covers everything: nothing more to grow.
                assertEquals(vm.state.value.historyCount, terminals.size)
                assertFalse(vm.state.value.hasMore)
            } finally {
                // The in-memory DB is deliberately not closed (see the paging test above).
            }
        }

    @Test
    fun clearHistoryRemovesAllRowsForTheLoadedSimOnly() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
            try {
                val realRepository = KeepaliveRepository(db, db.simConfigDao(), db.simHistoryDao())
                realRepository.insertHistory(historyRow(11, 1_700_000_000_000L, SendOutcome.SENT))
                realRepository.insertHistory(historyRow(11, 1_700_000_001_000L, SendOutcome.FAILED))
                realRepository.insertHistory(historyRow(12, 1_700_000_000_000L, SendOutcome.SENT))
                val vm = HistoryViewModel(realRepository, prefs, context)
                vm.loadHistory(11)
                waitFor { vm.state.value.history.size == 2 }
                vm.clearHistory()
                // clearHistory is a viewModelScope launch and Room commits the delete on its
                // own executor: wait for the commit before asserting, so the test does not
                // race the write (it failed under full-suite load with the immediate read).
                waitFor {
                    withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(11, Int.MAX_VALUE).first() }.isEmpty()
                }
                val rows11 = withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(11, Int.MAX_VALUE).first() }
                val rows12 = withContext(Dispatchers.IO) { db.simHistoryDao().observeNewest(12, Int.MAX_VALUE).first() }
                assertEquals(0, rows11.size)
                assertEquals(1, rows12.size)
                waitFor {
                    vm.state.value.history
                        .isEmpty()
                }
                assertTrue(
                    vm.state.value.history
                        .isEmpty(),
                )
            } finally {
                // The in-memory DB is deliberately not closed: Room 2.6.1's close() can deadlock
                // against the ViewModel's never-cancelled flow on its first open (lock-order
                // inversion) — see DashboardModelTest.tearDown for the full explanation.
            }
        }

    private fun historyRow(
        simId: Int,
        scheduledForMillis: Long,
        outcome: SendOutcome,
    ): SendHistoryEntity =
        SendHistoryEntity(
            simId = simId,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
        )

    // The "load more" minimum spinner delay runs on the test clock (the Main dispatcher's
    // virtual time): without auto-advance it never fires while the real-time polling below
    // runs, so the wait advances the virtual clock in real-time steps (10 virtual ms per 10
    // real ms) to let the 400 ms hold elapse, like it does on a device.
    private suspend fun waitFor(
        timeoutMs: Long = 10_000,
        advanceVirtual: Boolean = false,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < deadline) {
            delay(10)
            if (advanceVirtual) {
                testDispatcher.scheduler.advanceTimeBy(10)
            }
        }
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
