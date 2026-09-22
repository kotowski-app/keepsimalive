package app.kotowski.keepsimalive.util

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // History retention (days, 0 = unlimited)
    var historyRetentionDays: Int
        get() = prefs.getInt(KEY_HISTORY_RETENTION_DAYS, 0)
        set(value) {
            prefs.edit().putInt(KEY_HISTORY_RETENTION_DAYS, value).apply()
        }

    // Settings toggle (on by default): while true the persistent "keepalive is running"
    // notification is shown while some SIM keepalive is enabled.
    var showStickyNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STICKY_NOTIFICATION, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_STICKY_NOTIFICATION, value).apply()
        }

    // Settings toggle (off by default): while true every armed schedule additionally sets an
    // exact alarm (setExactAndAllowWhileIdle) that wakes the device at the scheduled time;
    // the WorkManager wake and the safety sweep stay as the backup. On Android 12+ this
    // needs the "Alarms & reminders" permission; while it is missing the alarms are simply
    // not armed and the app runs on the backup wake only.
    var exactWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXACT_WAKE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXACT_WAKE_ENABLED, value).apply()
        }

    // Late-send grace (minutes, one of LATE_SEND_GRACE_PRESETS; 15 = the default): how far
    // past the scheduled time a late wake still attempts the occurrence instead of consuming
    // it as missed. A stored value outside the presets (legacy or tampered) degrades to the
    // default.
    var lateSendGraceMinutes: Int
        get() =
            LATE_SEND_GRACE_PRESETS
                .firstOrNull { it == prefs.getInt(KEY_LATE_SEND_GRACE_MINUTES, DEFAULT_LATE_SEND_GRACE_MINUTES) }
                ?: DEFAULT_LATE_SEND_GRACE_MINUTES
        set(value) {
            prefs.edit().putInt(KEY_LATE_SEND_GRACE_MINUTES, value).apply()
        }

    // Theme mode (one of THEME_MODES, "system" = the default): "system" follows the
    // device's dark/light setting, the other two force the palette. A stored value outside
    // the modes (legacy or tampered) degrades to the default.
    var themeMode: String
        get() =
            prefs
                .getString(KEY_THEME_MODE, THEME_MODE_SYSTEM)
                .takeIf { it in THEME_MODES }
                ?: THEME_MODE_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
        }

    // The app language the user picked in Settings ("" = follow the system, the default;
    // "en"; "ru"). Below API 33 AppCompat keeps the pick in process-local static state
    // only, so this persisted tag is the durable record a fresh background process
    // re-applies at startup (and the appLocaleContext fallback reads).
    var appLanguageTag: String
        get() = prefs.getString(KEY_APP_LANGUAGE_TAG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_APP_LANGUAGE_TAG, value).apply()
        }

    // Consecutive "SIM not present" failures per SIM across retries and occurrences: when
    // it reaches AppConfig.MAX_SIM_NOT_PRESENT_FAILURES the schedule is auto-disabled (a
    // removed SIM must not loop forever).
    fun getSimNotPresentFailures(simId: Int): Int = prefs.getInt(KEY_SIM_NOT_PRESENT_FAILURES_PREFIX + simId, 0)

    fun setSimNotPresentFailures(
        simId: Int,
        count: Int,
    ) {
        prefs.edit().putInt(KEY_SIM_NOT_PRESENT_FAILURES_PREFIX + simId, count).apply()
    }

    // Clock-change recompute, per SIM: the armed occurrence (the deleted PENDING row's time)
    // the recompute is about to clear, persisted at the clear so it survives a process death
    // between the clear and the re-arm (the in-memory copy alone dies with the process and the
    // deleted occurrence would vanish without a record — for a fresh SIM no other anchor can
    // re-derive it). The reconciler's fresh-arm path consumes and clears it; deleteSchedule/
    // forgetSim clear it too, so a re-added SIM never inherits the old one. 0 = unset.
    fun getCatchUpAnchor(simId: Int): Long =
        prefs
            .getLong(KEY_CATCH_UP_ANCHOR_PREFIX + simId, 0L)
            .takeIf { it > 0L }
            ?: 0L

    fun setCatchUpAnchor(
        simId: Int,
        scheduledForMillis: Long,
    ) {
        prefs.edit().putLong(KEY_CATCH_UP_ANCHOR_PREFIX + simId, scheduledForMillis).apply()
    }

    fun clearCatchUpAnchor(simId: Int) {
        prefs.edit().remove(KEY_CATCH_UP_ANCHOR_PREFIX + simId).apply()
    }

    // Rhythm reset, per SIM: the wall-clock time the schedule was deleted. A re-enable
    // must not re-anchor the rhythm on SENT rows older than the delete (the SIM was
    // unconfigured then — those occurrences are not "missed", they belong to the off
    // period a new SIM starts without). A post-delete send makes the newest SENT row
    // newer than the marker, so the anchor resumes on its own (no consumption needed);
    // the marker stays set until the next delete. 0 = never deleted.
    fun getRhythmResetAtMillis(simId: Int): Long = prefs.getLong(KEY_RHYTHM_RESET_AT_PREFIX + simId, 0L)

    fun setRhythmResetAtMillis(
        simId: Int,
        millis: Long,
    ) {
        prefs.edit().putLong(KEY_RHYTHM_RESET_AT_PREFIX + simId, millis).apply()
    }

    companion object {
        const val PREFS_NAME = "keepalive_prefs"
        private const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
        private const val KEY_SIM_NOT_PRESENT_FAILURES_PREFIX = "sim_not_present_failures_sim_"
        private const val KEY_CATCH_UP_ANCHOR_PREFIX = "catch_up_anchor_sim_"
        private const val KEY_RHYTHM_RESET_AT_PREFIX = "rhythm_reset_at_"
        private const val KEY_SHOW_STICKY_NOTIFICATION = "show_sticky_notification"
        private const val KEY_EXACT_WAKE_ENABLED = "exact_wake_enabled"
        private const val KEY_LATE_SEND_GRACE_MINUTES = "late_send_grace_minutes"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE_TAG = "app_language_tag"

        // The theme modes in the order the settings dropdown shows them; the first is also
        // the default.
        const val THEME_MODE_SYSTEM: String = "system"
        const val THEME_MODE_LIGHT: String = "light"
        const val THEME_MODE_DARK: String = "dark"
        val THEME_MODES: List<String> = listOf(THEME_MODE_SYSTEM, THEME_MODE_LIGHT, THEME_MODE_DARK)

        // The selectable late-send grace presets (minutes) in the order the settings dropdown
        // shows them; the first is also the default.
        val LATE_SEND_GRACE_PRESETS: List<Int> = listOf(15, 60, 240, 1_440, 10_080)
        const val DEFAULT_LATE_SEND_GRACE_MINUTES: Int = 15
    }
}

// The late-send grace in the unit the catch-up math works in (millis). An extension over the
// stored minutes so the conversion lives in one place (and tests stub the minutes only).
val AppPrefs.lateSendGraceMillis: Long
    get() = lateSendGraceMinutes * AppConfig.MINUTE_MS
