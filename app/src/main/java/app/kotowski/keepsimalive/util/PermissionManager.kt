package app.kotowski.keepsimalive.util

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import app.kotowski.keepsimalive.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionState {
    GRANTED,
    DENIED_CAN_ASK,
    DENIED_PERMANENTLY,
}

enum class PermissionsAutoResetStatus {
    DISABLED,
    ACTIVE,
    NOT_AVAILABLE,
}

fun getPermissionsAutoResetStatusFromInt(status: Int): PermissionsAutoResetStatus =
    when (status) {
        UnusedAppRestrictionsConstants.DISABLED -> PermissionsAutoResetStatus.DISABLED
        UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> PermissionsAutoResetStatus.NOT_AVAILABLE
        else -> PermissionsAutoResetStatus.ACTIVE
    }

@Singleton
class PermissionManager
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) {
        private val prefs = appContext.getSharedPreferences("permission_state", Context.MODE_PRIVATE)

        fun isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(
                appContext,
                permission,
            ) == PackageManager.PERMISSION_GRANTED

        fun getState(
            activity: Activity,
            permission: String,
        ): PermissionState {
            if (isGranted(permission)) return PermissionState.GRANTED
            val requestedBefore = prefs.getBoolean(permission, false)
            if (!requestedBefore) return PermissionState.DENIED_CAN_ASK
            return if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                PermissionState.DENIED_CAN_ASK
            } else {
                PermissionState.DENIED_PERMANENTLY
            }
        }

        fun markRequested(permission: String) {
            prefs.edit().putBoolean(permission, true).apply()
        }

        // The single request path of every runtime permission in the app (no-op while
        // granted): the system dialog is launched while it can still be asked, and a
        // permanently denied permission (the dialog is gone for good) toasts and opens the
        // app's system settings page instead.
        fun request(
            activity: Activity,
            permission: String,
            launcher: ActivityResultLauncher<String>,
        ) {
            if (isGranted(permission)) return
            when (getState(activity, permission)) {
                PermissionState.GRANTED -> {
                    // Unreachable (isGranted above) but keeps the when exhaustive.
                }

                PermissionState.DENIED_CAN_ASK -> {
                    markRequested(permission)
                    launcher.launch(permission)
                }

                PermissionState.DENIED_PERMANENTLY -> {
                    ToastUtil.showLong(activity, R.string.permissions_permanently_denied)
                    openAppSettings(activity)
                }
            }
        }

        // The app's own system settings page: the single fallback target of every intent
        // factory below (a ROM without the dedicated settings activity lands here) and the
        // page opened for a permanently denied permission (openAppSettings).
        private fun appSettingsIntent(): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }

        // The single resolveActivity guard shared by the intent factories: a ROM without
        // the dedicated settings activity opens the app's system settings page instead of
        // crashing on the tap.
        private fun withFallback(intent: Intent): Intent =
            if (intent.resolveActivity(appContext.packageManager) != null) intent else appSettingsIntent()

        fun openAppSettings(activity: Activity) {
            activity.startActivity(appSettingsIntent())
        }

        suspend fun getPermissionsAutoResetStatus(): PermissionsAutoResetStatus =
            try {
                val status = PackageManagerCompat.getUnusedAppRestrictionsStatus(appContext).await()
                getPermissionsAutoResetStatusFromInt(status)
            } catch (e: Exception) {
                Logger.e("PermissionManager", "Failed to get permissions auto-reset status: ${e.message}", e)
                PermissionsAutoResetStatus.ACTIVE
            }

        fun createManagePermissionsAutoResetIntent(): Intent =
            try {
                withFallback(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(
                        appContext,
                        appContext.packageName,
                    ),
                )
            } catch (e: UnsupportedOperationException) {
                appSettingsIntent()
            }

        // The single guarded battery-optimization check (the dashboard row state and the
        // row tap): a ROM whose patched framework throws there must degrade to "not
        // ignoring" instead of crashing the caller.
        fun isIgnoringBatteryOptimizations(packageName: String): Boolean =
            try {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } catch (e: Exception) {
                Logger.e("PermissionManager", "Failed to check battery optimizations: ${e.message}", e)
                false
            }

        // The single guarded exact-alarm permission check: the "Alarms & reminders"
        // (SCHEDULE_EXACT_ALARM) permission only exists from API 31, so below 31 the check is
        // unconditionally true; from 31 on only the system setting is read (never the
        // manifest). A ROM whose patched framework throws degrades to "not granted".
        fun canScheduleExactAlarms(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return try {
                (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } catch (e: Exception) {
                Logger.e("PermissionManager", "Failed to check exact alarm permission: ${e.message}", e)
                false
            }
        }

        fun createRequestIgnoreBatteryOptimizationsIntent(): Intent =
            withFallback(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                },
            )

        fun createRequestScheduleExactAlarmIntent(): Intent =
            withFallback(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                },
            )
    }
