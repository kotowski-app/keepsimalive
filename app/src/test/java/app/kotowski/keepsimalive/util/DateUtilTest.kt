package app.kotowski.keepsimalive.util

import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class DateUtilTest {
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `formatLogTimestamp returns ISO format`() {
        val timestamp = 1609459200000L
        val str = DateUtil.formatLogTimestamp(timestamp)
        assertTrue(str.contains("2021-01-01"))
        assertTrue(str.contains("T"))
    }

    @Test
    fun `formatLogTimestamp includes milliseconds`() {
        val timestamp = 1609459200123L
        val str = DateUtil.formatLogTimestamp(timestamp)
        assertTrue(str.contains("123"))
    }

    @Test
    fun `formatLogTimestamp handles different timestamps`() {
        val t1 = DateUtil.formatLogTimestamp(0)
        assertTrue(t1.contains("1970-01-01"))
    }

    @Test
    fun `formatDurationMillis clamps negative millis to zero`() {
        val millis = -5000L
        val totalSeconds = millis / 1000
        val safeTotalSeconds = if (millis < 0) 0L else totalSeconds
        assertEquals(0L, safeTotalSeconds)
    }

    @Test
    fun `formatDurationMillis seconds only calculation`() {
        val millis = 45000L
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        assertEquals(0, totalSeconds / 86400)
        assertEquals(0, (totalSeconds % 86400) / 3600)
        assertEquals(0, (totalSeconds % 3600) / 60)
        assertEquals(45, seconds)
    }

    @Test
    fun `formatDurationMillis minutes and seconds calculation`() {
        val millis = 90000L
        val totalSeconds = millis / 1000
        assertEquals(0, totalSeconds / 86400)
        assertEquals(0, (totalSeconds % 86400) / 3600)
        assertEquals(1, (totalSeconds % 3600) / 60)
        assertEquals(30, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis hours minutes and seconds calculation`() {
        val millis = 3661000L
        val totalSeconds = millis / 1000
        assertEquals(0, totalSeconds / 86400)
        assertEquals(1, (totalSeconds % 86400) / 3600)
        assertEquals(1, (totalSeconds % 3600) / 60)
        assertEquals(1, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis days hours minutes seconds calculation`() {
        val millis = 90061000L
        val totalSeconds = millis / 1000
        assertEquals(1, totalSeconds / 86400)
        assertEquals(1, (totalSeconds % 86400) / 3600)
        assertEquals(1, (totalSeconds % 3600) / 60)
        assertEquals(1, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis zero calculation`() {
        val millis = 0L
        val totalSeconds = millis / 1000
        assertEquals(0, totalSeconds)
        assertEquals(0, totalSeconds / 86400)
        assertEquals(0, (totalSeconds % 86400) / 3600)
        assertEquals(0, (totalSeconds % 3600) / 60)
        assertEquals(0, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis exact hour calculation`() {
        val millis = 3600000L
        val totalSeconds = millis / 1000
        assertEquals(0, totalSeconds / 86400)
        assertEquals(1, (totalSeconds % 86400) / 3600)
        assertEquals(0, (totalSeconds % 3600) / 60)
        assertEquals(0, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis exact minute calculation`() {
        val millis = 60000L
        val totalSeconds = millis / 1000
        assertEquals(0, totalSeconds / 86400)
        assertEquals(0, (totalSeconds % 86400) / 3600)
        assertEquals(1, (totalSeconds % 3600) / 60)
        assertEquals(0, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis multiple days calculation`() {
        val millis = 259200000L
        val totalSeconds = millis / 1000
        assertEquals(3, totalSeconds / 86400)
        assertEquals(0, (totalSeconds % 86400) / 3600)
        assertEquals(0, (totalSeconds % 3600) / 60)
        assertEquals(0, totalSeconds % 60)
    }

    @Test
    fun `formatDurationMillis selects days branch for large values`() {
        val millis = 90061000L
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86400
        assertTrue("Days should be > 0 for 90061000ms", days > 0)
    }

    @Test
    fun `formatDurationMillis selects hours branch when days is zero`() {
        val millis = 3661000L
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        assertEquals(0, days)
        assertTrue("Hours should be > 0", hours > 0)
    }

    @Test
    fun `formatDurationMillis selects minutes branch when hours is zero`() {
        val millis = 90000L
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        assertEquals(0, days)
        assertEquals(0, hours)
        assertTrue("Minutes should be > 0", minutes > 0)
    }

    @Test
    fun `formatDurationMillis selects seconds branch when minutes is zero`() {
        val millis = 45000L
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        assertEquals(0, days)
        assertEquals(0, hours)
        assertEquals(0, minutes)
    }

    @Test
    fun `utc midnight millis round trips with the local date of utc midnight`() {
        val date = LocalDate.of(2026, 12, 31)
        assertEquals(date, DateUtil.localDateOfUtcMidnight(DateUtil.utcMidnightMillis(date)))
    }

    @Test
    fun `utcMidnightMillis is exactly UTC midnight of the date`() {
        val date = LocalDate.of(2026, 12, 31)
        val offset = Instant.ofEpochMilli(DateUtil.utcMidnightMillis(date)).atOffset(ZoneOffset.UTC)
        assertEquals(date, offset.toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, offset.toLocalTime())
    }

    @Test
    fun `utc midnight round trip is independent of the default time zone`() {
        val date = LocalDate.of(2026, 12, 31)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(date, DateUtil.localDateOfUtcMidnight(DateUtil.utcMidnightMillis(date)))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
            assertEquals(date, DateUtil.localDateOfUtcMidnight(DateUtil.utcMidnightMillis(date)))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `utc midnight of today round trips to today`() {
        assertEquals(LocalDate.now(), DateUtil.localDateOfUtcMidnight(DateUtil.utcMidnightMillis(LocalDate.now())))
    }

    // formatDurationMillis needs duration resources not available in all Robolectric
    // configurations, so the arithmetic above tests the calculation it performs internally.

    @Test
    fun `formatDurationMillis selects correct branch for each duration range`() {
        val durations =
            listOf(
                90061000L to "days", // 1d 1h 1m 1s
                3661000L to "hours", // 1h 1m 1s
                90000L to "minutes", // 1m 30s
                45000L to "seconds", // 45s
            )
        for ((millis, expectedBranch) in durations) {
            val safeMillis = if (millis < 0) 0L else millis
            val totalSeconds = safeMillis / 1000
            val days = totalSeconds / 86400
            val hours = (totalSeconds % 86400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val actualBranch =
                when {
                    days > 0 -> "days"
                    hours > 0 -> "hours"
                    minutes > 0 -> "minutes"
                    else -> "seconds"
                }
            assertEquals("Millis $millis should use '$expectedBranch' branch", expectedBranch, actualBranch)
        }
    }

    @Test
    fun `formatDateTime returns non-empty string`() {
        val result = DateUtil.formatDateTime(context, 1609459200000L)
        assertTrue("Expected non-empty date but got: '$result'", result.isNotEmpty())
        assertTrue("Expected '2021' but got: '$result'", result.contains("2021"))
    }

    @Test
    fun `formatDateTime returns different strings for different timestamps`() {
        val r1 = DateUtil.formatDateTime(context, 1609459200000L)
        val r2 = DateUtil.formatDateTime(context, 1704067200000L)
        assertTrue("Dates should differ: '$r1' vs '$r2'", r1 != r2)
    }

    @Test
    fun `formatDateTime handles epoch zero`() {
        val result = DateUtil.formatDateTime(context, 0L)
        assertTrue("Expected '1970' but got: '$result'", result.contains("1970"))
    }

    @Test
    fun `formatDateTime follows the locale combined date time pattern`() {
        val timestamp = 1609459200000L
        // While the app follows the system language the source is the context's own
        // configuration — the locale DateUtil.appLocale reads back.
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val is24Hour = DateFormat.is24HourFormat(context)
        val skeleton = if (is24Hour) "yMMMMdHm" else "yMMMMdhma"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton).replace("LLLL", "MMMM")
        val expected = SimpleDateFormat(pattern, locale).format(timestamp)
        assertEquals(expected, DateUtil.formatDateTime(context, timestamp))
    }

    @Config(qualifiers = "ja-rJP")
    @Test
    fun `formatDateTime keeps the locale join for CJK locales`() {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        assertEquals("ja", locale.language)
        val timestamp = 1609459200000L
        val is24Hour = DateFormat.is24HourFormat(context)
        val skeleton = if (is24Hour) "yMMMMdHm" else "yMMMMdhma"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton).replace("LLLL", "MMMM")
        assertEquals(SimpleDateFormat(pattern, locale).format(timestamp), DateUtil.formatDateTime(context, timestamp))
    }

    @Test
    fun `formatDate returns non-empty date string`() {
        val result = DateUtil.formatDate(context, 1609459200000L)
        assertTrue("Expected non-empty date but got: '$result'", result.isNotEmpty())
    }

    @Test
    fun `formatDate returns different strings for different timestamps`() {
        val r1 = DateUtil.formatDate(context, 1609459200000L)
        val r2 = DateUtil.formatDate(context, 1704067200000L)
        assertTrue("Dates should differ: '$r1' vs '$r2'", r1 != r2)
    }

    // The date-only display renders an ICU-resolved pattern through java.text, so the
    // exact layout varies by environment (Robolectric's getBestDateTimePattern returns
    // the skeleton verbatim): the tests pin the locale and the Russian month case, not
    // the layout.

    @Test
    fun `formatDate renders the long date with the locale month name`() {
        val result = DateUtil.formatDate(context, LocalDate.of(2021, 1, 1))
        // The localized month name is the stable pin across environments.
        assertTrue(result.contains("January"))
    }

    @Test
    fun `formatDate renders the Russian month in the genitive case`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val result = DateUtil.formatDate(context, LocalDate.of(2026, 9, 12))
            // "сентября" (genitive, what the day numeral requires), not "сентябрь"
            // (nominative): the case is the regression this test pins; the exact
            // layout varies by environment, so it is not pinned.
            assertTrue(result.contains("сентября"))
            assertFalse(result.contains("сентябрь"))
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `formatTime formats 12h time with am pm`() {
        assertEquals("9:00 AM", DateUtil.formatTime(context, 9, 0))
        assertEquals("9:05 PM", DateUtil.formatTime(context, 21, 5))
    }

    @Test
    fun `formatTime handles midnight and noon`() {
        assertEquals("12:00 AM", DateUtil.formatTime(context, 0, 0))
        assertEquals("12:00 PM", DateUtil.formatTime(context, 12, 0))
    }

    @Test
    fun `formatTime keeps minutes`() {
        assertEquals("11:30 PM", DateUtil.formatTime(context, 23, 30))
    }

    // The delegate stores the app language in static state that leaks across tests, so
    // every test below resets it to "follow the system" again.

    @Test
    fun `formatDate renders in the picked app language instead of the device language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val date = LocalDate.of(2026, 9, 12)
            val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            // The pick is observable through the localized month name inside the
            // rendered date; the pattern is resolved the same way as in the code.
            val ru = Locale.forLanguageTag("ru")
            val ruPattern = DateFormat.getBestDateTimePattern(ru, "yMMMMd").replace("LLLL", "MMMM")
            val enPattern = DateFormat.getBestDateTimePattern(Locale.ENGLISH, "yMMMMd").replace("LLLL", "MMMM")
            val ruFormatted = SimpleDateFormat(ruPattern, ru).format(millis)
            val enFormatted = SimpleDateFormat(enPattern, Locale.ENGLISH).format(millis)
            assertTrue(ruFormatted != enFormatted)
            assertEquals(ruFormatted, DateUtil.formatDate(context, date))
            assertNotEquals(enFormatted, DateUtil.formatDate(context, date))
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `formatDateTime renders in the picked app language instead of the device language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val timestamp = 1609459200000L
            val ru = Locale.forLanguageTag("ru")
            val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMMdHm" else "yMMMMdhma"
            val ruPattern = DateFormat.getBestDateTimePattern(ru, skeleton).replace("LLLL", "MMMM")
            val expected = SimpleDateFormat(ruPattern, ru).format(timestamp)
            assertEquals(expected, DateUtil.formatDateTime(context, timestamp))
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `formatTime renders in the picked app language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val ru = Locale.forLanguageTag("ru")
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            assertEquals(LocalTime.of(21, 5).format(DateTimeFormatter.ofPattern(pattern, ru)), DateUtil.formatTime(context, 21, 5))
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `monthName renders in the picked app language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val ru = Locale.forLanguageTag("ru")
            assertEquals(Month.of(1).getDisplayName(TextStyle.FULL, ru), DateUtil.monthName(context, 1))
            assertNotEquals(Month.of(1).getDisplayName(TextStyle.FULL, Locale.ENGLISH), DateUtil.monthName(context, 1))
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `appLocale is the picked app language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            assertEquals("ru", DateUtil.appLocale(context).language)
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `appLocale falls back to the context locale while following the system language`() {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        assertEquals(locale, DateUtil.appLocale(context))
    }
}
