package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.schedule.ScheduleEndState
import app.kotowski.keepsimalive.ui.RepeatIconButton
import app.kotowski.keepsimalive.ui.SettingsDropdownRow
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.TimeWindowSteps
import app.kotowski.keepsimalive.util.ToastUtil
import app.kotowski.keepsimalive.util.isValidE164
import app.kotowski.keepsimalive.util.validateConfigNumber
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

// The 12 month chips as an adaptive grid: the column count is always a divisor of 12
// (6, 4, 3, 2 or 1) so every row carries the same number of months, and each chip
// weight-fills its slot so all 12 are the same size.
@Composable
private fun MonthGrid(
    selectedMonths: Set<Int>,
    onMonthToggled: (Int) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = with(LocalDensity.current) { monthGridColumns(constraints.maxWidth.toDp()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 12 / columns) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0 until columns) {
                        val month = row * columns + column + 1
                        FilterChip(
                            selected = month in selectedMonths,
                            onClick = { onMonthToggled(month) },
                            // M3 1.3.1's FilterChip has no built-in selection icon, so the
                            // picked months get the same check the selected frequency
                            // segment shows.
                            leadingIcon =
                                if (month in selectedMonths) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            label = {
                                // The chip sizes to its content and left-aligns the label, so the
                                // label fills the weighted slot and centers inside it.
                                Text(
                                    text = DateUtil.monthName(context, month),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// The largest divisor of 12 (capped at 6 columns) that still leaves at least
// MIN_MONTH_CHIP_WIDTH per chip.
internal fun monthGridColumns(availableWidth: Dp): Int =
    intArrayOf(6, 4, 3, 2, 1).firstOrNull { availableWidth / it >= MIN_MONTH_CHIP_WIDTH } ?: 1

// The narrowest slot must fit a chip in its widest (selected) state: the 18dp check icon
// plus the longest month name ("September" at labelLarge ≈ 70dp, with headroom) plus the
// chip's 8dp and the label's 8dp horizontal padding on each side (16dp + 16dp).
// 18 + 74 + 16 + 16 = 124dp.
private val MIN_MONTH_CHIP_WIDTH = 124.dp

// M3 1.3.1's SingleChoiceSegmentedButtonRow does not receive synthetic clicks under
// Robolectric (verified: a plain weighted Row of clickables works, the M3 row does not),
// which makes the frequency switch untestable. This replicates the outlined segmented
// look with the same M3 tokens so the behavior is covered by tests; on-device it is the
// standard Surface(onClick) the M3 component is built from.
@Composable
private fun FrequencySegmentedRow(
    selectedIndex: Int,
    labels: List<String>,
    onSelected: (Int) -> Unit,
) {
    // The M3 outlined segmented tokens: the selected item is a secondary container, the
    // outline is 1dp (the row overlaps the items by it so the borders merge into one line).
    val colorScheme = MaterialTheme.colorScheme
    val border = BorderStroke(1.dp, colorScheme.outline)
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(-1.dp),
    ) {
        for (index in labels.indices) {
            val isSelected = index == selectedIndex
            Surface(
                onClick = { onSelected(index) },
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                color = if (isSelected) colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (isSelected) colorScheme.onSecondaryContainer else colorScheme.onSurface,
                border = border,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = labels[index], style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeepaliveConfigEditor(
    context: android.content.Context,
    simId: Int,
    initial: SimKeepaliveConfig?,
    lastSentAnchorMillis: Long? = null,
    // The user-configurable late-send grace (Settings): the pending end state below runs
    // the same catch-up the engine runs, with the same grace.
    lateSendGraceMinutes: Int = AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES,
    onConfigChanged: (SimKeepaliveConfig?) -> Unit,
    // The draft's frequency and picked months (reported with every publish): the pending
    // config is null for an invalid draft, so the screen needs them to explain a blocked
    // Save with the right message.
    onFrequencyChanged: (FrequencyType, Set<Int>) -> Unit = { _, _ -> },
    onValidationChanged: (Boolean, Boolean) -> Unit,
    onDisable: () -> Unit = {},
) {
    var enabled by remember { mutableStateOf(initial?.enabled ?: false) }

    var recipient by remember { mutableStateOf(initial?.recipientPhone.orEmpty()) }
    var message by remember { mutableStateOf(initial?.message.orEmpty()) }

    var hour by remember { mutableIntStateOf(initial?.hour ?: 12) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var showTimePicker by remember { mutableStateOf(false) }

    // A new schedule (no saved config) opens with the selected-months rhythm.
    var freqType by remember { mutableStateOf(initial?.freqType ?: FrequencyType.SELECTED_MONTHS) }

    // One numeric field of the draft (the typed text, the last valid value, the error):
    // the holder owns the stepper wrap and the text validation, and publishes
    // synchronously after every change through the hook the editor swaps in below.
    // Remembered per editor with no key: seeded once from the saved config, surviving
    // recomposition.
    val daysIntervalDraft =
        remember { NumberDraft(initial?.daysInterval ?: 30, AppConfig.DAYS_INTERVAL_MIN, AppConfig.DAYS_INTERVAL_MAX) }
    val dayOfMonthDraft =
        remember { NumberDraft(initial?.dayOfMonth ?: 1, AppConfig.DAY_OF_MONTH_MIN, AppConfig.DAY_OF_MONTH_MAX) }
    val monthsIntervalDraft =
        remember { NumberDraft(initial?.monthsInterval ?: 1, AppConfig.MONTHS_INTERVAL_MIN, AppConfig.MONTHS_INTERVAL_MAX) }

    // The picked months (1-12) of the SELECTED_MONTHS frequency, seeded from the saved
    // config; a new schedule starts with every month picked (it sends on all of them
    // until narrowed).
    var selectedMonths by remember { mutableStateOf(initial?.selectedMonths ?: (1..12).toSet()) }

    var endType by remember { mutableStateOf(initial?.endType ?: EndType.NEVER) }
    var endExpanded by remember { mutableStateOf(false) }
    val maxSendsDraft = remember { NumberDraft(initial?.maxSends ?: 1, AppConfig.MAX_SENDS_MIN, AppConfig.MAX_SENDS_MAX) }
    var endDate by remember { mutableStateOf(initial?.endDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    var timeWindow by remember { mutableIntStateOf(initial?.timeWindowMinutes ?: 0) }

    // The window ladder is cut at the cap for the rhythm being edited (a quarter of the
    // interval for N-day schedules, never crossing midnight, at most a week for monthly,
    // never leaving the month): the stepper and the slider can never select a window that
    // breaks that rhythm.
    val maxTimeWindow =
        SimKeepaliveConfig.maxTimeWindowMinutes(freqType, daysIntervalDraft.value, dayOfMonthDraft.value, hour, minute)

    // The end state of the schedule being edited, anchored like the engine (the newest
    // SENT row's occurrence): while it leaves no send, the toggle is disabled and the
    // Schedule Rules card explains why — fixing the end condition enables the toggle
    // again, so the impossible state is unreachable instead of rejected.
    // A selected-months draft with no picked month is unschedulable (Save stays dimmed
    // for the same reason): it has no next occurrence to preview, so the preview is
    // skipped, leaving the toggle enabled.
    val pendingEndState =
        if (freqType == FrequencyType.SELECTED_MONTHS && selectedMonths.isEmpty()) {
            null
        } else {
            ScheduleCalculator.endState(
                SimKeepaliveConfig(
                    simId = simId,
                    hour = hour,
                    minute = minute,
                    freqType = freqType,
                    daysInterval = daysIntervalDraft.value,
                    monthsInterval = monthsIntervalDraft.value,
                    dayOfMonth = dayOfMonthDraft.value,
                    selectedMonths = selectedMonths,
                    endType = endType,
                    maxSends = maxSendsDraft.value,
                    endDate = endDate,
                    timeWindowMinutes = timeWindow,
                    sendCount = initial?.sendCount ?: 0,
                ),
                lastSentAnchorMillis,
                ZonedDateTime.now(ZoneId.systemDefault()),
                lateSendGraceMinutes * AppConfig.MINUTE_MS,
            )
        }
    val pendingEndReason = pendingEndState?.let { endedReason(context, it) }

    val maxChars = AppConfig.SMS_MAX_CHARS

    LaunchedEffect(enabled) {
        if (!enabled) endExpanded = false
    }

    // Publishes the pending config synchronously at event time. Doing this in the
    // change handlers (instead of a post-composition SideEffect) keeps Save from
    // reading a frame-stale value when the user saves right after a change.
    fun publish() {
        val pending =
            buildPendingConfig(
                PendingConfigDraft(
                    simId = simId,
                    enabled = enabled,
                    recipientPhone = recipient,
                    message = message,
                    hour = hour,
                    minute = minute,
                    freqType = freqType,
                    daysInterval = daysIntervalDraft.value,
                    monthsInterval = monthsIntervalDraft.value,
                    dayOfMonth = dayOfMonthDraft.value,
                    selectedMonths = selectedMonths,
                    endType = endType,
                    maxSends = maxSendsDraft.value,
                    endDate = endDate,
                    timeWindowMinutes = timeWindow,
                    daysIntervalError = daysIntervalDraft.error,
                    monthsIntervalError = monthsIntervalDraft.error,
                    dayOfMonthError = dayOfMonthDraft.error,
                    maxSendsError = maxSendsDraft.error,
                ),
            )
        onConfigChanged(pending)
        onFrequencyChanged(freqType, selectedMonths)
    }

    // The drafts publish through the editor's publish(): the hook is re-swapped on every
    // composition, so the draft always runs the latest closure (the one that reads the
    // other draft fields at event time).
    daysIntervalDraft.onChanged = ::publish
    dayOfMonthDraft.onChanged = ::publish
    monthsIntervalDraft.onChanged = ::publish
    maxSendsDraft.onChanged = ::publish

    // Shrinking the rhythm (a smaller interval, a later day of month, a later time, a
    // frequency switch) can cut the window cap below the current selection: clamp it and
    // publish so the slider never sits past its range and a save never carries the
    // pre-cap window.
    LaunchedEffect(freqType, daysIntervalDraft.value, dayOfMonthDraft.value, hour, minute) {
        if (timeWindow > maxTimeWindow) {
            timeWindow = maxTimeWindow
            publish()
        }
    }

    LaunchedEffect(Unit) {
        onValidationChanged(!isValidE164(recipient), message.isBlank())
        publish()
    }

    // The engine may end the schedule while the editor is open (the last allowed send is
    // consumed, or the end condition is reached in the background): the DB row is the truth,
    // so the checked state follows it. The draft fields survive, the end reason below
    // explains what to change, and the commit backstop stays the last line of defense.
    LaunchedEffect(initial?.enabled) {
        enabled = initial?.enabled ?: false
        publish()
    }

    // Disabled while the edited schedule's end condition leaves no send; the reason
    // sits at the bottom of the Schedule Rules card below.
    KeepaliveToggleCard(
        checked = enabled,
        enabled = pendingEndReason == null,
        onClick = {
            val newState = !enabled
            enabled = newState
            if (newState) {
                ToastUtil.show(context, R.string.sim_config_will_be_enabled_on_save)
            } else {
                onDisable()
            }
            publish()
        },
    )

    // Always shown, regardless of the toggle.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sim_history_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = recipient.removePrefix("+"),
                onValueChange = { newText ->
                    recipient = "+${newText.removePrefix("+")}"
                    onValidationChanged(!isValidE164(recipient), message.isBlank())
                    publish()
                },
                label = { Text(stringResource(R.string.sim_config_recipient)) },
                prefix = { Text("+") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it.takeSmsSafe(maxChars)
                    onValidationChanged(!isValidE164(recipient), message.isBlank())
                    publish()
                },
                label = { Text(stringResource(R.string.sim_config_message)) },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Text(
                        text = "${message.length} / $maxChars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sim_config_card_schedule_rules),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = DateUtil.formatTime(context, hour, minute),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.sim_config_send_time)) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = stringResource(R.string.sim_config_send_time_select),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clickable { showTimePicker = true },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.sim_config_frequency),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Selected months leads (the default rhythm of a new schedule), then the two
            // legacy cadences in their old relative order.
            FrequencySegmentedRow(
                selectedIndex =
                    when (freqType) {
                        FrequencyType.SELECTED_MONTHS -> 0
                        FrequencyType.MONTHLY -> 1
                        FrequencyType.EVERY_N_DAYS -> 2
                    },
                labels =
                    listOf(
                        stringResource(R.string.sim_config_frequency_selected_months_label),
                        stringResource(R.string.sim_config_frequency_months_label),
                        stringResource(R.string.sim_config_frequency_days_label),
                    ),
                onSelected = { index ->
                    val newFreqType =
                        when (index) {
                            0 -> FrequencyType.SELECTED_MONTHS
                            1 -> FrequencyType.MONTHLY
                            else -> FrequencyType.EVERY_N_DAYS
                        }
                    if (newFreqType != freqType) {
                        freqType = newFreqType
                        publish()
                    }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (freqType == FrequencyType.EVERY_N_DAYS) {
                EditableNumberRow(
                    label = stringResource(R.string.sim_config_frequency_every),
                    draft = daysIntervalDraft,
                    suffix =
                        if (daysIntervalDraft.error) {
                            null
                        } else {
                            context.resources.getQuantityString(
                                R.plurals.sim_config_frequency_days,
                                daysIntervalDraft.value,
                            )
                        },
                )
            } else {
                // Both month cadences send on the configured day of month, so MONTHLY and
                // SELECTED_MONTHS share the "On day" row and its 29-31 hint; the interval
                // row is monthly-only and the month grid selected-months-only.
                if (freqType == FrequencyType.MONTHLY) {
                    EditableNumberRow(
                        label = stringResource(R.string.sim_config_frequency_every),
                        draft = monthsIntervalDraft,
                        suffix =
                            if (monthsIntervalDraft.error) {
                                null
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.sim_config_frequency_months,
                                    monthsIntervalDraft.value,
                                )
                            },
                    )
                } else {
                    MonthGrid(
                        selectedMonths = selectedMonths,
                        onMonthToggled = { month ->
                            selectedMonths =
                                if (month in selectedMonths) {
                                    selectedMonths - month
                                } else {
                                    selectedMonths + month
                                }
                            publish()
                        },
                    )
                    if (selectedMonths.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.sim_config_frequency_selected_months_pick),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                EditableNumberRow(
                    label = stringResource(R.string.sim_config_frequency_monthly_day),
                    draft = dayOfMonthDraft,
                    suffix = null,
                )
                if (dayOfMonthDraft.value in 29..31) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.sim_config_frequency_monthly_day_hint, dayOfMonthDraft.value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val endDisplayText = endConditionText(context, endType, maxSendsDraft.value, endDate)
            val endDateInvalid = endType == EndType.ON_DATE && endDate == null

            SettingsDropdownRow(
                value = endDisplayText,
                label = stringResource(R.string.sim_config_end_condition),
                items =
                    listOf(
                        stringResource(R.string.interval_never) to {
                            endType = EndType.NEVER
                            publish()
                        },
                        stringResource(R.string.sim_config_end_after_sends_option) to {
                            endType = EndType.AFTER_N_SENDS
                            publish()
                        },
                        stringResource(R.string.sim_config_end_on_date_option) to {
                            endType = EndType.ON_DATE
                            showDatePicker = true
                            publish()
                        },
                    ),
                expanded = endExpanded,
                onExpandedChange = { endExpanded = it },
                isError = endDateInvalid,
                supportingText =
                    if (endDateInvalid) {
                        stringResource(R.string.sim_config_end_date_required)
                    } else {
                        null
                    },
            )

            if (endType == EndType.AFTER_N_SENDS) {
                Spacer(modifier = Modifier.height(8.dp))
                EditableNumberRow(
                    label = stringResource(R.string.send_count),
                    draft = maxSendsDraft,
                    suffix = null,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.sim_config_time_window),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sim_config_time_window_value, TimeWindowSteps.formatValue(context, timeWindow)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RepeatIconButton(
                        onClick = {
                            timeWindow = TimeWindowSteps.previous(timeWindow)
                            publish()
                        },
                        contentDescription = stringResource(R.string.sim_config_time_window_decrease),
                        icon = Icons.Default.Remove,
                    )
                    RepeatIconButton(
                        onClick = {
                            // The cap is a ladder value, so a step past it lands exactly on it.
                            timeWindow = minOf(TimeWindowSteps.next(timeWindow), maxTimeWindow)
                            publish()
                        },
                        contentDescription = stringResource(R.string.sim_config_time_window_increase),
                        icon = Icons.Default.Add,
                    )
                }
            }
            if (maxTimeWindow > 0) {
                // Largest ladder step within the cap (the cap is ladder-snapped, so this is
                // its exact index): the slider can never select a step past the cap.
                val maxWindowIndex = TimeWindowSteps.indexAtMost(maxTimeWindow)
                Slider(
                    value = minOf(TimeWindowSteps.indexFor(timeWindow), maxWindowIndex).toFloat(),
                    onValueChange = {
                        timeWindow = TimeWindowSteps.valueFor(it.roundToInt())
                        publish()
                    },
                    valueRange = 0f..maxWindowIndex.toFloat(),
                    steps = maxWindowIndex - 1,
                )
            } else {
                // No window fits (the month has 28 days at its shortest, or the time is so
                // late that even the smallest step would cross midnight): explain the forced
                // zero instead of showing a dead "0-0 min" control.
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sim_config_time_window_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The same reason the detail screen's schedule-ended banner shows: while the
            // edited end condition leaves no send, the toggle above is disabled and this
            // explains what to change — fixing it here enables the toggle.
            pendingEndReason?.let { reason ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showTimePicker) {
        ShowTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                hour = h
                minute = m
                showTimePicker = false
                publish()
            },
            onDismiss = { showTimePicker = false },
        )
    }

    if (showDatePicker) {
        ShowDatePickerDialog(
            initialDateMillis =
                endDate?.let { DateUtil.utcMidnightMillis(it) } ?: DateUtil.utcMidnightMillis(LocalDate.now()),
            onConfirm = { millis ->
                endDate = DateUtil.localDateOfUtcMidnight(millis)
                showDatePicker = false
                publish()
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

// One numeric field of the editor draft: the typed text, the last valid value, and
// whether the typed text is out of range. The steppers wrap at the bounds (the value can
// never be invalid), the text can (the user is mid-edit); both paths end in the same
// publish, so the pending config never lags a change by a frame.
internal class NumberDraft(
    initialValue: Int,
    val min: Int,
    val max: Int,
) {
    var value by mutableIntStateOf(initialValue)
    var text by mutableStateOf(initialValue.toString())
    var error by mutableStateOf(false)

    // The editor's publish: swapped in on every composition so the draft always runs the
    // latest closure (the one that reads the other draft fields).
    var onChanged: () -> Unit = {}

    fun onTextChange(newText: String) {
        text = newText
        val result = validateConfigNumber(newText, value, min, max)
        error = !result.isValid
        value = result.value
        onChanged()
    }

    fun stepDown() {
        value = if (value > min) value - 1 else max
        text = value.toString()
        error = false
        onChanged()
    }

    fun stepUp() {
        value = if (value < max) value + 1 else min
        text = value.toString()
        error = false
        onChanged()
    }
}

@Composable
private fun EditableNumberRow(
    label: String,
    draft: NumberDraft,
    suffix: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        RepeatIconButton(
            onClick = { draft.stepDown() },
            contentDescription = stringResource(R.string.sim_config_step_decrease),
            icon = Icons.Default.Remove,
        )
        OutlinedTextField(
            value = draft.text,
            onValueChange = { draft.onTextChange(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(64.dp),
            isError = draft.error,
            textStyle =
                androidx.compose.ui.text
                    .TextStyle(textAlign = TextAlign.Center),
        )
        RepeatIconButton(
            onClick = { draft.stepUp() },
            contentDescription = stringResource(R.string.sim_config_step_increase),
            icon = Icons.Default.Add,
        )
        Text(
            text = if (suffix != null && draft.text.isNotEmpty()) suffix else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp).padding(start = 4.dp),
        )
    }
}

// Truncates to at most n UTF-16 code units; a cut between the halves of an astral
// character (emoji) drops the whole character instead of leaving an orphan surrogate
// that renders as a broken glyph and goes out with every SMS. Code-unit counting stays
// correct for the 70-per-segment limit.
internal fun String.takeSmsSafe(n: Int): String {
    if (length <= n) return this
    val cut =
        if (n > 0 && Character.isHighSurrogate(this[n - 1]) && Character.isLowSurrogate(this[n])) {
            n - 1
        } else {
            n
        }
    return substring(0, cut)
}

// The editor's draft as one value: a new draft field is a holder plus one named
// argument, never a positional slip.
internal data class PendingConfigDraft(
    val simId: Int,
    val enabled: Boolean,
    val recipientPhone: String,
    val message: String,
    val hour: Int,
    val minute: Int,
    val freqType: FrequencyType,
    val daysInterval: Int,
    val monthsInterval: Int,
    val dayOfMonth: Int,
    val selectedMonths: Set<Int>,
    val endType: EndType,
    val maxSends: Int,
    val endDate: LocalDate?,
    val timeWindowMinutes: Int,
    val daysIntervalError: Boolean,
    val monthsIntervalError: Boolean,
    val dayOfMonthError: Boolean,
    val maxSendsError: Boolean,
)

internal fun buildPendingConfig(draft: PendingConfigDraft): SimKeepaliveConfig? {
    // A number error blocks Save only while that field is part of the active schedule: a
    // hidden field (other frequency / end type) is not saved, so its stale error must not
    // trap the user behind a dimmed Save. The "On day" field belongs to both month
    // cadences (monthly and selected months share it).
    val numberFieldInvalid =
        (draft.freqType == FrequencyType.EVERY_N_DAYS && draft.daysIntervalError) ||
            (draft.freqType == FrequencyType.MONTHLY && (draft.monthsIntervalError || draft.dayOfMonthError)) ||
            (draft.freqType == FrequencyType.SELECTED_MONTHS && draft.dayOfMonthError) ||
            (draft.endType == EndType.AFTER_N_SENDS && draft.maxSendsError)
    // A selected-months draft without a picked month is unschedulable: nothing can be
    // armed for it, so it must not become a saved config (Save stays dimmed and the
    // editor's hint explains why).
    val valid =
        isValidE164(draft.recipientPhone) &&
            draft.message.isNotBlank() &&
            !numberFieldInvalid &&
            (draft.freqType != FrequencyType.SELECTED_MONTHS || draft.selectedMonths.isNotEmpty()) &&
            (draft.endType != EndType.ON_DATE || draft.endDate != null)
    if (!valid) return null
    return SimKeepaliveConfig(
        simId = draft.simId,
        enabled = draft.enabled,
        recipientPhone = draft.recipientPhone,
        message = draft.message.takeSmsSafe(AppConfig.SMS_MAX_CHARS),
        hour = draft.hour,
        minute = draft.minute,
        freqType = draft.freqType,
        daysInterval = draft.daysInterval,
        monthsInterval = draft.monthsInterval,
        dayOfMonth = draft.dayOfMonth,
        selectedMonths = draft.selectedMonths,
        endType = draft.endType,
        maxSends = draft.maxSends,
        endDate = draft.endDate,
        // Commit backstop for the same cap the editor's controls enforce: a stale draft
        // value must not be saved past the rhythm's window cap.
        timeWindowMinutes =
            minOf(
                draft.timeWindowMinutes,
                SimKeepaliveConfig.maxTimeWindowMinutes(
                    draft.freqType,
                    draft.daysInterval,
                    draft.dayOfMonth,
                    draft.hour,
                    draft.minute,
                ),
            ),
    )
}

internal fun endConditionText(
    context: android.content.Context,
    endType: EndType,
    maxSends: Int,
    endDate: LocalDate?,
): String =
    when (endType) {
        EndType.NEVER -> {
            context.getString(R.string.interval_never)
        }

        EndType.AFTER_N_SENDS -> {
            context.resources.getQuantityString(R.plurals.sim_config_end_after_sends, maxSends, maxSends)
        }

        EndType.ON_DATE -> {
            endDate?.let { DateUtil.formatDate(context, it) }
                ?: context.getString(R.string.sim_config_end_on_date_option)
        }
    }

// The user-facing reason for an ended schedule (the schedule-ended banner, the editor
// warning and the save backstop toast all show the same text); null while the schedule
// can still send.
internal fun endedReason(
    context: android.content.Context,
    end: ScheduleEndState,
): String? =
    when (end) {
        is ScheduleEndState.Running -> {
            null
        }

        is ScheduleEndState.EndedAfterNSends -> {
            context.getString(R.string.error_schedule_ended_sends, end.sent)
        }

        is ScheduleEndState.EndedPastEndDate -> {
            context.getString(
                R.string.error_schedule_ended_date,
                DateUtil.formatDate(context, end.nextSend.toLocalDate()),
                DateUtil.formatDate(context, end.endDate),
            )
        }
    }
