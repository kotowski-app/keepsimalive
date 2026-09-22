package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SimDetailPendingConfigTest {
    private fun build(
        enabled: Boolean = true,
        recipientPhone: String = "+15550100",
        message: String = "Keep this SIM alive",
        freqType: FrequencyType = FrequencyType.EVERY_N_DAYS,
        selectedMonths: Set<Int> = setOf(1, 3, 12),
        endType: EndType = EndType.NEVER,
        endDate: LocalDate? = null,
        daysIntervalError: Boolean = false,
        monthsIntervalError: Boolean = false,
        dayOfMonthError: Boolean = false,
        maxSendsError: Boolean = false,
        timeWindowMinutes: Int = 120,
    ): SimKeepaliveConfig? =
        buildPendingConfig(
            PendingConfigDraft(
                simId = 1,
                enabled = enabled,
                recipientPhone = recipientPhone,
                message = message,
                hour = 8,
                minute = 30,
                freqType = freqType,
                daysInterval = 30,
                monthsInterval = 2,
                dayOfMonth = 15,
                selectedMonths = selectedMonths,
                endType = endType,
                maxSends = 5,
                endDate = endDate,
                timeWindowMinutes = timeWindowMinutes,
                daysIntervalError = daysIntervalError,
                monthsIntervalError = monthsIntervalError,
                dayOfMonthError = dayOfMonthError,
                maxSendsError = maxSendsError,
            ),
        )

    @Test
    fun `valid enabled fields produce config with all values`() {
        val config = build()
        assertEquals(1, config!!.simId)
        assertEquals(true, config.enabled)
        assertEquals("+15550100", config.recipientPhone)
        assertEquals("Keep this SIM alive", config.message)
        assertEquals(8, config.hour)
        assertEquals(30, config.minute)
        assertEquals(FrequencyType.EVERY_N_DAYS, config.freqType)
        assertEquals(30, config.daysInterval)
        assertEquals(2, config.monthsInterval)
        assertEquals(15, config.dayOfMonth)
        assertEquals(EndType.NEVER, config.endType)
        assertEquals(5, config.maxSends)
        assertEquals(null, config.endDate)
        assertEquals(120, config.timeWindowMinutes)
    }

    @Test
    fun `selected months with a picked month produces a saved config`() {
        val config = build(freqType = FrequencyType.SELECTED_MONTHS)
        assertEquals(FrequencyType.SELECTED_MONTHS, config!!.freqType)
        assertEquals(setOf(1, 3, 12), config.selectedMonths)
    }

    @Test
    fun `selected months with no picked month produces no config`() {
        // An empty month set is unschedulable: the draft must stay out of the engine (Save
        // stays dimmed, the editor's hint explains why).
        assertNull(build(freqType = FrequencyType.SELECTED_MONTHS, selectedMonths = emptySet()))
    }

    @Test
    fun `active day of month error invalidates selected months config`() {
        // The "On day" field is shared by both month cadences, so its error blocks Save
        // for the selected-months frequency too.
        assertNull(build(freqType = FrequencyType.SELECTED_MONTHS, dayOfMonthError = true))
    }

    @Test
    fun `hidden months interval error does not block selected months config`() {
        // The "Every N months" row is monthly-only: its stale error must not trap a
        // selected-months draft behind a dimmed Save.
        val config = build(freqType = FrequencyType.SELECTED_MONTHS, monthsIntervalError = true)
        assertEquals(FrequencyType.SELECTED_MONTHS, config!!.freqType)
    }

    @Test
    fun `disabled config with invalid fields still returns null`() {
        assertNull(
            build(
                enabled = false,
                recipientPhone = "",
                message = "",
                endType = EndType.ON_DATE,
                endDate = null,
                daysIntervalError = true,
            ),
        )
    }

    @Test
    fun `disabled config with valid fields produces config`() {
        val config = build(enabled = false)
        assertEquals(false, config!!.enabled)
        assertEquals("+15550100", config.recipientPhone)
    }

    @Test
    fun `empty recipient invalidates enabled config`() {
        assertNull(build(recipientPhone = ""))
    }

    @Test
    fun `short recipient invalidates enabled config`() {
        assertNull(build(recipientPhone = "+123456"))
    }

    @Test
    fun `empty message invalidates enabled config`() {
        assertNull(build(message = ""))
    }

    @Test
    fun `whitespace-only message invalidates enabled config`() {
        assertNull(build(message = "     "))
        assertNull(build(message = "\t\n"))
    }

    @Test
    fun `active days interval error invalidates every n days config`() {
        assertNull(build(daysIntervalError = true))
    }

    @Test
    fun `active months interval error invalidates monthly config`() {
        assertNull(build(freqType = FrequencyType.MONTHLY, monthsIntervalError = true))
    }

    @Test
    fun `active day of month error invalidates monthly config`() {
        assertNull(build(freqType = FrequencyType.MONTHLY, dayOfMonthError = true))
    }

    @Test
    fun `active max sends error invalidates after n sends config`() {
        assertNull(build(endType = EndType.AFTER_N_SENDS, maxSendsError = true))
    }

    @Test
    fun `hidden days interval error does not block monthly config`() {
        val config = build(freqType = FrequencyType.MONTHLY, daysIntervalError = true)
        assertEquals(FrequencyType.MONTHLY, config!!.freqType)
    }

    @Test
    fun `hidden months interval error does not block every n days config`() {
        assertNotNull(build(monthsIntervalError = true))
    }

    @Test
    fun `hidden day of month error does not block every n days config`() {
        assertNotNull(build(dayOfMonthError = true))
    }

    @Test
    fun `hidden max sends error does not block never config`() {
        assertNotNull(build(maxSendsError = true))
    }

    @Test
    fun `hidden max sends error does not block on date config`() {
        val config = build(endType = EndType.ON_DATE, endDate = LocalDate.of(2024, 1, 1), maxSendsError = true)
        assertEquals(EndType.ON_DATE, config!!.endType)
    }

    @Test
    fun `end on date without date invalidates enabled config`() {
        assertNull(build(endType = EndType.ON_DATE))
    }

    @Test
    fun `end on date with date is valid`() {
        val config = build(endType = EndType.ON_DATE, endDate = LocalDate.of(2024, 1, 1))
        assertEquals(EndType.ON_DATE, config!!.endType)
        assertEquals(LocalDate.of(2024, 1, 1), config.endDate)
    }

    @Test
    fun `over-length message is truncated to 70 chars`() {
        val longMessage = "a".repeat(200)
        val config = build(message = longMessage)
        assertEquals(AppConfig.SMS_MAX_CHARS, config!!.message.length)
    }

    @Test
    fun `over-length message with emoji at the boundary drops the emoji whole`() {
        // 69 ascii + one emoji = 71 code units: the cap must not cut between the emoji's
        // surrogate halves (the stored message would end with an orphan surrogate).
        val message = "a".repeat(69) + "😀"
        val config = build(message = message)
        assertEquals("a".repeat(69), config!!.message)
        assertFalse(config.message.any { Character.isSurrogate(it) })
    }

    @Test
    fun `window above the rhythm cap is clamped to the cap`() {
        // EVERY_N_DAYS 30d at 08:30: 15h29m left in the day (929) beats the 7d N/4 cap, so
        // the cap is the largest step at or below, 8h (480): a stale 28d draft value must
        // not be saved.
        val config = build(timeWindowMinutes = 40320)
        assertEquals(480, config!!.timeWindowMinutes)
    }

    @Test
    fun `window at or below the rhythm cap is kept`() {
        val atCap = build(timeWindowMinutes = 480)
        assertEquals(480, atCap!!.timeWindowMinutes)
        val belowCap = build(timeWindowMinutes = 120)
        assertEquals(120, belowCap!!.timeWindowMinutes)
    }

    @Test
    fun `monthly day 31 clamps any window to zero`() {
        // Monthly day 31 has a zero cap (February has 28 days): the backstop forces zero.
        val config =
            buildPendingConfig(
                PendingConfigDraft(
                    simId = 1,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "Keep this SIM alive",
                    hour = 8,
                    minute = 30,
                    freqType = FrequencyType.MONTHLY,
                    daysInterval = 30,
                    monthsInterval = 1,
                    dayOfMonth = 31,
                    selectedMonths = emptySet(),
                    endType = EndType.NEVER,
                    maxSends = 5,
                    endDate = null,
                    timeWindowMinutes = 10080,
                    daysIntervalError = false,
                    monthsIntervalError = false,
                    dayOfMonthError = false,
                    maxSendsError = false,
                ),
            )
        assertEquals(0, config!!.timeWindowMinutes)
    }
}
