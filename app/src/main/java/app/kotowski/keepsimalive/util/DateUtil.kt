package app.kotowski.keepsimalive.util

import android.content.Context
import android.text.format.DateFormat
import app.kotowski.keepsimalive.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object DateUtil {
    private val englishFormatter24 = DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm", Locale.ENGLISH)
    private val englishFormatter12 = DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.ENGLISH)
    private val englishDateOnly = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    private val englishTime24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val englishTime12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)

    // The locale the dates render in: the app language picked in Settings (the passed
    // context already carries it) or, while the app follows the system language, the device
    // locale; an empty (theoretical) locale list degrades to the default locale.
    internal fun appLocale(context: Context): Locale {
        val config = context.appLocaleContext().resources.configuration
        return config.locales[0] ?: Locale.getDefault()
    }

    // The date/datetime pattern from the platform's ICU for the locale (the same data the
    // system's own formatting uses — Russian: the genitive "12 сентября"), with the LLLL ->
    // MMMM repair: older CLDR snapshots synthesize the stand-alone month letter, which is
    // wrong next to a day numeral. The rendering must go through java.text, which the
    // framework backs with that same ICU data — the platform's java.time carries a stale
    // CLDR snapshot that still renders Russian months in the nominative case ("12 сентябрь"),
    // so it must not format the month letters. A lookup failure throws; the caller degrades
    // to its fixed English formatter.
    private fun icuPattern(
        locale: Locale,
        skeleton: String,
    ): String = DateFormat.getBestDateTimePattern(locale, skeleton).replace("LLLL", "MMMM")

    // The localized full month name ("January" in an English locale): the platform formats
    // it for the app's locale, so no per-locale strings are needed.
    fun monthName(
        context: Context,
        month: Int,
    ): String = Month.of(month).getDisplayName(TextStyle.FULL, appLocale(context))

    fun formatDurationMillis(
        context: Context,
        millis: Long,
    ): String {
        val safeMillis = if (millis < 0) 0L else millis
        val totalSeconds = safeMillis / 1000
        val days = totalSeconds / AppConfig.SECONDS_PER_DAY
        val hours = (totalSeconds % AppConfig.SECONDS_PER_DAY) / AppConfig.SECONDS_PER_HOUR
        val minutes = (totalSeconds % AppConfig.SECONDS_PER_HOUR) / AppConfig.SECONDS_PER_MINUTE
        val seconds = totalSeconds % AppConfig.SECONDS_PER_MINUTE

        return when {
            days > 0 -> context.getString(R.string.duration_days, days, hours, minutes, seconds)
            hours > 0 -> context.getString(R.string.duration_hours, hours, minutes, seconds)
            minutes > 0 -> context.getString(R.string.duration_minutes, minutes, seconds)
            else -> context.getString(R.string.duration_seconds, seconds)
        }
    }

    // The pattern comes from icuPattern; the 12/24-hour choice comes from the system
    // setting via the skeleton.
    fun formatDateTime(
        context: Context,
        timestamp: Long,
    ): String {
        val locale = appLocale(context)
        return try {
            val is24Hour = DateFormat.is24HourFormat(context)
            val skeleton = if (is24Hour) "yMMMMdHm" else "yMMMMdhma"
            val pattern = icuPattern(locale, skeleton)
            SimpleDateFormat(pattern, locale).format(timestamp)
        } catch (e: Exception) {
            val fallback = if (DateFormat.is24HourFormat(context)) englishFormatter24 else englishFormatter12
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(fallback)
        }
    }

    fun formatTime(
        context: Context,
        hour: Int,
        minute: Int,
    ): String {
        val time = LocalTime.of(hour, minute)
        val locale = appLocale(context)
        return try {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            time.format(DateTimeFormatter.ofPattern(pattern, locale))
        } catch (e: Exception) {
            val fallback = if (DateFormat.is24HourFormat(context)) englishTime24 else englishTime12
            time.format(fallback)
        }
    }

    fun formatDate(
        context: Context,
        timestamp: Long,
    ): String = formatDate(context, localDateOf(timestamp))

    // The pattern comes from icuPattern (see it for why the rendering goes through java.text).
    fun formatDate(
        context: Context,
        date: LocalDate,
    ): String {
        val locale = appLocale(context)
        return try {
            val pattern = icuPattern(locale, "yMMMMd")
            SimpleDateFormat(pattern, locale).format(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } catch (e: Exception) {
            date.format(englishDateOnly)
        }
    }

    // The Material3 date picker represents dates as UTC-midnight timestamps, so a
    // local-midnight bridge would shift the picked day by one in non-UTC zones.
    fun utcMidnightMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun localDateOfUtcMidnight(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun localDateOf(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    fun formatLogTimestamp(timestamp: Long): String {
        val zdt = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return zdt.format(logTimestampFormatter)
    }
}
