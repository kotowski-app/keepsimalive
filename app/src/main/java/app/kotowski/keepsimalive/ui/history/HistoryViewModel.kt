package app.kotowski.keepsimalive.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.isTerminal
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.SimNameResolver
import app.kotowski.keepsimalive.util.appLocaleContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HistoryUiState(
    val isLoading: Boolean = false,
    // The SIM's display name for the top bar title ("<name> — History (N)"): resolved
    // asynchronously on load (live telephony read, then the last known identity, then the
    // subscription-id fallback), so it can lag the list by a frame right after entry.
    val simDisplayName: String = "",
    // The loaded window: the SIM's newest `loadedLimit` rows, observed live (re-emitted
    // on any DB change), newest-first. It can contain the in-flight row — the screen's
    // terminal filter drops it.
    val history: List<SendHistoryEntity> = emptyList(),
    // Exact terminal-row count from the DB (the loaded list is window-sized, not the total).
    val historyCount: Int = 0,
    // True while the loaded window is growing (drives the list's spinner).
    val isLoadingMore: Boolean = false,
    // The loaded terminal rows are below the exact terminal count: the window can still
    // be grown. The count flow is live, so this stays correct after deletions.
    val hasMore: Boolean = false,
    // The Settings history retention (days, 0 = off): while on, the screen shows the
    // auto-clear warning banner (the periodic sweep deletes finished rows past the period).
    val historyRetentionDays: Int = 0,
    // Non-null right after a manual "Run cleanup now": how many rows the cleanup deleted
    // (across all SIMs, like the sweep); the result dialog shows it until cleared.
    val cleanupDeletedCount: Int? = null,
)

// The per-SIM history screen: the finished sends of one SIM (the list plus the exact
// terminal count for the header), the auto-clear retention banner with its manual
// cleanup, and the Clear-all action. The list is a single growing live window from the
// DB (the newest `loadedLimit` rows, observed live, the limit growing by a page per
// scroll — no cursor, no bulk pre-load).
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val keepaliveRepository: KeepaliveRepository,
        private val prefs: AppPrefs,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HistoryUiState())
        val state: StateFlow<HistoryUiState> = _state.asStateFlow()

        private var loadedSimId: Int? = null
        private var historyJob: Job? = null
        private var countJob: Job? = null
        private var loadMoreJob: Job? = null

        // The loaded window's size (rows): it only grows (a page per loadMore, reset on a
        // new load), so a row pushed out by a newer insert re-enters as the window grows —
        // no row can be stranded.
        private var loadedLimit: Int = AppConfig.HISTORY_PAGE_SIZE

        // Driven by the screen once per destination (LaunchedEffect): the route argument is
        // the SIM's subscription id, so the collectors observe exactly that SIM.
        fun loadHistory(simId: Int) {
            loadedSimId = simId
            // A new load starts from the base window: the old limit dies with it, and an
            // in-flight grow must not outlive the fresh state.
            resetLoadedPages()
            _state.value =
                HistoryUiState(
                    isLoading = true,
                    historyRetentionDays = prefs.historyRetentionDays,
                )
            loadSimDisplayName(simId)
            startHistoryCollection(simId)
            startCountCollection(simId)
        }

        // The top bar title names the SIM ("<name> — History (N)"): the live identity first,
        // then the last known one (the SIM is out of the tray), then the subscription-id
        // fallback. The read runs on IO and degrades to the fallback on any failure: the
        // history itself never depends on READ_PHONE_STATE.
        private fun loadSimDisplayName(simId: Int) {
            viewModelScope.launch {
                val name =
                    withContext(Dispatchers.IO) {
                        SimNameResolver.resolveDisplayName(appContext.appLocaleContext(), simId)
                    }
                _state.update { it.copy(simDisplayName = name) }
            }
        }

        // The live window: the newest `loadedLimit` rows, re-emitted on any change, so a
        // send that lands (or flips to its terminal outcome) while the screen is open
        // appears at the top without interaction. The query already returns the DB's order
        // (scheduledForMillis DESC, id DESC): a single query cannot duplicate or misorder,
        // so no re-sort or dedup here.
        private fun startHistoryCollection(simId: Int) {
            historyJob?.cancel()
            historyJob =
                viewModelScope.launch {
                    keepaliveRepository
                        .observeNewest(simId, loadedLimit)
                        .collect { rows ->
                            _state.update {
                                withDerivedFlags(
                                    it.copy(history = rows, isLoading = false),
                                )
                            }
                        }
                }
        }

        private fun startCountCollection(simId: Int) {
            countJob?.cancel()
            countJob =
                viewModelScope.launch {
                    keepaliveRepository.observeHistoryCount(simId).collect { count ->
                        _state.update { withDerivedFlags(it.copy(historyCount = count)) }
                    }
                }
        }

        // Grows the live window by one page and keeps the spinner up for
        // HISTORY_LOAD_MORE_MIN_DELAY_MS (pure UX: the grow is a local counter bump, and
        // the new window's re-emit lands immediately with the larger list — the hold only
        // keeps the growth visible to the user).
        fun loadMore() {
            val current = _state.value
            if (current.isLoadingMore) return
            if (!current.hasMore) return
            val simId = loadedSimId ?: return
            _state.update { it.copy(isLoadingMore = true) }
            loadMoreJob =
                viewModelScope.launch {
                    loadedLimit += AppConfig.HISTORY_PAGE_SIZE
                    startHistoryCollection(simId)
                    delay(AppConfig.HISTORY_LOAD_MORE_MIN_DELAY_MS)
                    _state.update { it.copy(isLoadingMore = false) }
                }
        }

        fun clearHistory() {
            // No immediate list reset: the Clear deletes exactly the terminal rows, and the
            // live window re-emits the survivors once the delete commits — the list empties
            // with that re-emit, at most the surviving in-flight row remaining.
            viewModelScope.launch {
                keepaliveRepository.clearHistory(loadedSimId ?: return@launch)
            }
        }

        // Runs the retention cleanup right away instead of waiting for the next safety
        // sweep: the same global delete the sweep runs (finished rows older than the
        // retention period, across all SIMs). Refused while the retention is off (0).
        fun runCleanupNow() {
            val days = prefs.historyRetentionDays
            if (days <= 0) return
            viewModelScope.launch {
                val cutoff = System.currentTimeMillis() - days.toLong() * AppConfig.DAY_MS
                val deleted = keepaliveRepository.deleteOldHistory(cutoff)
                // No list reset: the sweep deletes old terminal rows, and the live window
                // re-emits the survivors once the delete commits.
                _state.update { it.copy(cleanupDeletedCount = deleted) }
            }
        }

        fun clearCleanupResult() {
            _state.update { it.copy(cleanupDeletedCount = null) }
        }

        // A fresh load: cancel an in-flight grow and start the window from its base size.
        private fun resetLoadedPages() {
            loadMoreJob?.cancel()
            loadMoreJob = null
            loadedLimit = AppConfig.HISTORY_PAGE_SIZE
        }

        // hasMore: the loaded terminal rows are below the exact terminal count (live, so it
        // stays correct after deletions) — the window can still be grown.
        private fun withDerivedFlags(state: HistoryUiState): HistoryUiState =
            state.copy(hasMore = terminalRowCount(state.history) < state.historyCount)

        private fun terminalRowCount(rows: List<SendHistoryEntity>): Int = rows.count { it.isTerminal }
    }
