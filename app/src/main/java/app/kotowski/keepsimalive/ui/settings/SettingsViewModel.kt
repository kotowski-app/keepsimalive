package app.kotowski.keepsimalive.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.ui.theme.ThemePreference
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.ToastUtil
import app.kotowski.keepsimalive.util.appQuantity
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// The period proposed when the user turns history retention on for the first time:
// 3650 days (about 10 years), so enabling the toggle must not clear the history the
// user has accumulated.
const val DEFAULT_RETENTION_DAYS: Int = 3650

data class SettingsUiState(
    // While true (the default) the persistent "keepalive is running" notification is shown
    // while some SIM keepalive is enabled.
    val showStickyNotification: Boolean = true,
    // History retention is on while a period is stored (0 = off, the default).
    val autoCleanupEnabled: Boolean = false,
    // The period shown in the editor dropdown: the stored one while it is one of the
    // presets, the default proposal otherwise.
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    // Exact wake-up (experimental, off by default): while true every armed schedule
    // additionally sets an exact alarm at the scheduled time (the WorkManager wake and the
    // safety sweep keep running as the backup).
    val exactWakeEnabled: Boolean = false,
    // The live "Alarms & reminders" permission (UI-cached): the toggle shows the active
    // state, intent AND granted; below API 31 exact alarms need no permission, so it is
    // unconditionally true there.
    val exactAlarmGranted: Boolean = true,
    // The live notifications permission (UI-cached): the sticky-notification toggle shows
    // the active state, intent AND granted; below API 33 the permission is granted with the
    // manifest (no runtime request), so it is unconditionally true there.
    val notificationsGranted: Boolean = true,
    // The late-send grace shown in the dropdown (minutes, one of AppPrefs.LATE_SEND_GRACE_PRESETS).
    val lateSendGraceMinutes: Int = AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES,
    // The theme mode (one of AppPrefs.THEME_MODES).
    val themeMode: String = AppPrefs.THEME_MODE_SYSTEM,
    // The app-specific language tag ("" = follow the system language, the default; "en"; "ru").
    val appLanguage: String = "",
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val prefs: AppPrefs,
        private val themePreference: ThemePreference,
        private val scheduleReconciler: ScheduleReconciler,
        private val armer: ScheduleArmer,
        private val permissionManager: PermissionManager,
    ) : ViewModel() {
        private val _state =
            MutableStateFlow(
                SettingsUiState(
                    showStickyNotification = prefs.showStickyNotification,
                    autoCleanupEnabled = prefs.historyRetentionDays > 0,
                    // A stored value not in the presets (legacy/tampered) degrades to the
                    // default in the UI, the engine keeps using the stored value until the
                    // next save — same rule as lateSendGraceMinutes.
                    retentionDays =
                        prefs.historyRetentionDays.takeIf { it in RETENTION_PERIOD_PRESETS } ?: DEFAULT_RETENTION_DAYS,
                    exactWakeEnabled = prefs.exactWakeEnabled,
                    exactAlarmGranted = permissionManager.canScheduleExactAlarms(),
                    notificationsGranted = notificationsGranted(),
                    lateSendGraceMinutes = prefs.lateSendGraceMinutes,
                    themeMode = prefs.themeMode,
                    appLanguage = currentAppLanguage(),
                ),
            )
        val state: StateFlow<SettingsUiState> = _state.asStateFlow()

        val permissionManagerForUi: PermissionManager = permissionManager

        // The live notifications (POST_NOTIFICATIONS) permission for the UI: below API 33
        // the permission is granted with the manifest (no runtime request), so the flag is
        // unconditionally true there; from 33 on only the live runtime state is read.
        private fun notificationsGranted(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return try {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Logger.e("SettingsVM", "Failed to check notifications permission: ${e.message}", e)
                false
            }
        }

        // Re-reads the live notifications permission (the screen drives it on every resume
        // and after the request dialog returns): the toggle shows the active state again,
        // and a grant while the sticky notification is on shows the persistent hint at once
        // (a revoke needs no dismissal — the system drops the notification itself).
        fun refreshNotificationsPermission() {
            val granted = notificationsGranted()
            val wasGranted = _state.value.notificationsGranted
            _state.update { it.copy(notificationsGranted = granted) }
            if (granted != wasGranted && prefs.showStickyNotification) {
                Logger.w(
                    "SettingsVM",
                    "Notifications permission ${if (granted) "granted" else "revoked"} while the sticky notification is on",
                )
                if (granted) {
                    viewModelScope.launch { scheduleReconciler.syncPersistentHint() }
                }
            }
        }

        fun setShowStickyNotification(value: Boolean) {
            prefs.showStickyNotification = value
            _state.update { it.copy(showStickyNotification = value) }
            viewModelScope.launch { scheduleReconciler.syncPersistentHint() }
        }

        // The single commit of the retention editor: off stores 0 (nothing deleted, no
        // reconcile), on stores the preset; the off->on transition enqueues a one-time
        // reconcile so expired history is deleted right away instead of waiting for the
        // next 24 h sweep (the bound the toast promises).
        fun commitRetention(
            enabled: Boolean,
            days: Int,
        ) {
            if (days !in RETENTION_PERIOD_PRESETS) return
            val wasEnabled = _state.value.autoCleanupEnabled
            prefs.historyRetentionDays = if (enabled) days else 0
            _state.update { it.copy(autoCleanupEnabled = enabled, retentionDays = if (enabled) days else DEFAULT_RETENTION_DAYS) }
            if (enabled && !wasEnabled) {
                ScheduleSafetyWorker.enqueueOnce(appContext)
                val hours = AppConfig.SAFETY_SWEEP_INTERVAL_HOURS.toInt()
                ToastUtil.show(
                    appContext,
                    appContext.appQuantity(R.plurals.settings_cleanup_will_run, hours, hours),
                )
            }
        }

        // The toggle is the user's intent: enabling it while the "Alarms & reminders"
        // permission is missing is allowed — the screen opens the system page right after,
        // and while the permission is not granted the alarms are simply not armed. Every
        // change syncs the exact alarms with the gate (active: re-arm, inactive: cancel —
        // both idempotent); the DB stays the source of truth, nothing is recomputed.
        fun setExactWakeEnabled(value: Boolean) {
            prefs.exactWakeEnabled = value
            _state.update { it.copy(exactWakeEnabled = value) }
            Logger.i("SettingsVM", "exact wake-up ${if (value) "enabled" else "disabled"}")
            viewModelScope.launch { syncExactAlarms() }
        }

        // Idempotent sync of the exact-alarm wakes with the gate: while active (feature on
        // AND permission granted) every armed schedule re-arms from a fresh read, otherwise
        // every pending alarm is cancelled (a turn-off or a revoke must not leave them).
        private suspend fun syncExactAlarms() {
            if (armer.exactWakeActive()) {
                armer.rearmAllExactAlarms()
            } else {
                armer.cancelAllExactAlarms()
            }
        }

        // Re-reads the live permission (the screen drives it on every resume and after the
        // system page returns): the toggle shows the active state again, and the exact
        // alarms follow the gate (a grant arms them, a revoke cancels them).
        fun refreshExactAlarmPermission() {
            val granted = permissionManager.canScheduleExactAlarms()
            val wasGranted = _state.value.exactAlarmGranted
            _state.update { it.copy(exactAlarmGranted = granted) }
            if (prefs.exactWakeEnabled && wasGranted != granted) {
                Logger.w(
                    "SettingsVM",
                    "Alarms & reminders permission ${if (granted) "re-granted" else "revoked"} while exact wake-up is on",
                )
            }
            viewModelScope.launch { syncExactAlarms() }
        }

        // Commits one of the presets only (the dropdown offers nothing else); the new grace
        // applies to the next send decision — the engine reads it on every run, so no
        // reconcile is needed.
        fun setLateSendGrace(minutes: Int) {
            if (minutes !in AppPrefs.LATE_SEND_GRACE_PRESETS) return
            prefs.lateSendGraceMinutes = minutes
            _state.update { it.copy(lateSendGraceMinutes = minutes) }
            Logger.i("SettingsVM", "late send grace set to $minutes minutes")
        }

        // Commits one of the three theme modes only (the dropdown offers nothing else); the
        // root collects the holder's flow, so the new palette applies right away, no restart.
        fun setThemeMode(mode: String) {
            if (mode !in AppPrefs.THEME_MODES) return
            themePreference.set(mode)
            _state.update { it.copy(themeMode = mode) }
        }

        // The app language: an empty tag resets to the system language. The pick is applied
        // right away (the activity is recreated with the new locale, no app restart) and
        // persisted by the platform (natively from API 33, by AppCompat below); the tag is
        // also stored in AppPrefs, since below API 33 the AppCompat state is process-local
        // and a fresh background process must re-apply the pick from it at startup. Only
        // the shipped language tags pass the guard ("en", "ru"); the dropdown offers
        // nothing else.
        fun setAppLanguage(tag: String) {
            if (tag.isNotEmpty() && tag != "en" && tag != "ru") return
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
            )
            prefs.appLanguageTag = tag
            _state.update { it.copy(appLanguage = tag) }
        }

        // The current app-specific language tag ("en") or "" while the system language is
        // followed (the default).
        private fun currentAppLanguage(): String {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            return if (appLocales.isEmpty) "" else (appLocales[0]?.toLanguageTag() ?: "")
        }

        companion object {
            // The retention periods the editor offers (exponential, ascending); the maximum
            // of 7300 days is 20 years.
            val RETENTION_PERIOD_PRESETS: List<Int> = listOf(30, 90, 180, 365, 730, 1825, 3650, 7300)
        }
    }
