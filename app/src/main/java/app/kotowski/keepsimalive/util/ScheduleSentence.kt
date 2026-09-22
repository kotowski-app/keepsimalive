package app.kotowski.keepsimalive.util

import android.content.Context
import android.icu.text.ListFormatter
import android.os.Build
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig

// The human-readable schedule sentence, e.g. "Every 30 days, at a random time between
// 9:00 PM and 9:05 PM (until September 30, 2027)".
fun scheduleSentence(
    context: Context,
    config: SimKeepaliveConfig,
): String {
    val frequency =
        when (config.freqType) {
            FrequencyType.EVERY_N_DAYS -> {
                // The interval==1 case uses a plain string: a plural whose `one` item omits the
                // number (as the old "every day") loses the count in multi-category languages
                // (e.g. Russian picks `one` for 21), so the number must live in the code's branch.
                if (config.daysInterval == 1) {
                    context.getString(R.string.schedule_freq_every_day)
                } else {
                    context.resources.getQuantityString(R.plurals.schedule_freq_days, config.daysInterval, config.daysInterval)
                }
            }

            FrequencyType.MONTHLY -> {
                val isShort = config.dayOfMonth in 29..31
                if (config.monthsInterval == 1) {
                    context.getString(
                        if (isShort) R.string.schedule_freq_monthly_short else R.string.schedule_freq_monthly,
                        config.dayOfMonth,
                    )
                } else {
                    val freqPlural =
                        if (isShort) R.plurals.schedule_freq_months_short else R.plurals.schedule_freq_months
                    context.resources.getQuantityString(
                        freqPlural,
                        config.monthsInterval,
                        config.dayOfMonth,
                        config.monthsInterval,
                    )
                }
            }

            FrequencyType.SELECTED_MONTHS -> {
                val isShort = config.dayOfMonth in 29..31
                // The picked months in calendar order, formatted with the platform
                // ListFormatter so the list separators are localized; the names are
                // platform-localized, so no per-locale strings are needed. A saved config
                // always carries picked months (the editor refuses an empty set, fromEntity
                // degrades a corrupted one), so an empty list renders an empty phrase.
                val monthNames =
                    config.selectedMonths.sorted().map {
                        DateUtil.monthName(context, it)
                    }
                val months =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ListFormatter.getInstance(DateUtil.appLocale(context)).format(monthNames)
                    } else {
                        // ListFormatter is API 26+; older devices get a plain comma-joined list.
                        monthNames.joinToString(", ")
                    }
                context.getString(
                    if (isShort) R.string.schedule_freq_selected_months_short else R.string.schedule_freq_selected_months,
                    months,
                    config.dayOfMonth,
                )
            }
        }
    val startTime = DateUtil.formatTime(context, config.hour, config.minute)
    val time =
        if (config.timeWindowMinutes == 0) {
            context.getString(R.string.schedule_time_at, startTime)
        } else {
            val endMinutes = config.hour * 60 + config.minute + config.timeWindowMinutes
            val endTime = DateUtil.formatTime(context, endMinutes / 60 % 24, endMinutes % 60)
            when {
                endMinutes < 1440 -> {
                    context.getString(R.string.schedule_time_between, startTime, endTime)
                }

                endMinutes < 2880 -> {
                    context.getString(R.string.schedule_time_between_next_day, startTime, endTime)
                }

                else -> {
                    context.resources.getQuantityString(
                        R.plurals.schedule_time_between_days_later,
                        endMinutes / 1440,
                        startTime,
                        endTime,
                        endMinutes / 1440,
                    )
                }
            }
        }
    val endClause =
        when (config.endType) {
            EndType.NEVER -> {
                ""
            }

            EndType.AFTER_N_SENDS -> {
                ", " +
                    context.resources.getQuantityString(R.plurals.schedule_stops_after_sends, config.maxSends, config.maxSends)
            }

            EndType.ON_DATE -> {
                config.endDate
                    ?.let { " " + context.getString(R.string.schedule_until_date, DateUtil.formatDate(context, it)) }
                    .orEmpty()
            }
        }
    return context.getString(R.string.schedule_sentence, frequency, time, endClause)
}

// The human reason an occurrence is skipped because the schedule ended (shown in the
// history for that skipped occurrence). The empty branches are defensive: both callers
// gate on ScheduleCalculator.isEndConditionReached, which never fires for NEVER or for
// ON_DATE without a date (sanitize degrades that to NEVER).
internal fun endConditionReason(
    context: Context,
    config: SimKeepaliveConfig,
): String =
    when (config.endType) {
        EndType.AFTER_N_SENDS -> {
            context.resources.getQuantityString(R.plurals.schedule_ended_after_sends, config.maxSends, config.maxSends)
        }

        EndType.ON_DATE -> {
            config.endDate
                ?.let { context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, it)) }
                ?: ""
        }

        EndType.NEVER -> {
            ""
        }
    }
