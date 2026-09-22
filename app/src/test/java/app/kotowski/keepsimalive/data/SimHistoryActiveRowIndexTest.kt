package app.kotowski.keepsimalive.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The partial unique index (one open PENDING/SENDING row per SIM — a retry row is a
// PENDING row with retryCount > 0) is created by KeepaliveDatabaseCallback on every
// database open; the test database must attach the same callback to see production
// behavior.
@RunWith(RobolectricTestRunner::class)
class SimHistoryActiveRowIndexTest {
    private lateinit var database: KeepaliveDatabase

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java)
                .addCallback(KeepaliveDatabaseCallback)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(
        simId: Int,
        scheduledForMillis: Long,
        outcome: String = SendOutcome.PENDING.name,
        // Non-zero for a retry row (a PENDING row the engine re-armed after a failure).
        retryCount: Int = 0,
    ) = SendHistoryEntity(
        simId = simId,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = scheduledForMillis,
        outcome = outcome,
        recipient = "+15550100",
        message = "keep alive",
        retryCount = retryCount,
    )

    @Test
    fun `a second open row for the same sim is rejected`() =
        runBlocking {
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            assertEquals(
                1,
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .size,
            )
            try {
                database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.PENDING.name))
                throw AssertionError("expected the unique index to reject a second open row")
            } catch (e: SQLiteConstraintException) {
            }
            try {
                // A retry row (PENDING, retryCount > 0) is as constrained as any open row.
                database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 3000, retryCount = 2))
                throw AssertionError("expected the unique index to reject a second open row")
            } catch (e: SQLiteConstraintException) {
            }
            assertEquals(
                1,
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .size,
            )
        }

    @Test
    fun `open rows for different sims are allowed`() =
        runBlocking {
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            database.simHistoryDao().insert(entity(simId = 2, scheduledForMillis = 1000, retryCount = 1))
            assertEquals(
                1,
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .size,
            )
            assertEquals(
                1,
                database
                    .simHistoryDao()
                    .observeNewest(2, Int.MAX_VALUE)
                    .first()
                    .size,
            )
        }

    @Test
    fun `terminal rows never collide with an open row`() =
        runBlocking {
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.SENDING.name))
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.SENT.name))
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 3000, outcome = SendOutcome.FAILED.name))
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 4000, outcome = SendOutcome.SKIPPED.name))
            assertEquals(
                4,
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .size,
            )
        }

    @Test
    fun `a finalized row frees the sim for the next open row`() =
        runBlocking {
            val open = database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 1000, outcome = SendOutcome.PENDING.name))
            database
                .simHistoryDao()
                .updateOutcome(open, SendOutcome.SENT.name, 1500L, null, 0, 1500L)
            database.simHistoryDao().insert(entity(simId = 1, scheduledForMillis = 2000, outcome = SendOutcome.PENDING.name))
            assertEquals(
                2,
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .size,
            )
        }
}
