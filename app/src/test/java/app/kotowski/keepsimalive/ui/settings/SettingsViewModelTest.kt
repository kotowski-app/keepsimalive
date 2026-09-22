package app.kotowski.keepsimalive.ui.settings

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.ui.theme.ThemePreference
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

// Real AppPrefs and the test WorkManager: the enable path persists the period and enqueues a
// one-time reconcile, and both are asserted on the real stores instead of mocks.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val prefs = AppPrefs(context)
    private val reconciler: ScheduleReconciler = mock()
    private val armer: ScheduleArmer = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        prefs.showStickyNotification = true
        prefs.historyRetentionDays = 0
        prefs.exactWakeEnabled = false
        prefs.lateSendGraceMinutes = AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES
        prefs.themeMode = AppPrefs.THEME_MODE_SYSTEM
        prefs.appLanguageTag = ""
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = SettingsViewModel(context, prefs, ThemePreference(prefs), reconciler, armer, PermissionManager(context))

    @Test
    fun `defaults have the sticky notification on with the 3650 day proposal`() {
        val vm = vm()
        val state = vm.state.value
        assertTrue(state.showStickyNotification)
        assertFalse(state.autoCleanupEnabled)
        assertEquals(3650, state.retentionDays)
    }

    @Test
    fun `a stored period loads into the state as enabled`() {
        prefs.historyRetentionDays = 90
        val state = vm().state.value
        assertTrue(state.autoCleanupEnabled)
        assertEquals(90, state.retentionDays)
    }

    @Test
    fun `showStickyNotification reads the stored pref into state on init`() {
        prefs.showStickyNotification = false
        assertFalse(vm().state.value.showStickyNotification)
    }

    @Test
    fun `setShowStickyNotification persists the flag, updates state and syncs the hint`() =
        runBlocking {
            val vm = vm()
            vm.setShowStickyNotification(false)
            assertFalse(prefs.showStickyNotification)
            assertFalse(vm.state.value.showStickyNotification)
            verify(reconciler).syncPersistentHint()
            vm.setShowStickyNotification(true)
            assertTrue(prefs.showStickyNotification)
            assertTrue(vm.state.value.showStickyNotification)
        }

    @Test
    fun `commitRetention on stores the preset and enqueues the cleanup pass`() {
        val vm = vm()
        vm.commitRetention(true, 730)
        assertTrue(vm.state.value.autoCleanupEnabled)
        assertEquals(730, vm.state.value.retentionDays)
        assertEquals(730, prefs.historyRetentionDays)
        val workInfos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(ScheduleSafetyWorker.WORK_NAME_ONCE)
                .get()
        assertTrue(workInfos.isNotEmpty())
    }

    @Test
    fun `commitRetention off clears the stored period and the proposal returns`() {
        val vm = vm()
        vm.commitRetention(true, 3650)
        assertEquals(3650, prefs.historyRetentionDays)
        vm.commitRetention(false, 3650)
        assertFalse(vm.state.value.autoCleanupEnabled)
        assertEquals(0, prefs.historyRetentionDays)
        assertEquals(3650, vm.state.value.retentionDays)
    }

    @Test
    fun `defaults have the exact wake off and the 15 minute grace`() {
        val state = vm().state.value
        assertFalse(state.exactWakeEnabled)
        // The suite default is 29, where exact alarms need no permission: unconditionally
        // granted.
        assertTrue(state.exactAlarmGranted)
        assertEquals(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES, state.lateSendGraceMinutes)
    }

    @Test
    fun `a stored exact wake and grace load into the state`() {
        prefs.exactWakeEnabled = true
        prefs.lateSendGraceMinutes = 1440
        val state = vm().state.value
        assertTrue(state.exactWakeEnabled)
        assertEquals(1440, state.lateSendGraceMinutes)
    }

    @Test
    fun `enabling the exact wake while the gate is active re-arms without cancelling`() =
        runBlocking {
            whenever(armer.exactWakeActive()).thenReturn(true)
            val vm = vm()
            vm.setExactWakeEnabled(true)
            assertTrue(prefs.exactWakeEnabled)
            assertTrue(vm.state.value.exactWakeEnabled)
            verify(armer).rearmAllExactAlarms()
            verify(armer, never()).cancelAllExactAlarms()
        }

    // The SCHEDULE_EXACT_ALARM gate only exists from API 31 (the suite default is 29, where
    // exact alarms need no permission), so the permission-gated paths run on 33.
    @Config(sdk = [33])
    @Test
    fun `enabling without the permission keeps the exact wake inactive and un-armed`() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            val vm = vm()
            vm.setExactWakeEnabled(true)
            assertTrue(prefs.exactWakeEnabled)
            assertTrue(vm.state.value.exactWakeEnabled)
            assertFalse(vm.state.value.exactAlarmGranted)
            verify(armer, never()).rearmAllExactAlarms()
            verify(armer).cancelAllExactAlarms()
        }

    @Config(sdk = [33])
    @Test
    fun `refreshing with the permission granted arms the exact alarms`() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            whenever(armer.exactWakeActive()).thenReturn(true)
            val vm = vm()
            vm.refreshExactAlarmPermission()
            assertTrue(vm.state.value.exactAlarmGranted)
            verify(armer).rearmAllExactAlarms()
            verify(armer, never()).cancelAllExactAlarms()
        }

    @Config(sdk = [33])
    @Test
    fun `refreshing with the permission revoked cancels the exact alarms`() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val vm = vm()
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            vm.refreshExactAlarmPermission()
            assertFalse(vm.state.value.exactAlarmGranted)
            verify(armer).cancelAllExactAlarms()
            verify(armer, never()).rearmAllExactAlarms()
        }

    @Test
    fun `disabling the exact wake cancels the pending exact alarms`() =
        runBlocking {
            val vm = vm()
            vm.setExactWakeEnabled(true)
            vm.setExactWakeEnabled(false)
            assertFalse(prefs.exactWakeEnabled)
            assertFalse(vm.state.value.exactWakeEnabled)
            // Both syncs take the cancel path: the mock gate is inactive (the enable arms
            // nothing), and the disable cancels whatever was armed from the enabled time.
            verify(armer, times(2)).cancelAllExactAlarms()
        }

    @Test
    fun `setLateSendGrace commits a preset and ignores anything else`() {
        val vm = vm()
        vm.setLateSendGrace(240)
        assertEquals(240, prefs.lateSendGraceMinutes)
        assertEquals(240, vm.state.value.lateSendGraceMinutes)
        vm.setLateSendGrace(999)
        assertEquals(240, prefs.lateSendGraceMinutes)
        assertEquals(240, vm.state.value.lateSendGraceMinutes)
    }

    @Test
    fun `commitRetention ignores a period that is not one of the presets`() {
        val vm = vm()
        vm.commitRetention(true, 999)
        assertFalse(vm.state.value.autoCleanupEnabled)
        assertEquals(3650, vm.state.value.retentionDays)
        assertEquals(0, prefs.historyRetentionDays)
    }

    // The off->on-only enqueue is not asserted here: under the REPLACE policy the second
    // commit (already enabled) must simply re-store the preset without a new reconcile.
    @Test
    fun `a period change while enabled re-stores the preset`() {
        val vm = vm()
        vm.commitRetention(true, 1825)
        assertEquals(1825, prefs.historyRetentionDays)
        vm.commitRetention(true, 730)
        assertEquals(730, prefs.historyRetentionDays)
        assertEquals(730, vm.state.value.retentionDays)
    }

    @Test
    fun `a stored period that is not a preset loads as enabled with the default proposal`() {
        prefs.historyRetentionDays = 45
        val state = vm().state.value
        assertTrue(state.autoCleanupEnabled)
        assertEquals(3650, state.retentionDays)
    }

    @Test
    fun `defaults have the system theme mode and the system language`() {
        val state = vm().state.value
        assertEquals(AppPrefs.THEME_MODE_SYSTEM, state.themeMode)
        assertEquals("", state.appLanguage)
    }

    @Test
    fun `a stored theme mode and a stored app language load into the state`() {
        prefs.themeMode = AppPrefs.THEME_MODE_DARK
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        val state = vm().state.value
        assertEquals(AppPrefs.THEME_MODE_DARK, state.themeMode)
        assertEquals("en", state.appLanguage)
    }

    @Test
    fun `setThemeMode persists the mode and updates the state`() {
        val vm = vm()
        vm.setThemeMode(AppPrefs.THEME_MODE_LIGHT)
        assertEquals(AppPrefs.THEME_MODE_LIGHT, prefs.themeMode)
        assertEquals(AppPrefs.THEME_MODE_LIGHT, vm.state.value.themeMode)
        // A mode outside the three is ignored (the dropdown offers nothing else).
        vm.setThemeMode("neon")
        assertEquals(AppPrefs.THEME_MODE_LIGHT, prefs.themeMode)
        assertEquals(AppPrefs.THEME_MODE_LIGHT, vm.state.value.themeMode)
    }

    @Test
    fun `setAppLanguage applies the tag, persists it, resets to the system language and ignores others`() {
        val vm = vm()
        vm.setAppLanguage("en")
        assertEquals("en", vm.state.value.appLanguage)
        // The pick is persisted for the fresh background processes below API 33 that must
        // re-apply it at startup (the AppCompat state alone is process-local there).
        assertEquals("en", prefs.appLanguageTag)
        assertEquals(LocaleListCompat.forLanguageTags("en"), AppCompatDelegate.getApplicationLocales())
        vm.setAppLanguage("ru")
        assertEquals("ru", vm.state.value.appLanguage)
        assertEquals("ru", prefs.appLanguageTag)
        assertEquals(LocaleListCompat.forLanguageTags("ru"), AppCompatDelegate.getApplicationLocales())
        vm.setAppLanguage("")
        assertEquals("", vm.state.value.appLanguage)
        assertEquals("", prefs.appLanguageTag)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        // A tag outside the shipped languages is ignored (the dropdown offers nothing else).
        vm.setAppLanguage("fr")
        assertEquals("", vm.state.value.appLanguage)
        assertEquals("", prefs.appLanguageTag)
    }

    // The POST_NOTIFICATIONS gate only exists from API 33 (the suite default is 29, where
    // the permission is granted with the manifest and needs no runtime state).
    @Test
    fun `below the gate the notifications permission is unconditionally granted`() {
        assertTrue(vm().state.value.notificationsGranted)
    }

    @Config(sdk = [34])
    @Test
    fun `a revoked notifications permission loads into the state as missing`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(vm().state.value.notificationsGranted)
    }

    @Config(sdk = [34])
    @Test
    fun `refreshing after the grant syncs the persistent hint while the sticky is on`() =
        runBlocking {
            shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val vm = vm()
            assertFalse(vm.state.value.notificationsGranted)
            shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            vm.refreshNotificationsPermission()
            assertTrue(vm.state.value.notificationsGranted)
            // The grant while the toggle is on shows the hint at once (the sync re-reads
            // the flags and posts the notification).
            verify(reconciler).syncPersistentHint()
        }

    @Config(sdk = [34])
    @Test
    fun `refreshing after a revoke does not sync the hint`() =
        runBlocking {
            shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val vm = vm()
            assertTrue(vm.state.value.notificationsGranted)
            shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            vm.refreshNotificationsPermission()
            assertFalse(vm.state.value.notificationsGranted)
            // A revoke needs no dismissal: the system drops the notification itself.
            verify(reconciler, never()).syncPersistentHint()
        }

    @Config(sdk = [34])
    @Test
    fun `refreshing the grant while the sticky is off does not sync the hint`() =
        runBlocking {
            shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val vm = vm()
            prefs.showStickyNotification = false
            shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            vm.refreshNotificationsPermission()
            assertTrue(vm.state.value.notificationsGranted)
            verify(reconciler, never()).syncPersistentHint()
        }
}
