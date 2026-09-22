package app.kotowski.keepsimalive.data

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimConfigDaoTest {
    private lateinit var database: KeepaliveDatabase
    private lateinit var dao: SimConfigDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeepaliveDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.simConfigDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(
        simId: Int,
        enabled: Boolean = false,
        recipientPhone: String = "",
    ) = SimConfigEntity(
        simId = simId,
        enabled = enabled,
        recipientPhone = recipientPhone,
        message = "msg",
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
    )

    @Test
    fun `getBySimId returns null when no config stored`() {
        assertNull(dao.getBySimId(1))
    }

    @Test
    fun `upsert then getBySimId returns stored entity`() {
        val stored = entity(simId = 1, enabled = true, recipientPhone = "+15550100")
        dao.upsert(stored)
        assertEquals(stored, dao.getBySimId(1))
    }

    @Test
    fun `upsert then getBySimId stores and returns the end date`() {
        dao.upsert(entity(simId = 1).copy(endDate = "2026-12-31"))
        assertEquals("2026-12-31", dao.getBySimId(1)?.endDate)
    }

    @Test
    fun `upsert with same simId overwrites previous value`() {
        dao.upsert(entity(simId = 2, enabled = false))
        dao.upsert(entity(simId = 2, enabled = true, recipientPhone = "+15550101"))
        val loaded = dao.getBySimId(2)
        assertEquals(true, loaded?.enabled)
        assertEquals("+15550101", loaded?.recipientPhone)
    }

    @Test
    fun `getBySimId returns null for different simId`() {
        dao.upsert(entity(simId = 3))
        assertNull(dao.getBySimId(4))
    }

    @Test
    fun `multiple sims stored independently`() {
        dao.upsert(entity(simId = 1, recipientPhone = "a"))
        dao.upsert(entity(simId = 2, recipientPhone = "b"))
        assertEquals("a", dao.getBySimId(1)?.recipientPhone)
        assertEquals("b", dao.getBySimId(2)?.recipientPhone)
    }

    @Test
    fun `updateUserColumns only updates user columns and preserves engine state`() {
        dao.upsert(
            SimConfigEntity(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "old",
                hour = 9,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                selectedMonths = "",
                endType = 0,
                maxSends = 5,
                endDate = null,
                timeWindowMinutes = 10,
                nextSendAtMillis = 1700000000000L,
                lastSentAtMillis = 1699000000000L,
                lastSentOccurrenceMillis = 1698000000000L,
                sendCount = 3,
            ),
        )
        dao.updateUserColumns(
            simId = 1,
            enabled = false,
            recipientPhone = "+15550101",
            message = "new",
            hour = 14,
            minute = 30,
            freqType = 2,
            daysInterval = 7,
            monthsInterval = 2,
            dayOfMonth = 15,
            selectedMonths = "1,3,12",
            endType = 1,
            maxSends = 10,
            endDate = "2027-05-18",
            timeWindowMinutes = 30,
        )
        val loaded = dao.getBySimId(1)
        assertEquals(false, loaded?.enabled)
        assertEquals("+15550101", loaded?.recipientPhone)
        assertEquals("new", loaded?.message)
        assertEquals(14, loaded?.hour)
        assertEquals(30, loaded?.minute)
        assertEquals(2, loaded?.freqType)
        assertEquals(7, loaded?.daysInterval)
        assertEquals(2, loaded?.monthsInterval)
        assertEquals(15, loaded?.dayOfMonth)
        assertEquals("1,3,12", loaded?.selectedMonths)
        assertEquals(1, loaded?.endType)
        assertEquals(10, loaded?.maxSends)
        assertEquals("2027-05-18", loaded?.endDate)
        assertEquals(30, loaded?.timeWindowMinutes)
        assertEquals(1700000000000L, loaded?.nextSendAtMillis)
        assertEquals(1699000000000L, loaded?.lastSentAtMillis)
        assertEquals(1698000000000L, loaded?.lastSentOccurrenceMillis)
        assertEquals(3, loaded?.sendCount)
    }

    @Test
    fun `recordSuccess writes last sent, the occurrence anchor, the count and the next send`() {
        dao.upsert(entity(simId = 1))
        dao.recordSuccess(1, 1700000000000L, 1699900000000L, 2, 1700100000000L)
        val loaded = dao.getBySimId(1)
        assertEquals(1700000000000L, loaded?.lastSentAtMillis)
        // The anchor is the sent occurrence's BASE (pre-jitter), written alongside the rest.
        assertEquals(1699900000000L, loaded?.lastSentOccurrenceMillis)
        assertEquals(2, loaded?.sendCount)
        assertEquals(1700100000000L, loaded?.nextSendAtMillis)
    }

    @Test
    fun `clearLastSentOccurrence only clears the anchor`() {
        dao.upsert(
            SimConfigEntity(
                simId = 1,
                enabled = true,
                recipientPhone = "+15550100",
                message = "msg",
                hour = 12,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                endType = 0,
                maxSends = 1,
                timeWindowMinutes = 0,
                nextSendAtMillis = 1700000000000L,
                lastSentAtMillis = 1699000000000L,
                lastSentOccurrenceMillis = 1698000000000L,
                sendCount = 3,
            ),
        )
        dao.clearLastSentOccurrence(1)
        val loaded = dao.getBySimId(1)
        assertNull(loaded?.lastSentOccurrenceMillis)
        assertEquals(1700000000000L, loaded?.nextSendAtMillis)
        assertEquals(1699000000000L, loaded?.lastSentAtMillis)
        assertEquals(3, loaded?.sendCount)
        dao.upsert(entity(simId = 2).copy(lastSentOccurrenceMillis = 1697000000000L))
        assertEquals(1697000000000L, dao.getBySimId(2)?.lastSentOccurrenceMillis)
    }

    @Test
    fun `updateUserColumns on non-existent row is a no-op`() {
        val affected =
            dao.updateUserColumns(
                simId = 99,
                enabled = true,
                recipientPhone = "+15550100",
                message = "m",
                hour = 12,
                minute = 0,
                freqType = 0,
                daysInterval = 30,
                monthsInterval = 1,
                dayOfMonth = 1,
                selectedMonths = "",
                endType = 0,
                maxSends = 1,
                endDate = null,
                timeWindowMinutes = 0,
            )
        assertEquals(0, affected)
        assertNull(dao.getBySimId(99))
    }

    @Test
    fun `deleteBySimId removes only the row of that sim`() {
        dao.upsert(entity(simId = 1))
        dao.upsert(entity(simId = 2))
        dao.deleteBySimId(1)
        assertNull(dao.getBySimId(1))
        assertNotNull(dao.getBySimId(2))
    }
}
