package app.kotowski.keepsimalive.data

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimHistoryDaoTest {
    private lateinit var database: KeepaliveDatabase
    private lateinit var dao: SimHistoryDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.simHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(
        simId: Int,
        scheduledForMillis: Long,
        outcome: String = SendOutcome.PENDING.name,
        firstAttemptAtMillis: Long? = null,
        // The occurrence base defaults to the delivery instant (window 0); the base-keyed
        // guard tests pass a different one to simulate a re-rolled jitter.
        occurrenceBaseMillis: Long? = null,
        // Non-zero for a retry row (a PENDING row the engine re-armed after a failure).
        retryCount: Int = 0,
    ) = SendHistoryEntity(
        simId = simId,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = occurrenceBaseMillis ?: scheduledForMillis,
        outcome = outcome,
        recipient = "+15550100",
        message = "keep alive",
        firstAttemptAtMillis = firstAttemptAtMillis,
        retryCount = retryCount,
    )

    // The guarded inserts (alignPendingRow / insertSkippedIfNotTerminal) only run under a live
    // enabled config, so the tests that expect a row upsert one.
    private fun upsertConfig(
        simId: Int,
        enabled: Boolean = true,
    ) {
        database.simConfigDao().upsert(
            SimConfigEntity(
                simId = simId,
                enabled = enabled,
                recipientPhone = "+15550100",
                message = "keep alive",
                hour = 12,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 0,
                maxSends = 1,
                timeWindowMinutes = 0,
            ),
        )
    }

    @Test
    fun `insert returns incrementing ids`() {
        val first = dao.insert(entity(simId = 1, scheduledForMillis = 1000))
        val second = dao.insert(entity(simId = 1, scheduledForMillis = 2000))
        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun `observeNewest returns newest first and is isolated per simId`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000))
            dao.insert(entity(simId = 2, scheduledForMillis = 9000))
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(3000L, 2000L, 1000L), rows.map { it.scheduledForMillis })
            assertEquals(listOf(1, 1, 1), rows.map { it.simId })
        }

    @Test
    fun `observeNewest returns at most limit rows, newest first, for that sim only`() =
        runBlocking {
            // The limit is the detail screen's per-SIM load window.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.FAILED.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 5000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 9000, outcome = SendOutcome.SENT.name))
            val rows = dao.observeNewest(1, 3).first()
            assertEquals(listOf(5000L, 4000L, 3000L), rows.map { it.scheduledForMillis })
            assertEquals(listOf(9000L), dao.observeNewest(2, 3).first().map { it.scheduledForMillis })
        }

    @Test
    fun `observeNewest breaks timestamp ties by id desc and is stable across emissions`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 5000, outcome = SendOutcome.SENT.name)) // id 1
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.SENT.name)) // id 2
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.FAILED.name)) // id 3
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name)) // id 4
            dao.insert(entity(simId = 2, scheduledForMillis = 9000, outcome = SendOutcome.SENT.name)) // id 5
            val flow = dao.observeNewest(1, Int.MAX_VALUE)
            assertEquals(listOf(1L, 3L, 2L, 4L), flow.first().map { it.id })
            // A re-emitting write must keep the tie order (no flicker): flip the newer tied row in place.
            dao.updateOutcome(3L, SendOutcome.FAILED.name, 4000L, "radio off", 0, 4000L)
            assertEquals(listOf(1L, 3L, 2L, 4L), flow.first().map { it.id })
            // Another SIM's rows never leak into the list.
            assertEquals(listOf(5L), dao.observeNewest(2, Int.MAX_VALUE).first().map { it.id })
        }

    @Test
    fun `observeNewest orders by timestamp desc then id desc and caps at limit`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 5000, outcome = SendOutcome.SENT.name)) // id 1
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.SENT.name)) // id 2
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.FAILED.name)) // id 3
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name)) // id 4
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENT.name)) // id 5
            dao.insert(entity(simId = 2, scheduledForMillis = 9000, outcome = SendOutcome.SENT.name)) // id 6
            val page = dao.observeNewest(1, 4).first()
            assertEquals(listOf(1L, 3L, 2L, 4L), page.map { it.id })
            // Another SIM's rows never leak into the page.
            assertEquals(listOf(6L), dao.observeNewest(2, 4).first().map { it.id })
        }

    @Test
    fun `observeNewest re-emits on insert and on the in-flight row's terminal flip`() =
        runBlocking {
            // The live window: rows landing or flipping while the screen is open must surface without interaction.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name)) // id 1
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENT.name)) // id 2
            val flow = dao.observeNewest(1, Int.MAX_VALUE)
            assertEquals(listOf(2L, 1L), flow.first().map { it.id })
            val live = dao.insert(entity(simId = 1, scheduledForMillis = 3000)) // id 3, PENDING
            assertEquals(listOf(3L, 2L, 1L), flow.first().map { it.id })
            dao.updateOutcome(live, SendOutcome.SENT.name, 2500L, null, 0, 2500L)
            assertEquals(SendOutcome.SENT.name, flow.first().first { it.id == 3L }.outcome)
        }

    @Test
    fun `observeTerminalCount counts only the terminal rows of that sim`() =
        runBlocking {
            // The header count excludes the live (non-terminal) rows and other SIMs' rows:
            // the loaded list is capped, so its in-memory size cannot be the total.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.FAILED.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SKIPPED.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 5000, outcome = SendOutcome.SENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 6000, outcome = SendOutcome.PENDING.name, retryCount = 2))
            dao.insert(entity(simId = 2, scheduledForMillis = 7000, outcome = SendOutcome.SENT.name))
            assertEquals(3, dao.observeTerminalCount(1).first())
            assertEquals(1, dao.observeTerminalCount(2).first())
            assertEquals(0, dao.observeTerminalCount(3).first())
        }

    @Test
    fun `observeInFlight returns only sims with sending or retry rows`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            val retryRow = dao.insert(entity(simId = 1, scheduledForMillis = 2000, retryCount = 2))
            dao.updateOutcome(retryRow, SendOutcome.PENDING.name, 1500L, "no service", 2, 1500L)
            val sending = dao.insert(entity(simId = 2, scheduledForMillis = 3000))
            dao.updateOutcome(sending, SendOutcome.SENDING.name, 3000L, null, 0, null)
            val sent = dao.insert(entity(simId = 3, scheduledForMillis = 4000, outcome = SendOutcome.SENT.name))
            dao.updateOutcome(sent, SendOutcome.SENT.name, 4000L, null, 3, 3500L)
            val rows = dao.observeInFlight().first()
            assertEquals(
                setOf(1 to SendOutcome.PENDING.name, 2 to SendOutcome.SENDING.name),
                rows.map { it.simId to it.outcome }.toSet(),
            )
            assertEquals(2, rows.first { it.simId == 1 }.retryCount)
            assertEquals(0, rows.first { it.simId == 2 }.retryCount)
        }

    @Test
    fun `observeInFlight returns a sending row and a pending retry row, but not a plain pending row`() =
        runBlocking {
            // A retry row is PENDING with retryCount > 0; a fresh PENDING is only "next",
            // not "in flight", and must stay out of the in-flight flow.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            val retryRow = dao.insert(entity(simId = 2, scheduledForMillis = 2000, retryCount = 1))
            dao.updateOutcome(retryRow, SendOutcome.PENDING.name, 1500L, "no service", 1, 1500L)
            val sending = dao.insert(entity(simId = 3, scheduledForMillis = 3000))
            dao.updateOutcome(sending, SendOutcome.SENDING.name, 3000L, null, 1, 2900L)
            val rows = dao.observeInFlight().first()
            assertEquals(listOf(2, 3), rows.map { it.simId }.sorted())
            val retry = rows.single { it.simId == 2 }
            assertEquals(SendOutcome.PENDING.name, retry.outcome)
            assertEquals(1, retry.retryCount)
            assertEquals(2000L, retry.scheduledForMillis)
            assertEquals(1500L, retry.lastAttemptAtMillis)
            val inFlight = rows.single { it.simId == 3 }
            assertEquals(SendOutcome.SENDING.name, inFlight.outcome)
            assertEquals(1, inFlight.retryCount)
            assertEquals(3000L, inFlight.scheduledForMillis)
            assertEquals(3000L, inFlight.lastAttemptAtMillis)
            assertNull(rows.firstOrNull { it.simId == 1 })
        }

    @Test
    fun `updateOutcome updates all fields`() =
        runBlocking {
            val id = dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            dao.updateOutcome(id, SendOutcome.FAILED.name, 2000L, "no signal", 2, 1500L)
            val row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.FAILED.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals("no signal", row.failureReason)
            assertEquals(2, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)
        }

    @Test
    fun `getActive returns null when no rows exist`() =
        runBlocking {
            assertNull(dao.getActive(1))
        }

    @Test
    fun `getActive returns null when only terminal rows exist`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.FAILED.name))
            assertNull(dao.getActive(1))
        }

    @Test
    fun `getActive returns only the newest active row`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.PENDING.name, retryCount = 2))
            val active = dao.getActive(1)
            assertEquals(3000L, active?.scheduledForMillis)
            assertEquals(SendOutcome.PENDING.name, active?.outcome)
            assertEquals(2, active?.retryCount)
        }

    @Test
    fun `getActive prefers a live attempt over a newer open row`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.PENDING.name, retryCount = 1))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.PENDING.name))
            val active = dao.getActive(1)
            assertEquals(1000L, active?.scheduledForMillis)
            assertEquals(SendOutcome.SENDING.name, active?.outcome)
        }

    @Test
    fun `getActive picks the newest pending row over an older retry row`() =
        runBlocking {
            // A retry row is a PENDING row: with no live attempt, the newest open row wins.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name, retryCount = 1))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.PENDING.name))
            val active = dao.getActive(1)
            assertEquals(3000L, active?.scheduledForMillis)
            assertEquals(SendOutcome.PENDING.name, active?.outcome)
            assertEquals(0, active?.retryCount)
        }

    @Test
    fun `deleteOlderThan deletes only terminal rows older than cutoff`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000))
            dao.insert(entity(simId = 1, scheduledForMillis = 2500, retryCount = 2))
            dao.insert(entity(simId = 2, scheduledForMillis = 3000, outcome = SendOutcome.FAILED.name))
            val deleted = dao.deleteOlderThan(2000)
            assertEquals(1, deleted)
            // The open PENDING rows (including the retry row) older than the cutoff survive:
            // they are owned by the send engine (deleting one would silently restart its
            // retry window).
            assertEquals(listOf(2500L, 2000L), dao.observeNewest(1, Int.MAX_VALUE).first().map { it.scheduledForMillis })
            assertEquals(listOf(3000L), dao.observeNewest(2, Int.MAX_VALUE).first().map { it.scheduledForMillis })
        }

    @Test
    fun `deleteBySimId removes only terminal rows of that sim`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.FAILED.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.SENDING.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 5000, outcome = SendOutcome.SENT.name))
            dao.deleteBySimId(1)
            // The in-flight PENDING/SENDING rows survive the Clear: the send engine owns them
            // to completion (clearing them would lose the occurrence's record / retry chain).
            assertEquals(listOf(4000L, 3000L), dao.observeNewest(1, Int.MAX_VALUE).first().map { it.scheduledForMillis })
            assertEquals(listOf(5000L), dao.observeNewest(2, Int.MAX_VALUE).first().map { it.scheduledForMillis })
        }

    @Test
    fun `deleteAllBySimId removes terminal and open rows of that sim only`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, retryCount = 1))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENDING.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 4000, outcome = SendOutcome.SENT.name))
            // Forget (unlike Clear) drops the open rows too: the caller cancels the armed
            // work first, so nothing outlives the deletion.
            dao.deleteAllBySimId(1)
            assertEquals(0, dao.observeNewest(1, Int.MAX_VALUE).first().size)
            assertEquals(listOf(4000L), dao.observeNewest(2, Int.MAX_VALUE).first().map { it.scheduledForMillis })
        }

    @Test
    fun `deletePending removes only fresh pending rows`() =
        runBlocking {
            // The retry row (PENDING, retryCount > 0) is engine-owned and survives a clear.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, retryCount = 2))
            dao.deletePending(1)
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(4000L, 3000L, 2000L), rows.map { it.scheduledForMillis })
        }

    @Test
    fun `deletePendingExcept removes only fresh pending rows except the kept one`() =
        runBlocking {
            // The retry row (PENDING, retryCount > 0) is engine-owned and survives, even at
            // a different time than the kept one.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, retryCount = 1))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name))
            dao.deletePendingExcept(1, 2000L)
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(3000L, 2000L), rows.map { it.scheduledForMillis })
        }

    @Test
    fun `deletePending and deletePendingExcept keep a retry row but drop a plain pending row`() =
        runBlocking {
            // A pending retry is the same row as the pending send (retryCount > 0): every
            // clear path must keep it, while a stale fresh PENDING is dropped.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            val retryRow = dao.insert(entity(simId = 1, scheduledForMillis = 1500, retryCount = 3))
            dao.updateOutcome(retryRow, SendOutcome.PENDING.name, 1200L, "no service", 3, 1200L)
            dao.deletePending(1)
            var rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(1500L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.PENDING.name, rows.single().outcome)
            assertEquals(3, rows.single().retryCount)
            assertEquals(1200L, rows.single().lastAttemptAtMillis)
            assertEquals(1200L, rows.single().firstAttemptAtMillis)

            // A retry row at a different time than the kept one survives the alignment drop.
            dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            val otherRetry = dao.insert(entity(simId = 2, scheduledForMillis = 1500, retryCount = 1))
            dao.updateOutcome(otherRetry, SendOutcome.PENDING.name, 1200L, "no service", 1, 1200L)
            dao.deletePendingExcept(2, 1000L)
            rows = dao.observeNewest(2, Int.MAX_VALUE).first()
            assertEquals(listOf(1500L, 1000L), rows.map { it.scheduledForMillis })
            assertEquals(1, rows.first().retryCount)
        }

    @Test
    fun `alignPendingRow inserts the pending row when no open row exists`() =
        runBlocking {
            upsertConfig(1)
            dao.alignPendingRow(1, 2000, 2000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(2000L, rows.single().scheduledForMillis)
            // The row carries the pre-jitter base it was aligned for.
            assertEquals(2000L, rows.single().occurrenceBaseMillis)
            assertEquals(SendOutcome.PENDING.name, rows.single().outcome)
            assertEquals("+15550100", rows.single().recipient)
            assertEquals("keep alive", rows.single().message)
        }

    @Test
    fun `alignPendingRow replaces a stale pending row when nothing is in flight`() =
        runBlocking {
            upsertConfig(1)
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            dao.alignPendingRow(1, 3000, 3000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(3000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.PENDING.name, rows.single().outcome)
        }

    @Test
    fun `alignPendingRow keeps an existing pending row at the same time`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.PENDING.name))
            dao.alignPendingRow(1, 3000, 3000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(3000L), rows.map { it.scheduledForMillis })
        }

    @Test
    fun `alignPendingRow never inserts while an in-flight row exists`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENDING.name))
            dao.alignPendingRow(1, 3000, 3000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(2000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.SENDING.name, rows.single().outcome)
        }

    @Test
    fun `alignPendingRow inserts nothing when the config row is gone`() =
        runBlocking {
            // An occurrence row can only exist under a live enabled schedule: a forget that
            // wins the race against a concurrent send deletes the config, so the late insert
            // must not resurrect a PENDING row for the forgotten SIM.
            dao.alignPendingRow(1, 2000, 2000, "+15550100", "keep alive")
            assertEquals(0, dao.observeNewest(1, Int.MAX_VALUE).first().size)
        }

    @Test
    fun `alignPendingRow inserts nothing when the config is disabled`() =
        runBlocking {
            // A disable that lands between the send's config re-read and its pending-row
            // insert must not leave a PENDING ghost on the off schedule (the reconciler's
            // disabled branch would only delete it later).
            upsertConfig(1, enabled = false)
            dao.alignPendingRow(1, 2000, 2000, "+15550100", "keep alive")
            assertEquals(0, dao.observeNewest(1, Int.MAX_VALUE).first().size)
        }

    @Test
    fun `claimOccurrence claims only open rows and keeps the first attempt`() =
        runBlocking {
            val pending = dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            assertEquals(1, dao.claimOccurrence(pending, 2000L, 2000L))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals(2000L, row.firstAttemptAtMillis)

            // An in-flight row is not claimable a second time.
            assertEquals(0, dao.claimOccurrence(pending, 3000L, 3000L))

            // A retry row is a PENDING row: the claim flips it to SENDING the same way.
            val retryRow =
                dao.insert(
                    entity(simId = 1, scheduledForMillis = 2000, retryCount = 2, firstAttemptAtMillis = 1500L),
                )
            assertEquals(1, dao.claimOccurrence(retryRow, 4000L, 5000L))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().first { it.scheduledForMillis == 2000L }
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            // IFNULL: the existing first attempt is kept, not overwritten.
            assertEquals(1500L, row.firstAttemptAtMillis)
            assertEquals(4000L, row.lastAttemptAtMillis)
            assertEquals(2, row.retryCount)

            val sent = dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name))
            assertEquals(0, dao.claimOccurrence(sent, 6000L, 6000L))
        }

    @Test
    fun `finalizeOccurrence updates only open rows`() =
        runBlocking {
            val pending = dao.insert(entity(simId = 1, scheduledForMillis = 1000))
            assertEquals(1, dao.finalizeOccurrence(pending, SendOutcome.FAILED.name, 2000L, "no service", 1, 1000L))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.FAILED.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals("no service", row.failureReason)
            assertEquals(1, row.retryCount)
            assertEquals(1000L, row.firstAttemptAtMillis)

            // An in-flight row is left untouched (0 rows updated): a stale write can never
            // clobber a concurrent attempt or a finalized outcome.
            val sending = dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENDING.name))
            assertEquals(0, dao.finalizeOccurrence(sending, SendOutcome.SKIPPED.name, 3000L, "late write", 2, null))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().first { it.scheduledForMillis == 2000L }
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            assertNull(row.failureReason)
        }

    @Test
    fun `finalizeSending flips a sending row to SENT`() =
        runBlocking {
            val sending = dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(1, dao.finalizeSending(sending, SendOutcome.SENT.name, 2000L, null, 1, 1500L, null))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SENT.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            // The success write lands with no failure reason: a stale reason must not
            // survive on a SENT row.
            assertNull(row.failureReason)
            assertEquals(1, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)

            // The flip is one-way: a second write over the now-terminal row updates nothing
            // (a stale success must not clobber what landed first).
            assertEquals(0, dao.finalizeSending(sending, SendOutcome.SENT.name, 3000L, null, 2, 1500L, null))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals(1, row.retryCount)

            // Rows that are not SENDING are refused: a success can never land over an open
            // row a concurrent finalizer is about to own, nor over a finalized one.
            val pending = dao.insert(entity(simId = 2, scheduledForMillis = 1000))
            assertEquals(0, dao.finalizeSending(pending, SendOutcome.SENT.name, 2000L, null, 0, null, null))
            val skipped = dao.insert(entity(simId = 2, scheduledForMillis = 2000, outcome = SendOutcome.SKIPPED.name))
            assertEquals(0, dao.finalizeSending(skipped, SendOutcome.SENT.name, 2000L, null, 0, null, null))
        }

    @Test
    fun `finalizeSending flips a sending row to FAILED`() =
        runBlocking {
            val sending = dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(1, dao.finalizeSending(sending, SendOutcome.FAILED.name, 2000L, "no signal", 0, 1500L, null))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.FAILED.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals("no signal", row.failureReason)
            assertEquals(0, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)

            // The flip is one-way: a second write over the now-terminal row updates nothing
            // (a stale failure must not clobber what landed first).
            assertEquals(0, dao.finalizeSending(sending, SendOutcome.FAILED.name, 3000L, "late write", 1, 1500L, null))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals("no signal", row.failureReason)
            assertEquals(0, row.retryCount)

            // Rows that are not SENDING are refused: a late radio result must never clobber
            // a concurrent finalizer's outcome (the stale check's interrupted skip, a
            // finalized row).
            val pending = dao.insert(entity(simId = 2, scheduledForMillis = 1000))
            assertEquals(0, dao.finalizeSending(pending, SendOutcome.FAILED.name, 2000L, "late write", 0, 1000L, null))
            val skipped = dao.insert(entity(simId = 2, scheduledForMillis = 2000, outcome = SendOutcome.SKIPPED.name))
            assertEquals(0, dao.finalizeSending(skipped, SendOutcome.FAILED.name, 2000L, "late write", 0, 1500L, null))
        }

    @Test
    fun `finalizeSending flips a sending row to a pending retry`() =
        runBlocking {
            // The retryable failure lands as a SENDING->PENDING flip (retryCount + 1): the
            // row stays open as the retry of the same occurrence.
            val sending = dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(1, dao.finalizeSending(sending, SendOutcome.PENDING.name, 2000L, "no service", 1, 1500L, null))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.PENDING.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals("no service", row.failureReason)
            assertEquals(1, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)

            // The flip is one-way: a second write over the now-open row updates nothing
            // (a stale failure must not clobber what landed first).
            assertEquals(0, dao.finalizeSending(sending, SendOutcome.PENDING.name, 3000L, "late write", 2, 1500L, null))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals("no service", row.failureReason)
            assertEquals(1, row.retryCount)

            // Rows that are not SENDING are refused: a retry must never resurrect an
            // occurrence the stale check already consumed as an interrupted skip — the
            // re-claim would re-send it.
            val skipped = dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.SKIPPED.name))
            assertEquals(0, dao.finalizeSending(skipped, SendOutcome.PENDING.name, 2000L, "late write", 1, 1000L, null))
            val pending = dao.insert(entity(simId = 3, scheduledForMillis = 1000))
            assertEquals(0, dao.finalizeSending(pending, SendOutcome.PENDING.name, 2000L, "late write", 1, 1000L, null))
        }

    @Test
    fun `finalizeSending flips a sending row to SKIPPED`() =
        runBlocking {
            val sending = dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(1, dao.finalizeSending(sending, SendOutcome.SKIPPED.name, 2000L, "disabled before send", 0, 1500L, null))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals(2000L, row.lastAttemptAtMillis)
            assertEquals("disabled before send", row.failureReason)
            assertEquals(0, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)

            // The flip is one-way: a second write over the now-terminal row updates nothing.
            assertEquals(0, dao.finalizeSending(sending, SendOutcome.SKIPPED.name, 3000L, "late write", 0, 1500L, null))
            row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals("disabled before send", row.failureReason)

            // Rows that are not SENDING are refused: a late disable-skip must not clobber a
            // concurrent owner's outcome (the stale check's skip reason, a finalized row).
            val skipped = dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.SKIPPED.name))
            assertEquals(0, dao.finalizeSending(skipped, SendOutcome.SKIPPED.name, 2000L, "late write", 0, 1000L, null))
            // The plain flip (staleBeforeMillis = null) ignores the attempt time: a row
            // whose attempt is fresh flips the same way, and an open retry row (PENDING,
            // retryCount > 0) is refused too.
            val fresh = dao.insert(entity(simId = 3, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            dao.updateOutcome(fresh, SendOutcome.SENDING.name, 5000L, null, 0, 5000L)
            assertEquals(1, dao.finalizeSending(fresh, SendOutcome.SKIPPED.name, 5000L, "late write", 0, 5000L, null))
            row = dao.observeNewest(3, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            val retryRow = dao.insert(entity(simId = 4, scheduledForMillis = 1000, retryCount = 1))
            assertEquals(0, dao.finalizeSending(retryRow, SendOutcome.SKIPPED.name, 2000L, "late write", 1, 1000L, null))
        }

    @Test
    fun `finalizeSending with a stale bound flips only a stale sending row`() =
        runBlocking {
            // A SENDING row whose attempt is before the bound flips (the stale check's
            // interrupted skip), carrying the write's own attempt and first-attempt times.
            val stale = dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            dao.updateOutcome(stale, SendOutcome.SENDING.name, 1500L, null, 0, 1500L)
            assertEquals(1, dao.finalizeSending(stale, SendOutcome.SKIPPED.name, 1500L, "send interrupted", 0, 1500L, 2000L))
            var row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals(1500L, row.lastAttemptAtMillis)
            assertEquals("send interrupted", row.failureReason)
            assertEquals(0, row.retryCount)
            assertEquals(1500L, row.firstAttemptAtMillis)

            // A NULL attempt time counts as stale: a claim that died before the attempt was
            // recorded resolves the same way.
            val unattempted = dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(1, dao.finalizeSending(unattempted, SendOutcome.SKIPPED.name, null, "send interrupted", 0, null, 2000L))
            row = dao.observeNewest(2, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertNull(row.lastAttemptAtMillis)
            assertNull(row.firstAttemptAtMillis)

            // A live attempt is never stale: at or after the bound the flip is refused and
            // the row stays SENDING for its owner's result write.
            val atBound = dao.insert(entity(simId = 3, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            dao.updateOutcome(atBound, SendOutcome.SENDING.name, 2000L, null, 0, 2000L)
            assertEquals(0, dao.finalizeSending(atBound, SendOutcome.SKIPPED.name, 2000L, "late write", 0, 2000L, 2000L))
            row = dao.observeNewest(3, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            assertNull(row.failureReason)
            val fresh = dao.insert(entity(simId = 4, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            dao.updateOutcome(fresh, SendOutcome.SENDING.name, 2500L, null, 0, 2500L)
            assertEquals(0, dao.finalizeSending(fresh, SendOutcome.SKIPPED.name, 2500L, "late write", 0, 2500L, 2000L))
            row = dao.observeNewest(4, Int.MAX_VALUE).first().single()
            assertEquals(SendOutcome.SENDING.name, row.outcome)
            assertEquals(2500L, row.lastAttemptAtMillis)
        }

    @Test
    fun `alignPendingRow does not re-arm an occurrence that already has a SENT row`() =
        runBlocking {
            // Simulates crash between row→SENT and recordSuccess: the row is SENT but the
            // schedule has not advanced. alignPendingRow must not insert a fresh PENDING for
            // the same occurrence time.
            dao.insert(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1000,
                    occurrenceBaseMillis = 1000,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            dao.alignPendingRow(1, 1000, 1000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SENT.name, rows.single().outcome)
        }

    @Test
    fun `alignPendingRow does not re-arm a consumed occurrence re-armed with a different delivery time`() =
        runBlocking {
            // M1: the occurrence was consumed as an interrupted skip at its old jittered
            // delivery time (base 1000 + jitter); a re-arm re-rolled the jitter, so the new
            // PENDING row would carry a different delivery instant (base 1000 + new jitter).
            // The guard keys on the base, so the re-arm must see the existing SKIPPED row
            // and not insert — otherwise the consumed occurrence gets re-sent.
            upsertConfig(1)
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SKIPPED.name, occurrenceBaseMillis = 1000))
            dao.alignPendingRow(1, 3000, 1000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SKIPPED.name, rows.single().outcome)
            assertNull(dao.getActive(1))
        }

    @Test
    fun `alignPendingRow inserts nothing when a FAILED row exists for the same time`() =
        runBlocking {
            // Crash state between the terminal finalize and the schedule advance: the
            // occurrence is consumed (FAILED row at the armed time, no open row, schedule
            // still pointing at it). A PENDING ghost for the same time would be re-sent
            // (restart within the grace) or recorded twice (restart past it).
            upsertConfig(1)
            dao.insert(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1000,
                    occurrenceBaseMillis = 1000,
                    outcome = SendOutcome.FAILED.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            dao.alignPendingRow(1, 1000, 1000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.FAILED.name, rows.single().outcome)
            assertNull(dao.getActive(1))
        }

    @Test
    fun `alignPendingRow inserts nothing when a SKIPPED row exists for the same time`() =
        runBlocking {
            // The skip twin of the FAILED guard: a recorded skip (a death between the skip
            // recording and the schedule advance) is a terminal row too — the occurrence is
            // consumed, and a PENDING ghost for the same time would re-send or duplicate it.
            upsertConfig(1)
            dao.insert(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1000,
                    occurrenceBaseMillis = 1000,
                    outcome = SendOutcome.SKIPPED.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            dao.alignPendingRow(1, 1000, 1000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SKIPPED.name, rows.single().outcome)
            assertNull(dao.getActive(1))
        }

    @Test
    fun `alignPendingRow still arms a different time while a terminal row exists at another time`() =
        runBlocking {
            // A terminal row is proof only for ITS exact occurrence time: the next
            // occurrence (the schedule already advanced past the consumed one) still gets
            // its PENDING row.
            upsertConfig(1)
            dao.insert(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 1000,
                    occurrenceBaseMillis = 1000,
                    outcome = SendOutcome.FAILED.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
            dao.alignPendingRow(1, 3000, 3000, "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(3000L, 1000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.PENDING.name, rows.single { it.scheduledForMillis == 3000L }.outcome)
        }

    @Test
    fun `insertSkippedIfNotTerminal inserts the skip row when no terminal row exists`() =
        runBlocking {
            upsertConfig(1)
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            val row = dao.observeNewest(1, Int.MAX_VALUE).first().single()
            assertEquals(1000L, row.scheduledForMillis)
            assertEquals(SendOutcome.SKIPPED.name, row.outcome)
            assertEquals("app not running", row.failureReason)
            assertEquals("+15550100", row.recipient)
            assertEquals("keep alive", row.message)
            assertEquals(0, row.retryCount)
            assertNull(row.lastAttemptAtMillis)
            assertNull(row.firstAttemptAtMillis)
        }

    @Test
    fun `insertSkippedIfNotTerminal does not record a skip over a SENT row for the same time`() =
        runBlocking {
            // The crash state from pre-fix builds: the row is SENT but the schedule never
            // advanced. The catch-up must not record the same occurrence as skipped on top
            // of the SENT proof.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SENT.name, rows.single().outcome)
        }

    @Test
    fun `insertSkippedIfNotTerminal still records skips around other SENT rows`() =
        runBlocking {
            // A SENT row at another time (or for another sim) is a different occurrence:
            // the skip for this one is still recorded.
            upsertConfig(1)
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(listOf(2000L, 1000L), rows.map { it.scheduledForMillis })
            assertEquals(SendOutcome.SKIPPED.name, rows.single { it.scheduledForMillis == 1000L }.outcome)
        }

    @Test
    fun `insertSkippedIfNotTerminal does not re-record a skip already recorded for the same time`() =
        runBlocking {
            // B-19 crash state: the run recorded the skip for a missed occurrence, then the
            // process died before the schedule advanced. The next run re-derives the same
            // missed set from the stale nextSendAtMillis and calls this again — the existing
            // SKIPPED row must not be duplicated.
            upsertConfig(1)
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(1000L, rows.single().scheduledForMillis)
            assertEquals(SendOutcome.SKIPPED.name, rows.single().outcome)
        }

    @Test
    fun `insertSkippedIfNotTerminal does not re-record a skip for a base already terminal at a different delivery time`() =
        runBlocking {
            // M1: the occurrence was consumed as an interrupted skip at its old jittered
            // delivery time (base 1000 + jitter); past the late-send grace the same
            // occurrence is re-derived as missed and re-recorded — with a re-rolled jitter,
            // so at a different delivery instant than the existing SKIPPED row. The guard
            // keys on the base, so the second skip must not land (it would show the same
            // occurrence twice).
            upsertConfig(1)
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SKIPPED.name, occurrenceBaseMillis = 1000))
            dao.insertSkippedIfNotTerminal(1, 3000, 1000, "app not running", "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.SKIPPED.name, rows.single().outcome)
            assertEquals(2000L, rows.single().scheduledForMillis)
            assertEquals(1000L, rows.single().occurrenceBaseMillis)
        }

    @Test
    fun `insertSkippedIfNotTerminal does not record a skip over a FAILED row for the same time`() =
        runBlocking {
            // A FAILED row is a terminal outcome too: the occurrence was already consumed
            // (failed permanently), so a skip record on top of it would show it twice.
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.FAILED.name))
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            val rows = dao.observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.FAILED.name, rows.single().outcome)
        }

    @Test
    fun `insertSkippedIfNotTerminal inserts nothing when the config row is gone`() =
        runBlocking {
            // The skip twin of the PENDING guard: no skip ghost for a SIM that no longer has
            // a schedule (a delete that wins the race against a concurrent catch-up).
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            assertEquals(0, dao.observeNewest(1, Int.MAX_VALUE).first().size)
        }

    @Test
    fun `insertSkippedIfNotTerminal inserts nothing when the config is disabled`() =
        runBlocking {
            upsertConfig(1, enabled = false)
            dao.insertSkippedIfNotTerminal(1, 1000, 1000, "app not running", "+15550100", "keep alive")
            assertEquals(0, dao.observeNewest(1, Int.MAX_VALUE).first().size)
        }

    @Test
    fun `countFinalizedByBase counts terminal rows of the same base, whatever their delivery time`() =
        runBlocking {
            assertEquals(0, dao.countFinalizedByBase(1, 1000))
            // A terminal row whose delivery instant differs from its base still matches by
            // base: the re-rolled jitter must not hide the consumed occurrence.
            dao.insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SKIPPED.name, occurrenceBaseMillis = 1000))
            assertEquals(1, dao.countFinalizedByBase(1, 1000))
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.FAILED.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.PENDING.name, occurrenceBaseMillis = 1000))
            assertEquals(2, dao.countFinalizedByBase(1, 1000))
            assertEquals(0, dao.countFinalizedByBase(1, 3000))
            assertEquals(1, dao.countFinalizedByBase(2, 1000))
        }

    @Test
    fun `latestSentMillis returns the newest SENT row's base and ignores other outcomes`() =
        runBlocking {
            assertNull(dao.latestSentMillis(1))
            dao.insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.SENT.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 5000, outcome = SendOutcome.PENDING.name))
            dao.insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.FAILED.name))
            dao.insert(entity(simId = 2, scheduledForMillis = 9000, outcome = SendOutcome.SENT.name))
            assertEquals(3000L, dao.latestSentMillis(1))
            assertEquals(9000L, dao.latestSentMillis(2))
        }

    @Test
    fun `latestSentMillis returns the base, not the jittered delivery time`() =
        runBlocking {
            dao.insert(entity(simId = 1, scheduledForMillis = 4500, outcome = SendOutcome.SENT.name, occurrenceBaseMillis = 4000))
            dao.insert(entity(simId = 1, scheduledForMillis = 1500, outcome = SendOutcome.SENT.name, occurrenceBaseMillis = 1000))
            assertEquals(4000L, dao.latestSentMillis(1))
        }
}
