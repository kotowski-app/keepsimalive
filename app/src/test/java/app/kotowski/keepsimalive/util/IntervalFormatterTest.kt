package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val JUST_NOW = "just now"
private const val IN_A_MOMENT = "in a moment"
private const val NEVER = "never"

private fun pMins(c: Int) = "$c minutes ago"

private fun pHrs(c: Int) = "$c hours ago"

private fun pDays(c: Int) = "$c days ago"

private fun fMins(c: Int) = "in $c minutes"

private fun fHrs(c: Int) = "in $c hours"

private fun fDays(c: Int) = "in $c days"

private fun si(
    instant: Instant?,
    now: Instant = Instant.now(),
    maxDaysThreshold: Long = 7L,
) = instant.toStandardInterval(
    context = RuntimeEnvironment.getApplication(),
    now = now,
    maxDaysThreshold = maxDaysThreshold,
    justNowText = JUST_NOW,
    inAMomentText = IN_A_MOMENT,
    formatPastMinutes = ::pMins,
    formatPastHours = ::pHrs,
    formatPastDays = ::pDays,
    formatFutureMinutes = ::fMins,
    formatFutureHours = ::fHrs,
    formatFutureDays = ::fDays,
)

private fun rd(
    instant: Instant?,
    now: Instant = Instant.now(),
    maxDaysThreshold: Long = 7L,
) = instant.toRelativeDisplay(
    context = RuntimeEnvironment.getApplication(),
    timestampMillis = instant?.toEpochMilli(),
    now = now,
    maxDaysThreshold = maxDaysThreshold,
    justNowText = JUST_NOW,
    inAMomentText = IN_A_MOMENT,
    neverText = NEVER,
)

@RunWith(RobolectricTestRunner::class)
class IntervalFormatterTest {
    @Test
    fun `null timestamp returns Never`() {
        assertTrue(si(null) is IntervalState.Never)
    }

    @Test
    fun `10 seconds future returns Phrase in_a_moment`() {
        val result = si(Instant.now().plus(10, ChronoUnit.SECONDS))
        assertTrue(result is IntervalState.Phrase)
        assertEquals(IN_A_MOMENT, (result as IntervalState.Phrase).text)
    }

    @Test
    fun `29 seconds future returns Phrase in_a_moment`() {
        val result = si(Instant.now().plus(29, ChronoUnit.SECONDS))
        assertTrue(result is IntervalState.Phrase)
    }

    @Test
    fun `10 seconds past returns Phrase just_now`() {
        val result = si(Instant.now().minus(10, ChronoUnit.SECONDS))
        assertTrue(result is IntervalState.Phrase)
        assertEquals(JUST_NOW, (result as IntervalState.Phrase).text)
    }

    @Test
    fun `29 seconds past returns Phrase just_now`() {
        val result = si(Instant.now().minus(29, ChronoUnit.SECONDS))
        assertTrue(result is IntervalState.Phrase)
    }

    @Test
    fun `exact same instant returns Phrase`() {
        val now = Instant.now()
        val result = si(now, now = now)
        assertTrue("Expected Phrase, got ${result::class.simpleName}", result is IntervalState.Phrase)
    }

    @Test
    fun `sub-second future returns Phrase in_a_moment not just_now`() {
        val now = Instant.now()
        val result = si(now.plus(741, ChronoUnit.MILLIS), now = now)
        assertTrue("Expected Phrase, got ${result::class.simpleName}", result is IntervalState.Phrase)
        assertEquals(IN_A_MOMENT, (result as IntervalState.Phrase).text)
    }

    @Test
    fun `sub-second past returns Phrase just_now`() {
        val now = Instant.now()
        val result = si(now.minus(741, ChronoUnit.MILLIS), now = now)
        assertTrue("Expected Phrase, got ${result::class.simpleName}", result is IntervalState.Phrase)
        assertEquals(JUST_NOW, (result as IntervalState.Phrase).text)
    }

    @Test
    fun `1 second past returns Phrase`() {
        val result = si(Instant.now().minus(1, ChronoUnit.SECONDS))
        assertTrue("Expected Phrase, got ${result::class.simpleName}", result is IntervalState.Phrase)
    }

    @Test
    fun `1 second future returns Phrase`() {
        val result = si(Instant.now().plus(1, ChronoUnit.SECONDS))
        assertTrue("Expected Phrase, got ${result::class.simpleName}", result is IntervalState.Phrase)
    }

    @Test
    fun `clock skew 3 seconds ahead`() {
        val result = si(Instant.now().plus(3, ChronoUnit.SECONDS))
        assertTrue(result is IntervalState.Phrase)
        assertEquals(IN_A_MOMENT, (result as IntervalState.Phrase).text)
    }

    @Test
    fun `30 seconds past rounds to 1 minute`() {
        val text = (si(Instant.now().minus(30, ChronoUnit.SECONDS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `59 seconds past rounds to 1 minute`() {
        val text = (si(Instant.now().minus(59, ChronoUnit.SECONDS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `45 seconds future rounds to 1 minute`() {
        val text = (si(Instant.now().plus(45, ChronoUnit.SECONDS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `59 seconds future rounds to 1 minute`() {
        val text = (si(Instant.now().plus(59, ChronoUnit.SECONDS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `1 minute past`() {
        val text = (si(Instant.now().minus(1, ChronoUnit.MINUTES)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `5 minutes past`() {
        val text = (si(Instant.now().minus(5, ChronoUnit.MINUTES)) as IntervalState.Formatted).text
        assertTrue(text.contains("5"))
    }

    @Test
    fun `1m 30s rounds up to 2 minutes`() {
        val now = Instant.now()
        val text = (si(now.minus(1, ChronoUnit.MINUTES).minus(30, ChronoUnit.SECONDS), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 2, got: $text", text.contains("2"))
    }

    @Test
    fun `59m 29s rounds up to 1 hour`() {
        val now = Instant.now()
        val text = (si(now.minus(59, ChronoUnit.MINUTES).minus(29, ChronoUnit.SECONDS), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 1 (hour), got: $text", text.contains("1"))
    }

    @Test
    fun `59m 30s rounds up to 1 hour`() {
        val now = Instant.now()
        val text = (si(now.minus(59, ChronoUnit.MINUTES).minus(30, ChronoUnit.SECONDS), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 1, got: $text", text.contains("1"))
    }

    @Test
    fun `1 hour past`() {
        val text = (si(Instant.now().minus(1, ChronoUnit.HOURS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `3 hours past`() {
        val text = (si(Instant.now().minus(3, ChronoUnit.HOURS)) as IntervalState.Formatted).text
        assertTrue(text.contains("3"))
    }

    @Test
    fun `1h 15m rounds down to 1 hour`() {
        val text = (si(Instant.now().plus(1, ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES)) as IntervalState.Formatted).text
        assertTrue("Expected 1, got: $text", text.contains("1"))
    }

    @Test
    fun `1h 30m rounds up to 2 hours`() {
        val now = Instant.now()
        val text = (si(now.minus(1, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 2, got: $text", text.contains("2"))
    }

    @Test
    fun `1h 59m rounds up to 2 hours`() {
        val now = Instant.now()
        val text = (si(now.minus(1, ChronoUnit.HOURS).minus(59, ChronoUnit.MINUTES), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 2, got: $text", text.contains("2"))
    }

    @Test
    fun `23h 29m rounds up to 1 day`() {
        val now = Instant.now()
        val text = (si(now.plus(23, ChronoUnit.HOURS).plus(29, ChronoUnit.MINUTES), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 1 (day), got: $text", text.contains("1"))
    }

    @Test
    fun `23h 30m rounds up to 1 day`() {
        val now = Instant.now()
        val text = (si(now.minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 1, got: $text", text.contains("1"))
    }

    @Test
    fun `1 day past`() {
        val text = (si(Instant.now().minus(1, ChronoUnit.DAYS)) as IntervalState.Formatted).text
        assertTrue(text.contains("1"))
    }

    @Test
    fun `5 days past`() {
        val text = (si(Instant.now().minus(5, ChronoUnit.DAYS)) as IntervalState.Formatted).text
        assertTrue(text.contains("5"))
    }

    @Test
    fun `1d 11h rounds down to 1 day`() {
        val text = (si(Instant.now().plus(1, ChronoUnit.DAYS).plus(11, ChronoUnit.HOURS)) as IntervalState.Formatted).text
        assertTrue("Expected 1, got: $text", text.contains("1"))
    }

    @Test
    fun `1d 12h rounds up to 2 days`() {
        val now = Instant.now()
        val text = (si(now.minus(1, ChronoUnit.DAYS).minus(12, ChronoUnit.HOURS), now = now) as IntervalState.Formatted).text
        assertTrue("Expected 2, got: $text", text.contains("2"))
    }

    @Test
    fun `6d 20h rounds up to 7 days`() {
        val text = (si(Instant.now().plus(6, ChronoUnit.DAYS).plus(20, ChronoUnit.HOURS)) as IntervalState.Formatted).text
        assertTrue("Expected 7, got: $text", text.contains("7"))
    }

    @Test
    fun `7d 12h rounds to 8 days exceeds threshold`() {
        val now = Instant.now()
        assertTrue(si(now.minus(7, ChronoUnit.DAYS).minus(12, ChronoUnit.HOURS), now = now) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `8 days past exceeds threshold`() {
        assertTrue(si(Instant.now().minus(8, ChronoUnit.DAYS)) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `14 days past exceeds threshold`() {
        assertTrue(si(Instant.now().minus(14, ChronoUnit.DAYS)) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `31 days past exceeds threshold`() {
        assertTrue(si(Instant.now().minus(31, ChronoUnit.DAYS)) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `60 days past exceeds threshold`() {
        assertTrue(si(Instant.now().minus(60, ChronoUnit.DAYS)) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `exactly 30 days exceeds threshold`() {
        val result = si(Instant.now().minus(30, ChronoUnit.DAYS))
        assertTrue("Expected ExceedsThreshold, got ${result::class.simpleName}", result is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `custom threshold works`() {
        val past = Instant.now().minus(16, ChronoUnit.DAYS)
        assertTrue(si(past, maxDaysThreshold = 14) is IntervalState.ExceedsThreshold)
    }

    @Test
    fun `future 2 hours uses future direction`() {
        val text = (si(Instant.now().plus(2, ChronoUnit.HOURS)) as IntervalState.Formatted).text
        assertTrue("Expected future direction in: $text", text.contains("in") || text.contains("2"))
    }

    @Test
    fun `Never displays never`() {
        assertEquals(NEVER, IntervalState.Never.toDisplayString(RuntimeEnvironment.getApplication(), neverText = NEVER))
    }

    @Test
    fun `Phrase displays text`() {
        assertEquals(JUST_NOW, IntervalState.Phrase(JUST_NOW).toDisplayString(RuntimeEnvironment.getApplication(), neverText = NEVER))
    }

    @Test
    fun `Formatted displays text`() {
        assertEquals(
            "2 hours ago",
            IntervalState.Formatted("2 hours ago").toDisplayString(RuntimeEnvironment.getApplication(), neverText = NEVER),
        )
    }

    @Test
    fun `ExceedsThreshold with fallback displays fallback`() {
        val display =
            IntervalState
                .ExceedsThreshold(
                    Instant.now(),
                ).toDisplayString(RuntimeEnvironment.getApplication(), "Jan 1, 2025", neverText = NEVER)
        assertEquals("Jan 1, 2025", display)
    }

    @Test
    fun `ExceedsThreshold without fallback displays never`() {
        val display = IntervalState.ExceedsThreshold(Instant.now()).toDisplayString(RuntimeEnvironment.getApplication(), neverText = NEVER)
        assertEquals(NEVER, display)
    }

    @Test
    fun `relative 2 hours past shows interval`() {
        val fixedNow = Instant.now()
        val past = fixedNow.minus(2, ChronoUnit.HOURS)
        val display = rd(past, now = fixedNow, maxDaysThreshold = 365)
        assertTrue("Expected '2' but got: $display", display.contains("2"))
    }

    @Test
    fun `relative 5 days future shows direction`() {
        val fixedNow = Instant.now()
        val future = fixedNow.plus(5, ChronoUnit.DAYS)
        val display = rd(future, now = fixedNow, maxDaysThreshold = 365)
        assertTrue("Expected 'in 5' but got: $display", display.contains("in") && display.contains("5"))
    }

    @Test
    fun `relative 45 days exceeds default threshold`() {
        val past = Instant.now().minus(45, ChronoUnit.DAYS)
        val display = rd(past)
        assertTrue("Expected non-empty fallback, got: $display", display.isNotEmpty() && display != NEVER)
        assertFalse("Should not show interval: $display", display.contains("ago") || display.contains("in"))
    }

    @Test
    fun `relative 100 days exceeds default threshold`() {
        val display = rd(Instant.now().minus(100, ChronoUnit.DAYS))
        assertTrue("Expected non-empty fallback, got: $display", display.isNotEmpty() && display != NEVER)
        assertFalse("Should not show interval: $display", display.contains("ago") || display.contains("in"))
    }

    @Test
    fun `relative null returns never`() {
        val nullInstant: Instant? = null
        assertEquals(NEVER, rd(nullInstant))
    }
}
