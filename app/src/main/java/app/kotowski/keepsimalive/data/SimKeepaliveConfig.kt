package app.kotowski.keepsimalive.data

import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.TimeWindowSteps
import app.kotowski.keepsimalive.util.isValidE164
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class SimKeepaliveConfig(
    val simId: Int,
    val enabled: Boolean = false,
    val recipientPhone: String = "",
    val message: String = "",
    val hour: Int = AppConfig.DEFAULT_HOUR,
    val minute: Int = 0,
    val freqType: FrequencyType = FrequencyType.MONTHLY,
    val daysInterval: Int = AppConfig.DEFAULT_DAYS_INTERVAL,
    val monthsInterval: Int = 1,
    val dayOfMonth: Int = 1,
    // The picked months (1-12) of the SELECTED_MONTHS frequency: the schedule sends on
    // dayOfMonth of each of them. An empty set is unschedulable (the editor refuses to
    // save one; see the fromEntity/sanitize backstops).
    val selectedMonths: Set<Int> = emptySet(),
    val endType: EndType = EndType.NEVER,
    val maxSends: Int = 1,
    val endDate: LocalDate? = null,
    val timeWindowMinutes: Int = 0,
    val nextSendAtMillis: Long? = null,
    val lastSentAtMillis: Long? = null,
    // Engine state (like lastSentAtMillis, which sameSchedule ignores too): the base of the
    // last successfully sent occurrence — the history-clear survival anchor.
    val lastSentOccurrenceMillis: Long? = null,
    val sendCount: Int = 0,
) {
    fun sanitize(): SimKeepaliveConfig {
        val safeEndType =
            when (endType) {
                EndType.ON_DATE -> if (endDate != null) EndType.ON_DATE else EndType.NEVER
                else -> endType
            }
        // A recipient the engine can never send (empty, too short, malformed) degrades to
        // empty, the same state the editor shows for a missing number, instead of
        // surviving to burn its first occurrence as FAILED.
        val safeRecipient = if (isValidE164(recipientPhone)) recipientPhone else ""
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val safeDaysInterval = daysInterval.coerceIn(AppConfig.DAYS_INTERVAL_MIN, AppConfig.DAYS_INTERVAL_MAX)
        val safeDayOfMonth = dayOfMonth.coerceIn(AppConfig.DAY_OF_MONTH_MIN, AppConfig.DAY_OF_MONTH_MAX)
        // The picked months are only meaningful in the 1-12 range (the editor writes a
        // clean set; a hand-edited row may carry junk).
        val safeSelectedMonths = selectedMonths.filter { it in 1..12 }.toSet()
        // A SELECTED_MONTHS schedule with no (valid) picked month can never fire: the
        // editor refuses to save one, so a config reaching here is corrupted — degrade it
        // to the nearest schedulable rhythm (monthly on the row's own day of month), the
        // same backstop fromEntity applies.
        val safeFreqType =
            if (freqType == FrequencyType.SELECTED_MONTHS && safeSelectedMonths.isEmpty()) {
                FrequencyType.MONTHLY
            } else {
                freqType
            }
        return copy(
            hour = safeHour,
            minute = safeMinute,
            daysInterval = safeDaysInterval,
            monthsInterval = monthsInterval.coerceIn(AppConfig.MONTHS_INTERVAL_MIN, AppConfig.MONTHS_INTERVAL_MAX),
            dayOfMonth = safeDayOfMonth,
            selectedMonths = safeSelectedMonths,
            freqType = safeFreqType,
            endType = safeEndType,
            maxSends = maxSends.coerceIn(AppConfig.MAX_SENDS_MIN, AppConfig.MAX_SENDS_MAX),
            sendCount = sendCount.coerceAtLeast(0),
            endDate = if (safeEndType == EndType.ON_DATE) endDate else null,
            // The window cap is computed from the coerced rhythm fields above (the same
            // values that are written), so a corrupt rhythm cannot widen or narrow it.
            timeWindowMinutes =
                timeWindowMinutes.coerceIn(
                    0,
                    maxTimeWindowMinutes(safeFreqType, safeDaysInterval, safeDayOfMonth, safeHour, safeMinute),
                ),
            recipientPhone = safeRecipient,
        )
    }

    // True when the two configs define the same occurrence times. A change to any of these
    // fields invalidates the engine's stored next send (it was computed from the previous
    // schedule), so a save that changes them must drop it and let the reconciler re-anchor.
    fun sameSchedule(other: SimKeepaliveConfig): Boolean =
        hour == other.hour &&
            minute == other.minute &&
            freqType == other.freqType &&
            daysInterval == other.daysInterval &&
            monthsInterval == other.monthsInterval &&
            dayOfMonth == other.dayOfMonth &&
            selectedMonths == other.selectedMonths &&
            timeWindowMinutes == other.timeWindowMinutes

    fun toEntity(): SimConfigEntity =
        SimConfigEntity(
            simId = simId,
            enabled = enabled,
            recipientPhone = recipientPhone,
            message = message,
            hour = hour,
            minute = minute,
            freqType = freqType.ordinal,
            daysInterval = daysInterval,
            monthsInterval = monthsInterval,
            dayOfMonth = dayOfMonth,
            selectedMonths = selectedMonths.sorted().joinToString(","),
            endType = endType.ordinal,
            maxSends = maxSends,
            endDate = endDate?.toString(),
            timeWindowMinutes = timeWindowMinutes,
            nextSendAtMillis = nextSendAtMillis,
            lastSentAtMillis = lastSentAtMillis,
            lastSentOccurrenceMillis = lastSentOccurrenceMillis,
            sendCount = sendCount,
        )

    companion object {
        // The largest selectable window for the given rhythm, snapped to the step ladder.
        // The window must not break the rhythm the user picked: for an N-day schedule at most a
        // quarter of the interval (capped at two weeks) and never so much that the shifted send
        // crosses midnight (a crossing would push the whole rhythm a day forward); for a monthly
        // schedule at most a week and never so much that the shifted send leaves the month (the
        // shortest month has 28 days, so days 28-31 have no room at all).
        fun maxTimeWindowMinutes(
            freqType: FrequencyType,
            daysInterval: Int,
            dayOfMonth: Int,
            hour: Int,
            minute: Int,
        ): Int =
            TimeWindowSteps.valueAtMost(
                when (freqType) {
                    // N/4 days, capped at 14 d (20_160 = 14 * 1440 minutes) and at the
                    // minutes left in the base's day (1_439 - time, a 24 h day like the
                    // monthly cap's 28-day month), so the shifted send lands at most at
                    // 23:59 and the next occurrence's date anchor stays on the base's date.
                    FrequencyType.EVERY_N_DAYS -> {
                        minOf(
                            daysInterval.toLong() * 360,
                            20_160L,
                            (1_439 - (hour * 60 + minute)).coerceAtLeast(0).toLong(),
                        ).toInt()
                    }

                    // At most 7 d (1_440 = 1440 minutes) and never leaving the month.
                    // SELECTED_MONTHS is a month-based rhythm too (one send per picked
                    // month), so it takes the same cap.
                    FrequencyType.MONTHLY,
                    FrequencyType.SELECTED_MONTHS,
                    -> {
                        minOf(7, maxOf(0, 28 - dayOfMonth)) * 1_440
                    }
                },
            )

        fun fromEntity(entity: SimConfigEntity): SimKeepaliveConfig {
            val rawFreqType =
                FrequencyType.values().getOrNull(entity.freqType) ?: FrequencyType.EVERY_N_DAYS
            val selectedMonths = parseSelectedMonths(entity.selectedMonths)
            // A SELECTED_MONTHS row with no picked months is unschedulable (the editor
            // refuses to save one), so the row is corrupted or tampered — degrade it to the
            // nearest schedulable rhythm (monthly on the row's own day of month) instead of
            // handing an empty month walk to the engine.
            val safeFreqType =
                if (rawFreqType == FrequencyType.SELECTED_MONTHS && selectedMonths.isEmpty()) {
                    FrequencyType.MONTHLY
                } else {
                    rawFreqType
                }
            return SimKeepaliveConfig(
                simId = entity.simId,
                enabled = entity.enabled,
                recipientPhone = entity.recipientPhone,
                message = entity.message,
                hour = entity.hour,
                minute = entity.minute,
                freqType = safeFreqType,
                daysInterval = entity.daysInterval,
                monthsInterval = entity.monthsInterval,
                dayOfMonth = entity.dayOfMonth,
                selectedMonths = selectedMonths,
                endType = EndType.values().getOrNull(entity.endType) ?: EndType.NEVER,
                maxSends = entity.maxSends,
                endDate = parseEndDate(entity.simId, entity.endDate),
                timeWindowMinutes = entity.timeWindowMinutes,
                nextSendAtMillis = entity.nextSendAtMillis,
                lastSentAtMillis = entity.lastSentAtMillis,
                lastSentOccurrenceMillis = entity.lastSentOccurrenceMillis,
                sendCount = entity.sendCount,
            )
        }

        // The picked months of a SELECTED_MONTHS schedule (a sorted comma-separated list of
        // month numbers, "" = none). The parse is defensive like parseEndDate: junk tokens
        // are dropped instead of throwing, so a hand-edited cell degrades to a valid set.
        private fun parseSelectedMonths(raw: String): Set<Int> =
            raw
                .split(',')
                .mapNotNull { part ->
                    part.trim().toIntOrNull()?.takeIf { it in 1..12 }
                }.toSet()

        // A non-null endDate that fails to parse is a corrupted cell (the column is only ever
        // written as LocalDate.toString()). Treat it as "no end date" instead of throwing:
        // the exception would propagate through every config read, and sanitize() already
        // degrades ON_DATE without a date to NEVER. The next save rewrites the cell,
        // self-healing it.
        private fun parseEndDate(
            simId: Int,
            raw: String?,
        ): LocalDate? =
            raw?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    Logger.w("SimKeepaliveConfig", "simId=$simId unparseable endDate='$it', treated as none", e)
                    null
                }
            }
    }
}
