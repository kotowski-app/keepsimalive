package app.kotowski.keepsimalive.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotowski.keepsimalive.BuildConfig
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.ui.NavigationRow
import app.kotowski.keepsimalive.ui.SettingsDropdownRow
import app.kotowski.keepsimalive.ui.SystemScrollStateScrollIndicator
import app.kotowski.keepsimalive.ui.TextConfirmDialog
import app.kotowski.keepsimalive.ui.icons.TranslateIcon
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.Links

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    var graceExpanded by remember { mutableStateOf(false) }
    // The period row's edit draft: while the row's Edit is active nothing is committed
    // until the row's Save. It serves two flows: the enable (toggle on with nothing
    // committed yet, the row opens in edit mode with the default proposal) and a period
    // change while the cleanup is already on.
    var retentionPeriodEditing by remember { mutableStateOf(false) }
    var retentionDraftDays by remember { mutableStateOf(DEFAULT_RETENTION_DAYS) }
    var retentionPeriodExpanded by remember { mutableStateOf(false) }
    var showEnableConfirm by remember { mutableStateOf(false) }
    var showShortenConfirm by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    // The enable-in-progress: the toggle is visually on but nothing is committed yet —
    // the period row is open in the edit mode, and the commit waits for the Save's
    // confirm dialog with the picked period.
    val enablePending = retentionPeriodEditing && !state.autoCleanupEnabled

    val alarmLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshExactAlarmPermission()
        }

    // The POST_NOTIFICATIONS request dialog (Android 13+): the result re-reads the live
    // permission, which turns the sticky row on once granted.
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshNotificationsPermission()
        }

    // The toggle shows the active state (intent AND permission): enabling it while the
    // "Alarms & reminders" permission is missing (Android 12+) opens the system page right
    // away; the refresh on the page's return (and on resume) turns the row on once granted
    // and arms the exact alarms with it.
    fun onExactWakeChanged(value: Boolean) {
        viewModel.setExactWakeEnabled(value)
        if (value && !state.exactAlarmGranted) {
            alarmLauncher.launch(viewModel.permissionManagerForUi.createRequestScheduleExactAlarmIntent())
        }
    }

    // The sticky notification row follows the exact wake-up pattern one row up: the stored
    // intent is committed right away, and enabling it while the notifications permission is
    // missing (Android 13+) requests it in the same gesture; the refresh on the dialog's
    // return (and on resume) turns the row on once granted. Below API 33 the request never
    // launches.
    fun onStickyNotificationChanged(value: Boolean) {
        viewModel.setShowStickyNotification(value)
        if (value && !state.notificationsGranted) {
            (context as? Activity)?.let { activity ->
                viewModel.permissionManagerForUi.request(
                    activity,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    notificationsLauncher,
                )
            }
        }
    }

    // The retention toggle: turning on commits nothing by itself — it opens the period
    // row in the edit mode with the default proposal, and the enable waits for the
    // Save's confirm dialog with the picked period; turning off deletes nothing, so it
    // stores 0 right away (a pending enable has nothing committed yet, so it is just
    // dropped).
    fun onRetentionToggleChanged(value: Boolean) {
        if (value) {
            retentionDraftDays = state.retentionDays
            retentionPeriodEditing = true
        } else {
            retentionPeriodExpanded = false
            retentionPeriodEditing = false
            if (state.autoCleanupEnabled) {
                viewModel.commitRetention(false, state.retentionDays)
            }
        }
    }

    // The period row's Save: while the enable is pending it always goes through the
    // confirm dialog (enabling starts the deletions) and commits with the picked period;
    // while the cleanup is already on, an unchanged draft just closes the edit, a longer
    // period deletes nothing and commits straight away, and a shorter one deletes the
    // sends it no longer covers, so it waits for the confirm dialog's OK.
    fun onRetentionSave() {
        retentionPeriodExpanded = false
        if (!state.autoCleanupEnabled) {
            showEnableConfirm = true
            return
        }
        if (retentionDraftDays == state.retentionDays) {
            retentionPeriodEditing = false
            return
        }
        if (retentionDraftDays < state.retentionDays) {
            showShortenConfirm = true
        } else {
            viewModel.commitRetention(true, retentionDraftDays)
            retentionPeriodEditing = false
        }
    }

    // The permission can change while the screen is away (the system page, the system
    // settings): on every resume the live state is re-read, so the toggle shows the active
    // state and the alarms follow (a grant arms them, a revoke cancels them).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshExactAlarmPermission()
        viewModel.refreshNotificationsPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title))
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
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .verticalScroll(scrollState),
            ) {
                SectionHeader(title = stringResource(R.string.settings_section_appearance))

                // The language picker: a pick recreates the activity with the new locale
                // right away (no app restart) and persists the choice. The translate icon
                // names the row (theme and language both only offer a pick, the icons keep
                // them apart).
                SettingsDropdownRow(
                    value = stringResource(languageLabelRes(state.appLanguage)),
                    label = stringResource(R.string.settings_language_label),
                    items =
                        listOf(
                            stringResource(R.string.settings_system_default) to { viewModel.setAppLanguage("") },
                            stringResource(R.string.settings_language_english) to { viewModel.setAppLanguage("en") },
                            stringResource(R.string.settings_language_russian) to { viewModel.setAppLanguage("ru") },
                        ),
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                    leadingIcon = {
                        Icon(
                            imageVector = TranslateIcon,
                            contentDescription = null,
                            modifier = Modifier.testTag("language_icon"),
                        )
                    },
                    testTag = "dropdown_language",
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                )

                // The theme picker: a pick recomposes the root with the new palette right
                // away (the root collects the holder's flow), no restart.
                SettingsDropdownRow(
                    value = stringResource(themeLabelRes(state.themeMode)),
                    label = stringResource(R.string.settings_theme_label),
                    items =
                        AppPrefs.THEME_MODES.map { mode ->
                            stringResource(themeLabelRes(mode)) to { viewModel.setThemeMode(mode) }
                        },
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it },
                    testTag = "dropdown_theme",
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                )

                SectionHeader(title = stringResource(R.string.settings_section_notifications))

                SettingsToggleRowWithSubtitle(
                    title = stringResource(R.string.settings_sticky_notification),
                    subtitle = stringResource(R.string.settings_sticky_notification_desc),
                    // The active state, like the exact wake-up row below: the stored intent
                    // AND the notifications permission (the toggle requests it while missing).
                    checked = state.showStickyNotification && state.notificationsGranted,
                    onCheckedChange = { onStickyNotificationChanged(it) },
                    testTag = "toggle_sticky_notification",
                )

                SectionHeader(title = stringResource(R.string.settings_section_scheduling))

                SettingsDropdownRow(
                    value = stringResource(graceLabelRes(state.lateSendGraceMinutes)),
                    label = stringResource(R.string.settings_grace_label),
                    items =
                        AppPrefs.LATE_SEND_GRACE_PRESETS.map { preset ->
                            stringResource(graceLabelRes(preset)) to { viewModel.setLateSendGrace(preset) }
                        },
                    expanded = graceExpanded,
                    onExpandedChange = { graceExpanded = it },
                    testTag = "dropdown_late_send_grace",
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                )

                Text(
                    text = stringResource(R.string.settings_grace_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SettingsToggleRowWithSubtitle(
                    title = stringResource(R.string.settings_exact_wake_title),
                    subtitle = stringResource(R.string.settings_exact_wake_desc),
                    checked = state.exactWakeEnabled && state.exactAlarmGranted,
                    onCheckedChange = { onExactWakeChanged(it) },
                    testTag = "toggle_exact_wake",
                )

                SectionHeader(title = stringResource(R.string.settings_section_data_retention))

                SettingsToggleRowWithSubtitle(
                    title = stringResource(R.string.settings_cleanup_title),
                    // The committed period is already named by the locked period row below,
                    // so the subtitle stays the plain description in both states.
                    subtitle = stringResource(R.string.settings_cleanup_desc),
                    // On while the enable is pending too: the toggle shows the intent, the
                    // commit waits for the period row's Save.
                    checked = state.autoCleanupEnabled || enablePending,
                    onCheckedChange = { onRetentionToggleChanged(it) },
                    testTag = "toggle_history_retention",
                )

                // The period row is visible with the cleanup on (locked until the row's
                // Edit — a one-tap dropdown pick must not silently re-decide a destructive
                // period) and with the enable pending (open in the edit mode from the
                // toggle).
                if (state.autoCleanupEnabled || enablePending) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (retentionPeriodEditing) {
                            SettingsDropdownRow(
                                value = stringResource(retentionPeriodLabelRes(retentionDraftDays)),
                                label = stringResource(R.string.settings_cleanup_period_label),
                                items =
                                    SettingsViewModel.RETENTION_PERIOD_PRESETS.map { preset ->
                                        stringResource(retentionPeriodLabelRes(preset)) to { retentionDraftDays = preset }
                                    },
                                expanded = retentionPeriodExpanded,
                                onExpandedChange = { retentionPeriodExpanded = it },
                                testTag = "dropdown_retention_period",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    retentionPeriodExpanded = false
                                    retentionPeriodEditing = false
                                },
                                modifier = Modifier.testTag("retention_cancel"),
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            // The filled tonal gives the row one clear primary action while
                            // the edit is pending: the change applies only with this tap.
                            FilledTonalButton(
                                onClick = { onRetentionSave() },
                                modifier = Modifier.testTag("retention_save"),
                            ) {
                                Text(stringResource(R.string.action_save))
                            }
                        } else {
                            // Locked: the period shows as plain text with its name — a
                            // grayed-out dropdown would suggest an interaction that does
                            // not exist until Edit, and a bare value is unclear.
                            Text(
                                text =
                                    stringResource(
                                        R.string.settings_cleanup_period_value,
                                        stringResource(retentionPeriodLabelRes(state.retentionDays)),
                                    ),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    retentionDraftDays = state.retentionDays
                                    retentionPeriodEditing = true
                                },
                                modifier = Modifier.testTag("retention_edit"),
                            ) {
                                Text(stringResource(R.string.action_edit))
                            }
                        }
                    }

                    // While the enable is pending nothing is committed yet: the hint below
                    // the row says so, in the same style as the other settings descriptions.
                    if (enablePending) {
                        Text(
                            text = stringResource(R.string.settings_cleanup_enable_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                SectionHeader(title = stringResource(R.string.log_viewer_title))

                // Navigation, not an action: the chevron row (NavigationRow) keeps a pure
                // "go somewhere" quiet — as a filled button it would be the loudest
                // element of the settings.
                NavigationRow(
                    label = stringResource(R.string.settings_logs),
                    onClick = onNavigateToLogViewer,
                    testTag = "open_logs",
                )

                SectionHeader(title = stringResource(R.string.settings_section_about))

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp)
                            .testTag("about_version"),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_version_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.settings_version_value,
                                    BuildConfig.VERSION_NAME.split("-")[0],
                                    stringResource(R.string.app_flavor_label),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AboutLinkRow(
                    title = stringResource(R.string.settings_about_source_code),
                    url = Links.REPO,
                    onOpenUrl = { uriHandler.openUri(it) },
                    testTag = "about_source_code",
                )

                AboutLinkRow(
                    title = stringResource(R.string.settings_about_report_bug),
                    url = Links.REPORT_BUG,
                    onOpenUrl = { uriHandler.openUri(it) },
                    testTag = "about_report_bug",
                )

                AboutLinkRow(
                    title = stringResource(R.string.settings_about_sponsor),
                    hint = stringResource(R.string.settings_about_sponsor_hint),
                    url = Links.DONATE,
                    onOpenUrl = { uriHandler.openUri(it) },
                    testTag = "about_sponsor",
                )
            }

            SystemScrollStateScrollIndicator(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // The enabling confirmation: shown from the period row's Save while the enable
            // is pending; enabling starts the deletions, so the commit with the picked
            // period waits for the explicit OK.
            if (showEnableConfirm) {
                RetentionConfirmDialog(
                    titleRes = R.string.settings_cleanup_enable_title,
                    confirmRes = R.string.settings_cleanup_enable_confirm,
                    days = retentionDraftDays,
                    onDismiss = { showEnableConfirm = false },
                    onConfirm = {
                        showEnableConfirm = false
                        retentionPeriodEditing = false
                        viewModel.commitRetention(true, retentionDraftDays)
                    },
                )
            }

            // The shortening confirmation: a shorter period deletes the sends it no longer
            // covers, so the commit waits for the explicit OK; dismissing keeps the edit
            // open with the draft.
            if (showShortenConfirm) {
                RetentionConfirmDialog(
                    titleRes = R.string.settings_cleanup_shorten_title,
                    confirmRes = R.string.settings_cleanup_shorten_confirm,
                    days = retentionDraftDays,
                    onDismiss = { showShortenConfirm = false },
                    onConfirm = {
                        showShortenConfirm = false
                        retentionPeriodEditing = false
                        viewModel.commitRetention(true, retentionDraftDays)
                    },
                )
            }
        }
    }
}

private fun graceLabelRes(minutes: Int): Int =
    when (minutes) {
        60 -> R.string.settings_grace_1h
        240 -> R.string.settings_grace_4h
        1440 -> R.string.settings_grace_1day
        10080 -> R.string.settings_grace_7day
        else -> R.string.settings_grace_15min
    }

// The dropdown label of a theme mode (a stored value outside the modes degrades to the
// default label, like the state does).
private fun themeLabelRes(mode: String): Int =
    when (mode) {
        AppPrefs.THEME_MODE_LIGHT -> R.string.settings_theme_light
        AppPrefs.THEME_MODE_DARK -> R.string.settings_theme_dark
        else -> R.string.settings_system_default
    }

private fun languageLabelRes(tag: String): Int =
    when (tag) {
        "en" -> R.string.settings_language_english
        "ru" -> R.string.settings_language_russian
        else -> R.string.settings_system_default
    }

// The dropdown label of a retention period preset (a stored value outside the presets
// degrades to the default label, like the state does). Shared with the SIM details'
// auto-clear history banner, which must name the same period the settings show.
internal fun retentionPeriodLabelRes(days: Int): Int =
    when (days) {
        30 -> R.string.settings_retention_1_month
        90 -> R.string.settings_retention_3_months
        180 -> R.string.settings_retention_6_months
        365 -> R.string.settings_retention_1_year
        730 -> R.string.settings_retention_2_years
        1825 -> R.string.settings_retention_5_years
        3650 -> R.string.settings_retention_10_years
        7300 -> R.string.settings_retention_20_years
        else -> R.string.settings_retention_10_years
    }

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
    HorizontalDivider(
        modifier = Modifier.padding(top = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsToggleRowWithSubtitle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun RetentionConfirmDialog(
    titleRes: Int,
    confirmRes: Int,
    days: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TextConfirmDialog(
        title = stringResource(titleRes),
        detail =
            stringResource(
                R.string.settings_cleanup_confirm_detail,
                stringResource(retentionPeriodLabelRes(days)),
            ),
        confirmLabel = stringResource(confirmRes),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun AboutLinkRow(
    title: String,
    url: String,
    onOpenUrl: (String) -> Unit,
    testTag: String,
    hint: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(url) }
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rows leave the app (a browser tab), so the column takes the row's width and
        // the open-in-new icon sits at the end — the "goes somewhere" cue the in-app
        // navigation rows carry as a chevron.
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${testTag}_icon"),
        )
    }
}
