package app.kotowski.keepsimalive.util

import android.icu.text.ListFormatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ScheduleSentenceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `sentence singular day with fixed time`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 1, hour = 9)
        assertEquals("Every day, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence every n days with fixed time`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 30, hour = 21)
        assertEquals("Every 30 days, at 9:00 PM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence every 2 days keeps the count in the plural branch`() {
        // daysInterval == 2 is the smallest value that takes the plural branch (1 uses the plain
        // "Every day" string); the count must appear, which a hardcoded/omitted singular would drop.
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 2, hour = 9)
        assertEquals("Every 2 days, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence small window shows between times`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 30, hour = 21, timeWindowMinutes = 5)
        assertEquals("Every 30 days, at a random time between 9:00 PM and 9:05 PM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence window crossing midnight adds the next day`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 23,
                minute = 30,
                timeWindowMinutes = 60,
            )
        assertEquals("Every 30 days, at a random time between 11:30 PM and 12:30 AM the next day", scheduleSentence(context, config))
    }

    @Test
    fun `sentence window ending exactly at midnight adds the next day`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 30, hour = 21, timeWindowMinutes = 180)
        assertEquals("Every 30 days, at a random time between 9:00 PM and 12:00 AM the next day", scheduleSentence(context, config))
    }

    @Test
    fun `sentence 24h window shows start and end as the same time the next day`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 365, hour = 21, timeWindowMinutes = 1440)
        assertEquals("Every 365 days, at a random time between 9:00 PM and 9:00 PM the next day", scheduleSentence(context, config))
    }

    @Test
    fun `every window step renders the correct sentence at 9am`() {
        val expected =
            mapOf(
                0 to "Every 30 days, at 9:00 AM",
                5 to "Every 30 days, at a random time between 9:00 AM and 9:05 AM",
                10 to "Every 30 days, at a random time between 9:00 AM and 9:10 AM",
                15 to "Every 30 days, at a random time between 9:00 AM and 9:15 AM",
                20 to "Every 30 days, at a random time between 9:00 AM and 9:20 AM",
                25 to "Every 30 days, at a random time between 9:00 AM and 9:25 AM",
                30 to "Every 30 days, at a random time between 9:00 AM and 9:30 AM",
                35 to "Every 30 days, at a random time between 9:00 AM and 9:35 AM",
                40 to "Every 30 days, at a random time between 9:00 AM and 9:40 AM",
                45 to "Every 30 days, at a random time between 9:00 AM and 9:45 AM",
                50 to "Every 30 days, at a random time between 9:00 AM and 9:50 AM",
                55 to "Every 30 days, at a random time between 9:00 AM and 9:55 AM",
                60 to "Every 30 days, at a random time between 9:00 AM and 10:00 AM",
                120 to "Every 30 days, at a random time between 9:00 AM and 11:00 AM",
                240 to "Every 30 days, at a random time between 9:00 AM and 1:00 PM",
                480 to "Every 30 days, at a random time between 9:00 AM and 5:00 PM",
                960 to "Every 30 days, at a random time between 9:00 AM and 1:00 AM the next day",
                1440 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM the next day",
                2880 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM 2 days later",
                5760 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM 4 days later",
                10080 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM 7 days later",
                20160 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM 14 days later",
                40320 to "Every 30 days, at a random time between 9:00 AM and 9:00 AM 28 days later",
            )
        assertEquals(expected.keys, TimeWindowSteps.STEPS.toSet())
        for ((window, sentence) in expected) {
            val config =
                SimKeepaliveConfig(
                    simId = 1,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 9,
                    timeWindowMinutes = window,
                )
            assertEquals("$window", sentence, scheduleSentence(context, config))
        }
    }

    @Test
    fun `window one minute past midnight uses the next day`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 181,
            )
        assertEquals("Every 30 days, at a random time between 9:00 PM and 12:01 AM the next day", scheduleSentence(context, config))
    }

    @Test
    fun `window ending just before two days uses the next day`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 1619,
            )
        assertEquals("Every 30 days, at a random time between 9:00 PM and 11:59 PM the next day", scheduleSentence(context, config))
    }

    @Test
    fun `window ending exactly two days later uses days later`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 1620,
            )
        assertEquals("Every 30 days, at a random time between 9:00 PM and 12:00 AM 2 days later", scheduleSentence(context, config))
    }

    @Test
    fun `midnight start uses the next day and two days correctly`() {
        val nextDay =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 0,
                timeWindowMinutes = 1440,
            )
        assertEquals("Every 30 days, at a random time between 12:00 AM and 12:00 AM the next day", scheduleSentence(context, nextDay))
        val twoDays =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 0,
                timeWindowMinutes = 2880,
            )
        assertEquals("Every 30 days, at a random time between 12:00 AM and 12:00 AM 2 days later", scheduleSentence(context, twoDays))
    }

    @Test
    fun `late night start with a four day window`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 23,
                minute = 30,
                timeWindowMinutes = 5760,
            )
        assertEquals("Every 30 days, at a random time between 11:30 PM and 11:30 PM 4 days later", scheduleSentence(context, config))
    }

    @Test
    fun `multi day window with odd end time`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 8,
                minute = 15,
                timeWindowMinutes = 21600,
            )
        assertEquals("Every 30 days, at a random time between 8:15 AM and 8:15 AM 15 days later", scheduleSentence(context, config))
    }

    @Test
    fun `max 28 day window at 9pm`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 40320,
            )
        assertEquals("Every 30 days, at a random time between 9:00 PM and 9:00 PM 28 days later", scheduleSentence(context, config))
    }

    @Test
    fun `monthly day 31 with a seven day window`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.MONTHLY,
                monthsInterval = 1,
                dayOfMonth = 31,
                hour = 21,
                timeWindowMinutes = 10080,
            )
        assertEquals(
            "Monthly on day 31 (last day of shorter months), at a random time between 9:00 PM and 9:00 PM 7 days later",
            scheduleSentence(context, config),
        )
    }

    @Test
    fun `multi day window with stops after sends clause`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 20160,
                endType = EndType.AFTER_N_SENDS,
                maxSends = 5,
            )
        assertEquals(
            "Every 30 days, at a random time between 9:00 PM and 9:00 PM 14 days later, stops after 5 sends",
            scheduleSentence(context, config),
        )
    }

    @Test
    fun `multi day window with on date end clause`() {
        val date = DateUtil.localDateOf(1759180800000L)
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                timeWindowMinutes = 20160,
                endType = EndType.ON_DATE,
                endDate = date,
            )
        val expected =
            context.getString(
                R.string.schedule_sentence,
                "Every 30 days",
                "at a random time between 9:00 PM and 9:00 PM 14 days later",
                " " + context.getString(R.string.schedule_until_date, DateUtil.formatDate(context, date)),
            )
        assertEquals(expected, scheduleSentence(context, config))
    }

    @Test
    fun `sentence after n sends end clause`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                endType = EndType.AFTER_N_SENDS,
                maxSends = 10,
            )
        assertEquals("Every 30 days, at 9:00 PM, stops after 10 sends", scheduleSentence(context, config))
    }

    @Test
    fun `sentence single send end clause is singular`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                endType = EndType.AFTER_N_SENDS,
                maxSends = 1,
            )
        assertEquals("Every 30 days, at 9:00 PM, stops after 1 send", scheduleSentence(context, config))
    }

    @Test
    fun `sentence monthly singular with day`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.MONTHLY, monthsInterval = 1, dayOfMonth = 5, hour = 9)
        assertEquals("Monthly on day 5, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence monthly plural with window crossing midnight`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.MONTHLY,
                monthsInterval = 2,
                dayOfMonth = 15,
                hour = 20,
                timeWindowMinutes = 540,
            )
        assertEquals(
            "Every 2 months on day 15, at a random time between 8:00 PM and 5:00 AM the next day",
            scheduleSentence(context, config),
        )
    }

    @Test
    fun `sentence midnight and noon use 12h am pm`() {
        val midnight = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 30, hour = 0)
        assertEquals("Every 30 days, at 12:00 AM", scheduleSentence(context, midnight))
        val noon = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.EVERY_N_DAYS, daysInterval = 30, hour = 12)
        assertEquals("Every 30 days, at 12:00 PM", scheduleSentence(context, noon))
    }

    @Test
    fun `sentence day 31 adds the short month qualifier`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.MONTHLY, monthsInterval = 1, dayOfMonth = 31, hour = 21)
        assertEquals("Monthly on day 31 (last day of shorter months), at 9:00 PM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence day 29 with plural months adds the short month qualifier`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.MONTHLY, monthsInterval = 2, dayOfMonth = 29, hour = 9)
        assertEquals("Every 2 months on day 29 (last day of shorter months), at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence day 28 has no short month qualifier`() {
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.MONTHLY, monthsInterval = 1, dayOfMonth = 28, hour = 9)
        assertEquals("Monthly on day 28, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence on date end uses device locale date`() {
        val date = DateUtil.localDateOf(1759180800000L)
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                endType = EndType.ON_DATE,
                endDate = date,
            )
        val expected =
            context.getString(
                R.string.schedule_sentence,
                "Every 30 days",
                "at 9:00 PM",
                " " + context.getString(R.string.schedule_until_date, DateUtil.formatDate(context, date)),
            )
        assertEquals(expected, scheduleSentence(context, config))
    }

    @Test
    fun `sentence on date without a date has no end clause`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                hour = 21,
                endType = EndType.ON_DATE,
                endDate = null,
            )
        assertEquals("Every 30 days, at 9:00 PM", scheduleSentence(context, config))
    }

    @Test
    fun `end reason after n sends`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.AFTER_N_SENDS, maxSends = 3)
        assertEquals("Schedule ended after 3 sends", endConditionReason(context, config))
    }

    @Test
    fun `end reason after a single send uses the singular`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.AFTER_N_SENDS, maxSends = 1)
        assertEquals("Schedule ended after 1 send", endConditionReason(context, config))
    }

    @Test
    fun `end reason on date uses device locale date`() {
        val date = DateUtil.localDateOf(1759180800000L)
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.ON_DATE, endDate = date)
        assertEquals(
            context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, date)),
            endConditionReason(context, config),
        )
    }

    @Test
    fun `end reason on date without a date is empty, never a fake date`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.ON_DATE, endDate = null)
        assertEquals("", endConditionReason(context, config))
    }

    @Test
    fun `end reason never is empty`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.NEVER)
        assertEquals("", endConditionReason(context, config))
    }

    @Test
    fun `sentence selected months lists the picked months in calendar order`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.SELECTED_MONTHS,
                dayOfMonth = 5,
                hour = 9,
                selectedMonths = setOf(12, 1, 3),
            )
        assertEquals("On day 5 of the following months: January, March, and December, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence selected months single month has no join`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.SELECTED_MONTHS, dayOfMonth = 5, hour = 9, selectedMonths = setOf(1))
        assertEquals("On day 5 of the following months: January, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence selected months two months are joined with and`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.SELECTED_MONTHS, dayOfMonth = 5, hour = 9, selectedMonths = setOf(1, 2))
        assertEquals("On day 5 of the following months: January and February, at 9:00 AM", scheduleSentence(context, config))
    }

    @Test
    fun `sentence selected months day 31 adds the short month qualifier`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.SELECTED_MONTHS,
                dayOfMonth = 31,
                hour = 9,
                selectedMonths = setOf(1, 2, 3),
            )
        assertEquals(
            "On day 31 of the following months: January, February, and March (last day of shorter months), at 9:00 AM",
            scheduleSentence(context, config),
        )
    }

    @Test
    fun `selected months list is joined in the app language, not the default locale`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        try {
            val config =
                SimKeepaliveConfig(
                    simId = 1,
                    freqType = FrequencyType.SELECTED_MONTHS,
                    dayOfMonth = 5,
                    hour = 9,
                    selectedMonths = setOf(1, 2),
                )
            val sentence = scheduleSentence(context, config)
            val ru = Locale.forLanguageTag("ru")
            val names = listOf(Month.of(1).getDisplayName(TextStyle.FULL, ru), Month.of(2).getDisplayName(TextStyle.FULL, ru))
            // The join must come from the app-language formatter: with a non-ru default
            // locale the default-locale formatter keeps the English "and".
            val ruList = ListFormatter.getInstance(ru).format(names)
            assertTrue("expected '$ruList' in '$sentence'", sentence.contains(ruList))
            val defaultList = ListFormatter.getInstance().format(names)
            if (defaultList != ruList) {
                assertFalse("expected no default-locale join in '$sentence'", sentence.contains(defaultList))
            }
        } finally {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }
}
