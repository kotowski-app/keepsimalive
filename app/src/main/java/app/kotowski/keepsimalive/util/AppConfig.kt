package app.kotowski.keepsimalive.util

object AppConfig {
    // Shared unit conversions (millis and seconds) for the durations below and for display
    // math, so "one day" is never computed twice.
    const val MINUTE_MS: Long = 60_000L
    const val HOUR_MS: Long = 60 * MINUTE_MS
    const val DAY_MS: Long = 24 * HOUR_MS
    const val SECONDS_PER_MINUTE: Long = 60L
    const val SECONDS_PER_HOUR: Long = 60 * SECONDS_PER_MINUTE
    const val SECONDS_PER_DAY: Long = 24 * SECONDS_PER_HOUR

    // Maximum consecutive "SIM not present" failures across retries before the schedule is
    // auto-disabled with a notification (prevents infinite failure loops for removed SIMs).
    // 14 ~= 23 h of continuous absence on the current backoff curve: auto-disable fires on
    // the 14th failure, so worst case ~25 h with the ±10% jitter — tolerates hour-scale
    // SIM-off (e.g. roaming) while a genuinely removed SIM is still cleaned up within a day.
    const val MAX_SIM_NOT_PRESENT_FAILURES: Int = 14

    // How often the UI clock ticks while a screen is on screen, refreshing the relative-time and
    // countdown text (the tick stops off-screen, see rememberCurrentTime's repeatOnLifecycle).
    const val CURRENT_TIME_TICK_MS: Long = 1_000L

    // How often the UI re-reads the live SIM state while a screen is open, so a SIM switched
    // off/on in system settings never stays stale on screen.
    const val SIM_STATE_REFRESH_MS: Long = 5_000L

    // Off-schedule ("Send off schedule") one-off send: the user-confirmed send is armed this
    // long in the future instead of going out immediately, so the user can still cancel it
    // while it is pending. The regular send engine runs it on time, and the confirm dialog's
    // warning is formatted from this value — the delay is never hardcoded in the UI.
    const val OFF_SCHEDULE_SEND_DELAY_MS: Long = 10_000L

    // Fixed SMS character limit used in the editor to avoid charset detection complexity.
    const val SMS_MAX_CHARS: Int = 70

    // Retry policy: exponential backoff base (5s * 2^attempt), the fraction of the capped
    // backoff used as the jitter range, and the window from the first attempt during which
    // the occurrence keeps retrying before it fails for good.
    const val BASE_BACKOFF_MS: Long = 5_000L
    const val BACKOFF_JITTER_FRACTION: Double = 0.10
    const val MAX_RETRY_PERIOD_MS: Long = 14 * DAY_MS

    // Late-wake grace: the default of the user-configurable late-send grace (Settings,
    // "Late send grace"): how far past the scheduled time an occurrence is still attempted
    // before it is consumed as missed (WorkManager delay, battery optimization).
    const val GRACE_MILLIS: Long = 15 * MINUTE_MS

    // Radio send timeout: no answer this long is a permanent failure (a late response after
    // the retry would mean a duplicate send).
    const val SEND_TIMEOUT_MS: Long = 30_000L

    // A SENDING row older than this is no longer a live attempt (the process died mid-send):
    // SEND_TIMEOUT_MS + the sending hold must stay below it.
    const val SENDING_STALE_MS: Long = MINUTE_MS

    // Display hold after the radio call so the SENDING state is actually visible in the UI.
    const val DEFAULT_SENDING_HOLD_MS: Long = 3_000L

    // Safety sweep cadence: how often the periodic reconciler re-checks every schedule. 24 h
    // is a product choice, not a platform floor (WorkManager's periodic minimum is 15
    // minutes). The platform does not guarantee the interval: under battery optimization
    // (Doze, aggressive OEM power management) the effective cadence can be longer, so
    // "next 24 h sweep" is a best-effort bound.
    const val SAFETY_SWEEP_INTERVAL_HOURS: Long = 24L

    // Debug log ring size.
    const val LOG_BUFFER_MAX_ENTRIES: Int = 500

    // How long after the scheduled time the UI keeps the in-progress display (dashboard
    // spinner, "in 0s") before declaring the send late.
    const val IN_FLIGHT_GRACE_MS: Long = 5_000L

    // How many history rows the live window grows by: the history screen grows the window by
    // one page when the user scrolls to the end of the loaded list.
    const val HISTORY_PAGE_SIZE: Int = 20

    // Minimum time the History list's "loading more" spinner stays on screen while the
    // window grows: pure UX — the grow itself is a limit bump and the window's re-emit
    // lands immediately, the hold only keeps the growth visible to the user.
    const val HISTORY_LOAD_MORE_MIN_DELAY_MS: Long = 400

    // The cap on history rows the SIM-detail query loads (the newest ones): the DB query is
    // bounded, so a long-lived SIM's unbounded history is never fully materialized; the
    // header count comes from a COUNT query and stays exact.
    const val HISTORY_LOAD_LIMIT: Int = 1000

    // User-config validation bounds, shared by SimKeepaliveConfig.sanitize() and the editor
    // steppers.
    const val DAYS_INTERVAL_MIN: Int = 1
    const val DAYS_INTERVAL_MAX: Int = 365
    const val MONTHS_INTERVAL_MIN: Int = 1
    const val MONTHS_INTERVAL_MAX: Int = 12
    const val DAY_OF_MONTH_MIN: Int = 1
    const val DAY_OF_MONTH_MAX: Int = 31
    const val MAX_SENDS_MIN: Int = 1
    const val MAX_SENDS_MAX: Int = 999

    // Product defaults for a new keepalive.
    const val DEFAULT_HOUR: Int = 12
    const val DEFAULT_DAYS_INTERVAL: Int = 30
}
