package app.kotowski.keepsimalive.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.isTerminal
import app.kotowski.keepsimalive.ui.Banner
import app.kotowski.keepsimalive.ui.DetailRow
import app.kotowski.keepsimalive.ui.SystemLazyColumnScrollIndicator
import app.kotowski.keepsimalive.ui.TextConfirmDialog
import app.kotowski.keepsimalive.ui.settings.retentionPeriodLabelRes
import app.kotowski.keepsimalive.util.DateUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    simId: Int,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.state.collectAsStateWithLifecycle()
    var detailItem by remember { mutableStateOf<SendHistoryEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(simId) {
        viewModel.loadHistory(simId)
    }

    // The finished sends of this SIM (terminal rows only; the live rows are surfaced by
    // the SIM details screen, not here).
    val rows = terminalRows(state.value.history)

    Scaffold(
        topBar = {
            HistoryTopBar(
                simDisplayName = state.value.simDisplayName,
                historyCount = state.value.historyCount,
                hasRows = rows.isNotEmpty(),
                onNavigateBack = onNavigateBack,
                onClearClick = { showClearConfirm = true },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // While the auto-clear retention is on the banner stays visible even with an
            // empty history: it is the explanation of an (auto-cleared) empty list.
            if (state.value.historyRetentionDays > 0) {
                Banner(
                    icon = Icons.Default.CleaningServices,
                    title = stringResource(R.string.sim_history_auto_clear_on),
                    description =
                        stringResource(
                            R.string.sim_history_auto_clear_desc,
                            stringResource(retentionPeriodLabelRes(state.value.historyRetentionDays)),
                        ),
                    buttonText = stringResource(R.string.sim_history_run_cleanup_now),
                    onButtonClick = { viewModel.runCleanupNow() },
                    buttonTestTag = "run_cleanup_now",
                )
            }
            when {
                state.value.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                rows.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.sim_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val listState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        HistoryList(
                            history = state.value.history,
                            onItemClicked = { detailItem = it },
                            onLoadMore = { viewModel.loadMore() },
                            isLoadingMore = state.value.isLoadingMore,
                            hasMore = state.value.hasMore,
                            state = listState,
                        )
                        SystemLazyColumnScrollIndicator(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }

    if (detailItem != null) {
        HistoryDetailDialog(item = detailItem!!, onDismiss = { detailItem = null })
    }

    if (showClearConfirm) {
        TextConfirmDialog(
            title = stringResource(R.string.sim_history_clear_confirm),
            detail = null,
            confirmLabel = stringResource(R.string.sim_config_delete),
            onConfirm = {
                showClearConfirm = false
                viewModel.clearHistory()
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (state.value.cleanupDeletedCount != null) {
        TextConfirmDialog(
            title = stringResource(R.string.sim_history_cleanup_complete),
            detail =
                context.resources.getQuantityString(
                    R.plurals.sim_history_cleanup_deleted,
                    state.value.cleanupDeletedCount!!,
                    state.value.cleanupDeletedCount!!,
                ),
            confirmLabel = stringResource(android.R.string.ok),
            onConfirm = { viewModel.clearCleanupResult() },
            onDismiss = { viewModel.clearCleanupResult() },
            showCancel = false,
        )
    }
}

// -- Top Bar --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryTopBar(
    simDisplayName: String = "",
    historyCount: Int,
    hasRows: Boolean,
    onNavigateBack: () -> Unit,
    onClearClick: () -> Unit,
) {
    val context = LocalContext.current
    TopAppBar(
        title = {
            // The exact terminal count from the DB (the loaded list is capped), next to the
            // SIM's name: the title doubles as the "which SIM's history is this" answer.
            Text(
                text =
                    if (simDisplayName.isEmpty()) {
                        context.getString(R.string.sim_history_title_count, historyCount)
                    } else {
                        context.getString(R.string.sim_history_screen_title, simDisplayName, historyCount)
                    },
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                )
            }
        },
        actions = {
            // Nothing to clear while the list has no terminal rows: the action disappears
            // with the last row.
            if (hasRows) {
                IconButton(
                    onClick = onClearClick,
                    modifier = Modifier.testTag("history_clear_button"),
                ) {
                    // The trash, not a "clear" glyph: this deletes rows.
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear),
                    )
                }
            }
        },
    )
}

// -- List --

// History = finished occurrences only (SENT/FAILED/SKIPPED): PENDING is the next (future)
// occurrence or a waiting retry (retryCount > 0), SENDING the in-flight attempt — live
// states the SIM details screen already surfaces
// ("Send in…"/"Trying now"/"Retry in…").
internal fun terminalRows(history: List<SendHistoryEntity>): List<SendHistoryEntity> = history.filter { it.isTerminal }

// Renders the terminal rows the ViewModel has loaded, newest first; a row that lands in
// the live window while the list is open appears at the top without interaction (the
// window re-emits on every DB change).
@Composable
internal fun HistoryList(
    history: List<SendHistoryEntity>,
    onItemClicked: (SendHistoryEntity) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    state: LazyListState = rememberLazyListState(),
) {
    val rows = terminalRows(history)
    // The last row on screen; derived so the trigger re-runs only when it actually changes.
    val lastVisibleIndex by remember {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?: 0
        }
    }
    // The load runs in a scope that outlives the trigger effect: the spinner item itself
    // changes lastVisibleIndex (it becomes the last visible row), which restarts the
    // effect; the in-flight guard (isLoadingMore, held in the ViewModel) keeps the
    // restart from re-triggering while the window is growing.
    val scope = rememberCoroutineScope()
    LaunchedEffect(lastVisibleIndex, hasMore, rows.size) {
        if (isLoadingMore || !hasMore) return@LaunchedEffect
        if (lastVisibleIndex < rows.size - 2) return@LaunchedEffect
        scope.launch { onLoadMore() }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Id keys: the Room auto-generated id is the row's identity, so a top insert (a
        // send finishing while the list is open) keeps every visible row anchored instead
        // of shifting the whole window under the finger. Rows built outside Room
        // (tests, previews) must set distinct ids — a duplicate fails loudly in the test,
        // never in production.
        itemsIndexed(rows, key = { _, row -> row.id }) { _, row ->
            HistoryRow(item = row, onClick = { onItemClicked(row) })
        }
        if (isLoadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("history_loading_more"))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: SendHistoryEntity,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val outcome = SendOutcome.values().firstOrNull { o -> o.name == item.outcome }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            // The scheduled time: the list is sorted by it, so the displayed time must match
            // the sort key (the (last) attempt time stays in the detail dialog).
            Text(
                text = DateUtil.formatDateTime(context, item.scheduledForMillis),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.failureReason != null) {
                Text(
                    text = item.failureReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.outcome == SendOutcome.SENT.name && item.lastAttemptAtMillis != null) {
                Text(
                    text = successReason(context, item.lastAttemptAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        outcome?.let { outcomeStringRes(it) }?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// The history list only ever holds terminal rows (PENDING/SENDING are filtered out before
// it is loaded), so only the terminal outcomes need a row label here.
private fun outcomeStringRes(outcome: SendOutcome): Int? =
    when (outcome) {
        SendOutcome.SENT -> R.string.sim_history_outcome_sent
        SendOutcome.FAILED -> R.string.sim_history_outcome_failed
        SendOutcome.SKIPPED -> R.string.sim_history_outcome_skipped
        else -> null
    }

internal fun successReason(
    context: android.content.Context,
    sentAtMillis: Long,
): String = context.getString(R.string.sim_history_sent_successfully_on, DateUtil.formatDate(context, sentAtMillis))

// -- Row Detail Dialog --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDetailDialog(
    item: SendHistoryEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val outcome = SendOutcome.values().firstOrNull { o -> o.name == item.outcome }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sim_history_detail_title)) },
        text = {
            Column {
                DetailRow(
                    label = stringResource(R.string.sim_history_detail_scheduled),
                    value = DateUtil.formatDateTime(context, item.scheduledForMillis),
                )
                if (item.lastAttemptAtMillis != null) {
                    DetailRow(
                        label =
                            stringResource(
                                if (item.outcome == SendOutcome.SENT.name) {
                                    R.string.sim_history_detail_sent_at
                                } else {
                                    R.string.sim_history_detail_tried_at
                                },
                            ),
                        value = DateUtil.formatDateTime(context, item.lastAttemptAtMillis),
                    )
                }
                DetailRow(
                    label = stringResource(R.string.sim_history_detail_outcome),
                    // Blank for an unknown outcome - that should never happen
                    value = outcome?.let { outcomeStringRes(it) }?.let { stringResource(it) } ?: "",
                )
                if (item.failureReason != null) {
                    DetailRow(
                        label = stringResource(R.string.sim_history_detail_failure),
                        value = item.failureReason,
                    )
                }
                if (item.retryCount > 0) {
                    DetailRow(
                        label = stringResource(R.string.sim_history_detail_retries),
                        value = "${item.retryCount}",
                    )
                }
                DetailRow(
                    label = stringResource(R.string.sim_config_recipient),
                    value = item.recipient,
                )
                DetailRow(
                    label = stringResource(R.string.sim_config_message),
                    value = item.message,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
