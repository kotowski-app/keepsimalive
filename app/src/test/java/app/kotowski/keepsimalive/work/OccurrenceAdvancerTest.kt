package app.kotowski.keepsimalive.work

import android.app.Application
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.LogBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class OccurrenceAdvancerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var repository: KeepaliveRepository
    private lateinit var armer: ScheduleArmer
    private lateinit var advancer: OccurrenceAdvancer

    @Before
    fun setup() {
        repository = mock()
        armer = mock()
        advancer = OccurrenceAdvancer(context, repository, armer)
    }

    private fun config() =
        SimKeepaliveConfig(
            simId = 1,
            enabled = true,
            recipientPhone = "+15550100",
            message = "keep alive",
            hour = 12,
            minute = 0,
            freqType = FrequencyType.EVERY_N_DAYS,
            daysInterval = 30,
            endType = EndType.NEVER,
            timeWindowMinutes = 0,
        )

    private fun expectedNext(baseMillis: Long): Long =
        Instant
            .ofEpochMilli(baseMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(30)
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `nextOccurrenceMillis applies the schedule rhythm`() =
        runBlocking {
            val last = System.currentTimeMillis() - 60_000
            val lastOccurrence = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
            assertEquals(expectedNext(last), advancer.nextOccurrenceMillis(config(), lastOccurrence))
        }

    @Test
    fun `skipInterrupted marks the row skipped with reason and advances`() =
        runBlocking<Unit> {
            val last = System.currentTimeMillis() - 60_000
            val lastOccurrence = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
            val row =
                SendHistoryEntity(
                    id = 7L,
                    simId = 1,
                    scheduledForMillis = last,
                    occurrenceBaseMillis = last,
                    outcome = SendOutcome.SENDING.name,
                    lastAttemptAtMillis = last,
                    firstAttemptAtMillis = last,
                    retryCount = 1,
                    recipient = "+15550100",
                    message = "keep alive",
                )

            LogBuffer.clear()
            val flipBefore = System.currentTimeMillis()
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(true)
            val resolved = advancer.skipInterrupted(1, config(), row, lastOccurrence)

            assertTrue(resolved)
            // The skip and the advance land in one transaction (finalizeOutcome): the
            // guarded stale flip (staleness bound = now - SENDING_STALE_MS) commits with
            // the AdvanceToNext consequence.
            val flipAfter = System.currentTimeMillis()
            verify(
                repository,
            ).finalizeOutcome(
                eq(7L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(last),
                eq(context.getString(R.string.error_send_interrupted)),
                eq(1),
                eq(last),
                argThat { value: Long -> value in (flipBefore - AppConfig.SENDING_STALE_MS)..(flipAfter - AppConfig.SENDING_STALE_MS) },
                eq(FinalizeAdvance.AdvanceToNext(1, expectedNext(last), expectedNext(last), "+15550100", "keep alive")),
            )
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            // The skip log carries the attempt and occurrence times so a misrecorded skip
            // (a delivered SMS whose process died before the result write) is traceable.
            assertTrue(
                LogBuffer.entries.any {
                    it.level == "W" && it.tag == "OccurrenceAdvancer" &&
                        it.message.contains("attempt interrupted (attempt at $last, occurrence $last)")
                },
            )
        }

    // The stale-check worker and a late send worker can race to resolve the same dead
    // attempt: the flip is atomic, and only the flip winner advances the schedule.
    @Test
    fun `skipInterrupted defers when the flip loses the race - no double advance`() =
        runBlocking<Unit> {
            val last = System.currentTimeMillis() - 120_000
            val lastOccurrence = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
            val row =
                SendHistoryEntity(
                    id = 7L,
                    simId = 1,
                    scheduledForMillis = last,
                    occurrenceBaseMillis = last,
                    outcome = SendOutcome.SENDING.name,
                    lastAttemptAtMillis = last,
                    firstAttemptAtMillis = last,
                    retryCount = 1,
                    recipient = "+15550100",
                    message = "keep alive",
                )
            // The concurrent resolver won the flip.
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(false)

            val resolved = advancer.skipInterrupted(1, config(), row, lastOccurrence)

            assertFalse(resolved)
            // No advance on top of the winner's: the loser must not re-jitter the next
            // occurrence or desync it from the pending row.
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
            // The deferral is logged so the race is diagnosable.
            assertTrue(
                LogBuffer.entries.any {
                    it.level == "I" && it.tag == "OccurrenceAdvancer" &&
                        it.message.contains("concurrently resolved")
                },
            )
        }

    @Test
    fun `recordMissedOccurrence records the guarded skip without advancing the schedule`() =
        runBlocking<Unit> {
            advancer.recordMissedOccurrence(1, 1_700_000_000_000L, 1_700_000_000_000L, config())

            verify(repository).insertSkippedIfNotTerminal(
                eq(1),
                eq(1_700_000_000_000L),
                eq(1_700_000_000_000L),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            // The record is insert-only: the schedule advance and re-arm belong to the caller.
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `nextOccurrenceMillis re-anchors an old-zone occurrence into the current zone`() =
        runBlocking {
            // The occurrence ZDT was captured before a clock change (zone or offset), so it
            // is anchored in a zone that is no longer in force: the next occurrence must be
            // built on the wall clock of the zone that IS in force now, not the old one.
            val zone = ZoneId.systemDefault()
            val oldZone =
                ZoneOffset.ofTotalSeconds(
                    zone.rules.getOffset(Instant.now()).totalSeconds + 3 * 3_600,
                )
            val instant = Instant.parse("2026-08-27T10:00:00Z")

            val actual = advancer.nextOccurrenceMillis(config(), instant.atZone(oldZone))

            val expected =
                instant
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            assertEquals(expected, actual)
            // The stale old-zone anchoring is not what lands:
            val stale =
                instant
                    .atZone(oldZone)
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(oldZone)
                    .toInstant()
                    .toEpochMilli()
            assertNotEquals(stale, actual)
        }

    @Test
    fun `nextOccurrenceMillis caps the window at the end date`() =
        runBlocking {
            // The next base occurrence lands exactly on the end date with the max window:
            // without the cap the roll could arm a day past the end date, where the end
            // check would end the schedule and drop the last intended send.
            val zone = ZoneId.systemDefault()
            val endDate = LocalDate.now(zone).plusDays(30)
            val cappedConfig =
                config().copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                    timeWindowMinutes = 40320,
                )
            val lastOccurrence = endDate.minusDays(30).atTime(12, 0).atZone(zone)

            val nextZdt = Instant.ofEpochMilli(advancer.nextOccurrenceMillis(cappedConfig, lastOccurrence)).atZone(zone)

            assertTrue(!nextZdt.toLocalDate().isBefore(endDate))
            assertTrue(!nextZdt.toLocalDate().isAfter(endDate))
        }

    @Test
    fun `stale threshold is above the display hold`() {
        assertEquals(true, AppConfig.SENDING_STALE_MS > AppConfig.DEFAULT_SENDING_HOLD_MS)
    }

    @Test
    fun `nextOccurrenceBaseMillis is the pre-jitter base and nextOccurrenceMillis stays in its window`() =
        runBlocking {
            // With a non-zero window the delivery time is base + jitter; the base is the
            // configured time exactly, and both come from one shared derivation.
            val windowedConfig = config().copy(timeWindowMinutes = 60)
            val last = System.currentTimeMillis() - 60_000
            val lastOccurrence = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())

            val base = advancer.nextOccurrenceBaseMillis(windowedConfig, lastOccurrence)
            assertEquals(expectedNext(last), base)

            val windowMs = 60L * 60_000L
            repeat(50) {
                val delivery = advancer.nextOccurrenceMillis(windowedConfig, lastOccurrence)
                assertTrue("delivery $delivery outside [base, base+window]", delivery in base..base + windowMs)
            }
        }
}
