package app.kotowski.keepsimalive.util

import android.content.Context
import app.kotowski.keepsimalive.R
import kotlin.math.abs

object TimeWindowSteps {
    val STEPS: IntArray =
        intArrayOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 120, 240, 480, 960, 1440, 2880, 5760, 10080, 20160, 40320)

    fun valueAtMost(minutes: Int): Int {
        var best = 0
        for (v in STEPS) {
            if (v > minutes) break
            best = v
        }
        return best
    }

    // The index of the largest step at or below the bound (indexFor's "nearest" would pick
    // the step above for an off-ladder bound).
    fun indexAtMost(value: Int): Int {
        var best = 0
        for (i in STEPS.indices) {
            if (STEPS[i] > value) break
            best = i
        }
        return best
    }

    fun indexFor(value: Int): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in STEPS.indices) {
            val dist = abs(STEPS[i] - value)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    fun valueFor(index: Int): Int = STEPS[index.coerceIn(0, STEPS.lastIndex)]

    fun next(value: Int): Int = valueFor(indexFor(value) + 1)

    fun previous(value: Int): Int = valueFor(indexFor(value) - 1)

    fun formatValue(
        context: Context,
        minutes: Int,
    ): String =
        when {
            minutes < 60 -> context.getString(R.string.sim_config_time_window_minutes, minutes)
            minutes < 1440 -> context.getString(R.string.sim_config_time_window_hours, minutes / 60)
            else -> context.getString(R.string.sim_config_time_window_days, minutes / 1440)
        }
}
