package app.kotowski.keepsimalive.schedule

import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.random.Random

class ScheduleCalculatorTest {
    @Test
    fun `firstOccurrence every n days returns today when time is still ahead`() {
        val result = ScheduleCalculator.firstOccurrence(everyNDays(hour = 14), zdt(2026, 8, 16, 10, 0))
        assertEquals(zdt(2026, 8, 16, 14, 0), result)
    }

    @Test
    fun `firstOccurrence every n days returns tomorrow when time has passed`() {
        val result = ScheduleCalculator.firstOccurrence(everyNDays(hour = 14), zdt(2026, 8, 16, 15, 0))
        assertEquals(zdt(2026, 8, 17, 14, 0), result)
    }

    @Test
    fun `firstOccurrence every n days returns tomorrow when now equals scheduled time`() {
        val result = ScheduleCalculator.firstOccurrence(everyNDays(hour = 14), zdt(2026, 8, 16, 14, 0))
        assertEquals(zdt(2026, 8, 17, 14, 0), result)
    }

    @Test
    fun `firstOccurrence monthly returns next month when day already passed`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 15), zdt(2026, 8, 20, 10, 0))
        assertEquals(zdt(2026, 9, 15, 10, 0), result)
    }

    @Test
    fun `firstOccurrence monthly returns same month when day still ahead`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 15), zdt(2026, 8, 10, 10, 0))
        assertEquals(zdt(2026, 8, 15, 10, 0), result)
    }

    @Test
    fun `firstOccurrence monthly returns day 31 of current month when ahead`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 31), zdt(2026, 3, 20, 10, 0))
        assertEquals(zdt(2026, 3, 31, 10, 0), result)
    }

    @Test
    fun `firstOccurrence monthly clamps day 31 to April 30`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 31), zdt(2026, 4, 20, 10, 0))
        assertEquals(zdt(2026, 4, 30, 10, 0), result)
    }

    @Test
    fun `firstOccurrence monthly advances past clamped day of current month`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 31), zdt(2026, 4, 30, 11, 0))
        assertEquals(zdt(2026, 5, 31, 10, 0), result)
    }

    @Test
    fun `firstOccurrence monthly returns clamped day of current month when ahead`() {
        val result = ScheduleCalculator.firstOccurrence(monthlyConfig(hour = 10, dayOfMonth = 31), zdt(2026, 1, 15, 10, 0))
        assertEquals(zdt(2026, 1, 31, 10, 0), result)
    }

    @Test
    fun `nextOccurrence every n days adds interval keeping local time`() {
        val result = ScheduleCalculator.nextOccurrence(everyNDays(hour = 14, daysInterval = 30), zdt(2026, 8, 16, 14, 0))
        assertEquals(zdt(2026, 9, 15, 14, 0), result)
    }

    @Test
    fun `nextOccurrence every n days snaps to the configured time not the last send time`() {
        val result = ScheduleCalculator.nextOccurrence(everyNDays(hour = 19, minute = 0, daysInterval = 1), zdt(2026, 8, 16, 17, 45))
        assertEquals(zdt(2026, 8, 17, 19, 0), result)
    }

    @Test
    fun `nextOccurrence every n days is dst safe in berlin zone`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val result = ScheduleCalculator.nextOccurrence(everyNDays(hour = 10, daysInterval = 1), zdt(2026, 3, 27, 10, 0, berlin))
        assertEquals(2026, result.year)
        assertEquals(3, result.monthValue)
        assertEquals(28, result.dayOfMonth)
        assertEquals(10, result.hour)
        assertEquals(0, result.minute)
        assertEquals(berlin, result.zone)
    }

    @Test
    fun `nextOccurrence after a max-window send keeps the configured rhythm`() {
        // Regression for the midnight-crossing drift: the selectable window is capped at the
        // minutes left in the day (see SimKeepaliveConfig.maxTimeWindowMinutes), so even the
        // largest roll of a late base keeps the send on the base's date and the next
        // occurrence stays at base + interval instead of drifting a day forward.
        val evening = everyNDays(hour = 23, minute = 30, daysInterval = 1)
        val eveningBase = zdt(2026, 8, 16, 23, 30)
        val eveningRoll =
            eveningBase.plusMinutes(
                SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 1, 1, 23, 30).toLong(),
            )
        assertEquals(eveningBase.toLocalDate(), eveningRoll.toLocalDate())
        assertEquals(zdt(2026, 8, 17, 23, 30), ScheduleCalculator.nextOccurrence(evening, eveningRoll))

        val midday = everyNDays(hour = 12, minute = 0, daysInterval = 30)
        val middayBase = zdt(2026, 8, 16, 12, 0)
        val middayRoll =
            middayBase.plusMinutes(
                SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 30, 1, 12, 0).toLong(),
            )
        assertEquals(middayBase.toLocalDate(), middayRoll.toLocalDate())
        assertEquals(zdt(2026, 9, 15, 12, 0), ScheduleCalculator.nextOccurrence(midday, middayRoll))
    }

    @Test
    fun `nextOccurrence monthly clamps day 31 to February 28 in non leap year`() {
        val result =
            ScheduleCalculator.nextOccurrence(
                monthlyConfig(hour = 10, monthsInterval = 1, dayOfMonth = 31),
                zdt(2026, 1, 31, 10, 0),
            )
        assertEquals(zdt(2026, 2, 28, 10, 0), result)
    }

    @Test
    fun `nextOccurrence monthly restores configured day after clamping`() {
        val result =
            ScheduleCalculator.nextOccurrence(
                monthlyConfig(hour = 10, monthsInterval = 1, dayOfMonth = 31),
                zdt(2026, 2, 28, 10, 0),
            )
        assertEquals(zdt(2026, 3, 31, 10, 0), result)
    }

    @Test
    fun `nextOccurrence monthly applies two month interval`() {
        val result =
            ScheduleCalculator.nextOccurrence(
                monthlyConfig(hour = 10, monthsInterval = 2, dayOfMonth = 15),
                zdt(2026, 4, 15, 10, 0),
            )
        assertEquals(zdt(2026, 6, 15, 10, 0), result)
    }

    @Test
    fun `withWindow zero returns base unchanged`() {
        val base = zdt(2026, 8, 16, 14, 0)
        assertEquals(base, ScheduleCalculator.withWindow(base, 0, random = Random(42)))
    }

    @Test
    fun `withWindow with fixed seed is deterministic and within range`() {
        val base = zdt(2026, 8, 16, 14, 0)
        val first = ScheduleCalculator.withWindow(base, 60, random = Random(42))
        val second = ScheduleCalculator.withWindow(base, 60, random = Random(42))
        assertEquals(first, second)
        assertTrue(!first.isBefore(base))
        assertTrue(!first.isAfter(base.plusMinutes(60)))
    }

    @Test
    fun `withWindow 1440 stays within 24 hours`() {
        val base = zdt(2026, 8, 16, 14, 0)
        repeat(20) { seed ->
            val result = ScheduleCalculator.withWindow(base, 1440, random = Random(seed.toLong()))
            assertTrue(!result.isBefore(base))
            assertTrue(!result.isAfter(base.plusDays(1)))
        }
    }

    @Test
    fun `withWindow 40320 stays within 28 days`() {
        val base = zdt(2026, 8, 16, 14, 0)
        repeat(20) { seed ->
            val result = ScheduleCalculator.withWindow(base, 40320, random = Random(seed.toLong()))
            assertTrue(!result.isBefore(base))
            assertTrue(!result.isAfter(base.plusDays(28)))
        }
    }

    @Test
    fun `withWindow with an end date far ahead rolls exactly like no bound`() {
        val base = zdt(2026, 8, 16, 14, 0)
        val farEndDate = LocalDate.of(2027, 1, 1)
        repeat(20) { seed ->
            val unbounded = ScheduleCalculator.withWindow(base, 40320, random = Random(seed.toLong()))
            val bounded = ScheduleCalculator.withWindow(base, 40320, farEndDate, random = Random(seed.toLong()))
            assertEquals(unbounded, bounded)
        }
    }

    @Test
    fun `withWindow caps the roll at the end date`() {
        val base = zdt(2026, 8, 16, 14, 0)
        val endDate = LocalDate.of(2026, 8, 20)
        var cappedObserved = false
        repeat(100) { seed ->
            val unbounded = ScheduleCalculator.withWindow(base, 40320, random = Random(seed.toLong()))
            val bounded = ScheduleCalculator.withWindow(base, 40320, endDate, random = Random(seed.toLong()))
            assertTrue(!bounded.isBefore(base))
            assertTrue(bounded.toLocalDate() <= endDate)
            cappedObserved = cappedObserved || bounded.isBefore(unbounded)
        }
        // At least one roll must have been truncated, otherwise the cap is not exercised.
        assertTrue(cappedObserved)
    }

    @Test
    fun `withWindow with the base on the end date never crosses it`() {
        val base = zdt(2026, 8, 20, 9, 0)
        val endDate = LocalDate.of(2026, 8, 20)
        repeat(100) { seed ->
            val result = ScheduleCalculator.withWindow(base, 40320, endDate, random = Random(seed.toLong()))
            assertTrue(!result.isBefore(base))
            assertEquals(endDate, result.toLocalDate())
        }
    }

    @Test
    fun `withWindow with the base past the end date returns base unchanged`() {
        val base = zdt(2026, 8, 21, 9, 0)
        assertEquals(base, ScheduleCalculator.withWindow(base, 40320, LocalDate.of(2026, 8, 20), random = Random(42)))
    }

    @Test
    fun `withWindow with the base at the end of the end date day returns base unchanged`() {
        val base = zdt(2026, 8, 20, 23, 59)
        assertEquals(base, ScheduleCalculator.withWindow(base, 40320, LocalDate.of(2026, 8, 20), random = Random(42)))
    }

    @Test
    fun `resolveCatchUp keeps occurrence within grace`() {
        val now = zdt(2026, 8, 16, 14, 10)
        val scheduled = zdt(2026, 8, 16, 14, 0)
        val result = ScheduleCalculator.resolveCatchUp(everyNDays(hour = 14, daysInterval = 30), scheduled, now)
        assertEquals(scheduled, result.sendAt)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `resolveCatchUp skips occurrence far in the past`() {
        val now = zdt(2026, 8, 16, 16, 0)
        val scheduled = zdt(2026, 8, 16, 14, 0)
        val result = ScheduleCalculator.resolveCatchUp(everyNDays(hour = 14, daysInterval = 30), scheduled, now)
        assertEquals(listOf(scheduled), result.skipped)
        assertEquals(zdt(2026, 9, 15, 14, 0), result.sendAt)
        assertTrue(!result.sendAt.isBefore(now.minusMinutes(15)))
    }

    @Test
    fun `resolveCatchUp skips multiple late daily occurrences`() {
        val now = zdt(2026, 8, 16, 12, 0)
        val scheduled = zdt(2026, 8, 14, 13, 0)
        val result = ScheduleCalculator.resolveCatchUp(everyNDays(hour = 13, daysInterval = 1), scheduled, now)
        assertEquals(listOf(zdt(2026, 8, 14, 13, 0), zdt(2026, 8, 15, 13, 0)), result.skipped)
        assertEquals(zdt(2026, 8, 16, 13, 0), result.sendAt)
        assertTrue(result.sendAt.isAfter(now))
    }

    @Test
    fun `resolveCatchUp does not skip at exact grace boundary`() {
        val now = zdt(2026, 8, 16, 14, 15)
        val scheduled = zdt(2026, 8, 16, 14, 0)
        val result = ScheduleCalculator.resolveCatchUp(everyNDays(hour = 14, daysInterval = 30), scheduled, now)
        assertEquals(scheduled, result.sendAt)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `hasMoreOccurrence never end type is always true`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.NEVER)
        assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 1, 1, 12, 0)))
        assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 999, zdt(2026, 1, 1, 12, 0)))
    }

    @Test
    fun `hasMoreOccurrence after n sends compares sent count against max`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3)
        val last = zdt(2026, 9, 10, 12, 0)
        assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 2, last))
        assertTrue(!ScheduleCalculator.hasMoreOccurrence(config, 3, last))
    }

    @Test
    fun `hasMoreOccurrence on date true when next occurrence before end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 5, 12, 0)))
    }

    @Test
    fun `hasMoreOccurrence on date false when next occurrence after end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(!ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 15, 12, 0)))
    }

    @Test
    fun `hasMoreOccurrence on date true when next occurrence on end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 10, 12, 0)))
    }

    @Test
    fun `isAfterEndDate is false for non date end types`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.NEVER)
        assertTrue(!ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
    }

    @Test
    fun `isAfterEndDate is false on and before the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(!ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 5, 12, 0, ZoneId.systemDefault())))
        assertTrue(!ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 15, 23, 59, ZoneId.systemDefault())))
    }

    @Test
    fun `isAfterEndDate is true after the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 16, 0, 0, ZoneId.systemDefault())))
    }

    @Test
    fun `isEndConditionReached never end type is false`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.NEVER, sendCount = 999, maxSends = 1)
        assertTrue(!ScheduleCalculator.isEndConditionReached(config, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
    }

    @Test
    fun `isEndConditionReached after n sends compares send count against max`() {
        val notReached = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 2)
        val reached = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 3)
        val at = zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())
        assertTrue(!ScheduleCalculator.isEndConditionReached(notReached, at))
        assertTrue(ScheduleCalculator.isEndConditionReached(reached, at))
    }

    @Test
    fun `isEndConditionReached on date follows the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(!ScheduleCalculator.isEndConditionReached(config, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
        assertTrue(ScheduleCalculator.isEndConditionReached(config, zdt(2026, 9, 16, 12, 0, ZoneId.systemDefault())))
    }

    @Test
    fun `endState never end type is always running`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.NEVER, sendCount = 999, maxSends = 1)
        val now = zdt(2026, 1, 1, 12, 0)
        assertTrue(ScheduleCalculator.endState(config, null, now) is ScheduleEndState.Running)
        assertTrue(ScheduleCalculator.endState(config, zdt(2020, 1, 1, 12, 0).toInstant().toEpochMilli(), now) is ScheduleEndState.Running)
    }

    @Test
    fun `endState after n sends is ended only when the limit is consumed`() {
        val now = zdt(2026, 1, 1, 12, 0)
        val under = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 2)
        val at = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 3)
        val over = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 4)
        assertTrue(ScheduleCalculator.endState(under, null, now) is ScheduleEndState.Running)
        assertTrue(ScheduleCalculator.endState(at, null, now) !is ScheduleEndState.Running)
        assertTrue(ScheduleCalculator.endState(over, null, now) !is ScheduleEndState.Running)
    }

    @Test
    fun `endState on date is running while an occurrence remains on or before the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 10, 12, 0)
        assertTrue(
            ScheduleCalculator.endState(
                config,
                lastSent.toInstant().toEpochMilli(),
                zdt(2026, 9, 12, 12, 0),
            ) is ScheduleEndState.Running,
        )
    }

    @Test
    fun `endState on date is running when catch-up lands on the inclusive end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 5, 12, 0)
        // Catch-up skips 09-10 (past the grace) and lands on the end date 09-15 itself:
        // one send is still possible, so the schedule is not over.
        assertTrue(
            ScheduleCalculator.endState(
                config,
                lastSent.toInstant().toEpochMilli(),
                zdt(2026, 9, 12, 12, 0),
            ) is ScheduleEndState.Running,
        )
    }

    @Test
    fun `endState on date is ended once the last send was on the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 15, 12, 0)
        assertTrue(
            ScheduleCalculator.endState(
                config,
                lastSent.toInstant().toEpochMilli(),
                zdt(2026, 9, 20, 12, 0),
            ) !is ScheduleEndState.Running,
        )
    }

    @Test
    fun `endState on date is ended when catch-up skips to past the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 5, 12, 0)
        // Catch-up skips 09-10 and 09-15 (both past the grace) and lands on 09-20, past the end.
        assertTrue(
            ScheduleCalculator.endState(
                config,
                lastSent.toInstant().toEpochMilli(),
                zdt(2026, 9, 20, 12, 0),
            ) !is ScheduleEndState.Running,
        )
    }

    @Test
    fun `endState on date without a last send uses the first occurrence`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        assertTrue(ScheduleCalculator.endState(config, null, zdt(2026, 9, 10, 12, 0)) is ScheduleEndState.Running)
        assertTrue(ScheduleCalculator.endState(config, null, zdt(2026, 9, 20, 12, 0)) !is ScheduleEndState.Running)
    }

    @Test
    fun `endState on date is running when the end date is missing`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.ON_DATE, endDate = null)
        assertTrue(ScheduleCalculator.endState(config, null, zdt(2026, 1, 1, 12, 0)) is ScheduleEndState.Running)
    }

    @Test
    fun `endState running carries the next occurrence after the last send`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 10, 12, 0)
        val state = ScheduleCalculator.endState(config, lastSent.toInstant().toEpochMilli(), zdt(2026, 9, 12, 12, 0))
        assertEquals(ScheduleEndState.Running(zdt(2026, 9, 15, 12, 0)), state)
    }

    @Test
    fun `endState running without a last send uses the first occurrence`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.NEVER)
        val state = ScheduleCalculator.endState(config, null, zdt(2026, 9, 10, 12, 0))
        assertEquals(ScheduleEndState.Running(zdt(2026, 9, 11, 12, 0)), state)
    }

    @Test
    fun `endState after n sends ended carries the sent count and the limit`() {
        val config = everyNDays(daysInterval = 5).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 3)
        val state = ScheduleCalculator.endState(config, null, zdt(2026, 9, 10, 12, 0))
        assertEquals(ScheduleEndState.EndedAfterNSends(zdt(2026, 9, 11, 12, 0), 3, 3), state)
    }

    @Test
    fun `endState past end date carries the next send and the end date`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val lastSent = zdt(2026, 9, 15, 12, 0)
        val state = ScheduleCalculator.endState(config, lastSent.toInstant().toEpochMilli(), zdt(2026, 9, 20, 12, 0))
        assertEquals(ScheduleEndState.EndedPastEndDate(zdt(2026, 9, 20, 12, 0), LocalDate.of(2026, 9, 15)), state)
    }

    // Regression: the end date used to be a local-midnight epoch reinterpreted with the
    // device zone at check time, so a timezone change moved the boundary by a day.
    @Test
    fun `on date end boundary is independent of the device timezone`() {
        val config = onDateConfig(daysInterval = 5, endYear = 2026, endMonth = 9, endDay = 15)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            assertTrue(!ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
            assertTrue(ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 16, 0, 0, ZoneId.systemDefault())))
            assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 10, 12, 0, ZoneId.systemDefault())))
            assertTrue(!ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago")) // UTC-11, other side of the date line
            assertTrue(!ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
            assertTrue(ScheduleCalculator.isAfterEndDate(config, zdt(2026, 9, 16, 0, 0, ZoneId.systemDefault())))
            assertTrue(ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 10, 12, 0, ZoneId.systemDefault())))
            assertTrue(!ScheduleCalculator.hasMoreOccurrence(config, 0, zdt(2026, 9, 15, 12, 0, ZoneId.systemDefault())))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `resolveCatchUp keeps an occurrence within an extended grace`() {
        val now = zdt(2026, 8, 16, 16, 0) // 2h past the occurrence
        val scheduled = zdt(2026, 8, 16, 14, 0)
        val result =
            ScheduleCalculator.resolveCatchUp(
                everyNDays(hour = 14, daysInterval = 30),
                scheduled,
                now,
                graceMillis = 2L * 3_600_000L,
            )
        assertEquals(scheduled, result.sendAt)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `resolveCatchUp skips an occurrence past an extended grace`() {
        val now = zdt(2026, 8, 16, 16, 1) // 2h 1min past the occurrence
        val scheduled = zdt(2026, 8, 16, 14, 0)
        val result =
            ScheduleCalculator.resolveCatchUp(
                everyNDays(hour = 14, daysInterval = 30),
                scheduled,
                now,
                graceMillis = 2L * 3_600_000L,
            )
        assertEquals(listOf(scheduled), result.skipped)
        assertEquals(zdt(2026, 9, 15, 14, 0), result.sendAt)
    }

    @Test
    fun `endState flips from ended to running when the extended grace covers the catch-up`() {
        // Daily schedule ending on 08-16, last sent 08-13: the next occurrence is 08-14, two
        // days one hour in the past at check time. The default 15-min grace skips all the way
        // to 08-17 (past the end date = ended); the 3-day grace keeps 08-14 (on or before the
        // end date = running) — the UI and the engine must agree on which grace they run.
        val config = onDateConfig(daysInterval = 1, endYear = 2026, endMonth = 8, endDay = 16).copy(hour = 13, minute = 0)
        val lastSent = zdt(2026, 8, 13, 13, 0).toInstant().toEpochMilli()
        val now = zdt(2026, 8, 16, 14, 0)
        assertTrue(ScheduleCalculator.endState(config, lastSent, now) !is ScheduleEndState.Running)
        assertTrue(
            ScheduleCalculator.endState(
                config,
                lastSent,
                now,
                graceMillis = 3L * 24 * 3_600_000L,
            ) is ScheduleEndState.Running,
        )
    }

    @Test
    fun `firstOccurrence selected months returns the first picked month after now`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 5, months = setOf(1, 3, 12))
        // Now in February: January is already past and unpicked anyway, March is next.
        assertEquals(zdt(2026, 3, 5, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 2, 10, 10, 0)))
    }

    @Test
    fun `firstOccurrence selected months returns the current month when its day is still ahead`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 20, months = setOf(8))
        assertEquals(zdt(2026, 8, 20, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 8, 10, 10, 0)))
    }

    @Test
    fun `firstOccurrence selected months skips unpicked months and wraps the year`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 5, months = setOf(1, 12))
        // Now in January after day 5: the next picked month is December of this year.
        assertEquals(zdt(2026, 12, 5, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 1, 6, 10, 0)))
        // Now in December after day 5: the next picked month is January of next year.
        assertEquals(zdt(2027, 1, 5, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 12, 6, 10, 0)))
    }

    @Test
    fun `firstOccurrence selected months clamps day 31 to the last day of a short picked month`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 31, months = setOf(2, 3))
        // The first picked month after now (January 15) is February, which has 28 days
        // in 2026 — the same clamping monthly uses.
        assertEquals(zdt(2026, 2, 28, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 1, 15, 10, 0)))
    }

    @Test
    fun `firstOccurrence selected months keeps day 29 in a leap February`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 29, months = setOf(2))
        assertEquals(zdt(2028, 2, 29, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2028, 1, 15, 10, 0)))
    }

    @Test
    fun `firstOccurrence selected months advances past the clamped day of the current picked month`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 31, months = setOf(4, 5))
        // April 30 11:00 is past the clamped April 30 10:00: the next picked month is May 31.
        assertEquals(zdt(2026, 5, 31, 10, 0), ScheduleCalculator.firstOccurrence(config, zdt(2026, 4, 30, 11, 0)))
    }

    @Test
    fun `nextOccurrence selected months walks to the next picked month after the last send`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 5, months = setOf(1, 3, 12))
        // Last send on March 5: March must not be sent twice, the next picked month is December.
        assertEquals(zdt(2026, 12, 5, 10, 0), ScheduleCalculator.nextOccurrence(config, zdt(2026, 3, 5, 10, 0)))
    }

    @Test
    fun `nextOccurrence selected months wraps the year`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 5, months = setOf(1, 12))
        assertEquals(zdt(2027, 1, 5, 10, 0), ScheduleCalculator.nextOccurrence(config, zdt(2026, 12, 5, 10, 0)))
    }

    @Test
    fun `nextOccurrence selected months clamps like monthly and restores the day after`() {
        val config = selectedMonthsConfig(hour = 10, dayOfMonth = 31, months = setOf(2))
        assertEquals(zdt(2026, 2, 28, 10, 0), ScheduleCalculator.nextOccurrence(config, zdt(2026, 1, 5, 10, 0)))
        // A clamped send restores the configured day in the next picked month (2027
        // February has 28 days again).
        assertEquals(zdt(2027, 2, 28, 10, 0), ScheduleCalculator.nextOccurrence(config, zdt(2026, 2, 28, 10, 0)))
    }

    @Test
    fun `baseOf every n days maps a jittered instant to the configured time on the same date`() {
        val config = everyNDays(hour = 14, minute = 30, daysInterval = 30)
        assertEquals(zdt(2026, 8, 16, 14, 30), ScheduleCalculator.baseOf(config, zdt(2026, 8, 16, 15, 45)))
    }

    @Test
    fun `baseOf monthly maps a jittered instant to the configured day and time`() {
        val config = monthlyConfig(hour = 10, minute = 15, dayOfMonth = 15)
        assertEquals(zdt(2026, 8, 15, 10, 15), ScheduleCalculator.baseOf(config, zdt(2026, 8, 15, 12, 0)))
    }

    @Test
    fun `baseOf monthly clamps the configured day to the month length`() {
        val config = monthlyConfig(hour = 10, minute = 0, dayOfMonth = 31)
        assertEquals(zdt(2026, 2, 28, 10, 0), ScheduleCalculator.baseOf(config, zdt(2026, 2, 28, 11, 0)))
    }

    @Test
    fun `baseOf selected months maps a jittered instant to the configured day at clamped length and time`() {
        val config = selectedMonthsConfig(hour = 10, minute = 0, dayOfMonth = 31, months = setOf(2, 3))
        assertEquals(zdt(2026, 2, 28, 10, 0), ScheduleCalculator.baseOf(config, zdt(2026, 2, 28, 13, 30)))
        assertEquals(zdt(2026, 3, 31, 10, 0), ScheduleCalculator.baseOf(config, zdt(2026, 3, 31, 11, 0)))
    }

    @Test
    fun `baseOf is idempotent on clean bases for every freq type`() {
        val everyN = zdt(2026, 8, 16, 14, 30)
        assertEquals(everyN, ScheduleCalculator.baseOf(everyNDays(hour = 14, minute = 30, daysInterval = 30), everyN))
        val monthly = zdt(2026, 8, 15, 10, 15)
        assertEquals(monthly, ScheduleCalculator.baseOf(monthlyConfig(hour = 10, minute = 15, dayOfMonth = 15), monthly))
        val clampedMonthly = zdt(2026, 2, 28, 10, 0)
        assertEquals(clampedMonthly, ScheduleCalculator.baseOf(monthlyConfig(hour = 10, minute = 0, dayOfMonth = 31), clampedMonthly))
        val selected = zdt(2026, 3, 31, 10, 0)
        assertEquals(
            selected,
            ScheduleCalculator.baseOf(selectedMonthsConfig(hour = 10, minute = 0, dayOfMonth = 31, months = setOf(3)), selected),
        )
    }

    @Test
    fun `selected months with an empty month set fails loud`() {
        // Unschedulable by construction: the editor refuses to save an empty set and
        // fromEntity/sanitize degrade a corrupted row, so a config reaching the calculator
        // is tampered — fail loud instead of walking the calendar forever.
        val config = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.SELECTED_MONTHS, selectedMonths = emptySet())
        val now = zdt(2026, 8, 16, 10, 0)
        assertThrows(IllegalStateException::class.java) { ScheduleCalculator.firstOccurrence(config, now) }
        assertThrows(IllegalStateException::class.java) { ScheduleCalculator.nextOccurrence(config, now) }
    }

    private fun zdt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.of("UTC"),
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    private fun everyNDays(
        hour: Int = 12,
        minute: Int = 0,
        daysInterval: Int = 30,
    ): SimKeepaliveConfig =
        SimKeepaliveConfig(
            simId = 1,
            hour = hour,
            minute = minute,
            freqType = FrequencyType.EVERY_N_DAYS,
            daysInterval = daysInterval,
        )

    private fun monthlyConfig(
        hour: Int = 12,
        minute: Int = 0,
        monthsInterval: Int = 1,
        dayOfMonth: Int = 15,
    ): SimKeepaliveConfig =
        SimKeepaliveConfig(
            simId = 1,
            hour = hour,
            minute = minute,
            freqType = FrequencyType.MONTHLY,
            monthsInterval = monthsInterval,
            dayOfMonth = dayOfMonth,
        )

    private fun selectedMonthsConfig(
        hour: Int = 12,
        minute: Int = 0,
        dayOfMonth: Int = 15,
        months: Set<Int>,
    ): SimKeepaliveConfig =
        SimKeepaliveConfig(
            simId = 1,
            hour = hour,
            minute = minute,
            freqType = FrequencyType.SELECTED_MONTHS,
            dayOfMonth = dayOfMonth,
            selectedMonths = months,
        )

    private fun onDateConfig(
        daysInterval: Int,
        endYear: Int,
        endMonth: Int,
        endDay: Int,
    ): SimKeepaliveConfig =
        everyNDays(daysInterval = daysInterval).copy(endType = EndType.ON_DATE, endDate = LocalDate.of(endYear, endMonth, endDay))
}
