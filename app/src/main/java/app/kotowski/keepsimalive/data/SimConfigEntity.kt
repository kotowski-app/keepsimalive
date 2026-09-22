package app.kotowski.keepsimalive.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sim_config")
data class SimConfigEntity(
    @PrimaryKey
    val simId: Int,
    val enabled: Boolean,
    val recipientPhone: String,
    val message: String,
    val hour: Int,
    val minute: Int,
    val freqType: Int,
    val daysInterval: Int,
    val monthsInterval: Int,
    val dayOfMonth: Int,
    // The picked months of the SELECTED_MONTHS frequency as a sorted comma-separated list
    // of month numbers ("1,3,12"; "" = none); SimKeepaliveConfig maps it to/from Set<Int>.
    val selectedMonths: String = "",
    val endType: Int,
    val maxSends: Int,
    // The calendar date the user picked as an ISO "yyyy-MM-dd" string, not a timestamp:
    // an epoch interpreted with the device's zone at check time would drift by a day after
    // a timezone change. SimKeepaliveConfig maps it to/from LocalDate.
    val endDate: String? = null,
    val timeWindowMinutes: Int,
    val nextSendAtMillis: Long? = null,
    val lastSentAtMillis: Long? = null,
    // Engine-owned: the BASE (pre-jitter occurrence time) of the last successfully sent
    // occurrence, written in the same transaction as the SENT row flip (recordSuccess). The
    // history-clear survival anchor: when the SENT rows are gone the recompute re-derives
    // the rhythm from it instead of calling firstOccurrence(now). Never written from the UI.
    val lastSentOccurrenceMillis: Long? = null,
    val sendCount: Int = 0,
)
