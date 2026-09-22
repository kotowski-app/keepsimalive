package app.kotowski.keepsimalive.data

import androidx.room.Room
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class KeepaliveRepositoryTest {
    private lateinit var database: KeepaliveDatabase
    private lateinit var repository: KeepaliveRepository

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
        repository = KeepaliveRepository(database, database.simConfigDao(), database.simHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // The exact pre-jitter base the engine derives for the occurrence at `millis` (Stage D:
    // ScheduleCalculator.baseOf, anchored in the system-default zone like production) — the
    // value the new rows' occurrenceBaseMillis carries.
    private fun baseOf(
        config: SimKeepaliveConfig,
        millis: Long,
    ): Long =
        ScheduleCalculator
            .baseOf(config, ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
            .toInstant()
            .toEpochMilli()

    @Test
    fun `getConfig returns null for unknown sim`() =
        runBlocking {
            assertNull(repository.getConfig(99))
        }

    @Test
    fun `saveConfig then getConfig returns same config`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 7,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    hour = 9,
                    minute = 15,
                    freqType = FrequencyType.MONTHLY,
                    monthsInterval = 2,
                    // Day 5 (not 30): the window cap leaves no room from day 28 on, so a
                    // non-zero window only round-trips on earlier days.
                    dayOfMonth = 5,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 5,
                    timeWindowMinutes = 10,
                )
            repository.saveConfig(config)
            assertEquals(config, repository.getConfig(7))
        }

    @Test
    fun `saveConfig with null end date round trips`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 8, endType = EndType.NEVER, endDate = null)
            repository.saveConfig(config)
            val loaded = repository.getConfig(8)
            assertEquals(config, loaded)
            assertNull(loaded?.endDate)
        }

    @Test
    fun `saveConfig with on date end round trips the calendar date`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 10,
                    enabled = true,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.of(2026, 12, 31),
                )
            repository.saveConfig(config)
            assertEquals(config, repository.getConfig(10))
        }

    @Test
    fun `saveConfig overwrites existing config for same sim`() =
        runBlocking {
            // Valid E.164 markers: sanitize() normalizes unsentable recipients to "", so a
            // short marker would read back as empty and hide the overwrite.
            repository.saveConfig(SimKeepaliveConfig(simId = 9, enabled = false, recipientPhone = "+15550101"))
            val updated = SimKeepaliveConfig(simId = 9, enabled = true, recipientPhone = "+15550102")
            repository.saveConfig(updated)
            assertEquals(updated, repository.getConfig(9))
        }

    @Test
    fun `configs for different sims are independent`() =
        runBlocking {
            // Valid E.164 markers (see the overwrite test): unsentable values read back empty.
            repository.saveConfig(SimKeepaliveConfig(simId = 1, recipientPhone = "+15550101"))
            repository.saveConfig(SimKeepaliveConfig(simId = 2, recipientPhone = "+15550102"))
            assertEquals("+15550101", repository.getConfig(1)?.recipientPhone)
            assertEquals("+15550102", repository.getConfig(2)?.recipientPhone)
        }

    @Test
    fun `getConfig sanitizes corrupted row`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.simConfigDao().upsert(
                    SimConfigEntity(
                        simId = 21,
                        enabled = true,
                        recipientPhone = "12345678901",
                        message = "m",
                        hour = 99,
                        minute = 99,
                        freqType = 7,
                        daysInterval = 0,
                        monthsInterval = 99,
                        dayOfMonth = 40,
                        endType = 5,
                        maxSends = 0,
                        endDate = null,
                        timeWindowMinutes = 999,
                    ),
                )
            }
            val loaded = repository.getConfig(21)
            assertEquals(23, loaded?.hour)
            assertEquals(59, loaded?.minute)
            assertEquals(FrequencyType.EVERY_N_DAYS, loaded?.freqType)
            assertEquals(1, loaded?.daysInterval)
            assertEquals(12, loaded?.monthsInterval)
            assertEquals(31, loaded?.dayOfMonth)
            assertEquals(EndType.NEVER, loaded?.endType)
            assertEquals(1, loaded?.maxSends)
            // The window is capped by the coerced rhythm (23:59 leaves no minutes in the
            // day for a window), not the global max.
            assertEquals(0, loaded?.timeWindowMinutes)
            assertEquals("", loaded?.recipientPhone)
        }

    @Test
    fun `insertHistory then getActiveHistory returns stored active row`() =
        runBlocking {
            val id =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 1,
                        scheduledForMillis = 1700000000000L,
                        occurrenceBaseMillis = 1700000000000L,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
            val active = repository.getActiveHistory(1)
            assertEquals(id, active?.id)
            assertEquals(1, active?.simId)
            assertEquals(SendOutcome.PENDING.name, active?.outcome)
        }

    @Test
    fun `clearHistory removes only terminal rows for the sim`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1700000000000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000001000L,
                    occurrenceBaseMillis = 1700000001000L,
                    outcome = SendOutcome.FAILED.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000002000L,
                    occurrenceBaseMillis = 1700000002000L,
                    outcome = SendOutcome.PENDING.name,
                    retryCount = 2,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 3,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1700000000000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "b",
                    message = "n",
                ),
            )
            repository.clearHistory(2)
            // The in-flight retry row (PENDING, retryCount > 0) survives the Clear: the send
            // engine owns it to completion (clearing it would lose the retry chain).
            val active = repository.getActiveHistory(2)
            assertEquals(1700000002000L, active?.scheduledForMillis)
            val sim2Rows = withContext(Dispatchers.IO) { database.simHistoryDao().observeNewest(2, Int.MAX_VALUE).first() }
            val sim3Rows = withContext(Dispatchers.IO) { database.simHistoryDao().observeNewest(3, Int.MAX_VALUE).first() }
            assertEquals(listOf(1700000002000L), sim2Rows.map { it.scheduledForMillis })
            assertEquals(1, sim3Rows.size)
        }

    @Test
    fun `clearPendingHistory removes only fresh pending rows`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1700000000000L,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000001000L,
                    occurrenceBaseMillis = 1700000001000L,
                    outcome = SendOutcome.SENDING.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            // The retry row (PENDING, retryCount > 0) is engine-owned and survives the clear.
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000002000L,
                    occurrenceBaseMillis = 1700000002000L,
                    outcome = SendOutcome.PENDING.name,
                    retryCount = 1,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.clearPendingHistory(2)
            val rows = withContext(Dispatchers.IO) { database.simHistoryDao().observeNewest(2, Int.MAX_VALUE).first() }
            assertEquals(listOf(1700000002000L, 1700000001000L), rows.map { it.scheduledForMillis })
        }

    @Test
    fun `alignPendingRow re-creates the pending row at the given time`() =
        runBlocking {
            // The guarded insert only runs under a live enabled schedule.
            repository.saveConfig(SimKeepaliveConfig(simId = 2, enabled = true))
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1700000000000L,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.alignPendingRow(2, 1700000009000L, 1700000009000L, "b", "n")
            val rows = withContext(Dispatchers.IO) { database.simHistoryDao().observeNewest(2, Int.MAX_VALUE).first() }
            assertEquals(listOf(1700000009000L), rows.map { it.scheduledForMillis })
            assertEquals("b", rows.single().recipient)
            assertEquals("n", rows.single().message)
        }

    @Test
    fun `alignPendingRow never shadows an in-flight row`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000001000L,
                    occurrenceBaseMillis = 1700000001000L,
                    outcome = SendOutcome.SENDING.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.alignPendingRow(2, 1700000009000L, 1700000009000L, "b", "n")
            val rows = withContext(Dispatchers.IO) { database.simHistoryDao().observeNewest(2, Int.MAX_VALUE).first() }
            assertEquals(listOf(1700000001000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.SENDING.name, rows.single().outcome)
        }

    @Test
    fun `finalizeOutcome success writes the SENT row, the counters, the anchor and the next row together`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 1,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                ),
            )
            // The row's delivery time (1_700_000_000_000) is its base (1_699_000_000_000)
            // plus a re-rolled window jitter: the anchor written on success is the base,
            // not the delivery instant.
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 1,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_699_000_000_000L,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "+15550100",
                        message = "keep alive",
                        lastAttemptAtMillis = 1_700_000_000_000L,
                        firstAttemptAtMillis = 1_700_000_000_000L,
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    1_700_000_000_500L,
                    null,
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.Success(
                        1,
                        1_699_000_000_000L,
                        1,
                        1_700_100_000_000L,
                        1_700_099_000_000L,
                        "+15550100",
                        "keep alive",
                    ),
                )

            assertTrue(applied)
            val rows = repository.observeNewest(1, Int.MAX_VALUE).first()
            val row = rows.single { it.scheduledForMillis == 1_700_000_000_000L }
            assertEquals(SendOutcome.SENT.name, row.outcome)
            assertEquals(1_700_000_000_500L, row.lastAttemptAtMillis)
            val config = repository.getConfig(1)
            assertEquals(1, config?.sendCount)
            assertEquals(1_700_000_000_500L, config?.lastSentAtMillis)
            // The last-sent-occurrence anchor is the row's base, committed with the row flip.
            assertEquals(1_699_000_000_000L, config?.lastSentOccurrenceMillis)
            assertEquals(1_700_100_000_000L, config?.nextSendAtMillis)
            assertEquals(true, config?.enabled)
            val nextRow = rows.single { it.scheduledForMillis == 1_700_100_000_000L }
            assertEquals(SendOutcome.PENDING.name, nextRow.outcome)
            assertEquals(1_700_099_000_000L, nextRow.occurrenceBaseMillis)
            assertEquals("+15550100", nextRow.recipient)
            assertEquals("keep alive", nextRow.message)
        }

    @Test
    fun `finalizeOutcome success ending writes the SENT row and the end together, no next row`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 2,
                    enabled = true,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 2,
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 2,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_699_000_000_000L,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "a",
                        message = "m",
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    1_700_000_000_500L,
                    null,
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.Success(2, 1_699_000_000_000L, 3, null, null, "a", "m"),
                )

            assertTrue(applied)
            assertEquals(
                SendOutcome.SENT.name,
                repository
                    .observeNewest(2, Int.MAX_VALUE)
                    .first()
                    .single()
                    .outcome,
            )
            val config = repository.getConfig(2)
            assertEquals(false, config?.enabled)
            assertEquals(3, config?.sendCount)
            assertEquals(1_700_000_000_500L, config?.lastSentAtMillis)
            // The final send's occurrence base is anchored with the end, in the same
            // transaction.
            assertEquals(1_699_000_000_000L, config?.lastSentOccurrenceMillis)
            assertNull(config?.nextSendAtMillis)
            assertNull(repository.getActiveHistory(2))
        }

    @Test
    fun `finalizeOutcome on an already terminal row writes nothing`() =
        runBlocking {
            // The config carries the pre-finalize state: the concurrent owner's (the stale
            // check's) advance already landed.
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 3,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    lastSentAtMillis = 1_600_000_000_000L,
                    lastSentOccurrenceMillis = 1_600_000_000_001L,
                    sendCount = 2,
                    nextSendAtMillis = 1_600_100_000_000L,
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 3,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_700_000_000_000L,
                        outcome = SendOutcome.SKIPPED.name,
                        failureReason = "Send interrupted",
                        recipient = "+15550100",
                        message = "keep alive",
                        lastAttemptAtMillis = 1_700_000_000_000L,
                        firstAttemptAtMillis = 1_700_000_000_000L,
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    1_700_000_000_500L,
                    null,
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.Success(
                        3,
                        1_699_000_000_000L,
                        3,
                        1_700_100_000_000L,
                        1_700_099_000_000L,
                        "+15550100",
                        "keep alive",
                    ),
                )

            assertFalse(applied)
            val rows = repository.observeNewest(3, Int.MAX_VALUE).first()
            val row = rows.single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals("Send interrupted", row.failureReason)
            assertEquals(1_700_000_000_000L, row.lastAttemptAtMillis)
            // The loser's consequence write is skipped with the transaction: the concurrent
            // owner's advance stands untouched.
            assertEquals(1, rows.size)
            val config = repository.getConfig(3)
            assertEquals(2, config?.sendCount)
            assertEquals(1_600_000_000_000L, config?.lastSentAtMillis)
            assertEquals(1_600_000_000_001L, config?.lastSentOccurrenceMillis)
            assertEquals(1_600_100_000_000L, config?.nextSendAtMillis)
        }

    @Test
    fun `finalizeOutcome advanceToNext from a pending row flips, advances and arms the next row`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 4,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 4,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_700_000_000_000L,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.PENDING,
                    SendOutcome.SKIPPED,
                    null,
                    "No permission at send",
                    0,
                    null,
                    null,
                    FinalizeAdvance.AdvanceToNext(
                        4,
                        1_700_100_000_000L,
                        1_700_099_000_000L,
                        "+15550100",
                        "keep alive",
                    ),
                )

            assertTrue(applied)
            val rows = repository.observeNewest(4, Int.MAX_VALUE).first()
            val row = rows.single { it.scheduledForMillis == 1_700_000_000_000L }
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals("No permission at send", row.failureReason)
            assertNull(row.lastAttemptAtMillis)
            assertEquals(1_700_100_000_000L, repository.getConfig(4)?.nextSendAtMillis)
            val nextRow = rows.single { it.scheduledForMillis == 1_700_100_000_000L }
            assertEquals(SendOutcome.PENDING.name, nextRow.outcome)
            assertEquals(1_700_099_000_000L, nextRow.occurrenceBaseMillis)
        }

    @Test
    fun `finalizeOutcome advanceToNext from a sending row flips, advances and arms the next row`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 5,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 5,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_700_000_000_000L,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "+15550100",
                        message = "keep alive",
                        lastAttemptAtMillis = 1_700_000_000_000L,
                        firstAttemptAtMillis = 1_700_000_000_000L,
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.FAILED,
                    1_700_000_000_500L,
                    "SIM not present",
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.AdvanceToNext(
                        5,
                        1_700_100_000_000L,
                        1_700_099_000_000L,
                        "+15550100",
                        "keep alive",
                    ),
                )

            assertTrue(applied)
            val rows = repository.observeNewest(5, Int.MAX_VALUE).first()
            val row = rows.single { it.scheduledForMillis == 1_700_000_000_000L }
            assertEquals(SendOutcome.FAILED.name, row.outcome)
            assertEquals("SIM not present", row.failureReason)
            assertEquals(1_700_000_000_500L, row.lastAttemptAtMillis)
            assertEquals(1_700_100_000_000L, repository.getConfig(5)?.nextSendAtMillis)
            assertEquals(1_700_100_000_000L, rows.single { it.outcome == SendOutcome.PENDING.name }.scheduledForMillis)
        }

    @Test
    fun `finalizeOutcome end flips the row and ends the schedule, no row written`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 6,
                    enabled = true,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 2,
                    sendCount = 1,
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 6,
                        scheduledForMillis = 1_700_100_000_000L,
                        occurrenceBaseMillis = 1_700_100_000_000L,
                        recipient = "a",
                        message = "m",
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.PENDING,
                    SendOutcome.SKIPPED,
                    null,
                    "Ended after 2 sends",
                    0,
                    null,
                    null,
                    FinalizeAdvance.End(6),
                )

            assertTrue(applied)
            val rows = repository.observeNewest(6, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SKIPPED.name, rows.single().outcome)
            assertEquals("Ended after 2 sends", rows.single().failureReason)
            val config = repository.getConfig(6)
            assertEquals(false, config?.enabled)
            assertNull(config?.nextSendAtMillis)
        }

    @Test
    fun `finalizeOutcome disable closes the row, clears the next send and keeps the SIM off`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 7,
                    enabled = false,
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 7,
                        scheduledForMillis = 1_700_100_000_000L,
                        occurrenceBaseMillis = 1_700_100_000_000L,
                        recipient = "a",
                        message = "m",
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.PENDING,
                    SendOutcome.SKIPPED,
                    null,
                    "Disabled before send",
                    0,
                    null,
                    null,
                    FinalizeAdvance.Disable(7),
                )

            assertTrue(applied)
            val row = repository.observeNewest(7, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals("Disabled before send", row.failureReason)
            val config = repository.getConfig(7)
            assertEquals(false, config?.enabled)
            assertNull(config?.nextSendAtMillis)
            assertNull(repository.getActiveHistory(7))
        }

    @Test
    fun `finalizeOutcome autoDisable disables with the config's user columns and clears the next send`() =
        runBlocking {
            val inLockConfig =
                SimKeepaliveConfig(
                    simId = 8,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    hour = 9,
                    minute = 15,
                    lastSentAtMillis = 1_600_000_000_000L,
                    sendCount = 5,
                    nextSendAtMillis = 1_700_100_000_000L,
                )
            repository.saveConfig(inLockConfig)
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 8,
                        scheduledForMillis = 1_700_100_000_000L,
                        occurrenceBaseMillis = 1_700_100_000_000L,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )

            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.PENDING,
                    SendOutcome.FAILED,
                    1_700_000_000_500L,
                    "Auto-disabled after 14 SIM-not-present failures",
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.AutoDisable(inLockConfig),
                )

            assertTrue(applied)
            val row = repository.observeNewest(8, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.FAILED.name, row.outcome)
            assertEquals("Auto-disabled after 14 SIM-not-present failures", row.failureReason)
            val config = repository.getConfig(8)
            assertEquals(false, config?.enabled)
            // The user columns come from the in-lock config, not a UI-cached snapshot.
            assertEquals("+15550100", config?.recipientPhone)
            assertEquals("keep alive", config?.message)
            assertEquals(9, config?.hour)
            assertEquals(15, config?.minute)
            // Engine columns the auto-disable does not own are untouched.
            assertEquals(1_600_000_000_000L, config?.lastSentAtMillis)
            assertEquals(5, config?.sendCount)
            assertNull(config?.nextSendAtMillis)
            assertNull(repository.getActiveHistory(8))
        }

    @Test
    fun `finalizeOutcome with a stale bound refuses a fresh sending row`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 9,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            val now = 1_700_000_000_000L
            val rowId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 9,
                        scheduledForMillis = now,
                        occurrenceBaseMillis = now,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "+15550100",
                        message = "keep alive",
                        lastAttemptAtMillis = now,
                        firstAttemptAtMillis = now,
                    ),
                )

            // A live attempt (fresh lastAttemptAtMillis) is never stale: the guarded flip
            // must refuse it and the advance must not land.
            val applied =
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SKIPPED,
                    now,
                    "Send interrupted",
                    0,
                    now,
                    now - AppConfig.SENDING_STALE_MS,
                    FinalizeAdvance.AdvanceToNext(
                        9,
                        now + AppConfig.DAY_MS,
                        now + AppConfig.DAY_MS,
                        "+15550100",
                        "keep alive",
                    ),
                )

            assertFalse(applied)
            val row = repository.observeNewest(9, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            assertEquals(now, row.lastAttemptAtMillis)
            assertEquals(1_700_100_000_000L, repository.getConfig(9)?.nextSendAtMillis)
        }

    @Test
    fun `finalizeGraceSkips flips the anchor, records the misses and re-arms together`() =
        runBlocking {
            // Daily rhythm: the 48h-spaced walk elements below keep distinct bases in any
            // zone, so the base-keyed guards act on distinct occurrences.
            val d1 = 1_700_000_000_000L
            val d2 = d1 + 2L * AppConfig.DAY_MS
            val d3 = d1 + 3L * AppConfig.DAY_MS
            val config =
                SimKeepaliveConfig(
                    simId = 10,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 1,
                )
            repository.saveConfig(config)
            val anchorId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 10,
                        scheduledForMillis = d1,
                        occurrenceBaseMillis = d1,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
            val anchor = requireNotNull(repository.getActiveHistory(10))

            val applied =
                repository.finalizeGraceSkips(
                    config,
                    anchor,
                    listOf(d2),
                    "Missed occurrence",
                    d3,
                )

            assertTrue(applied)
            val rows = repository.observeNewest(10, Int.MAX_VALUE).first()
            val anchorRow = rows.single { it.id == anchorId }
            assertEquals(SendOutcome.SKIPPED.name, anchorRow.outcome)
            assertEquals("Missed occurrence", anchorRow.failureReason)
            assertNull(anchorRow.lastAttemptAtMillis)
            val missRow = rows.single { it.scheduledForMillis == d2 }
            assertEquals(SendOutcome.SKIPPED.name, missRow.outcome)
            assertEquals("Missed occurrence", missRow.failureReason)
            assertEquals(baseOf(config, d2), missRow.occurrenceBaseMillis)
            // The re-arm landed with the flips, carrying the exact pre-jitter base.
            assertEquals(d3, repository.getConfig(10)?.nextSendAtMillis)
            val armed = requireNotNull(repository.getActiveHistory(10))
            assertEquals(d3, armed.scheduledForMillis)
            assertEquals(baseOf(config, d3), armed.occurrenceBaseMillis)
        }

    @Test
    fun `finalizeGraceSkips without an anchor records the misses and re-arms`() =
        runBlocking {
            // Daily rhythm with 48h-spaced elements: distinct bases in any zone.
            val d1 = 1_700_000_000_000L
            val d2 = d1 + 2L * AppConfig.DAY_MS
            val d3 = d1 + 3L * AppConfig.DAY_MS
            val config =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 1,
                )
            repository.saveConfig(config)

            val applied =
                repository.finalizeGraceSkips(
                    config,
                    null,
                    listOf(d1, d2),
                    "Missed occurrence",
                    d3,
                )

            assertTrue(applied)
            val rows = repository.observeNewest(11, Int.MAX_VALUE).first()
            assertEquals(
                listOf(d3, d2, d1),
                rows.map { it.scheduledForMillis },
            )
            assertEquals(
                listOf(SendOutcome.PENDING.name, SendOutcome.SKIPPED.name, SendOutcome.SKIPPED.name),
                rows.map { it.outcome },
            )
            assertEquals(d3, repository.getConfig(11)?.nextSendAtMillis)
        }

    @Test
    fun `finalizeGraceSkips on a claimed anchor writes nothing`() =
        runBlocking {
            // Daily rhythm with 48h-spaced elements: distinct bases in any zone.
            val d1 = 1_700_000_000_000L
            val d2 = d1 + 2L * AppConfig.DAY_MS
            val d3 = d1 + 3L * AppConfig.DAY_MS
            val config =
                SimKeepaliveConfig(
                    simId = 12,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 1,
                    nextSendAtMillis = d1,
                )
            repository.saveConfig(config)
            // The anchor row, then claimed to SENDING between the read and the finalize.
            val anchorId =
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 12,
                        scheduledForMillis = d1,
                        occurrenceBaseMillis = d1,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
            val anchor = requireNotNull(repository.getActiveHistory(12))
            withContext(Dispatchers.IO) {
                database.simHistoryDao().claimOccurrence(anchorId, d1, d1)
            }

            val applied =
                repository.finalizeGraceSkips(
                    config,
                    anchor,
                    listOf(d2),
                    "Missed occurrence",
                    d3,
                )

            assertFalse(applied)
            val rows = repository.observeNewest(12, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SENDING.name, rows.single().outcome)
            assertEquals(d1, repository.getConfig(12)?.nextSendAtMillis)
        }

    @Test
    fun `finalizeGraceSkips does not duplicate an already recorded miss`() =
        runBlocking {
            // Daily rhythm with 48h-spaced elements: distinct bases in any zone.
            val d1 = 1_700_000_000_000L
            val d2 = d1 + 2L * AppConfig.DAY_MS
            val d3 = d1 + 3L * AppConfig.DAY_MS
            val config =
                SimKeepaliveConfig(
                    simId = 13,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 1,
                )
            repository.saveConfig(config)
            // A terminal row already proves the first missed occurrence (by its exact base),
            // whatever jittered delivery instant its row carries.
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 13,
                    scheduledForMillis = d1 + 111_111L,
                    occurrenceBaseMillis = baseOf(config, d1),
                    outcome = SendOutcome.SKIPPED.name,
                    failureReason = "Missed occurrence",
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )

            val applied =
                repository.finalizeGraceSkips(
                    config,
                    null,
                    listOf(d1, d2),
                    "Missed occurrence",
                    d3,
                )

            assertTrue(applied)
            val rows = repository.observeNewest(13, Int.MAX_VALUE).first()
            // One row per occurrence: the pre-existing skip is not duplicated.
            assertEquals(
                listOf(d3, d2, d1 + 111_111L),
                rows.map { it.scheduledForMillis },
            )
            assertEquals(1, rows.count { it.occurrenceBaseMillis == baseOf(config, d1) })
        }

    @Test
    fun `finalizeOutcome rolls back the flip when the consequence write fails`() {
        val simConfigDao: SimConfigDao = mock()
        doThrow(
            RuntimeException("crash in the consequence write"),
        ).whenever(simConfigDao).recordSuccess(any(), any(), any(), any(), anyOrNull())
        val rowId =
            runBlocking {
                repository.insertHistory(
                    SendHistoryEntity(
                        simId = 14,
                        scheduledForMillis = 1_700_000_000_000L,
                        occurrenceBaseMillis = 1_700_000_000_000L,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "a",
                        message = "m",
                        lastAttemptAtMillis = 1_700_000_000_000L,
                        firstAttemptAtMillis = 1_700_000_000_000L,
                    ),
                )
            }
        val repo = KeepaliveRepository(database, simConfigDao, database.simHistoryDao())
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                repo.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    1_700_000_000_500L,
                    null,
                    0,
                    1_700_000_000_000L,
                    null,
                    FinalizeAdvance.Success(14, 1_700_000_000_000L, 1, 1_700_100_000_000L, 1_700_099_000_000L, "a", "m"),
                )
            }
        }
        // One transaction: the consequence failure rolled the flip back, so the row is
        // still open and nothing advanced.
        val row = runBlocking { repository.observeNewest(14, Int.MAX_VALUE).first().single() }
        assertEquals(SendOutcome.SENDING.name, row.outcome)
        assertNull(row.failureReason)
        assertNull(runBlocking { repository.getConfig(14) })
    }

    @Test
    fun `finalizeGraceSkips rolls back the anchor flip and the misses when the re-arm write fails`() {
        val simConfigDao: SimConfigDao = mock()
        doThrow(RuntimeException("crash in the re-arm write")).whenever(simConfigDao).updateNextSend(any(), anyOrNull())
        val repo = KeepaliveRepository(database, simConfigDao, database.simHistoryDao())
        runBlocking {
            // Daily rhythm with 48h-spaced elements: distinct bases in any zone.
            val d1 = 1_700_000_000_000L
            val d2 = d1 + 2L * AppConfig.DAY_MS
            val d3 = d1 + 3L * AppConfig.DAY_MS
            val config =
                SimKeepaliveConfig(
                    simId = 15,
                    enabled = true,
                    recipientPhone = "a",
                    message = "m",
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 1,
                    nextSendAtMillis = d1,
                )
            repository.saveConfig(config)
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 15,
                    scheduledForMillis = d1,
                    occurrenceBaseMillis = d1,
                    recipient = "a",
                    message = "m",
                ),
            )
            val anchor = requireNotNull(repository.getActiveHistory(15))
            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    repo.finalizeGraceSkips(
                        config,
                        anchor,
                        listOf(d2),
                        "Missed occurrence",
                        d3,
                    )
                }
            }
            val rows = repository.observeNewest(15, Int.MAX_VALUE).first()
            // One transaction: the anchor flip and the miss record rolled back with the
            // failed re-arm.
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.PENDING.name, rows.single().outcome)
            assertEquals(d1, repository.getConfig(15)?.nextSendAtMillis)
        }
    }

    @Test
    fun `observeHistoryCount counts only the terminal rows of the sim`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_001_000L,
                    occurrenceBaseMillis = 1_700_000_001_000L,
                    outcome = SendOutcome.FAILED.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            // A live (non-terminal) row of the same sim and a terminal row of another sim
            // must not count.
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_002_000L,
                    occurrenceBaseMillis = 1_700_000_002_000L,
                    outcome = SendOutcome.PENDING.name,
                    retryCount = 2,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            assertEquals(2, repository.observeHistoryCount(1).first())
            assertEquals(1, repository.observeHistoryCount(2).first())
        }

    @Test
    fun `observeNewest passes the live window through with the limit`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_003_000L,
                    occurrenceBaseMillis = 1_700_000_003_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_002_000L,
                    occurrenceBaseMillis = 1_700_000_002_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_001_000L,
                    occurrenceBaseMillis = 1_700_000_001_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1_700_000_009_000L,
                    occurrenceBaseMillis = 1_700_000_009_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            val rows = repository.observeNewest(1, 2).first()
            assertEquals(listOf(1_700_000_003_000L, 1_700_000_002_000L), rows.map { it.scheduledForMillis })
        }

    @Test
    fun `insertSkippedIfNotTerminal records the skip only when the occurrence is not already terminal`() =
        runBlocking {
            // The guarded insert only runs under a live enabled schedule.
            repository.saveConfig(SimKeepaliveConfig(simId = 3, enabled = true))
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 3,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertSkippedIfNotTerminal(3, 1_700_000_000_000L, 1_700_000_000_000L, "late", "a", "m")
            repository.insertSkippedIfNotTerminal(3, 1_700_000_001_000L, 1_700_000_001_000L, "late", "a", "m")
            val rows = repository.observeNewest(3, Int.MAX_VALUE).first()
            assertEquals(listOf(1_700_000_001_000L, 1_700_000_000_000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.SENT.name, rows.single { it.scheduledForMillis == 1_700_000_000_000L }.outcome)
            assertEquals(SendOutcome.SKIPPED.name, rows.single { it.scheduledForMillis == 1_700_000_001_000L }.outcome)
        }

    @Test
    fun `latestSentMillis reads the newest SENT row`() =
        runBlocking {
            assertNull(repository.latestSentMillis(4))
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 4,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 4,
                    scheduledForMillis = 1_700_000_001_000L,
                    occurrenceBaseMillis = 1_700_000_001_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 4,
                    scheduledForMillis = 1_700_000_002_000L,
                    occurrenceBaseMillis = 1_700_000_002_000L,
                    outcome = SendOutcome.PENDING.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            assertEquals(1_700_000_001_000L, repository.latestSentMillis(4))
        }

    @Test
    fun `observeInFlightStates maps dao rows to a per-sim state`() =
        runBlocking {
            // The DAO returns SENDING rows plus PENDING rows with retryCount > 0 (the
            // retry rows); each maps under its simId.
            val simHistoryDao: SimHistoryDao = mock()
            val rows =
                MutableStateFlow(
                    listOf(
                        InFlightOccurrence(1, SendOutcome.PENDING.name, 2, 1_700_000_001_000L, 1_700_000_002_000L),
                        InFlightOccurrence(2, SendOutcome.SENDING.name, 0, 1_700_000_003_000L, 1_700_000_004_000L),
                    ),
                )
            whenever(simHistoryDao.observeInFlight()).thenReturn(rows)
            val repo = KeepaliveRepository(database, database.simConfigDao(), simHistoryDao)
            assertEquals(
                mapOf(
                    1 to InFlightOccurrence(1, SendOutcome.PENDING.name, 2, 1_700_000_001_000L, 1_700_000_002_000L),
                    2 to InFlightOccurrence(2, SendOutcome.SENDING.name, 0, 1_700_000_003_000L, 1_700_000_004_000L),
                ),
                repo.observeInFlightStates().first(),
            )
            rows.value =
                listOf(
                    InFlightOccurrence(3, SendOutcome.SENDING.name, 1, 1_700_000_005_000L, 1_700_000_006_000L),
                )
            assertEquals(
                mapOf(3 to InFlightOccurrence(3, SendOutcome.SENDING.name, 1, 1_700_000_005_000L, 1_700_000_006_000L)),
                repo.observeInFlightStates().first(),
            )
        }

    @Test
    fun `observeInFlightStates prefers sending when a sim has both states`() =
        runBlocking {
            // One SIM somehow holding both a retry row and a SENDING row (they cannot — one
            // open row per SIM — but the merge must be defensive): the SENDING row wins.
            val simHistoryDao: SimHistoryDao = mock()
            whenever(
                simHistoryDao.observeInFlight(),
            ).thenReturn(
                MutableStateFlow(
                    listOf(
                        InFlightOccurrence(1, SendOutcome.PENDING.name, 2, 1_700_000_001_000L, 1_700_000_002_000L),
                        InFlightOccurrence(1, SendOutcome.SENDING.name, 2, 1_700_000_003_000L, 1_700_000_004_000L),
                    ),
                ),
            )
            val repo = KeepaliveRepository(database, database.simConfigDao(), simHistoryDao)
            assertEquals(
                mapOf(1 to InFlightOccurrence(1, SendOutcome.SENDING.name, 2, 1_700_000_003_000L, 1_700_000_004_000L)),
                repo.observeInFlightStates().first(),
            )
        }

    @Test
    fun `saveUserColumns preserves engine-owned schedule state`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 5,
                    enabled = true,
                    recipientPhone = "+15550100",
                    message = "original",
                    hour = 9,
                    minute = 0,
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 5,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1699000000000L,
                    outcome = SendOutcome.SENDING.name,
                    recipient = "+15550100",
                    message = "original",
                    lastAttemptAtMillis = 1700000000000L,
                    firstAttemptAtMillis = 1700000000000L,
                ),
            )

            // The UI save takes a snapshot of the row, the engine finalizes the send in
            // the meantime (the SENT flip and the schedule advance commit together), and
            // only then the save lands: the engine's sendCount/lastSent/nextSend must
            // survive, because the save writes user columns only.
            val snapshot =
                requireNotNull(repository.getConfig(5))
            val rowId = requireNotNull(repository.getActiveHistory(5)).id
            assertTrue(
                repository.finalizeOutcome(
                    rowId,
                    SendOutcome.SENDING,
                    SendOutcome.SENT,
                    1700000000000L,
                    null,
                    0,
                    1700000000000L,
                    null,
                    FinalizeAdvance.Success(5, 1699000000000L, 3, 1700100000000L, 1699100000000L, "+15550100", "original"),
                ),
            )
            repository.saveUserColumns(
                snapshot.copy(
                    recipientPhone = "+15550101",
                    message = "updated",
                    hour = 14,
                    minute = 30,
                ),
            )

            val after = repository.getConfig(5)
            assertEquals("+15550101", after?.recipientPhone)
            assertEquals("updated", after?.message)
            assertEquals(14, after?.hour)
            assertEquals(30, after?.minute)
            // The occurrence anchor is engine-owned too: a UI save never clobbers it.
            assertEquals(3, after?.sendCount)
            assertEquals(1700000000000L, after?.lastSentAtMillis)
            assertEquals(1699000000000L, after?.lastSentOccurrenceMillis)
            assertEquals(1700100000000L, after?.nextSendAtMillis)
        }

    @Test
    fun `clearLastSentOccurrence clears only the anchor column`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 4,
                    enabled = true,
                    lastSentAtMillis = 1_700_000_000_000L,
                    lastSentOccurrenceMillis = 1_699_000_000_000L,
                    sendCount = 2,
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            repository.clearLastSentOccurrence(4)
            val config = repository.getConfig(4)
            assertNull(config?.lastSentOccurrenceMillis)
            assertEquals(1_700_000_000_000L, config?.lastSentAtMillis)
            assertEquals(2, config?.sendCount)
            assertEquals(1_700_100_000_000L, config?.nextSendAtMillis)
        }

    @Test
    fun `countFinalizedByBase reports terminal rows by base, whatever their delivery time`() =
        runBlocking {
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_002_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SKIPPED.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            assertEquals(1, repository.countFinalizedByBase(1, 1_700_000_000_000L))
            assertEquals(0, repository.countFinalizedByBase(1, 1_700_000_001_000L))
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1_700_000_003_000L,
                    occurrenceBaseMillis = 1_700_000_001_000L,
                    outcome = SendOutcome.PENDING.name,
                    recipient = "a",
                    message = "m",
                ),
            )
            assertEquals(0, repository.countFinalizedByBase(1, 1_700_000_001_000L))
        }

    @Test
    fun `deleteSim removes the config and every history row including open ones`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 3,
                    enabled = true,
                    recipientPhone = "+15550100",
                    lastSentAtMillis = 1_700_000_000_000L,
                    sendCount = 2,
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 3,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 3,
                    scheduledForMillis = 1_700_100_000_000L,
                    occurrenceBaseMillis = 1_700_100_000_000L,
                    outcome = SendOutcome.PENDING.name,
                    retryCount = 1,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            // Another SIM's data must survive the forget.
            repository.saveConfig(SimKeepaliveConfig(simId = 4, enabled = true))
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 4,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )

            repository.deleteSim(3)

            assertNull(repository.getConfig(3))
            assertEquals(0, repository.observeNewest(3, Int.MAX_VALUE).first().size)
            assertNotNull(repository.getConfig(4))
            assertEquals(1, repository.observeNewest(4, Int.MAX_VALUE).first().size)
        }

    @Test
    fun `deleteSim is one transaction, a failed history delete never reaches the config delete`() {
        val simHistoryDao: SimHistoryDao = mock()
        val simConfigDao: SimConfigDao = mock()
        doThrow(RuntimeException("crash between the deletes")).whenever(simHistoryDao).deleteAllBySimId(3)
        val repo = KeepaliveRepository(database, simConfigDao, simHistoryDao)
        assertThrows(RuntimeException::class.java) {
            runBlocking { repo.deleteSim(3) }
        }
        verify(simConfigDao, never()).deleteBySimId(3)
    }

    @Test
    fun `deleteConfig removes the config row and keeps every history row including open ones`() =
        runBlocking {
            repository.saveConfig(
                SimKeepaliveConfig(
                    simId = 5,
                    enabled = true,
                    recipientPhone = "+15550100",
                    lastSentAtMillis = 1_700_000_000_000L,
                    sendCount = 2,
                    nextSendAtMillis = 1_700_100_000_000L,
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 5,
                    scheduledForMillis = 1_700_000_000_000L,
                    occurrenceBaseMillis = 1_700_000_000_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            repository.insertHistory(
                SendHistoryEntity(
                    simId = 5,
                    scheduledForMillis = 1_700_100_000_000L,
                    occurrenceBaseMillis = 1_700_100_000_000L,
                    outcome = SendOutcome.PENDING.name,
                    retryCount = 1,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            // Another SIM's data must survive the unconfigure.
            repository.saveConfig(SimKeepaliveConfig(simId = 6, enabled = true))

            repository.deleteConfig(5)

            assertNull(repository.getConfig(5))
            // Unconfigure keeps the history (terminal and open rows alike): the screen's
            // history section must survive, and the open row is the caller's to finalize.
            assertEquals(2, repository.observeNewest(5, Int.MAX_VALUE).first().size)
            assertNotNull(repository.getConfig(6))
        }
}
