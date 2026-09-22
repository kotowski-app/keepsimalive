package app.kotowski.keepsimalive.ui.history

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The finished-sends list of the history screen: terminal rows only, newest first — every
// row the ViewModel has loaded (the DB serves the growing live window, the list renders
// what is loaded). HistoryList is a LazyColumn, so off-screen rows and the spinner do not exist in
// the tree until scrolled into view: the scroll container is addressed by action
// (performScrollToIndex) before asserting a row, and the item count (rows + optional
// spinner) is asserted on the list state.
@RunWith(RobolectricTestRunner::class)
class HistoryListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private var listState: LazyListState? = null

    private fun render(
        history: List<SendHistoryEntity>,
        onLoadMore: () -> Unit = {},
        isLoadingMore: Boolean = false,
        hasMore: Boolean = false,
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val state =
                    androidx.compose.foundation.lazy
                        .rememberLazyListState()
                listState = state
                HistoryList(
                    history = history,
                    onItemClicked = {},
                    onLoadMore = onLoadMore,
                    isLoadingMore = isLoadingMore,
                    hasMore = hasMore,
                    state = state,
                )
            }
        }
    }

    private fun node(text: String) = composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    // The LazyColumn's item count: the loaded rows plus the "loading" spinner while a page
    // is fetching (the spinner is the last item).
    private fun itemCount(): Int = requireNotNull(listState).layoutInfo.totalItemsCount

    private fun spinner() = composeTestRule.onNodeWithTag("history_loading_more")

    private fun scrollToItem(index: Int) {
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(index)
        composeTestRule.waitForIdle()
    }

    @Test
    fun terminalRowsAreShownWithoutExpansion() {
        val rows =
            listOf(
                SendHistoryEntity(
                    id = 1,
                    simId = 1,
                    scheduledForMillis = 3_600_000L,
                    occurrenceBaseMillis = 3_600_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "m",
                ),
                SendHistoryEntity(
                    id = 2,
                    simId = 1,
                    scheduledForMillis = 7_200_000L,
                    occurrenceBaseMillis = 7_200_000L,
                    outcome = SendOutcome.FAILED.name,
                    failureReason = "No service",
                    recipient = "+15550100",
                    message = "m",
                ),
            )
        render(rows)
        val firstRowDate = DateUtil.formatDateTime(context, 3_600_000L)
        node(firstRowDate).assertIsDisplayed()
        node("Failed").assertIsDisplayed()
        node("No service").assertIsDisplayed()
    }

    @Test
    fun nonTerminalRowsAreHiddenButTerminalRowsAreShown() {
        val rows =
            listOf(
                SendHistoryEntity(
                    id = 1,
                    simId = 1,
                    scheduledForMillis = 3_600_000L,
                    occurrenceBaseMillis = 3_600_000L,
                    outcome = SendOutcome.PENDING.name,
                    recipient = "+15550100",
                    message = "m",
                ),
                SendHistoryEntity(
                    id = 2,
                    simId = 1,
                    scheduledForMillis = 7_200_000L,
                    occurrenceBaseMillis = 7_200_000L,
                    lastAttemptAtMillis = 7_200_000L,
                    outcome = SendOutcome.SENDING.name,
                    recipient = "+15550100",
                    message = "m",
                ),
                // A retry row (PENDING, retryCount > 0) is non-terminal like the others.
                SendHistoryEntity(
                    id = 3,
                    simId = 1,
                    scheduledForMillis = 10_800_000L,
                    occurrenceBaseMillis = 10_800_000L,
                    lastAttemptAtMillis = 10_800_000L,
                    outcome = SendOutcome.PENDING.name,
                    failureReason = "No service",
                    retryCount = 1,
                    recipient = "+15550100",
                    message = "m",
                ),
                SendHistoryEntity(
                    id = 4,
                    simId = 1,
                    scheduledForMillis = 14_400_000L,
                    occurrenceBaseMillis = 14_400_000L,
                    lastAttemptAtMillis = 14_400_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "m",
                ),
            )
        render(rows)
        node(DateUtil.formatDateTime(context, 14_400_000L)).assertIsDisplayed()
        node("Sent").assertIsDisplayed()
        node(DateUtil.formatDateTime(context, 3_600_000L)).assertDoesNotExist()
        node(DateUtil.formatDateTime(context, 7_200_000L)).assertDoesNotExist()
        node(DateUtil.formatDateTime(context, 10_800_000L)).assertDoesNotExist()
        node("Pending").assertDoesNotExist()
        node("Trying now").assertDoesNotExist()
        node("Retrying").assertDoesNotExist()
        node("No service").assertDoesNotExist()
    }

    @Test
    fun rowShowsTheScheduledTimeEvenWhenAttempted() {
        val rows =
            listOf(
                SendHistoryEntity(
                    id = 1,
                    simId = 1,
                    scheduledForMillis = 3_600_000L,
                    occurrenceBaseMillis = 3_600_000L,
                    lastAttemptAtMillis = 4_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "m",
                ),
                SendHistoryEntity(
                    id = 2,
                    simId = 1,
                    scheduledForMillis = 7_200_000L,
                    occurrenceBaseMillis = 7_200_000L,
                    outcome = SendOutcome.SKIPPED.name,
                    recipient = "+15550100",
                    message = "m",
                ),
            )
        render(rows)
        // The list is sorted by scheduled time, so the row must show it (the attempt time
        // stays in the detail dialog only).
        node(DateUtil.formatDateTime(context, 3_600_000L)).assertIsDisplayed()
        node(DateUtil.formatDateTime(context, 4_000_000L)).assertDoesNotExist()
        node(DateUtil.formatDateTime(context, 7_200_000L)).assertIsDisplayed()
    }

    // n terminal rows, one hour apart, DESC (newest first): row #1 (1-based from the
    // newest) carries the highest timestamp, row #n the lowest. The id tracks the
    // timestamp (id k = BASE + k * HOUR), so a larger rows(n) keeps every existing row's
    // id — the keys stay stable across the top-insert recomposition.
    private fun rows(n: Int): List<SendHistoryEntity> =
        List(n) { i ->
            SendHistoryEntity(
                id = (n - i).toLong(),
                simId = 1,
                scheduledForMillis = BASE + (n - i) * HOUR,
                occurrenceBaseMillis = BASE + (n - i) * HOUR,
                outcome = SendOutcome.SENT.name,
                recipient = "+15550100",
                message = "m",
            )
        }

    // The displayed date of row #row (1-based from the newest) of a DESC list of n rows.
    private fun dateOf(
        n: Int,
        row: Int,
    ): String = DateUtil.formatDateTime(context, BASE + (n - row + 1) * HOUR)

    @Test
    fun allLoadedRowsAreRenderedWithoutAClientSideCap() {
        // The list renders everything the ViewModel loaded (here: all 45, with nothing more to fetch).
        render(rows(45))
        composeTestRule.waitForIdle()
        assertEquals(45, itemCount())
        spinner().assertDoesNotExist()
        scrollToItem(44)
        node(dateOf(45, 45)).assertIsDisplayed()
    }

    @Test
    fun fewRowsShowAllWithoutSpinner() {
        render(rows(3))
        for (row in 1..3) {
            node(dateOf(3, row)).assertIsDisplayed()
        }
        // Nothing more to fetch: exactly the 3 rows, no spinner item.
        assertEquals(3, itemCount())
        spinner().assertDoesNotExist()
    }

    @Test
    fun scrollingToWithinTwoRowsOfTheBottomInvokesOnLoadMore() {
        var invoked = 0
        render(rows(20), onLoadMore = { invoked++ }, hasMore = true)
        // Well above the trigger window (within two rows of the bottom): no trigger.
        scrollToItem(10)
        assertEquals(0, invoked)
        // At the bottom: one trigger (the in-flight guard stops repeats).
        scrollToItem(19)
        composeTestRule.waitUntil(10_000) { invoked == 1 }
        assertEquals(1, invoked)
    }

    @Test
    fun onLoadMoreIsNotInvokedWhenThereIsNothingMore() {
        var invoked = 0
        render(rows(20), onLoadMore = { invoked++ }, hasMore = false)
        scrollToItem(19)
        assertEquals(0, invoked)
        spinner().assertDoesNotExist()
    }

    @Test
    fun onLoadMoreIsNotInvokedAgainWhileALoadIsInFlight() {
        var invoked = 0
        render(rows(20), onLoadMore = { invoked++ }, isLoadingMore = true, hasMore = true)
        scrollToItem(19)
        assertEquals(0, invoked)
    }

    @Test
    fun spinnerIsShownOnlyWhileALoadIsInFlight() {
        val loading = mutableStateOf(false)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val state =
                    androidx.compose.foundation.lazy
                        .rememberLazyListState()
                listState = state
                HistoryList(
                    history = rows(3),
                    onItemClicked = {},
                    onLoadMore = {},
                    isLoadingMore = loading.value,
                    hasMore = true,
                    state = state,
                )
            }
        }
        assertEquals(3, itemCount())
        spinner().assertDoesNotExist()
        loading.value = true
        composeTestRule.waitForIdle()
        spinner().assertExists()
        assertEquals(4, itemCount())
        loading.value = false
        composeTestRule.waitForIdle()
        spinner().assertDoesNotExist()
        assertEquals(3, itemCount())
    }

    @Test
    fun nonTerminalRowsDoNotCountTowardsTheTrigger() {
        var invoked = 0
        val history =
            rows(20) +
                listOf(
                    SendHistoryEntity(
                        id = 21,
                        simId = 1,
                        scheduledForMillis = BASE + 21 * HOUR,
                        occurrenceBaseMillis = BASE + 21 * HOUR,
                        outcome = SendOutcome.PENDING.name,
                        recipient = "+15550100",
                        message = "m",
                    ),
                )
        render(history, onLoadMore = { invoked++ }, hasMore = true)
        // The PENDING row is a live occurrence: it is never rendered.
        node(DateUtil.formatDateTime(context, BASE + 21 * HOUR)).assertDoesNotExist()
        node("Pending").assertDoesNotExist()
        assertEquals(20, itemCount())
        // The trigger window counts the rendered (terminal) rows only: the last terminal
        // row (index 19) is within two of the bottom.
        scrollToItem(19)
        composeTestRule.waitUntil(10_000) { invoked == 1 }
        assertEquals(1, invoked)
    }

    @Test
    fun aNewerLoadedRowAppearsAtTheTopWithoutInteraction() {
        // The list is state-driven (a second setContent per test is not allowed by the
        // compose rule), so the new row arrives as a recomposition: the live window
        // re-emitted with a newer row at the top of the loaded list, exactly like the live
        // screen (the user reads a newest-first list at the top).
        val history = mutableStateOf(rows(20))
        composeTestRule.setContent {
            KeepSimAliveTheme {
                val state =
                    androidx.compose.foundation.lazy
                        .rememberLazyListState()
                listState = state
                HistoryList(
                    history = history.value,
                    onItemClicked = {},
                    onLoadMore = {},
                    isLoadingMore = false,
                    hasMore = true,
                    state = state,
                )
            }
        }
        node(dateOf(20, 1)).assertIsDisplayed()
        // A newer row lands at the top of the DESC list: rows(21) adds exactly one newer
        // timestamp to the same 20 rows.
        history.value = rows(21)
        composeTestRule.waitForIdle()
        scrollToItem(0)
        node(dateOf(21, 1)).assertIsDisplayed()
        // All 21 loaded rows render (no client-side page cap).
        assertEquals(21, itemCount())
    }

    private companion object {
        const val BASE = 2L * 24 * 60 * 60 * 1000L

        const val HOUR = 60L * 60 * 1000
    }
}
