package app.kotowski.keepsimalive.schedule

import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.random.Random

object ScheduleCalculator {
    fun firstOccurrence(
        config: SimKeepaliveConfig,
        now: ZonedDateTime,
    ): ZonedDateTime =
        when (config.freqType) {
            FrequencyType.EVERY_N_DAYS -> {
                val candidate = now.toLocalDate().atTime(config.hour, config.minute).atZone(now.zone)
                if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
            }

            FrequencyType.MONTHLY -> {
                nextMonthlyAfter(config, now)
            }

            FrequencyType.SELECTED_MONTHS -> {
                nextSelectedMonthsAfter(config, YearMonth.from(now), now)
            }
        }

    private fun nextMonthlyAfter(
        config: SimKeepaliveConfig,
        now: ZonedDateTime,
    ): ZonedDateTime {
        var month = YearMonth.from(now)
        while (true) {
            val candidate = atConfiguredTime(config, month, now.zone)
            if (candidate.isAfter(now)) return candidate
            month = month.plusMonths(1)
        }
    }

    // An empty month set can never match: the editor refuses to save one and
    // fromEntity/sanitize degrade a corrupted row, so a config reaching here is tampered —
    // fail loud instead of walking the calendar forever.
    private fun nextSelectedMonthsAfter(
        config: SimKeepaliveConfig,
        from: YearMonth,
        after: ZonedDateTime,
    ): ZonedDateTime {
        if (config.selectedMonths.isEmpty()) {
            error("SELECTED_MONTHS schedule has no picked months")
        }
        var month = from
        while (true) {
            if (month.monthValue in config.selectedMonths) {
                val candidate = atConfiguredTime(config, month, after.zone)
                if (candidate.isAfter(after)) return candidate
            }
            month = month.plusMonths(1)
        }
    }

    fun nextOccurrence(
        config: SimKeepaliveConfig,
        last: ZonedDateTime,
    ): ZonedDateTime =
        when (config.freqType) {
            FrequencyType.EVERY_N_DAYS -> {
                last
                    .toLocalDate()
                    .plusDays(config.daysInterval.toLong())
                    .atTime(config.hour, config.minute)
                    .atZone(last.zone)
            }

            FrequencyType.MONTHLY -> {
                val targetMonth = YearMonth.from(last).plusMonths(config.monthsInterval.toLong())
                atConfiguredTime(config, targetMonth, last.zone)
            }

            // Start at the month after the last send's: a picked month carries one send per
            // year, so the same month must not be sent twice.
            FrequencyType.SELECTED_MONTHS -> {
                nextSelectedMonthsAfter(config, YearMonth.from(last).plusMonths(1), last)
            }
        }

    // The base occurrence `occurrence` was armed for: the window never crosses midnight
    // (every-N-days) or leaves the month (monthly, selected months), so the base is the
    // occurrence's date or month at the configured time. Idempotent: a clean base maps to
    // itself, so it is safe to apply at every arm site unconditionally.
    fun baseOf(
        config: SimKeepaliveConfig,
        occurrence: ZonedDateTime,
    ): ZonedDateTime =
        when (config.freqType) {
            FrequencyType.EVERY_N_DAYS -> {
                occurrence.toLocalDate().atTime(config.hour, config.minute).atZone(occurrence.zone)
            }

            FrequencyType.MONTHLY,
            FrequencyType.SELECTED_MONTHS,
            -> {
                atConfiguredTime(config, YearMonth.from(occurrence), occurrence.zone)
            }
        }

    // The random shift applied to a base occurrence: the send goes out at a random time
    // within [base, base + window]. `notAfterDate` (the inclusive ON_DATE end date) caps the
    // shift so the armed time never lands on a day after it — uncapped, the end check would
    // drop the last intended send; it goes out early instead of being lost.
    fun withWindow(
        base: ZonedDateTime,
        windowMinutes: Int,
        notAfterDate: LocalDate? = null,
        random: Random = Random.Default,
    ): ZonedDateTime {
        if (windowMinutes == 0) return base
        val maxJitterMinutes =
            if (notAfterDate != null) {
                // Minutes left until the end of the end date (the end comparison is
                // day-granular, so any time on that day is still valid); negative when the
                // base is already past the end date.
                val minutesLeft =
                    Duration
                        .between(base, notAfterDate.atTime(LocalTime.MAX).atZone(base.zone))
                        .toMinutes()
                        .toInt()
                minOf(windowMinutes, minutesLeft.coerceAtLeast(0))
            } else {
                windowMinutes
            }
        if (maxJitterMinutes <= 0) return base
        return base.plusMinutes(random.nextInt(maxJitterMinutes + 1).toLong())
    }

    // `graceMillis` bounds how far past its scheduled time an occurrence is still attempted
    // when the work fires: later occurrences are consumed as missed, never re-sent, and the
    // newest one within the grace is the one the fire sends.
    fun resolveCatchUp(
        config: SimKeepaliveConfig,
        scheduled: ZonedDateTime,
        now: ZonedDateTime,
        graceMillis: Long = AppConfig.GRACE_MILLIS,
    ): CatchUpResult {
        val nowMillis = now.toInstant().toEpochMilli()
        val skipped = mutableListOf<ZonedDateTime>()
        var occ = scheduled
        while (nowMillis - occ.toInstant().toEpochMilli() > graceMillis) {
            skipped += occ
            occ = nextOccurrence(config, occ)
        }
        return CatchUpResult(occ, skipped)
    }

    fun hasMoreOccurrence(
        config: SimKeepaliveConfig,
        sentCount: Int,
        lastOccurrence: ZonedDateTime,
    ): Boolean {
        return when (config.endType) {
            EndType.NEVER -> {
                true
            }

            EndType.AFTER_N_SENDS -> {
                sentCount < config.maxSends
            }

            EndType.ON_DATE -> {
                // Calendar dates compared as-is: the stored end date is the day the user
                // picked, so a timezone change can never move the boundary.
                val endDate = config.endDate ?: return false
                nextOccurrence(config, lastOccurrence).toLocalDate() <= endDate
            }
        }
    }

    // The end date is inclusive: an occurrence on the end date itself is still valid, so an
    // occurrence is only past the end date when it falls on a day strictly after it.
    fun isAfterEndDate(
        config: SimKeepaliveConfig,
        at: ZonedDateTime,
    ): Boolean {
        if (config.endType != EndType.ON_DATE) return false
        val endDate = config.endDate ?: return false
        return at.toLocalDate() > endDate
    }

    fun isEndConditionReached(
        config: SimKeepaliveConfig,
        at: ZonedDateTime,
    ): Boolean =
        when (config.endType) {
            EndType.NEVER -> false
            EndType.AFTER_N_SENDS -> config.sendCount >= config.maxSends
            EndType.ON_DATE -> isAfterEndDate(config, at)
        }

    // The schedule's end state as of `now`, anchored like the engine (the newest SENT
    // history row's occurrence, never the delivery time; null when nothing was ever sent),
    // so the UI and the reconciler always agree on whether the schedule is over. Every state
    // carries the next occurrence the engine would schedule, and the ended states carry the
    // values that make the schedule over, so the UI can explain why enabling it would do
    // nothing. `graceMillis` must be the user-configurable late-send grace: the catch-up it
    // runs is the same one the engine runs, so a different grace would make the UI and the
    // engine disagree.
    fun endState(
        config: SimKeepaliveConfig,
        lastSentMillis: Long?,
        now: ZonedDateTime,
        graceMillis: Long = AppConfig.GRACE_MILLIS,
    ): ScheduleEndState {
        val sendAt =
            if (lastSentMillis != null) {
                val anchor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastSentMillis), now.zone)
                resolveCatchUp(config, nextOccurrence(config, anchor), now, graceMillis).sendAt
            } else {
                firstOccurrence(config, now)
            }
        return when (config.endType) {
            EndType.NEVER -> {
                ScheduleEndState.Running(sendAt)
            }

            EndType.AFTER_N_SENDS -> {
                if (config.sendCount >= config.maxSends) {
                    ScheduleEndState.EndedAfterNSends(sendAt, config.sendCount, config.maxSends)
                } else {
                    ScheduleEndState.Running(sendAt)
                }
            }

            EndType.ON_DATE -> {
                val endDate = config.endDate
                if (endDate != null && sendAt.toLocalDate() > endDate) {
                    ScheduleEndState.EndedPastEndDate(sendAt, endDate)
                } else {
                    ScheduleEndState.Running(sendAt)
                }
            }
        }
    }

    private fun atConfiguredTime(
        config: SimKeepaliveConfig,
        month: YearMonth,
        zone: ZoneId,
    ): ZonedDateTime {
        val day = min(config.dayOfMonth, month.lengthOfMonth())
        return month.atDay(day).atTime(config.hour, config.minute).atZone(zone)
    }
}

data class CatchUpResult(
    val sendAt: ZonedDateTime,
    val skipped: List<ZonedDateTime> = emptyList(),
)

// The end state of a schedule (see ScheduleCalculator.endState).
sealed interface ScheduleEndState {
    val nextSend: ZonedDateTime

    data class Running(
        override val nextSend: ZonedDateTime,
    ) : ScheduleEndState

    data class EndedAfterNSends(
        override val nextSend: ZonedDateTime,
        val sent: Int,
        val max: Int,
    ) : ScheduleEndState

    data class EndedPastEndDate(
        override val nextSend: ZonedDateTime,
        val endDate: LocalDate,
    ) : ScheduleEndState
}
