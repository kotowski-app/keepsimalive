package app.kotowski.keepsimalive.data

import app.kotowski.keepsimalive.util.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SimKeepaliveConfigTest {
    @Test
    fun `defaults match UI defaults`() {
        val config = SimKeepaliveConfig(simId = 1)
        assertEquals(1, config.simId)
        assertEquals(false, config.enabled)
        assertEquals("", config.recipientPhone)
        assertEquals("", config.message)
        assertEquals(12, config.hour)
        assertEquals(0, config.minute)
        assertEquals(FrequencyType.MONTHLY, config.freqType)
        assertEquals(30, config.daysInterval)
        assertEquals(1, config.monthsInterval)
        assertEquals(1, config.dayOfMonth)
        assertEquals(emptySet<Int>(), config.selectedMonths)
        assertEquals(EndType.NEVER, config.endType)
        assertEquals(1, config.maxSends)
        assertNull(config.endDate)
        assertEquals(0, config.timeWindowMinutes)
        assertNull(config.lastSentOccurrenceMillis)
    }

    @Test
    fun `sameSchedule is true for identical schedule fields`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                hour = 9,
                minute = 30,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 7,
                monthsInterval = 1,
                dayOfMonth = 15,
                timeWindowMinutes = 60,
            )
        assertTrue(config.sameSchedule(config.copy()))
    }

    @Test
    fun `sameSchedule is false when any timing field differs`() {
        val base = SimKeepaliveConfig(simId = 1)
        assertFalse(base.sameSchedule(base.copy(hour = 13)))
        assertFalse(base.sameSchedule(base.copy(minute = 30)))
        assertFalse(base.sameSchedule(base.copy(freqType = FrequencyType.EVERY_N_DAYS)))
        assertFalse(base.sameSchedule(base.copy(daysInterval = 7)))
        assertFalse(base.sameSchedule(base.copy(monthsInterval = 2)))
        assertFalse(base.sameSchedule(base.copy(dayOfMonth = 15)))
        // A changed picked-month set moves the occurrence times, like the other fields.
        assertFalse(base.sameSchedule(base.copy(selectedMonths = setOf(1))))
        assertFalse(base.sameSchedule(base.copy(timeWindowMinutes = 60)))
    }

    @Test
    fun `sameSchedule ignores user fields and engine state`() {
        val base = SimKeepaliveConfig(simId = 1)
        assertTrue(base.sameSchedule(base.copy(enabled = true)))
        assertTrue(base.sameSchedule(base.copy(recipientPhone = "+15550100")))
        assertTrue(base.sameSchedule(base.copy(message = "hello")))
        assertTrue(base.sameSchedule(base.copy(endType = EndType.AFTER_N_SENDS, maxSends = 5)))
        assertTrue(base.sameSchedule(base.copy(endType = EndType.ON_DATE, endDate = LocalDate.of(2024, 1, 1))))
        assertTrue(base.sameSchedule(base.copy(nextSendAtMillis = 1L, lastSentAtMillis = 2L, sendCount = 3)))
        // Engine state: the last-sent-occurrence anchor never changes the occurrence times.
        assertTrue(base.sameSchedule(base.copy(lastSentOccurrenceMillis = 42L)))
    }

    @Test
    fun `toEntity and fromEntity round trip preserves all fields`() {
        val config =
            SimKeepaliveConfig(
                simId = 5,
                enabled = true,
                recipientPhone = "+15550101",
                message = "hello",
                hour = 8,
                minute = 30,
                freqType = FrequencyType.MONTHLY,
                daysInterval = 14,
                monthsInterval = 2,
                dayOfMonth = 15,
                selectedMonths = setOf(2, 11),
                endType = EndType.ON_DATE,
                maxSends = 7,
                endDate = LocalDate.of(2024, 1, 1),
                timeWindowMinutes = 15,
            )
        assertEquals(config, SimKeepaliveConfig.fromEntity(config.toEntity()))
    }

    @Test
    fun `toEntity stores the end date as an ISO calendar date string`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.ON_DATE, endDate = LocalDate.of(2026, 12, 31))
        assertEquals("2026-12-31", config.toEntity().endDate)
        assertEquals(LocalDate.of(2026, 12, 31), SimKeepaliveConfig.fromEntity(config.toEntity()).endDate)
    }

    @Test
    fun `round trip with null end date keeps null`() {
        val config = SimKeepaliveConfig(simId = 3, endType = EndType.NEVER, endDate = null)
        assertNull(SimKeepaliveConfig.fromEntity(config.toEntity()).endDate)
    }

    @Test
    fun `toEntity and fromEntity round trip preserves send tracking fields`() {
        val config =
            SimKeepaliveConfig(
                simId = 6,
                nextSendAtMillis = 1704067200000L,
                lastSentAtMillis = 1704067100000L,
                lastSentOccurrenceMillis = 1704067000000L,
                sendCount = 3,
            )
        val entity = config.toEntity()
        assertEquals(1704067200000L, entity.nextSendAtMillis)
        assertEquals(1704067100000L, entity.lastSentAtMillis)
        assertEquals(1704067000000L, entity.lastSentOccurrenceMillis)
        assertEquals(3, entity.sendCount)
        assertEquals(config, SimKeepaliveConfig.fromEntity(entity))
    }

    @Test
    fun `toEntity stores simId as primary key and enum ordinals`() {
        val config =
            SimKeepaliveConfig(
                simId = 42,
                enabled = true,
                freqType = FrequencyType.MONTHLY,
                endType = EndType.AFTER_N_SENDS,
            )
        val entity = config.toEntity()
        assertEquals(42, entity.simId)
        assertEquals(1, entity.freqType)
        assertEquals(1, entity.endType)
    }

    @Test
    fun `fromEntity maps unknown ordinals to safe defaults`() {
        val config =
            SimKeepaliveConfig.fromEntity(
                SimKeepaliveConfig(simId = 1).toEntity().copy(freqType = 7, endType = 5),
            )
        assertEquals(FrequencyType.EVERY_N_DAYS, config.freqType)
        assertEquals(EndType.NEVER, config.endType)
    }

    @Test
    fun `fromEntity preserves a selected months row with picked months`() {
        val entity =
            SimKeepaliveConfig(
                simId = 1,
            ).toEntity().copy(freqType = FrequencyType.SELECTED_MONTHS.ordinal, selectedMonths = "1,3,12")
        val config = SimKeepaliveConfig.fromEntity(entity)
        assertEquals(FrequencyType.SELECTED_MONTHS, config.freqType)
        assertEquals(setOf(1, 3, 12), config.selectedMonths)
    }

    @Test
    fun `fromEntity degrades a selected months row with an empty month set to monthly`() {
        // An empty month set is unschedulable (the editor refuses to save one), so a row
        // carrying it is corrupted or tampered — degrade it to the nearest schedulable
        // rhythm (monthly on the row's own day of month) instead of handing an empty month
        // walk to the engine.
        val entity = SimKeepaliveConfig(simId = 1).toEntity().copy(freqType = FrequencyType.SELECTED_MONTHS.ordinal, selectedMonths = "")
        assertEquals(FrequencyType.MONTHLY, SimKeepaliveConfig.fromEntity(entity).freqType)
    }

    @Test
    fun `fromEntity parses the selected months list defensively`() {
        // Junk tokens (non-numeric, out of the 1-12 range, blank) are dropped, never thrown.
        val entity = SimKeepaliveConfig(simId = 1).toEntity().copy(selectedMonths = "0,13,5,abc,,7, 12 ")
        assertEquals(setOf(5, 7, 12), SimKeepaliveConfig.fromEntity(entity).selectedMonths)
    }

    @Test
    fun `toEntity stores the picked months as a sorted comma list and fromEntity parses it back`() {
        val config =
            SimKeepaliveConfig(simId = 1, freqType = FrequencyType.SELECTED_MONTHS, dayOfMonth = 5, selectedMonths = setOf(12, 1, 3))
        assertEquals("1,3,12", config.toEntity().selectedMonths)
        assertEquals(config, SimKeepaliveConfig.fromEntity(config.toEntity()))
    }

    @Test
    fun `toEntity stores an empty month set as an empty string`() {
        val entity = SimKeepaliveConfig(simId = 1, freqType = FrequencyType.MONTHLY).toEntity()
        assertEquals("", entity.selectedMonths)
        assertEquals(emptySet<Int>(), SimKeepaliveConfig.fromEntity(entity).selectedMonths)
    }

    @Test
    fun `fromEntity with unparseable end date returns null instead of throwing`() {
        val entity = SimKeepaliveConfig(simId = 1).toEntity().copy(endDate = "not-a-date")
        val config = SimKeepaliveConfig.fromEntity(entity)
        assertNull(config.endDate)
    }

    @Test
    fun `fromEntity with empty end date string returns null instead of throwing`() {
        val entity = SimKeepaliveConfig(simId = 1).toEntity().copy(endDate = "")
        assertNull(SimKeepaliveConfig.fromEntity(entity).endDate)
    }

    @Test
    fun `fromEntity with corrupted end date logs a warning`() {
        LogBuffer.clear()
        val entity = SimKeepaliveConfig(simId = 9).toEntity().copy(endDate = "31/12/2026")
        SimKeepaliveConfig.fromEntity(entity)
        val entry = LogBuffer.entries.single()
        assertEquals("W", entry.level)
        assertEquals("SimKeepaliveConfig", entry.tag)
        assertTrue(entry.message.contains("simId=9"))
        assertTrue(entry.message.contains("31/12/2026"))
    }

    @Test
    fun `sanitize degrades on-date config with corrupted end date to never`() {
        val entity =
            SimKeepaliveConfig(simId = 1, endType = EndType.ON_DATE).toEntity().copy(endDate = "garbage")
        val sanitized = SimKeepaliveConfig.fromEntity(entity).sanitize()
        assertEquals(EndType.NEVER, sanitized.endType)
        assertNull(sanitized.endDate)
    }

    @Test
    fun `sanitize keeps a valid config unchanged`() {
        val config =
            SimKeepaliveConfig(
                simId = 5,
                enabled = true,
                recipientPhone = "+15550101",
                message = "hello",
                hour = 8,
                minute = 30,
                freqType = FrequencyType.MONTHLY,
                daysInterval = 14,
                monthsInterval = 2,
                dayOfMonth = 15,
                selectedMonths = setOf(2, 11),
                endType = EndType.ON_DATE,
                maxSends = 7,
                endDate = LocalDate.of(2024, 1, 1),
                timeWindowMinutes = 15,
            )
        assertEquals(config, config.sanitize())
    }

    @Test
    fun `sanitize clamps values above range to max`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                hour = 25,
                minute = 75,
                daysInterval = 999,
                monthsInterval = 13,
                dayOfMonth = 32,
                maxSends = 1000,
                timeWindowMinutes = 99_999,
            )
        val sanitized = config.sanitize()
        assertEquals(23, sanitized.hour)
        assertEquals(59, sanitized.minute)
        assertEquals(365, sanitized.daysInterval)
        assertEquals(12, sanitized.monthsInterval)
        assertEquals(31, sanitized.dayOfMonth)
        assertEquals(999, sanitized.maxSends)
        // Default frequency is monthly and dayOfMonth clamps to 31: day 31 has no room
        // for a window (February has 28 days), so the cap is zero.
        assertEquals(0, sanitized.timeWindowMinutes)
    }

    @Test
    fun `sanitize clamps values below range to min`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                hour = -5,
                minute = -1,
                daysInterval = 0,
                monthsInterval = 0,
                dayOfMonth = 0,
                maxSends = 0,
                timeWindowMinutes = -1,
            )
        val sanitized = config.sanitize()
        assertEquals(0, sanitized.hour)
        assertEquals(0, sanitized.minute)
        assertEquals(1, sanitized.daysInterval)
        assertEquals(1, sanitized.monthsInterval)
        assertEquals(1, sanitized.dayOfMonth)
        assertEquals(1, sanitized.maxSends)
        assertEquals(0, sanitized.timeWindowMinutes)
    }

    @Test
    fun `maxTimeWindowMinutes caps N-day windows at a quarter of the interval for small N at early times`() {
        // N/4 days at 00:00 (a full day left, so the midnight cap never binds here):
        // 1d -> 6h (360) -> the largest ladder step at or below is 4h (240).
        assertEquals(240, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 1, 1, 0, 0))
        // 2d -> 12h (720) -> the largest step at or below is 8h (480).
        assertEquals(480, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 2, 1, 0, 0))
        // 3d -> 18h (1080) -> the largest step at or below is 16h (960).
        assertEquals(960, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 3, 1, 0, 0))
    }

    @Test
    fun `maxTimeWindowMinutes caps N-day windows at the minutes left in the day`() {
        // 30d at 12:00: 7h59m left in the day (719) beats the 7d N/4 cap -> 8h (480).
        assertEquals(480, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 30, 1, 12, 0))
        // 30d at 00:00: a full day left (1439) beats the 7d N/4 cap -> 16h (960).
        assertEquals(960, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 30, 1, 0, 0))
        // 1d at 23:30: 29m left in the day -> the largest step at or below is 25m.
        assertEquals(25, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 1, 1, 23, 30))
        // 1d at 23:59: no room left -> 0.
        assertEquals(0, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 1, 1, 23, 59))
    }

    @Test
    fun `maxTimeWindowMinutes caps large N-day intervals at the day, not two weeks`() {
        // 365d at 00:00: the N/4 (91d) and 14d caps both exceed the day left (1439) -> 16h (960).
        assertEquals(960, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 365, 1, 0, 0))
        // 56d at 12:00: the day left (719) beats the 14d cap -> 8h (480).
        assertEquals(480, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, 56, 1, 12, 0))
    }

    @Test
    fun `max N-day window never crosses midnight`() {
        // The whole point of the midnight cap: with the max selectable window, no roll of the
        // jitter may land on the next date (the next occurrence anchors on the send's date).
        val zone = ZoneId.of("UTC")
        for (hour in 0..23) {
            for (minute in intArrayOf(0, 15, 30, 45, 59)) {
                for (n in intArrayOf(1, 2, 7, 30, 365)) {
                    val cap = SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.EVERY_N_DAYS, n, 1, hour, minute)
                    if (cap == 0) continue
                    val base = ZonedDateTime.of(2025, 6, 10, hour, minute, 0, 0, zone)
                    assertEquals(
                        "N=$n base=$hour:$minute",
                        base.toLocalDate(),
                        base.plusMinutes(cap.toLong()).toLocalDate(),
                    )
                }
            }
        }
    }

    @Test
    fun `maxTimeWindowMinutes caps monthly windows at a week staying in the month`() {
        // Day 1..21: 28 - day leaves >= 7d room, so the 7d cap (10080) applies. The time of
        // day is irrelevant for monthly (the cap is about the month, not the day).
        assertEquals(10080, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 1, 23, 30))
        assertEquals(10080, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 21, 0, 0))
        // Day 22: 6d room (8640) -> the largest step at or below is 4d (5760).
        assertEquals(5760, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 22, 12, 0))
        // Day 25: 3d room (4320) -> the largest step at or below is 2d (2880).
        assertEquals(2880, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 25, 12, 0))
        // Day 28: zero room -> 0.
        assertEquals(0, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 28, 12, 0))
        // Day 31: negative room clamps to 0.
        assertEquals(0, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 31, 12, 0))
    }

    @Test
    fun `maxTimeWindowMinutes uses the monthly cap for selected months`() {
        // A month-based rhythm (one send per picked month): the same cap as monthly for the
        // same day of month, while the editor previews the window of the prototype.
        assertEquals(
            SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 1, 23, 30),
            SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.SELECTED_MONTHS, 1, 1, 23, 30),
        )
        assertEquals(
            SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.MONTHLY, 1, 22, 12, 0),
            SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.SELECTED_MONTHS, 1, 22, 12, 0),
        )
        assertEquals(0, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.SELECTED_MONTHS, 1, 28, 12, 0))
        assertEquals(0, SimKeepaliveConfig.maxTimeWindowMinutes(FrequencyType.SELECTED_MONTHS, 1, 31, 12, 0))
    }

    @Test
    fun `sanitize clamps the window to the rhythm cap`() {
        // N-day 30d at the default 12:00: a stored 28d window (40320) exceeds the
        // minutes-left-in-day cap (480) -> clamped.
        val nDay =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                timeWindowMinutes = 40320,
            )
        assertEquals(480, nDay.sanitize().timeWindowMinutes)
        // N-day 1d at 23:30: a stored 4h window (240) exceeds the 25m cap -> clamped.
        val evening =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 1,
                hour = 23,
                minute = 30,
                timeWindowMinutes = 240,
            )
        assertEquals(25, evening.sanitize().timeWindowMinutes)
        // Monthly day 25: a stored 7d window (10080) exceeds the 2d cap -> clamped.
        val monthly =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 25,
                timeWindowMinutes = 10080,
            )
        assertEquals(2880, monthly.sanitize().timeWindowMinutes)
        // A window already at the cap stays unchanged.
        val atCap =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
                timeWindowMinutes = 480,
            )
        assertEquals(480, atCap.sanitize().timeWindowMinutes)
    }

    @Test
    fun `sanitize drives the window cap from the coerced day of month`() {
        // dayOfMonth 32 clamps to 31, which has a zero cap: the coerced value must drive
        // the window clamp, not the raw field.
        val raw =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 32,
                timeWindowMinutes = 40320,
            )
        assertEquals(31, raw.sanitize().dayOfMonth)
        assertEquals(0, raw.sanitize().timeWindowMinutes)
    }

    @Test
    fun `sanitize coerces negative sendCount to zero`() {
        val config = SimKeepaliveConfig(simId = 1, sendCount = -5)
        assertEquals(0, config.sanitize().sendCount)
    }

    @Test
    fun `sanitize keeps valid picked months unchanged`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.SELECTED_MONTHS,
                dayOfMonth = 5,
                selectedMonths = setOf(1, 3, 12),
            )
        assertEquals(config, config.sanitize())
    }

    @Test
    fun `sanitize coerces the picked months to the valid range`() {
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.SELECTED_MONTHS,
                selectedMonths = setOf(0, 13, 5, 12),
            )
        assertEquals(setOf(5, 12), config.sanitize().selectedMonths)
    }

    @Test
    fun `sanitize degrades a selected months config with no valid months to monthly`() {
        // The backstop for a config that somehow carries an empty (or all-junk) month set:
        // it is unschedulable, so it degrades like fromEntity does for a corrupted row.
        val config =
            SimKeepaliveConfig(
                simId = 1,
                freqType = FrequencyType.SELECTED_MONTHS,
                selectedMonths = setOf(0, 13),
            )
        val sanitized = config.sanitize()
        assertEquals(FrequencyType.MONTHLY, sanitized.freqType)
        assertEquals(emptySet<Int>(), sanitized.selectedMonths)
    }

    @Test
    fun `sanitize resets endType to never when end date missing`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.ON_DATE, endDate = null)
        val sanitized = config.sanitize()
        assertEquals(EndType.NEVER, sanitized.endType)
        assertNull(sanitized.endDate)
    }

    @Test
    fun `sanitize drops end date when endType is not on date`() {
        val config = SimKeepaliveConfig(simId = 1, endType = EndType.AFTER_N_SENDS, endDate = LocalDate.of(2024, 1, 1))
        val sanitized = config.sanitize()
        assertEquals(EndType.AFTER_N_SENDS, sanitized.endType)
        assertNull(sanitized.endDate)
    }

    @Test
    fun `sanitize clears invalid recipient of 8+ chars`() {
        val config = SimKeepaliveConfig(simId = 1, recipientPhone = "12345678901")
        assertEquals("", config.sanitize().recipientPhone)
    }

    @Test
    fun `sanitize keeps a valid recipient and clears every unsentable one`() {
        assertEquals("+15550100", SimKeepaliveConfig(simId = 1, recipientPhone = "+15550100").sanitize().recipientPhone)
        // Sub-8-char values can never pass the engine's E.164 pre-flight, so they degrade
        // to empty like any other unsentable value instead of surviving to fail a send.
        assertEquals("", SimKeepaliveConfig(simId = 1, recipientPhone = "123").sanitize().recipientPhone)
        assertEquals("", SimKeepaliveConfig(simId = 1, recipientPhone = "").sanitize().recipientPhone)
    }
}
