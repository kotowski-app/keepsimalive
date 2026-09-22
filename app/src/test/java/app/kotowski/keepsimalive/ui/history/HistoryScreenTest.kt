package app.kotowski.keepsimalive.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.SimIdentityCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Renders the whole history screen with a real view model and a mocked data layer: the
// screen's jobs (top bar count, banner visibility, empty state, clear flow, cleanup
// result, row tap) and the "load more" journey (scroll to the end -> the spinner holds for
// the minimum delay around the window grow, pure UX) are covered here; the list's
// rendering and trigger live in HistoryListTest.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext = RuntimeEnvironment.getApplication()

    // Main is pinned to the test dispatcher (like DashboardScreenTest): the view model's
    // "load more" minimum delay then runs on the virtual test clock, which the spinner
    // journey below advances by hand instead of the real-time Robolectric looper.
    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository: KeepaliveRepository = mock()
    private val prefs: AppPrefs = mock()

    private val regionFlow = MutableStateFlow<List<SendHistoryEntity>>(emptyList())
    private val countFlow = MutableStateFlow(0)
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.observeNewest(anyInt(), anyInt())).thenReturn(regionFlow)
        whenever(repository.observeHistoryCount(anyInt())).thenReturn(countFlow)
        // No live subscription for 11 in the test device: the top bar title resolves from
        // the last known identity.
        SimIdentityCache.save(
            appContext,
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
        viewModel = HistoryViewModel(repository, prefs, appContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private var historyBackClicked = false

    private fun render(simId: Int = 11) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    simId = simId,
                    onNavigateBack = { historyBackClicked = true },
                )
            }
        }
    }

    // Distinct ids: the list keys its rows by id, so rows created outside Room (id = 0 by
    // default) would collapse into one.
    private fun row(
        id: Long,
        scheduledForMillis: Long,
        outcome: SendOutcome = SendOutcome.SENT,
    ) = SendHistoryEntity(
        id = id,
        simId = 11,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = scheduledForMillis,
        lastAttemptAtMillis = if (outcome == SendOutcome.SENT) scheduledForMillis + 1000 else null,
        outcome = outcome.name,
        recipient = "+15550100",
        message = "keep alive",
    )

    // The top bar title: the SIM name (from the cached identity saved in setup) plus the
    // exact terminal count from the DB.
    private fun title(count: Int) = "Vodafone — History ($count)"

    // The load settles on the first flow emissions (the mocked flows emit immediately):
    // the top bar title is up as soon as the screen is composed.
    private fun waitForTitle(count: Int) {
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(title(count), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun spinner() = composeTestRule.onNodeWithTag("history_loading_more")

    private fun scrollTo(index: Int) {
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(index)
        composeTestRule.waitForIdle()
    }

    @Test
    fun loadsAndShowsTheTerminalRows() {
        regionFlow.value = listOf(row(1, 3_600_000L), row(2, 7_200_000L, SendOutcome.FAILED))
        countFlow.value = 2
        render()
        // Wait for the exact titled top bar (the SIM name resolves on its own coroutine,
        // a frame or two after the first list emission).
        waitForTitle(2)
        composeTestRule.onNodeWithText(title(2), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(DateUtil.formatDateTime(appContext, 3_600_000L), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(DateUtil.formatDateTime(appContext, 7_200_000L), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun topBarShowsTheExactCountNotTheLoadedListSize() {
        // The loaded list is window-sized (the DB serves the growing window): the title
        // must show the exact terminal count from the DB, not the smaller in-memory size.
        regionFlow.value = (1..45).map { row(it.toLong(), 3_600_000L + it * 3_600_000L) }
        countFlow.value = 1000
        render()
        waitForTitle(1000)
    }

    @Test
    fun clearActionShownOnlyWhileThereAreRows() {
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        composeTestRule.onNodeWithContentDescription("Clear").assertIsDisplayed()
    }

    @Test
    fun clearConfirmsThenDeletesForTheLoadedSim() {
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Delete all history for this SIM?", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        verifyBlocking(repository) { clearHistory(11) }
    }

    @Test
    fun clearCancelDeletesNothing() {
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Delete all history for this SIM?", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Delete all history for this SIM?", useUnmergedTree = true).assertDoesNotExist()
        verifyBlocking(repository, times(0)) { clearHistory(any()) }
    }

    @Test
    fun emptyHistoryShowsTheEmptyText() {
        render()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("No history yet", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }

    @Test
    fun emptyHistoryWithTheRetentionOnShowsTheBanner() {
        // An (auto-cleared) empty list must not drop the warning with the list.
        whenever(prefs.historyRetentionDays).thenReturn(30)
        render()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("No history yet", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Auto-clear is on").assertIsDisplayed()
    }

    @Test
    fun retentionOffHidesTheBanner() {
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        composeTestRule.onNodeWithText("Auto-clear", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Run cleanup now").assertDoesNotExist()
    }

    // Journey: with retention on, "Run cleanup now" deletes a single old row: the result
    // dialog must read the singular "Deleted 1 record", never "Deleted 1 records".
    @Test
    fun cleanupResultDialogUsesTheSingularForOneDeletedRecord() {
        whenever(prefs.historyRetentionDays).thenReturn(365)
        wheneverBlocking { repository.deleteOldHistory(any()) }.thenReturn(1)
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        viewModel.runCleanupNow()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Deleted 1 record", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun rowTapOpensTheDetailDialog() {
        regionFlow.value = listOf(row(1, 3_600_000L))
        countFlow.value = 1
        render()
        waitForTitle(1)
        composeTestRule
            .onNodeWithText(DateUtil.formatDateTime(appContext, 3_600_000L), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Message details", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun backButtonInvokesTheCallback() {
        render()
        waitForTitle(0)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(historyBackClicked)
    }

    // The "load more" journey: the grow is a local limit bump and the new window's re-emit
    // lands immediately, so the rows are already in the list while the spinner is still up
    // — the hold (HISTORY_LOAD_MORE_MIN_DELAY_MS) is pure UX and only keeps the growth
    // visible. Two virtual clocks are advanced by hand: Compose's frame clock (the
    // trigger, the recomposition, the frames) and the test scheduler's clock (Main is
    // pinned to it, so the view model's minimum hold is a virtual-time delay that only
    // elapses when the scheduler is advanced). The window's re-emit comes from the mocked
    // flow: the test drives the grown rows through it, and the unconfined dispatcher
    // delivers them immediately, so no real-time polling is needed anywhere in the
    // journey.
    @Test
    fun spinnerHoldsForTheMinimumDelayWhileTheWindowGrows() {
        // The live window: the 20 newest rows, 40 terminal rows in total.
        regionFlow.value = (1..20).map { row(it.toLong(), BASE + (41 - it) * HOUR) }
        countFlow.value = 40
        render()
        waitForTitle(40)
        // The clock goes manual only once the entry has settled (the SIM name resolves on
        // its own IO coroutine): the minimum delay must not be auto-advanced by the idle
        // clock while the spinner is asserted.
        composeTestRule.mainClock.autoAdvance = false
        // The hold's anchor on the virtual clock: the grow (and its hold) is synchronous
        // with the trigger, so the hold is measured from here.
        val triggerAtScheduler = testDispatcher.scheduler.currentTime
        // Scroll to the bottom of the loaded list: the trigger fires the grow on the
        // test dispatcher.
        scrollTo(19)
        val triggerAt = composeTestRule.mainClock.currentTime
        // Advance until the load has started and the spinner is up (it comes up fast — the
        // load starts on the scroll, not after a delay).
        val spinnerUpDeadline = triggerAt + 1_000
        while (!spinnerIsUp() && composeTestRule.mainClock.currentTime < spinnerUpDeadline) {
            composeTestRule.mainClock.advanceTimeBy(50)
            composeTestRule.waitForIdle()
        }
        spinner().assertExists()
        val spinnerUpAt = composeTestRule.mainClock.currentTime
        assertTrue(
            "the spinner came up $spinnerUpAt - triggerAt ms after the trigger",
            spinnerUpAt - triggerAt < AppConfig.HISTORY_LOAD_MORE_MIN_DELAY_MS,
        )
        // The grow's re-emit is immediate: the 40 rows land in the list while the spinner
        // is still held (the hold is pure UX, the rows wait for nothing).
        regionFlow.value = (1..40).map { row(it.toLong(), BASE + (41 - it) * HOUR) }
        // Pump a frame so the grown list reaches the tree: with the manual frame clock a
        // bare waitForIdle does not always run the pending recomposition.
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.waitForIdle()
        // The spinner is the list's LAST item: with 40 rows it is off-screen at the
        // current scroll position (lazy items do not exist in the tree), so scroll to the
        // bottom to bring it (and the oldest row) into view. hasMore is now false, so
        // the bottom trigger cannot fire another grow.
        scrollTo(39)
        spinner().assertExists()
        // The list already holds all 40 rows under the held spinner: the oldest row shows
        // at the bottom.
        composeTestRule
            .onNodeWithText(DateUtil.formatDateTime(appContext, BASE + HOUR), useUnmergedTree = true)
            .assertIsDisplayed()
        // Keep elapsing the hold on the virtual scheduler until the spinner comes down.
        // Each step also takes a little real time (thread latency, not virtual time) and
        // pumps Compose frames so the state change reaches the tree.
        runBlocking {
            val doneDeadline = System.currentTimeMillis() + 5_000
            while (spinnerIsUp() && System.currentTimeMillis() < doneDeadline) {
                delay(10)
                composeTestRule.mainClock.advanceTimeBy(50)
                testDispatcher.scheduler.advanceTimeBy(50)
                composeTestRule.waitForIdle()
            }
        }
        spinner().assertDoesNotExist()
        // The hold was respected from the trigger: the grow's re-emit was instant, so the
        // spinner stayed up until the minimum delay had elapsed (the 300 margin absorbs
        // the 50 ms clock steps).
        assertTrue(
            "the spinner came down ${testDispatcher.scheduler.currentTime - triggerAtScheduler} virtual ms after the trigger",
            testDispatcher.scheduler.currentTime - triggerAtScheduler >= 300,
        )
        // The grown window is in the list: the oldest of the 40 rows shows at the bottom.
        composeTestRule
            .onNodeWithText(DateUtil.formatDateTime(appContext, BASE + HOUR), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun spinnerIsUp(): Boolean = runCatching { spinner().assertExists() }.isSuccess

    private companion object {
        const val BASE = 2L * 24 * 60 * 60 * 1000L

        const val HOUR = 60L * 60 * 1000
    }
}
