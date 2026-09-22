package app.kotowski.keepsimalive.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@Entity(tableName = "sim_config")
private data class LegacySimConfigEntity(
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
    val endType: Int,
    val maxSends: Int,
    val endDateMillis: Long?,
    val timeWindowMinutes: Int,
)

@Dao
private interface LegacySimConfigDao {
    @Upsert
    fun upsert(config: LegacySimConfigEntity)

    @Query("SELECT * FROM sim_config WHERE simId = :simId")
    fun getBySimId(simId: Int): LegacySimConfigEntity?
}

@Database(entities = [LegacySimConfigEntity::class], version = 2, exportSchema = false)
private abstract class LegacyKeepaliveDatabase : RoomDatabase() {
    abstract fun simConfigDao(): LegacySimConfigDao
}

@Entity(tableName = "sim_config")
private data class V5SimConfigEntity(
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
    val endType: Int,
    val maxSends: Int,
    val endDateMillis: Long?,
    val timeWindowMinutes: Int,
    val nextSendAtMillis: Long? = null,
    val lastSentAtMillis: Long? = null,
    val sendCount: Int = 0,
)

@Dao
private interface V5SimConfigDao {
    @Upsert
    fun upsert(config: V5SimConfigEntity)

    @Query("SELECT * FROM sim_config WHERE simId = :simId")
    fun getBySimId(simId: Int): V5SimConfigEntity?
}

@Database(entities = [V5SimConfigEntity::class, SendHistoryEntity::class], version = 5, exportSchema = false)
private abstract class V5KeepaliveDatabase : RoomDatabase() {
    abstract fun simConfigDao(): V5SimConfigDao

    abstract fun simHistoryDao(): SimHistoryDao
}

@RunWith(RobolectricTestRunner::class)
class DestructiveMigrationTest {
    private var dbFile: File? = null

    @Test
    fun `opening v1 over a v2 database drops old data and creates the new schema`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "destructive_${UUID.randomUUID()}.db")
        dbFile = file

        val v2 =
            Room
                .databaseBuilder(context, LegacyKeepaliveDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
        v2.simConfigDao().upsert(
            LegacySimConfigEntity(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "keep alive",
                hour = 9,
                minute = 30,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 0,
                maxSends = 1,
                endDateMillis = null,
                timeWindowMinutes = 0,
            ),
        )
        v2.close()

        val current =
            Room
                .databaseBuilder(context, KeepaliveDatabase::class.java, file.absolutePath)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()

        assertNull(current.simConfigDao().getBySimId(1))

        current.simConfigDao().upsert(
            SimConfigEntity(
                simId = 2,
                enabled = true,
                recipientPhone = "+15550101",
                message = "keep alive",
                hour = 12,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 0,
                maxSends = 1,
                endDate = null,
                timeWindowMinutes = 0,
                nextSendAtMillis = 1700000000000L,
                lastSentAtMillis = 1690000000000L,
                sendCount = 4,
            ),
        )
        val config = current.simConfigDao().getBySimId(2)
        assertEquals(1700000000000L, config?.nextSendAtMillis)
        assertEquals(1690000000000L, config?.lastSentAtMillis)
        assertEquals(4, config?.sendCount)

        val inserted =
            current.simHistoryDao().insert(
                SendHistoryEntity(
                    simId = 2,
                    scheduledForMillis = 1700000000000L,
                    occurrenceBaseMillis = 1700000000000L,
                    recipient = "+15550101",
                    message = "keep alive",
                ),
            )
        val rows = runBlocking { current.simHistoryDao().observeNewest(2, Int.MAX_VALUE).first() }
        assertEquals(1, rows.size)
        assertEquals(inserted, rows.first().id)
        current.close()
    }

    @Test
    fun `opening v1 over a v5 database drops the old end date rows`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "destructive_v5_${UUID.randomUUID()}.db")
        dbFile = file

        val v5 =
            Room
                .databaseBuilder(context, V5KeepaliveDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
        v5.simConfigDao().upsert(
            V5SimConfigEntity(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "keep alive",
                hour = 9,
                minute = 30,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 2,
                maxSends = 1,
                endDateMillis = 1704067200000L,
                timeWindowMinutes = 0,
            ),
        )
        v5.close()

        val current =
            Room
                .databaseBuilder(context, KeepaliveDatabase::class.java, file.absolutePath)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()

        assertNull(current.simConfigDao().getBySimId(1))
        assertNull(runBlocking { current.simHistoryDao().getActive(1) })

        current.simConfigDao().upsert(
            SimConfigEntity(
                simId = 2,
                enabled = true,
                recipientPhone = "+15550101",
                message = "keep alive",
                hour = 12,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 2,
                maxSends = 1,
                endDate = "2026-12-31",
                timeWindowMinutes = 0,
            ),
        )
        assertEquals("2026-12-31", current.simConfigDao().getBySimId(2)?.endDate)
        current.close()
    }

    @Test
    fun `opening the current database over an unknown older schema fails instead of wiping data`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "loud_${UUID.randomUUID()}.db")
        dbFile = file

        val v2 =
            Room
                .databaseBuilder(context, LegacyKeepaliveDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
        v2.simConfigDao().upsert(
            LegacySimConfigEntity(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "keep alive",
                hour = 9,
                minute = 30,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 0,
                maxSends = 1,
                endDateMillis = null,
                timeWindowMinutes = 0,
            ),
        )
        v2.close()

        val current =
            Room
                .databaseBuilder(context, KeepaliveDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
        val result =
            runCatching { current.simConfigDao().getBySimId(1) }
        val thrown = result.exceptionOrNull()
        runCatching { current.close() }
        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `database version is 1`() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java).build()
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    @After
    fun tearDown() {
        dbFile?.delete()
        dbFile = null
    }
}
