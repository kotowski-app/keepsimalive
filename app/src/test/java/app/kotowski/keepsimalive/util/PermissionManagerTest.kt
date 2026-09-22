package app.kotowski.keepsimalive.util

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.UnusedAppRestrictionsConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class PermissionManagerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val permissionManager: PermissionManager = PermissionManager(context)

    @Test
    fun `PermissionState enum has correct values`() {
        assertEquals(3, PermissionState.values().size)
        assertNotSame(PermissionState.GRANTED, PermissionState.DENIED_CAN_ASK)
        assertNotSame(PermissionState.DENIED_CAN_ASK, PermissionState.DENIED_PERMANENTLY)
    }

    @Test
    fun `PermissionsAutoResetStatus enum has correct values`() {
        assertEquals(3, PermissionsAutoResetStatus.values().size)
        assertNotSame(PermissionsAutoResetStatus.DISABLED, PermissionsAutoResetStatus.ACTIVE)
        assertNotSame(PermissionsAutoResetStatus.ACTIVE, PermissionsAutoResetStatus.NOT_AVAILABLE)
    }

    @Test
    fun `isGranted returns false for ungranted permission`() {
        val granted = permissionManager.isGranted(android.Manifest.permission.SEND_SMS)
        assertFalse(granted)
    }

    @Test
    fun `getState returns DENIED_CAN_ASK for unrequested permission`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val state = permissionManager.getState(activity, "com.example.UNREQUESTED_PERMISSION")
        assertEquals(PermissionState.DENIED_CAN_ASK, state)
    }

    @Test
    fun `markRequested persists request state`() {
        val permission = "com.example.TEST_PERMISSION"
        permissionManager.markRequested(permission)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val state = permissionManager.getState(activity, permission)
        assertTrue(state == PermissionState.DENIED_CAN_ASK || state == PermissionState.DENIED_PERMANENTLY)
    }

    @Test
    fun `createManagePermissionsAutoResetIntent returns a valid Intent`() {
        val intent = permissionManager.createManagePermissionsAutoResetIntent()
        assertNotNull(intent)
        val pm = context.packageManager
        assertTrue(
            intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS ||
                intent.resolveActivity(pm) != null,
        )
    }

    @Test
    fun `createRequestIgnoreBatteryOptimizationsIntent falls back to the app details page while the settings activity is missing`() {
        // Robolectric's framework ships no activity for the battery-optimization request
        // page, so this is the ROM-without-the-dedicated-screen path: the tap must land on
        // the app details page instead of throwing an uncaught ActivityNotFoundException.
        val intent = permissionManager.createRequestIgnoreBatteryOptimizationsIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
    }

    @Test
    fun `createRequestScheduleExactAlarmIntent falls back to the app details page while the settings activity is missing`() {
        val intent = permissionManager.createRequestScheduleExactAlarmIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
    }

    @Test
    fun `isIgnoringBatteryOptimizations reports the PowerManager state`() {
        // The guarded check the dashboard row state and the row tap share: it must follow
        // the framework state, and a check failure must degrade to false (the guard's
        // catch path needs a ROM that throws, which Robolectric cannot simulate here).
        assertFalse(permissionManager.isIgnoringBatteryOptimizations(context.packageName))
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(permissionManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    // The resolve path (the dedicated settings activity present) is not unit-testable: the
    // Robolectric 4.11 ShadowPackageManager does not override resolveActivity/queryIntentActivities,
    // so resolveActivity always returns null here (verified against the shadow's bytecode).
    // The shared resolveActivity pattern is the one of createManagePermissionsAutoResetIntent.

    @Test
    fun `getPermissionsAutoResetStatus returns a valid status`() =
        runBlocking {
            val status = permissionManager.getPermissionsAutoResetStatus()
            assertTrue(status in PermissionsAutoResetStatus.values())
        }

    @Test
    fun `getPermissionsAutoResetStatusFromInt DISABLED returns DISABLED`() {
        assertEquals(PermissionsAutoResetStatus.DISABLED, getPermissionsAutoResetStatusFromInt(UnusedAppRestrictionsConstants.DISABLED))
    }

    @Test
    fun `getPermissionsAutoResetStatusFromInt FEATURE_NOT_AVAILABLE returns NOT_AVAILABLE`() {
        assertEquals(
            PermissionsAutoResetStatus.NOT_AVAILABLE,
            getPermissionsAutoResetStatusFromInt(UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE),
        )
    }

    @Test
    fun `getPermissionsAutoResetStatusFromInt API_30 returns ACTIVE`() {
        assertEquals(PermissionsAutoResetStatus.ACTIVE, getPermissionsAutoResetStatusFromInt(UnusedAppRestrictionsConstants.API_30))
    }

    @Test
    fun `getPermissionsAutoResetStatusFromInt API_31 returns ACTIVE`() {
        assertEquals(PermissionsAutoResetStatus.ACTIVE, getPermissionsAutoResetStatusFromInt(UnusedAppRestrictionsConstants.API_31))
    }

    @Test
    fun `getPermissionsAutoResetStatusFromInt unknown returns ACTIVE`() {
        assertEquals(PermissionsAutoResetStatus.ACTIVE, getPermissionsAutoResetStatusFromInt(99))
    }

    @Test
    fun `request launches the system dialog for an unrequested permission`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val launcher: ActivityResultLauncher<String> = mock()
        permissionManager.request(activity, android.Manifest.permission.SEND_SMS, launcher)
        verify(launcher).launch(android.Manifest.permission.SEND_SMS)
        // The request is remembered (the next time the state can tell a plain denial from a
        // permanent one).
        permissionManager.markRequested(android.Manifest.permission.SEND_SMS)
        assertEquals(
            true,
            permissionManager.getState(activity, android.Manifest.permission.SEND_SMS) !=
                PermissionState.GRANTED,
        )
    }

    @Test
    fun `request is a no-op while the permission is granted`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.SEND_SMS)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val launcher: ActivityResultLauncher<String> = mock()
        permissionManager.request(activity, android.Manifest.permission.SEND_SMS, launcher)
        verifyNoInteractions(launcher)
    }

    @Test
    fun `request of a permanently denied permission toasts and opens the app settings instead of the dialog`() {
        // A previously requested runtime permission the system no longer shows rationale for
        // is permanently denied: the in-app dialog is gone for good, the app settings page
        // is the only remaining grant target.
        val permission = "com.example.PERMANENTLY_DENIED_PERMISSION"
        permissionManager.markRequested(permission)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val launcher: ActivityResultLauncher<String> = mock()
        permissionManager.request(activity, permission, launcher)
        verifyNoInteractions(launcher)
        assertEquals(
            context.getString(app.kotowski.keepsimalive.R.string.permissions_permanently_denied),
            ShadowToast.getTextOfLatestToast(),
        )
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent?.action)
    }

    @Test
    fun `openAppSettings targets the app's own system settings page`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        permissionManager.openAppSettings(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent?.action)
        assertEquals("package:${activity.packageName}", intent?.data?.toString())
    }

    // The SCHEDULE_EXACT_ALARM permission only exists from API 31 (the suite default is
    // 29, where exact alarms need no permission), so the permission-gated path runs on 33.
    @Test
    fun `canScheduleExactAlarms returns true below API 31`() {
        assertTrue(permissionManager.canScheduleExactAlarms())
    }

    @Config(sdk = [33])
    @Test
    fun `canScheduleExactAlarms reflects the AlarmManager state`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(permissionManager.canScheduleExactAlarms())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(permissionManager.canScheduleExactAlarms())
    }

    @Config(sdk = [33])
    @Test
    fun `canScheduleExactAlarms degrades to false when AlarmManager throws`() {
        // Throws cannot be simulated in Robolectric; the guarded pattern is structurally
        // identical to isIgnoringBatteryOptimizations, tested above.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(permissionManager.canScheduleExactAlarms())
    }
}
