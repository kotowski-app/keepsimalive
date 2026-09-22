package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.Banner
import app.kotowski.keepsimalive.ui.DetailRow
import app.kotowski.keepsimalive.ui.NavigationRow
import app.kotowski.keepsimalive.ui.SaveCancelBar
import app.kotowski.keepsimalive.ui.SystemScrollStateScrollIndicator
import app.kotowski.keepsimalive.ui.TextConfirmDialog
import app.kotowski.keepsimalive.ui.icons.DeleteIcon
import app.kotowski.keepsimalive.ui.icons.EditSquareIcon
import app.kotowski.keepsimalive.ui.icons.HistoryIcon
import app.kotowski.keepsimalive.ui.rememberCurrentTime
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.SimPhoneStateData
import app.kotowski.keepsimalive.util.ToastUtil
import app.kotowski.keepsimalive.util.isValidE164
import app.kotowski.keepsimalive.util.scheduleSentence
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimDetailScreen(
    viewModel: SimDetailViewModel,
    simId: Int,
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = viewModel.state.collectAsStateWithLifecycle()
    val currentTime = rememberCurrentTime()
    var pendingConfig by remember { mutableStateOf<SimKeepaliveConfig?>(null) }
    // The frequency and picked months of the draft being edited: the pending config is
    // null for an invalid draft, so they must be reported separately to explain a blocked
    // Save with the right message.
    var draftFreqType by remember { mutableStateOf(FrequencyType.MONTHLY) }
    var draftSelectedMonths by remember { mutableStateOf(emptySet<Int>()) }
    var editing by remember { mutableStateOf(false) }
    var recipientInvalid by remember { mutableStateOf(false) }
    var messageEmpty by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showForgetConfirm by remember { mutableStateOf(false) }
    var showOffScheduleConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(simId) {
        viewModel.loadSimData(context, simId)
    }

    // The live-SIM re-refresh runs only while this screen is on screen (below STARTED it
    // cancels, so no telephony reads happen off-screen). Like the dashboard's poll, the
    // refresh runs its first pass immediately on every resume: a delay-first order would
    // leave a dead zone after returning, during which a re-granted permission (or a
    // re-inserted SIM) would not be noticed. The `editing` flag (read on every tick)
    // tells the VM a draft is open, so a permission-lost swap cannot destroy it.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshSimData(simId, editing)
                delay(AppConfig.SIM_STATE_REFRESH_MS)
            }
        }
    }

    LaunchedEffect(state.value.justSaved) {
        if (state.value.justSaved) {
            ToastUtil.show(context, R.string.sim_config_saved)
            editing = false
            viewModel.clearSavedFlag()
        }
    }

    // The forget commits asynchronously (delete + cleanup under the send lock): the
    // screen leaves only with this one-shot signal, so the ViewModel outlives its own
    // delete. A refused forget never sets the flag — the user stays on the screen with
    // the toast.
    LaunchedEffect(state.value.justForgot) {
        if (state.value.justForgot) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    state.value.data?.let { data ->
                        Text(text = simDetailHeader(context, data, state.value.multiSimDevice, state.value.simMissing))
                    } ?: Text(stringResource(R.string.sim_detail_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.value.data != null && editing) {
                SaveCancelBar(
                    onCancel = {
                        editing = false
                        viewModel.clearSavedFlag()
                    },
                    onSave = {
                        if (recipientInvalid) {
                            ToastUtil.show(context, R.string.sim_config_recipient_invalid)
                            return@SaveCancelBar
                        }
                        if (messageEmpty) {
                            ToastUtil.show(context, R.string.sim_config_message_required)
                            return@SaveCancelBar
                        }
                        val pending = pendingConfig
                        if (pending == null) {
                            // The remaining invalid states: a number field out of range or
                            // "On date" without a picked date (both highlighted red in the
                            // editor) — or a selected-months draft with no picked month,
                            // which the generic message would mislead on.
                            ToastUtil.show(
                                context,
                                if (draftFreqType == FrequencyType.SELECTED_MONTHS && draftSelectedMonths.isEmpty()) {
                                    R.string.sim_config_frequency_selected_months_pick
                                } else {
                                    R.string.sim_config_invalid
                                },
                            )
                            return@SaveCancelBar
                        }
                        viewModel.saveConfig(pending)
                    },
                    saveEnabled = pendingConfig != null,
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Error state before the missing-data spinner: every error state carries no
            // data, so a data==null-first order would spin forever instead of showing it.
            val showSpinner = state.value.isLoading || (state.value.data == null && state.value.error == null)
            when {
                showSpinner -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.value.error != null -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.SimCard,
                            contentDescription = stringResource(R.string.sim_detail_error_icon),
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.value.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val data = state.value.data!!
                    val scrollState = rememberScrollState()
                    val inFlight = inFlightRow(state.value.history)
                    // The off-schedule send is only offered while the keepalive is on and can
                    // still send: on an ended schedule the engine would consume the one-off
                    // as an explained skip (no SMS), and otherwise there is no configured
                    // message. Not offered while an attempt is in flight ("Trying now"):
                    // the send slot is engine-owned to the attempt's result write, so the
                    // button would be a dead-end tap. The ViewModel re-checks the same
                    // state (defensive).
                    val offScheduleAllowed =
                        inFlight == null &&
                            state.value.config?.let { config ->
                                config.enabled &&
                                    state.value.endedReason == null &&
                                    isValidE164(config.recipientPhone) &&
                                    config.message.isNotBlank()
                            } == true

                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Shown while the SIM is not in the device: the card below renders
                            // the last known identity, so the banner makes clear the data is
                            // not live and offers to forget the remembered SIM.
                            if (state.value.simMissing) {
                                Banner(
                                    icon = Icons.Default.Warning,
                                    title = stringResource(R.string.error_sim_not_present),
                                    description = stringResource(R.string.sim_missing_last_known_desc),
                                    buttonText = stringResource(R.string.sim_forget),
                                    onButtonClick = { showForgetConfirm = true },
                                    buttonTestTag = "forget_button",
                                )
                            }
                            SimInfoCard(
                                context = context,
                                data = data,
                                config = state.value.config,
                                currentTime = currentTime,
                                inFlight = inFlight,
                                onRetryClick = { viewModel.retryNow(data.simId) },
                                // The off-schedule send (and the Cancel for the confirmed
                                // one while it is pending) lives in this card, in the
                                // Retry's slot.
                                offSchedulePending = state.value.offScheduleSendPendingAtMillis != null,
                                onSendOffScheduleClick =
                                    if (offScheduleAllowed) {
                                        { showOffScheduleConfirm = true }
                                    } else {
                                        null
                                    },
                                onCancelOffScheduleClick = { viewModel.cancelOffSchedule(simId) },
                                simMissing = state.value.simMissing,
                                lastSeenAtMillis = state.value.lastSeenAtMillis,
                                lastSeenSlotIndex = state.value.lastSeenSlotIndex,
                                multiSimDevice = state.value.multiSimDevice,
                            )
                            if (editing) {
                                KeepaliveConfigEditor(
                                    context = context,
                                    simId = simId,
                                    // A never-configured SIM opens in the editor with the new-schedule
                                    // proposal: keepalive on, selected-months rhythm, every month
                                    // picked (the user narrows the months).
                                    initial =
                                        state.value.config
                                            ?: SimKeepaliveConfig(
                                                simId = simId,
                                                enabled = true,
                                                freqType = FrequencyType.SELECTED_MONTHS,
                                                selectedMonths = (1..12).toSet(),
                                            ),
                                    lastSentAnchorMillis = state.value.lastSentAnchorMillis,
                                    lateSendGraceMinutes = state.value.lateSendGraceMinutes,
                                    onConfigChanged = { pendingConfig = it },
                                    onFrequencyChanged = { freqType, months ->
                                        draftFreqType = freqType
                                        draftSelectedMonths = months
                                    },
                                    onValidationChanged = { invalid, empty ->
                                        recipientInvalid = invalid
                                        messageEmpty = empty
                                    },
                                    // Toggle-off in the editor commits the disable (like the summary
                                    // toggle) and only then closes the editor: closing alone would
                                    // silently drop the intent to stop sending (the pending config
                                    // is a draft read only by Save, and Save is gone with the bar).
                                    onDisable = {
                                        viewModel.setEnabled(false)
                                        editing = false
                                    },
                                )
                            } else {
                                val config = state.value.config
                                // A schedule whose end condition is already met can never send:
                                // while it is off the toggle is disabled and the schedule-ended
                                // banner explains why and what to change. A legacy row that is
                                // still on stays tappable so the user can turn it off.
                                val endedReason = state.value.endedReason
                                KeepaliveToggleCard(
                                    checked = config?.enabled ?: false,
                                    enabled = config == null || endedReason == null || config.enabled,
                                    onClick = {
                                        if (config == null) {
                                            editing = true
                                        } else {
                                            viewModel.setEnabled(!config.enabled)
                                        }
                                    },
                                )
                                endedReason?.let { reason ->
                                    Banner(
                                        icon = Icons.Default.Warning,
                                        title = stringResource(R.string.error_schedule_ended),
                                        description = reason,
                                    )
                                }
                                // The settings are always visible (not only while enabled), so a
                                // disabled SIM can be inspected and edited without toggling first.
                                config?.let {
                                    KeepaliveSummaryCard(
                                        context = context,
                                        config = it,
                                        onEdit = { editing = true },
                                        onDelete = { showDeleteConfirm = true },
                                    )
                                }
                            }
                            // This is a navigation, not an action: the chevron row (NavigationRow)
                            // keeps it secondary to the SIM info card's Send, the screen's one
                            // primary. The count is the exact terminal count from the DB, same
                            // as the history screen's top bar.
                            NavigationRow(
                                label = stringResource(R.string.sim_history_title_count, state.value.historyCount),
                                onClick = onNavigateToHistory,
                                leadingIcon = HistoryIcon,
                                testTag = "history_button",
                            )
                        }
                        SystemScrollStateScrollIndicator(
                            scrollState = scrollState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            TextConfirmDialog(
                title = stringResource(R.string.sim_config_delete_confirm),
                detail = stringResource(R.string.sim_config_delete_confirm_detail),
                confirmLabel = stringResource(R.string.sim_config_delete),
                onConfirm = {
                    showDeleteConfirm = false
                    viewModel.deleteSchedule(simId)
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }

        if (showForgetConfirm) {
            TextConfirmDialog(
                title = stringResource(R.string.sim_forget_confirm),
                detail = stringResource(R.string.sim_forget_confirm_detail),
                confirmLabel = stringResource(R.string.sim_forget),
                onConfirm = {
                    showForgetConfirm = false
                    viewModel.forgetSim(simId)
                    // No navigation here: a synchronous pop would clear the screen's
                    // ViewModel and cancel the forget before its delete runs. The screen
                    // leaves only once the forget has committed (the justForgot signal
                    // above); a refused forget keeps the user on the screen with the toast.
                },
                onDismiss = { showForgetConfirm = false },
            )
        }

        if (showOffScheduleConfirm) {
            // Confirmation only: the confirm tap arms the one-off and closes the dialog; the
            // SIM info card's bottom button becomes the Cancel while the send is pending (no
            // countdown — the card's "Next send" row and "Send in…" status show the armed
            // fire time).
            TextConfirmDialog(
                title = stringResource(R.string.sim_send_off_schedule_confirm),
                detail =
                    context.getString(
                        R.string.sim_send_off_schedule_confirm_detail,
                        DateUtil.formatDurationMillis(context, AppConfig.OFF_SCHEDULE_SEND_DELAY_MS),
                    ),
                confirmLabel = stringResource(R.string.sim_send_off_schedule),
                onConfirm = {
                    showOffScheduleConfirm = false
                    viewModel.sendOffSchedule(simId)
                },
                onDismiss = { showOffScheduleConfirm = false },
            )
        }
    }
}

// The row that owns the current occurrence: a live SENDING wins over a waiting retry row
// (PENDING, retryCount > 0); a plain PENDING owns nothing to display.
internal fun inFlightRow(history: List<SendHistoryEntity>): SendHistoryEntity? =
    history.firstOrNull { it.outcome == SendOutcome.SENDING.name }
        ?: history.firstOrNull { it.outcome == SendOutcome.PENDING.name && it.retryCount > 0 }

internal fun simDetailHeader(
    context: android.content.Context,
    data: SimPhoneStateData,
    multiSimDevice: Boolean,
    simMissing: Boolean = false,
): String {
    val base = data.displayName.ifEmpty { context.getString(R.string.sim_detail_title) }
    return when {
        // A removed SIM has no meaningful slot any more: the header says so instead.
        multiSimDevice && simMissing -> context.getString(R.string.sim_detail_title_missing, base)

        multiSimDevice -> context.getString(R.string.sim_detail_title_with_slot, base, data.slotIndex)

        else -> base
    }
}

// -- Toggle Card (shared by summary and editor modes) --

@Composable
internal fun KeepaliveToggleCard(
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (checked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("toggle_card")
                    .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sim_config_toggle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            // The switch is wired like the row: a tap on either toggles the keepalive, so
            // the switch is a real toggle for screen readers (a null handler would
            // advertise a dead control). The switch consumes its own tap, so the row's
            // click and this handler never double-fire.
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { onClick() },
                modifier = Modifier.testTag("toggle_switch"),
            )
        }
    }
}

// -- Human-Readable Summary --

@Composable
private fun KeepaliveSummaryCard(
    context: android.content.Context,
    config: SimKeepaliveConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // Icon-only (the app's edit_square/delete icons): both neutral — the
                // confirm dialog explains the irreversibility, the color does not.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = EditSquareIcon,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = DeleteIcon,
                            contentDescription = stringResource(R.string.sim_config_delete),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow(
                label = stringResource(R.string.sim_config_recipient),
                value = config.recipientPhone.ifEmpty { "\u2014" },
            )
            DetailRow(
                label = stringResource(R.string.sim_config_message),
                value = config.message.ifEmpty { context.resources.getString(R.string.sim_config_message_default) },
                maxLines = Int.MAX_VALUE,
            )
            DetailRow(
                label = stringResource(R.string.sim_config_policy),
                value = scheduleSentence(context, config),
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}
