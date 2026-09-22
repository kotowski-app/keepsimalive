package app.kotowski.keepsimalive.util

import android.content.Context
import app.kotowski.keepsimalive.R
import java.time.Duration
import java.time.Instant

sealed class IntervalState {
    object Never : IntervalState()

    data class Phrase(
        val text: String,
    ) : IntervalState()

    data class Formatted(
        val text: String,
    ) : IntervalState()

    data class ExceedsThreshold(
        val timestamp: Instant,
    ) : IntervalState()
}

fun Instant?.toStandardInterval(
    context: Context,
    now: Instant = Instant.now(),
    maxDaysThreshold: Long = 7L,
    justNowText: String = context.getString(R.string.interval_just_now),
    inAMomentText: String = context.getString(R.string.interval_in_a_moment),
    formatPastMinutes: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.past_minutes, c, c) },
    formatPastHours: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.past_hours, c, c) },
    formatPastDays: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.past_days, c, c) },
    formatFutureMinutes: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.future_minutes, c, c) },
    formatFutureHours: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.future_hours, c, c) },
    formatFutureDays: (Int) -> String = { c -> context.resources.getQuantityString(R.plurals.future_days, c, c) },
): IntervalState {
    if (this == null) return IntervalState.Never

    val isPast = this.isBefore(now)
    val duration = Duration.between(this, now).abs()
    val totalSeconds = duration.seconds

    // Rounding to the nearest unit: add half the unit, then integer-divide by it.
    val days = (totalSeconds + AppConfig.SECONDS_PER_DAY / 2) / AppConfig.SECONDS_PER_DAY
    if (days > maxDaysThreshold) return IntervalState.ExceedsThreshold(this)
    if (days >= 1) {
        val formatted = if (isPast) formatPastDays(days.toInt()) else formatFutureDays(days.toInt())
        return IntervalState.Formatted(formatted)
    }

    val hours = (totalSeconds + AppConfig.SECONDS_PER_HOUR / 2) / AppConfig.SECONDS_PER_HOUR
    if (hours >= 1) {
        val formatted = if (isPast) formatPastHours(hours.toInt()) else formatFutureHours(hours.toInt())
        return IntervalState.Formatted(formatted)
    }

    val minutes = (totalSeconds + AppConfig.SECONDS_PER_MINUTE / 2) / AppConfig.SECONDS_PER_MINUTE
    if (minutes >= 1) {
        val formatted = if (isPast) formatPastMinutes(minutes.toInt()) else formatFutureMinutes(minutes.toInt())
        return IntervalState.Formatted(formatted)
    }

    // Sub-30s fallback: the totalSeconds == 0 case is sub-second in both directions, so
    // "just now" (past) vs "in a moment" (future) must key off isPast alone — a sub-second
    // future time rendered "just now" caused a visible flicker right before a scheduled send.
    val phrase = if (isPast) justNowText else inAMomentText
    return IntervalState.Phrase(phrase)
}

fun IntervalState.toDisplayString(
    context: Context,
    fallbackDate: String = "",
    neverText: String = context.getString(R.string.interval_never),
): String =
    when (this) {
        is IntervalState.Never -> neverText
        is IntervalState.Phrase -> text
        is IntervalState.Formatted -> text
        is IntervalState.ExceedsThreshold -> fallbackDate.ifEmpty { neverText }
    }

// The display form of the interval (toStandardInterval's result) with the app's default
// quantity-string formatters.
fun Instant?.toRelativeDisplay(
    context: Context,
    timestampMillis: Long? = this?.toEpochMilli(),
    now: Instant = Instant.now(),
    maxDaysThreshold: Long = 7L,
    justNowText: String = context.getString(R.string.interval_just_now),
    inAMomentText: String = context.getString(R.string.interval_in_a_moment),
    neverText: String = context.getString(R.string.interval_never),
): String {
    val state = this.toStandardInterval(context, now, maxDaysThreshold, justNowText, inAMomentText)
    return state.toDisplayString(
        context,
        fallbackDate = timestampMillis?.let { DateUtil.formatDate(context, it) } ?: "",
        neverText = neverText,
    )
}
