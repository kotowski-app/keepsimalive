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

// The onOpen callback owns the one-open-row index DDL and the legacy RETRYING-row rename on
// every open (no Room version bump); the upgrade path is exercised directly.
@RunWith(RobolectricTestRunner::class)
class KeepaliveDatabaseCallbackTest {
    private lateinit var database: KeepaliveDatabase

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `onOpen renames a legacy retrying row to pending and rebuilds the index`() =
        runBlocking {
            // The pre-upgrade state: the old index (its WHERE carries the RETRYING literal)
            // and the open RETRYING row the install held at upgrade time.
            val supportDb = database.openHelper.writableDatabase
            supportDb.execSQL(
                "CREATE UNIQUE INDEX index_send_history_one_open_per_sim ON send_history (simId) WHERE outcome IN ('PENDING', 'SENDING', 'RETRYING')",
            )
            database.simHistoryDao().insert(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = 2000,
                    occurrenceBaseMillis = 1000,
                    lastAttemptAtMillis = 1500,
                    outcome = "RETRYING",
                    failureReason = "no service",
                    recipient = "+15550100",
                    message = "keep alive",
                    firstAttemptAtMillis = 1500,
                    retryCount = 2,
                ),
            )
            KeepaliveDatabaseCallback.onOpen(supportDb)

            val row =
                database
                    .simHistoryDao()
                    .observeNewest(1, Int.MAX_VALUE)
                    .first()
                    .single()
            assertEquals(SendOutcome.PENDING.name, row.outcome)
            assertEquals(2, row.retryCount)
            assertEquals(1500L, row.lastAttemptAtMillis)
            assertEquals(1500L, row.firstAttemptAtMillis)
            assertEquals(2000L, row.scheduledForMillis)
            assertEquals("no service", row.failureReason)

            try {
                database.simHistoryDao().insert(
                    SendHistoryEntity(
                        simId = 1,
                        scheduledForMillis = 3000,
                        occurrenceBaseMillis = 3000,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
                throw AssertionError("expected the unique index to reject a second open row")
            } catch (e: SQLiteConstraintException) {
            }
            database.simHistoryDao().insert(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 3000,
                    occurrenceBaseMillis = 3000,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )

            // A second onOpen must be a no-op (idempotent).
            KeepaliveDatabaseCallback.onOpen(supportDb)
            val rows = database.simHistoryDao().observeNewest(1, Int.MAX_VALUE).first()
            assertEquals(1, rows.size)
            assertEquals(SendOutcome.PENDING.name, rows.single().outcome)
            assertEquals(2, rows.single().retryCount)
            try {
                database.simHistoryDao().insert(
                    SendHistoryEntity(
                        simId = 1,
                        scheduledForMillis = 4000,
                        occurrenceBaseMillis = 4000,
                        recipient = "+15550100",
                        message = "keep alive",
                    ),
                )
                throw AssertionError("expected the unique index to reject a second open row")
            } catch (e: SQLiteConstraintException) {
            }
        }
}
