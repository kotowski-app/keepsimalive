package app.kotowski.keepsimalive.ui.settings

import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.BuildConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.ui.theme.ThemePreference
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.Links
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

// Renders the whole screen with a real view model and real prefs (the toggle effects are
// asserted on the persisted values); only the reconciler is mocked.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application = RuntimeEnvironment.getApplication()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val prefs = AppPrefs(appContext)
    private lateinit var viewModel: SettingsViewModel

    private var backClicked = false
    private var logsClicked = false

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
        prefs.showStickyNotification = true
        prefs.historyRetentionDays = 0
        prefs.themeMode = AppPrefs.THEME_MODE_SYSTEM
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        backClicked = false
        logsClicked = false
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // The delegate stores the app language in static state that leaks across test
        // classes, so reset it to "follow the system" after every test too.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    private fun render() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SettingsScreen(
                    onNavigateBack = { backClicked = true },
                    onNavigateToLogViewer = { logsClicked = true },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun stickyToggle() = composeTestRule.onNodeWithTag("toggle_sticky_notification")

    private fun retentionToggle() = composeTestRule.onNodeWithTag("toggle_history_retention")

    private fun periodEditButton() = composeTestRule.onNodeWithTag("retention_edit")

    private fun periodSaveButton() = composeTestRule.onNodeWithTag("retention_save")

    private fun periodCancelButton() = composeTestRule.onNodeWithTag("retention_cancel")

    private fun exactWakeToggle() = composeTestRule.onNodeWithTag("toggle_exact_wake")

    private fun graceDropdown() = composeTestRule.onNodeWithTag("dropdown_late_send_grace")

    private fun themeDropdown() = composeTestRule.onNodeWithTag("dropdown_theme")

    private fun languageDropdown() = composeTestRule.onNodeWithTag("dropdown_language")

    @Test
    fun `screen shows both sections with the sticky toggle on and the retention row while off`() {
        render()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sticky notification").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data retention").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-clear history").assertIsDisplayed()
        // The App logs section: the header plus the "Open logs" navigation row (label +
        // trailing chevron — a row, not a filled button: it goes somewhere, it does
        // nothing).
        composeTestRule.onNodeWithText("App logs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open logs", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_logs").assertIsDisplayed()
        // While off the period row is hidden: there is nothing to edit yet.
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithTag("dropdown_retention_period").assertDoesNotExist()
    }

    // Appearance leads the settings, and within the section the language row precedes the
    // theme row. The section is at the top, so the small default viewport already covers it.
    @Test
    fun `the appearance section leads the settings and the language row carries the translate icon`() {
        render()
        val topOf: (String) -> Float =
            { text ->
                composeTestRule
                    .onNodeWithText(text)
                    .fetchSemanticsNode()
                    .positionInRoot
                    .y
            }
        assertTrue(topOf("Appearance") < topOf("Notifications"))
        assertTrue(topOf("Language") < topOf("Theme"))
        // The translate icon names the language row (the theme row has no leading icon). Its
        // tag fuses with the field's own tag in the merged tree, so address it in the
        // unmerged tree and check it occupies space.
        val iconNodes =
            composeTestRule.onAllNodes(hasTestTag("language_icon"), useUnmergedTree = true)
        assertEquals(1, iconNodes.fetchSemanticsNodes().size)
        val iconBounds = iconNodes.onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue(iconBounds.width > 0)
        assertTrue(iconBounds.height > 0)
    }

    @Test
    fun `toggling the sticky notification row persists the pref`() {
        render()
        stickyToggle().performClick()
        assertFalse(prefs.showStickyNotification)
    }

    @Test
    fun `the retention row while off shows the description and no period controls`() {
        render()
        composeTestRule.onNodeWithText("Auto-clear history").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Automatically delete finished sends from the SIM history after a certain period of time.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retention period", substring = true).assertDoesNotExist()
    }

    @Test
    fun `toggling the auto-clear on opens the period edit, and saving confirms the enable`() {
        render()
        retentionToggle().performClick()
        // No dialog on the toggle itself: the enable waits for the Save with the picked
        // period, and nothing is committed in between.
        composeTestRule.onNodeWithText("Enable auto-clear?").assertDoesNotExist()
        retentionToggle().assertIsOn()
        periodSaveButton().assertIsDisplayed()
        periodCancelButton().assertIsDisplayed()
        composeTestRule.onNodeWithText("10 years", substring = false).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Pick a period and tap Save to enable the auto-clear.")
            .assertIsDisplayed()
        assertEquals(0, prefs.historyRetentionDays)
        composeTestRule.onNodeWithTag("dropdown_retention_period").performClick()
        composeTestRule.onNodeWithText("2 years", substring = false).performClick()
        periodSaveButton().performClick()
        composeTestRule.onNodeWithText("Enable auto-clear?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Sends older than the selected retention period (2 years) will be permanently deleted from the SIM history.")
            .assertIsDisplayed()
        assertEquals(0, prefs.historyRetentionDays)
        // Dismissing keeps the edit open with the draft: the row's Cancel is behind the
        // dialog, the dialog's button is composed last.
        composeTestRule
            .onAllNodesWithText("Cancel", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.onNodeWithText("Enable auto-clear?").assertDoesNotExist()
        assertEquals(0, prefs.historyRetentionDays)
        periodSaveButton().assertIsDisplayed()
        periodSaveButton().performClick()
        composeTestRule.onNodeWithText("Enable").performClick()
        assertTrue(viewModel.state.value.autoCleanupEnabled)
        assertEquals(730, prefs.historyRetentionDays)
        retentionToggle().assertIsOn()
        composeTestRule
            .onNodeWithText("Automatically delete finished sends from the SIM history after a certain period of time.")
            .assertIsDisplayed()
        periodEditButton().assertIsDisplayed()
        composeTestRule.onNodeWithText("Retention period: 2 years", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithTag("dropdown_retention_period").assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Pick a period and tap Save to enable the auto-clear.")
            .assertDoesNotExist()
    }

    @Test
    fun `cancelling the enable edit turns the toggle off without committing`() {
        render()
        retentionToggle().performClick()
        retentionToggle().assertIsOn()
        periodSaveButton().assertIsDisplayed()
        periodCancelButton().performClick()
        assertEquals(0, prefs.historyRetentionDays)
        retentionToggle().assertIsOff()
        periodEditButton().assertDoesNotExist()
        composeTestRule.onNodeWithTag("dropdown_retention_period").assertDoesNotExist()
    }

    @Test
    fun `toggling off again during the enable edit discards it without committing`() {
        render()
        retentionToggle().performClick()
        periodSaveButton().assertIsDisplayed()
        retentionToggle().performClick()
        assertEquals(0, prefs.historyRetentionDays)
        retentionToggle().assertIsOff()
        periodEditButton().assertDoesNotExist()
    }

    @Test
    fun `toggling the auto-clear off commits directly without the dialog`() {
        prefs.historyRetentionDays = 730
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        composeTestRule
            .onNodeWithText("Automatically delete finished sends from the SIM history after a certain period of time.")
            .assertIsDisplayed()
        periodEditButton().assertIsDisplayed()
        composeTestRule.onNodeWithText("Retention period: 2 years", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithTag("dropdown_retention_period").assertDoesNotExist()
        retentionToggle().performClick()
        assertEquals(0, prefs.historyRetentionDays)
        retentionToggle().assertIsOff()
        composeTestRule.onNodeWithText("Enable auto-clear?").assertDoesNotExist()
        periodEditButton().assertDoesNotExist()
    }

    @Test
    fun `shortening the period shows the confirm dialog, and confirming commits it`() {
        prefs.historyRetentionDays = 3650
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        periodEditButton().performClick()
        // A period change (already enabled) shows no pending hint.
        composeTestRule
            .onNodeWithText("Pick a period and tap Save to enable the auto-clear.")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("dropdown_retention_period").performClick()
        composeTestRule.onNodeWithText("2 years", substring = false).performClick()
        periodSaveButton().performClick()
        composeTestRule.onNodeWithText("Shorten retention period?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Sends older than the selected retention period (2 years) will be permanently deleted from the SIM history.")
            .assertIsDisplayed()
        // The commit waits for the OK: dismissing keeps the edit open with the draft.
        assertEquals(3650, prefs.historyRetentionDays)
        // The row's Cancel is behind the dialog: the dialog's button is composed last.
        composeTestRule
            .onAllNodesWithText("Cancel", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.onNodeWithText("Shorten retention period?").assertDoesNotExist()
        assertEquals(3650, prefs.historyRetentionDays)
        periodSaveButton().assertIsDisplayed()
        periodSaveButton().performClick()
        composeTestRule.onNodeWithText("Shorten").performClick()
        assertEquals(730, prefs.historyRetentionDays)
        composeTestRule
            .onNodeWithText("Automatically delete finished sends from the SIM history after a certain period of time.")
            .assertIsDisplayed()
        periodEditButton().assertIsDisplayed()
    }

    @Test
    fun `lengthening the period commits directly without the confirm dialog`() {
        prefs.historyRetentionDays = 180
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        periodEditButton().performClick()
        composeTestRule.onNodeWithTag("dropdown_retention_period").performClick()
        composeTestRule.onNodeWithText("1 year", substring = false).performClick()
        periodSaveButton().performClick()
        assertEquals(365, prefs.historyRetentionDays)
        composeTestRule.onNodeWithText("Shorten retention period?").assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Automatically delete finished sends from the SIM history after a certain period of time.")
            .assertIsDisplayed()
        periodEditButton().assertIsDisplayed()
    }

    @Test
    fun `saving an unchanged period closes the edit without the confirm dialog`() {
        prefs.historyRetentionDays = 730
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        periodEditButton().performClick()
        periodSaveButton().performClick()
        assertEquals(730, prefs.historyRetentionDays)
        composeTestRule.onNodeWithText("Shorten retention period?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Enable auto-clear?").assertDoesNotExist()
        periodEditButton().assertIsDisplayed()
    }

    @Test
    fun `cancelling the period edit discards the draft`() {
        prefs.historyRetentionDays = 3650
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        periodEditButton().performClick()
        composeTestRule.onNodeWithTag("dropdown_retention_period").performClick()
        composeTestRule.onNodeWithText("2 years", substring = false).performClick()
        periodCancelButton().performClick()
        assertEquals(3650, prefs.historyRetentionDays)
        periodEditButton().assertIsDisplayed()
        // The locked row shows the stored period as labeled plain text again, no dropdown.
        composeTestRule.onNodeWithText("Retention period: 10 years", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithTag("dropdown_retention_period").assertDoesNotExist()
    }

    @Test
    fun `the scheduling section shows the exact wake off and the default grace`() {
        render()
        composeTestRule.onNodeWithText("Scheduling").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exact wake-up (experimental)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Late send grace", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("15 minutes").assertIsDisplayed()
        composeTestRule.onNodeWithText("If the system wakes the app late", substring = true).assertIsDisplayed()
        exactWakeToggle().assertIsOff()
        // The grace row comes first: the exact-wake toggle sits below it.
        val graceBounds = graceDropdown().getUnclippedBoundsInRoot()
        val wakeBounds = exactWakeToggle().getUnclippedBoundsInRoot()
        assertTrue(graceBounds.top < wakeBounds.top)
        // The dropdown gets the same top gap as the other settings rows.
        val notificationsBottom = composeTestRule.onNodeWithText("Notifications").getUnclippedBoundsInRoot().bottom
        val stickyTop = composeTestRule.onNodeWithText("Sticky notification").getUnclippedBoundsInRoot().top
        val schedulingBottom = composeTestRule.onNodeWithText("Scheduling").getUnclippedBoundsInRoot().bottom
        assertEquals(stickyTop - notificationsBottom, graceBounds.top - schedulingBottom)
    }

    @Test
    fun `toggling the exact wake row persists the intent`() {
        // The shadow grants the "Alarms & reminders" permission so the toggle does not open
        // the system page (the permission flow is the Dashboard row's domain).
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        render()
        exactWakeToggle().performClick()
        assertTrue(prefs.exactWakeEnabled)
        exactWakeToggle().performClick()
        assertFalse(prefs.exactWakeEnabled)
    }

    // The SCHEDULE_EXACT_ALARM gate only exists from API 31 (the suite default is 29, where
    // exact alarms need no permission), so the permission-gated paths run on 33; the view
    // model is rebuilt after the shadow state is set so its init read sees it.
    @Config(sdk = [33])
    @Test
    fun `enabling the exact wake without the permission keeps the row off and opens the system page`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        exactWakeToggle().performClick()
        exactWakeToggle().assertIsOff()
        assertTrue(prefs.exactWakeEnabled)
        val activity = (composeTestRule as AndroidComposeTestRule<*, *>).activity
        // Robolectric's framework ships no activity for ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        // so the resolveActivity guard in createRequestScheduleExactAlarmIntent falls back to
        // the app's details page — the same path a ROM without the dedicated settings screen
        // takes (before the guard, the unresolvable dedicated intent would have crashed the tap).
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            started?.action,
        )
        assertEquals("package:${activity.packageName}", started?.data?.toString())
    }

    @Config(sdk = [33])
    @Test
    fun `enabling the exact wake with the permission granted turns the row on`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        exactWakeToggle().performClick()
        exactWakeToggle().assertIsOn()
        assertTrue(prefs.exactWakeEnabled)
    }

    // The POST_NOTIFICATIONS gate only exists from API 33 (the suite default is 29, where
    // the permission is granted with the manifest and the row behaves like before), so the
    // permission-gated paths run on 34; the view model is rebuilt after the shadow state
    // is set so its init read sees it.
    @Config(sdk = [34])
    @Test
    fun `the sticky row stays off while the notifications permission is missing`() {
        shadowOf(appContext).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        stickyToggle().assertIsOff()
        assertTrue(prefs.showStickyNotification)
    }

    @Config(sdk = [34])
    @Test
    fun `enabling the sticky notification without the permission keeps the row off and requests it`() {
        shadowOf(appContext).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        stickyToggle().performClick()
        // The intent is persisted, but the row follows the active state (intent AND
        // granted), so it stays off while the permission is missing...
        stickyToggle().assertIsOff()
        assertTrue(prefs.showStickyNotification)
        // ...and the same gesture requests the permission (the runtime dialog, not a
        // system page).
        val activity = (composeTestRule as AndroidComposeTestRule<*, *>).activity
        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull(request)
        assertArrayEquals(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            request?.requestedPermissions,
        )
    }

    @Config(sdk = [34])
    @Test
    fun `enabling the sticky notification with the permission granted turns the row on without a request`() {
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Start from the intent off, so the click is the enabling gesture (with the intent
        // on and the permission granted the row would already be on).
        prefs.showStickyNotification = false
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        stickyToggle().assertIsOff()
        stickyToggle().performClick()
        stickyToggle().assertIsOn()
        assertTrue(prefs.showStickyNotification)
        // The permission is already granted: no request dialog is launched.
        val activity = (composeTestRule as AndroidComposeTestRule<*, *>).activity
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    // Journey: the notifications permission is granted away (the system settings) while
    // the screen is behind — on the return the ON_RESUME refresh re-reads the live state,
    // and the sticky row turns on (the stored intent was already on).
    @Config(sdk = [34])
    @Test
    fun `the sticky row turns on on resume after the notification permission is granted away`() {
        shadowOf(appContext).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                ThemePreference(prefs),
                mock<ScheduleReconciler>(),
                mock<ScheduleArmer>(),
                PermissionManager(appContext),
            )
        render()
        stickyToggle().assertIsOff()
        // An app switch: the activity's lifecycle (the one the screen's effect observes)
        // goes down to CREATED and back up, firing ON_RESUME on the return; the grant
        // happens away, in between.
        val lifecycleRegistry = (composeTestRule as AndroidComposeTestRule<*, *>).activity.lifecycle as LifecycleRegistry
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeTestRule.waitForIdle()
        stickyToggle().assertIsOn()
    }

    @Test
    fun `the late send grace dropdown commits a preset`() {
        render()
        graceDropdown().performClick()
        composeTestRule.onNodeWithText("4 hours").performClick()
        assertEquals(240, prefs.lateSendGraceMinutes)
        composeTestRule.onNodeWithText("4 hours", substring = false).assertIsDisplayed()
    }

    // The Appearance section is tall, so these tests render on a taller screen where the
    // whole column is visible without scrolling.
    @Config(qualifiers = "h3000dp")
    @Test
    fun `the appearance section shows the theme and language pickers with the system defaults`() {
        render()
        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Theme", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithText("Language", substring = false).assertIsDisplayed()
        // Both pickers start on the system default.
        assertEquals(
            2,
            composeTestRule.onAllNodesWithText("System default", substring = false).fetchSemanticsNodes().size,
        )
    }

    @Config(qualifiers = "h3000dp")
    @Test
    fun `picking a theme commits the mode and shows it in the row`() {
        render()
        themeDropdown().performClick()
        composeTestRule.onNodeWithText("Dark", substring = false).performClick()
        assertEquals(AppPrefs.THEME_MODE_DARK, prefs.themeMode)
        composeTestRule.onNodeWithText("Dark", substring = false).assertIsDisplayed()
    }

    @Config(qualifiers = "h3000dp")
    @Test
    fun `picking a language commits the tag, and the system option resets it`() {
        render()
        languageDropdown().performClick()
        composeTestRule.onNodeWithText("English", substring = false).performClick()
        assertEquals("en", viewModel.state.value.appLanguage)
        composeTestRule.onNodeWithText("English", substring = false).assertIsDisplayed()
        // Back to the system language: the tag resets to empty. The theme field shows the
        // same text too, so the menu's option (composed last) is the one clicked.
        languageDropdown().performClick()
        composeTestRule
            .onAllNodesWithText("System default", substring = false)
            .onLast()
            .performClick()
        assertEquals("", viewModel.state.value.appLanguage)
    }

    @Config(qualifiers = "h3000dp")
    @Test
    fun `picking russian commits the ru tag and shows it in the row`() {
        render()
        languageDropdown().performClick()
        composeTestRule.onNodeWithText("Русский", substring = false).performClick()
        assertEquals("ru", viewModel.state.value.appLanguage)
        // The menu is dismissed after the pick, so the field is the only "Русский" node.
        composeTestRule.onNodeWithText("Русский", substring = false).assertIsDisplayed()
    }

    @Test
    fun `the back arrow and the logs button navigate`() {
        render()
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backClicked)
        composeTestRule.onNodeWithText("Open logs", substring = false).performClick()
        assertTrue(logsClicked)
    }

    // The Scheduling section is taller than the h1200dp class default, so these About tests
    // render on a taller screen where the whole column is visible without scrolling.
    @Config(qualifiers = "h3000dp")
    @Test
    fun `the about section renders the version as the first item and each link row with its title and url`() {
        render()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Version", substring = false).assertIsDisplayed()
        val versionValue = "${BuildConfig.VERSION_NAME.split("-")[0]} FOSS"
        composeTestRule.onNodeWithText(versionValue, substring = false).assertIsDisplayed()
        // The version row is the first item of the section, above the first link row.
        val versionBounds = composeTestRule.onNodeWithTag("about_version").getUnclippedBoundsInRoot()
        val sourceBounds = composeTestRule.onNodeWithTag("about_source_code").getUnclippedBoundsInRoot()
        assertTrue(versionBounds.top < sourceBounds.top)
        composeTestRule.onNodeWithText("Buy developer a coffee").assertIsDisplayed()
        composeTestRule.onNodeWithText(Links.DONATE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Can't donate? Star the repository on GitHub.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Source code").assertIsDisplayed()
        composeTestRule.onNodeWithText(Links.REPO).assertIsDisplayed()
        composeTestRule.onNodeWithText("Report a bug").assertIsDisplayed()
        composeTestRule.onNodeWithText(Links.REPORT_BUG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Request a feature").assertDoesNotExist()
        // Each link row carries the open-in-new icon at its end: the rows leave the app,
        // so they need the "goes somewhere" cue (the in-app navigation rows use a chevron
        // for the same purpose). The version row has no icon.
        for (iconTag in listOf("about_source_code_icon", "about_report_bug_icon", "about_sponsor_icon")) {
            val iconNodes = composeTestRule.onAllNodes(hasTestTag(iconTag), useUnmergedTree = true)
            assertEquals(1, iconNodes.fetchSemanticsNodes().size)
            val iconBounds = iconNodes.onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue(iconBounds.width > 0)
            assertTrue(iconBounds.height > 0)
        }
    }

    @Config(qualifiers = "h3000dp")
    @Test
    fun `tapping each about row opens the matching github url`() {
        render()
        val shadow = shadowOf(appContext)

        composeTestRule.onNodeWithTag("about_sponsor").performClick()
        assertEquals(Uri.parse(Links.DONATE), shadow.nextStartedActivity?.data)

        composeTestRule.onNodeWithTag("about_source_code").performClick()
        assertEquals(Uri.parse(Links.REPO), shadow.nextStartedActivity?.data)

        composeTestRule.onNodeWithTag("about_report_bug").performClick()
        assertEquals(Uri.parse(Links.REPORT_BUG), shadow.nextStartedActivity?.data)
    }
}
