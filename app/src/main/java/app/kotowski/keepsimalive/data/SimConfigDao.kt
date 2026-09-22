package app.kotowski.keepsimalive.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SimConfigDao {
    @Upsert
    fun upsert(config: SimConfigEntity)

    @Query("SELECT * FROM sim_config WHERE simId = :simId")
    fun getBySimId(simId: Int): SimConfigEntity?

    @Query("SELECT * FROM sim_config")
    fun observeAll(): Flow<List<SimConfigEntity>>

    @Query("SELECT * FROM sim_config")
    suspend fun getAll(): List<SimConfigEntity>

    // Targeted engine writes: the send engine owns only the schedule-state columns, so it must
    // never upsert the whole row - a full write from a stale read would clobber user edits
    // (recipient, message, frequency, ...) that landed in between.

    @Query("UPDATE sim_config SET nextSendAtMillis = :nextSendAtMillis WHERE simId = :simId")
    fun updateNextSend(
        simId: Int,
        nextSendAtMillis: Long?,
    )

    // The anchor is written here, inside the finalizeOutcome transaction: the SENT row flip
    // and the anchor commit together, so the anchor can never outlive (or precede) the row
    // it points at.
    @Query(
        "UPDATE sim_config SET lastSentAtMillis = :lastSentAtMillis, lastSentOccurrenceMillis = :lastSentOccurrenceMillis, sendCount = :sendCount, nextSendAtMillis = :nextSendAtMillis WHERE simId = :simId",
    )
    fun recordSuccess(
        simId: Int,
        lastSentAtMillis: Long,
        lastSentOccurrenceMillis: Long,
        sendCount: Int,
        nextSendAtMillis: Long?,
    )

    @Query("UPDATE sim_config SET enabled = 0, nextSendAtMillis = NULL WHERE simId = :simId")
    fun endSchedule(simId: Int)

    // The history-clear survival anchor (the base of the last sent occurrence): written by
    // recordSuccess with the SENT flip, read only when no SENT rows remain. Cleared only
    // when the schedule ends or the config row is deleted — never by a re-arm: a re-arm
    // writes a PENDING row, not a SENT one, so clearing it there would orphan the rhythm
    // after a later history clear.
    @Query("UPDATE sim_config SET lastSentOccurrenceMillis = NULL WHERE simId = :simId")
    fun clearLastSentOccurrence(simId: Int)

    @Query("DELETE FROM sim_config WHERE simId = :simId")
    fun deleteBySimId(simId: Int)

    // UI save writes only the user-controlled columns; engine-owned schedule state
    // (nextSendAtMillis, lastSentAtMillis, sendCount) is never overwritten from the UI cache.
    @Query(
        "UPDATE sim_config SET enabled = :enabled, recipientPhone = :recipientPhone, message = :message, hour = :hour, minute = :minute, freqType = :freqType, daysInterval = :daysInterval, monthsInterval = :monthsInterval, dayOfMonth = :dayOfMonth, selectedMonths = :selectedMonths, endType = :endType, maxSends = :maxSends, endDate = :endDate, timeWindowMinutes = :timeWindowMinutes WHERE simId = :simId",
    )
    fun updateUserColumns(
        simId: Int,
        enabled: Boolean,
        recipientPhone: String,
        message: String,
        hour: Int,
        minute: Int,
        freqType: Int,
        daysInterval: Int,
        monthsInterval: Int,
        dayOfMonth: Int,
        // Sorted comma-separated month numbers ("1,3,12"; "" = none)
        selectedMonths: String,
        endType: Int,
        maxSends: Int,
        // ISO calendar date ("yyyy-MM-dd")
        endDate: String?,
        timeWindowMinutes: Int,
    ): Int
}
