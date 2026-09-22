package app.kotowski.keepsimalive.work

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.concurrent.futures.ResolvableFuture
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.receiver.SendAlarmReceiver
import app.kotowski.keepsimalive.schedule.RetryPolicy
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.KeepaliveNotification
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.PermissionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ScheduleReconcilerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var repository: KeepaliveRepository
    private lateinit var armer: ScheduleArmer
    private lateinit var prefs: AppPrefs
    private val sendLock = SimSendLock()
    private lateinit var reconciler: ScheduleReconciler

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        repository = mock()
        armer = mock()
        prefs = mock()
        reconciler =
            ScheduleReconciler(
                context,
                repository,
                armer,
                prefs,
                OccurrenceAdvancer(context, repository, armer),
                sendLock,
            )
        whenever(armer.sendWorkName(anyInt())).thenAnswer { "keepalive_send_${it.arguments[0]}" }
        // Unstubbed mock returns 0, which would skip every past occurrence in the catch-up.
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
        // Unstubbed suspend calls return null on the JVM (NPE on unbox): explicit defaults below.
        runBlocking {
            // Flips win by default (the unbox reason above); a test stubs false for the race it drives.
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(true)
            whenever(repository.getAllConfigs()).thenReturn(emptyList())
            // Runs on every re-arm: no consumed occurrences unless a test stubs its own chain.
            whenever(repository.countFinalizedByBase(anyInt(), anyLong())).thenReturn(0)
        }
    }

    private fun config(
        simId: Int = 1,
        enabled: Boolean = true,
        nextSendAtMillis: Long? = null,
    ): SimKeepaliveConfig =
        SimKeepaliveConfig(
            simId = simId,
            enabled = enabled,
            recipientPhone = "+15550100",
            message = "keep alive",
            hour = 12,
            minute = 0,
            freqType = FrequencyType.EVERY_N_DAYS,
            daysInterval = 30,
            nextSendAtMillis = nextSendAtMillis,
        )

    // Pre-jitter base, anchored in the system-default zone like production.
    private fun baseOf(
        config: SimKeepaliveConfig,
        millis: Long,
    ): Long =
        ScheduleCalculator
            .baseOf(config, ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
            .toInstant()
            .toEpochMilli()

    // reconcileAll reads the SIM ids from getAllConfigs() but re-reads each config via
    // getConfig: stub both to the same row (drive them separately in the individual tests).
    private suspend fun stubConfigs(vararg configs: SimKeepaliveConfig) {
        whenever(repository.getAllConfigs()).thenReturn(configs.toList())
        configs.forEach { whenever(repository.getConfig(it.simId)).thenReturn(it) }
    }

    @Test
    fun `disabled config cancels send work`() =
        runBlocking<Unit> {
            stubConfigs(config(enabled = false, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L))

            reconciler.reconcileAll()

            verify(armer).cancelSend(1)
            // No open row: the disarm lives in the finalizeOutcome transaction, so no flip, nothing to clear.
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `disabled config resolves a stale sending row as skipped`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val staleAttempt = now - 70_000
            stubConfigs(config(enabled = false, nextSendAtMillis = staleAttempt))
            val row =
                pendingRow(1, staleAttempt, outcome = SendOutcome.SENDING)
                    .copy(lastAttemptAtMillis = staleAttempt)
            whenever(repository.getActiveHistory(1)).thenReturn(row)

            reconciler.reconcileAll()

            verify(armer).cancelSend(1)
            // Resolved with the disable reason (a skip is never unexplained) through the
            // guarded flip, never the unguarded update.
            verify(
                repository,
            ).finalizeOutcome(
                eq(row.id),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(staleAttempt),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                eq(staleAttempt),
                isNull(),
                eq(FinalizeAdvance.Disable(1)),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            // The disarm lands in the same transaction as the flip: no separate writes.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disabled config resolves a fresh sending row as skipped`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val freshAttempt = now - 5_000
            stubConfigs(config(enabled = false, nextSendAtMillis = freshAttempt))
            val row =
                pendingRow(1, freshAttempt, outcome = SendOutcome.SENDING)
                    .copy(lastAttemptAtMillis = freshAttempt)
            whenever(repository.getActiveHistory(1)).thenReturn(row)

            reconciler.reconcileAll()

            // The guarded flip resolves it at once (or it ghosts as "Trying now"); the
            // guard, not the cancel, protects a live owner (see the concurrent-owner test below).
            verify(
                repository,
            ).finalizeOutcome(
                eq(row.id),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(freshAttempt),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                eq(freshAttempt),
                isNull(),
                eq(FinalizeAdvance.Disable(1)),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disabled config leaves a sending row to its concurrent owner when the flip misses`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val attempt = now - 5_000
            stubConfigs(config(enabled = false, nextSendAtMillis = attempt))
            val row =
                pendingRow(1, attempt, outcome = SendOutcome.SENDING)
                    .copy(lastAttemptAtMillis = attempt, firstAttemptAtMillis = attempt)
            whenever(repository.getActiveHistory(1)).thenReturn(row)
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(false)

            reconciler.reconcileAll()

            verify(armer).cancelSend(1)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
            // The flip lost the race: nothing is written, disarm included.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
        }

    @Test
    fun `disabled config resolves an orphaned retrying row as skipped`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val lastAttempt = now - 60_000
            stubConfigs(config(enabled = false, nextSendAtMillis = lastAttempt + 10_000L))
            val row =
                pendingRow(1, lastAttempt - 30_000, retryCount = 2, outcome = SendOutcome.PENDING)
                    .copy(lastAttemptAtMillis = lastAttempt, firstAttemptAtMillis = lastAttempt - 30_000)
            whenever(repository.getActiveHistory(1)).thenReturn(row)

            reconciler.reconcileAll()

            verify(armer).cancelSend(1)
            // Orphaned (a disabled SIM owns no armed work): finalized so it never ghosts
            // a no-op Retry button while the SIM stays off.
            verify(
                repository,
            ).finalizeOutcome(
                eq(row.id),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                eq(lastAttempt),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(2),
                eq(lastAttempt - 30_000),
                isNull(),
                eq(FinalizeAdvance.Disable(1)),
            )
            // Disarmed in the same transaction as the flip (no separate writes): a
            // re-enable recomputes from the last send instead of re-running the occurrence.
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disabled config records a skip for an open pending row`() =
        runBlocking<Unit> {
            val armed = System.currentTimeMillis() + 3_600_000L
            val row = pendingRow(1, armed, 0)
            whenever(repository.getConfig(1)).thenReturn(config(enabled = false, nextSendAtMillis = armed))
            whenever(repository.getActiveHistory(1)).thenReturn(row)

            reconciler.reconcileSim(1)

            // A skip is never unexplained: flipped as skipped with the disable reason
            // (never attempted, so no last attempt); the disarm lands in the same transaction.
            verify(armer).cancelSend(1)
            verify(
                repository,
            ).finalizeOutcome(
                eq(row.id),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                isNull(),
                isNull(),
                eq(FinalizeAdvance.Disable(1)),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `re-enable after a mid-send disable recomputes instead of re-running the cleared occurrence`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val lastSent = now - 10L * 24 * 3_600_000L
            val consumed = now - 60_000

            // Disable mid-flight: flip + disarm in one transaction, so the consumed
            // occurrence is not re-armed later.
            stubConfigs(config(enabled = false, nextSendAtMillis = consumed))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, consumed, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = consumed),
                )
            reconciler.reconcileAll()
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(consumed),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                eq(consumed),
                isNull(),
                eq(FinalizeAdvance.Disable(1)),
            )

            // Re-enable with no next send: recompute from the newest SENT row, not a
            // delay-0 re-run of the consumed occurrence.
            whenever(repository.getConfig(1))
                .thenReturn(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            reconciler.reconcileSim(1)

            val expected =
                Instant
                    .ofEpochMilli(lastSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    // The rhythm-reset marker (the delete's wall-clock time) suppresses the last-send
    // anchor, or the re-enable would re-anchor on the pre-delete send and record the
    // whole off period as missed occurrences.
    @Test
    fun `re-enable after a delete anchors fresh when the newest SENT row predates the rhythm reset`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // 100 days back (30-day rhythm): the pre-delete grid's next occurrence is 20
            // days out, so the armed day alone proves the anchor.
            val lastSent = now - 100L * 24 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)
            // The delete wrote the marker after the last send ...
            whenever(prefs.getRhythmResetAtMillis(1)).thenReturn(now)

            reconciler.reconcileAll()

            // The off period is not a miss ...
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            // ... and the schedule arms from now (today or tomorrow at 12:00), not 20
            // days out on the pre-delete rhythm.
            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            val armedDay = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()).toLocalDate()
            val today = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate()
            assertTrue("armed day $armedDay not today or tomorrow", armedDay in today..today.plusDays(1))
            verify(repository).alignPendingRow(eq(1), eq(next), eq(next), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    // The marker stays set; the anchor read sees the newer row and it expires on its own.
    @Test
    fun `re-enable after a delete keeps the SENT anchor when the newest SENT row postdates the rhythm reset`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // Sent 10 days after the delete (30-day rhythm): the next occurrence is 20
            // days out — a plain re-save re-anchor, no catch-up.
            val resetAt = now - 30L * 24 * 3_600_000L
            val lastSent = now - 10L * 24 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)
            whenever(prefs.getRhythmResetAtMillis(1)).thenReturn(resetAt)

            reconciler.reconcileAll()

            // The post-delete send outranks the marker: rhythm anchors on it, nothing in
            // between is skipped.
            val expected =
                Instant
                    .ofEpochMilli(lastSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
        }

    @Test
    fun `enabled config without next send gets scheduled`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            stubConfigs(config(enabled = true, nextSendAtMillis = null))

            reconciler.reconcileAll()

            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            verify(repository, never()).saveConfig(any())
            verify(repository).alignPendingRow(eq(1), eq(next!!), eq(next!!), eq("+15550100"), eq("keep alive"))
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
        }

    // The armed epoch encodes "hour:minute in the armed zone"; without the recompute the
    // SMS keeps firing at the old wall-clock time.
    @Test
    fun `timezone change clears the armed epoch and recomputes it in the new zone`() =
        runBlocking<Unit> {
            val oldZone = ZoneId.of("Pacific/Kiritimati") // UTC+14
            val newZone = ZoneId.of("Pacific/Pago_Pago") // UTC-11
            val original = TimeZone.getDefault()
            var oldArmed = 0L
            try {
                TimeZone.setDefault(TimeZone.getTimeZone(oldZone))
                // "Today at 12:00 in the old zone", in the future.
                oldArmed =
                    ZonedDateTime
                        .now(oldZone)
                        .toLocalDate()
                        .atTime(12, 0)
                        .atZone(oldZone)
                        .plusDays(1)
                        .toInstant()
                        .toEpochMilli()
                // The stored epoch now reads as another wall-clock time.
                TimeZone.setDefault(TimeZone.getTimeZone(newZone))
                val armedConfig = config(enabled = true, nextSendAtMillis = oldArmed)
                // The clear pass sees the armed epoch; the per-SIM re-read sees the cleared
                // state, like the real repository after the clear pass's updateNextSend(null).
                whenever(repository.getAllConfigs()).thenReturn(listOf(armedConfig))
                whenever(repository.getConfig(1)).thenReturn(armedConfig.copy(nextSendAtMillis = null))

                reconciler.recomputeAfterTimezoneChange()
            } finally {
                TimeZone.setDefault(original)
            }

            // A suspend verify inside the try/finally would unbox the mock's null Boolean return.
            verify(armer).cancelSend(1)
            verify(repository).clearPendingHistory(1)
            // Two writes: the clear (null) and the recompute in the new zone.
            val updates =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository, times(2)).updateNextSend(eq(1), capture())
                    }.allValues
            assertNull(updates.first())
            val next = updates.filterNotNull().single()
            // The re-armed moment reads as the configured 12:00 in the new zone.
            assertEquals(LocalTime.of(12, 0), Instant.ofEpochMilli(next).atZone(newZone).toLocalTime())
            assertNotEquals(oldArmed, next)
            verify(repository).alignPendingRow(eq(1), eq(next), eq(next), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    // Cancelling would record a possibly delivered SMS as skipped: the live attempt owns
    // the row to completion and its result write re-anchors the next occurrence.
    @Test
    fun `timezone change leaves a live in-flight send untouched`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now - 5_000
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000),
                )

            reconciler.recomputeAfterTimezoneChange()

            // No epoch clear (the attempt's result write replaces it), no pending drop, no re-arm, no skip.
            verify(armer, never()).cancelSend(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            // If the attempt dies, the stale check resolves the row the moment it goes stale.
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
            assertTrue(
                "stale check runAfter ${staleCheck.nextScheduleTimeMillis} not within 5s of attempt+stale threshold",
                Math.abs(staleCheck.nextScheduleTimeMillis - (now - 5_000 + AppConfig.SENDING_STALE_MS)) < 5_000,
            )
        }

    // The recompute neither cancels (no live work) nor clears the epoch; the reconcileAll
    // pass resolves the stale row as interrupted.
    @Test
    fun `timezone change resolves a stale in-flight send as interrupted`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now - 70_000
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 70_000),
                )
            val flipBefore = System.currentTimeMillis()

            reconciler.recomputeAfterTimezoneChange()

            verify(armer, never()).cancelSend(anyInt())
            verify(repository, never()).clearPendingHistory(anyInt())
            // The advance lands in the same transaction as the guarded stale flip — no
            // separate write.
            val flipAfter = System.currentTimeMillis()
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            anyLong(),
                            eq(SendOutcome.SENDING),
                            eq(SendOutcome.SKIPPED),
                            eq(now - 70_000),
                            eq(context.getString(R.string.error_send_interrupted)),
                            eq(0),
                            isNull(),
                            argThat { value: Long? ->
                                value != null &&
                                    value in (flipBefore - AppConfig.SENDING_STALE_MS)..(flipAfter - AppConfig.SENDING_STALE_MS)
                            },
                            capture(),
                        )
                    }.firstValue as FinalizeAdvance.AdvanceToNext
            assertTrue(advance.nextSendAtMillis > now)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer).armSend(1)
        }

    // The recompute deletes the armed row, not finalizes it: without the re-derivation a
    // fresh SIM (no SENT anchor) loses its first send without a record.

    @Test
    fun `timezone change re-arms the deleted occurrence of a fresh SIM instead of dropping it`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // Armed 30s from now with a 60 min window: the armed time already carries its jitter.
            val armed = now + 30_000
            val windowedConfig = config(enabled = true, nextSendAtMillis = armed).copy(timeWindowMinutes = 60)
            // The clear pass sees the armed epoch; the per-SIM re-read sees the cleared state.
            whenever(repository.getAllConfigs()).thenReturn(listOf(windowedConfig))
            whenever(repository.getConfig(1)).thenReturn(windowedConfig.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0), null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.recomputeAfterTimezoneChange()

            // Re-armed at its own armed instant (no double jitter); the row carries the
            // exact pre-jitter base, not the armed instant.
            verify(armer).cancelSend(1)
            verify(repository).clearPendingHistory(1)
            verify(repository).updateNextSend(eq(1), eq(armed))
            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(windowedConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
        }

    @Test
    fun `timezone change re-arms the deleted occurrence within grace even for a SIM with history`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // 10 min past — inside the 15 min grace; the deleted-row anchor wins over the
            // last-send anchor, so the due occurrence is re-sent, not skipped.
            val armed = now - 10 * AppConfig.MINUTE_MS
            val lastSent = now - 30L * 24 * 3_600_000L
            val windowedConfig = config(enabled = true, nextSendAtMillis = armed).copy(timeWindowMinutes = 60)
            whenever(repository.getAllConfigs()).thenReturn(listOf(windowedConfig))
            whenever(repository.getConfig(1)).thenReturn(windowedConfig.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0), null)
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)

            reconciler.recomputeAfterTimezoneChange()

            verify(repository).updateNextSend(eq(1), eq(armed))
            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(windowedConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
        }

    // Without the retryCount guard the recompute would re-arm a fresh pending, losing the
    // retry chain (count, first/last attempt, backoff stage).
    @Test
    fun `timezone change keeps an open retry row and restores its retry time`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val lastAttempt = now - 60_000
            val occurrence = lastAttempt - 30_000
            // Armed at the retry time written after the second attempt.
            val armed = RetryPolicy.computeNextRetryTime(2, lastAttempt)
            val armedConfig = config(enabled = true, nextSendAtMillis = armed)
            // The clear pass sees the armed epoch; the per-SIM re-read sees the cleared
            // state, like the real repository after the clear pass's updateNextSend(null).
            whenever(repository.getAllConfigs()).thenReturn(listOf(armedConfig))
            whenever(repository.getConfig(1)).thenReturn(armedConfig.copy(nextSendAtMillis = null))
            // The retry row survives the clear (deletePending excludes it).
            val row =
                pendingRow(1, occurrence, retryCount = 2, outcome = SendOutcome.PENDING)
                    .copy(lastAttemptAtMillis = lastAttempt, firstAttemptAtMillis = occurrence)
            whenever(repository.getActiveHistory(1)).thenReturn(row)

            reconciler.recomputeAfterTimezoneChange()

            // No catch-up anchor: re-deriving from the occurrence would reset the retry state.
            verify(prefs, never()).setCatchUpAnchor(anyInt(), anyLong())
            verify(armer).cancelSend(1)
            verify(repository).clearPendingHistory(1)
            // Two writes: the clear (null) and the restored retry (attempt 2 backoff 20s +/- 10%).
            val updates =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository, times(2)).updateNextSend(eq(1), capture())
                    }.allValues
            assertNull(updates.first())
            val retryAt = updates.filterNotNull().single()
            assertTrue(
                "retryAt $retryAt not within 18..22s of the last attempt",
                retryAt in (lastAttempt + 18_000)..(lastAttempt + 22_000),
            )
            verify(armer).armSend(1)
            // No fresh pending is aligned over it.
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `timezone change records the deleted occurrence of a fresh SIM as skipped when past grace`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // 20 min past — beyond the 15 min grace.
            val armed = now - 20 * AppConfig.MINUTE_MS
            val armedConfig = config(enabled = true, nextSendAtMillis = armed)
            whenever(repository.getAllConfigs()).thenReturn(listOf(armedConfig))
            whenever(repository.getConfig(1)).thenReturn(armedConfig.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0), null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.recomputeAfterTimezoneChange()

            // The SKIPPED record is keyed on the exact base; the schedule re-arms at the
            // next cycle base.
            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(armed),
                eq(baseOf(armedConfig, armed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            val expected =
                Instant
                    .ofEpochMilli(armed)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    @Test
    fun `timezone change records the deleted last occurrence of an ending schedule`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val armed = now - 20 * AppConfig.MINUTE_MS
            // Ends before the next occurrence after the deleted one: the walk records the
            // miss and lands past the end date.
            val endingConfig =
                config(enabled = true, nextSendAtMillis = armed).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now(ZoneId.systemDefault()).plusDays(10),
                )
            whenever(repository.getAllConfigs()).thenReturn(listOf(endingConfig))
            whenever(repository.getConfig(1)).thenReturn(endingConfig.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0), null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.recomputeAfterTimezoneChange()

            // The miss is recorded even though the end check stops the arm, keyed on the
            // deleted occurrence's exact base.
            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(armed),
                eq(baseOf(endingConfig, armed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            verify(repository, never()).updateNextSend(eq(1), anyLong())
            verify(armer, never()).armSend(anyInt())
        }

    // A process death in the clear→re-arm window must not lose the deleted occurrence:
    // the clear half persists a durable catch-up anchor, the fresh arm consumes it. The
    // tests drive each half separately with a real prefs file in between.

    // Wired to the REAL prefs: the persisted anchor lives there; the class-level
    // reconciler keeps mocked prefs, whose anchor degrades to "unset".
    private fun crashReconciler(realPrefs: AppPrefs): ScheduleReconciler =
        ScheduleReconciler(
            context,
            repository,
            armer,
            realPrefs,
            OccurrenceAdvancer(context, repository, armer),
            sendLock,
        )

    @Test
    fun `recompute clear half persists the deleted occurrence anchor before clearing`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val armed = System.currentTimeMillis() + 30 * AppConfig.MINUTE_MS
            whenever(repository.getAllConfigs()).thenReturn(listOf(config(enabled = true, nextSendAtMillis = armed)))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0))

            crashReconciler(realPrefs).clearArmedSchedulesForRecompute()

            // The anchor is persisted before the clear: a death anywhere from here to the
            // re-arm must not lose the deleted occurrence.
            assertEquals(armed, realPrefs.getCatchUpAnchor(1))
            verify(armer).cancelSend(1)
            verify(repository).updateNextSend(eq(1), isNull())
            verify(repository).clearPendingHistory(1)
        }

    @Test
    fun `a crashed recompute re-arms the persisted anchor within grace on the next trigger`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val armed = System.currentTimeMillis() - 10 * AppConfig.MINUTE_MS
            val freshConfig = config(enabled = true, nextSendAtMillis = null)
            realPrefs.setCatchUpAnchor(1, armed)
            stubConfigs(freshConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            // Re-armed at its own armed instant, not the next future occurrence,
            // carrying its exact base.
            verify(repository).updateNextSend(eq(1), eq(armed))
            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(freshConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            // Consumed: the anchor is cleared from the prefs after the re-arm.
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a crashed recompute records the persisted anchor as skipped past grace on the next trigger`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            // 20 min past — beyond the 15 min grace.
            val armed = System.currentTimeMillis() - 20 * AppConfig.MINUTE_MS
            val freshConfig = config(enabled = true, nextSendAtMillis = null)
            realPrefs.setCatchUpAnchor(1, armed)
            stubConfigs(freshConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            // The SKIPPED record is keyed on the exact base; the schedule re-arms at the
            // next cycle base.
            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(armed),
                eq(baseOf(freshConfig, armed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            val expected =
                Instant
                    .ofEpochMilli(armed)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a crashed recompute's persisted anchor is consumed by a per-SIM reconcile`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            // The stale-check worker triggers per-SIM: it must consume the persisted anchor too.
            val armed = System.currentTimeMillis() - 10 * AppConfig.MINUTE_MS
            val freshConfig = config(enabled = true, nextSendAtMillis = null)
            realPrefs.setCatchUpAnchor(1, armed)
            whenever(repository.getConfig(1)).thenReturn(freshConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileSim(1)

            verify(repository).updateNextSend(eq(1), eq(armed))
            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(freshConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a crashed recompute's persisted anchor ends the schedule and is cleared when the walk lands past the end`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val armed = System.currentTimeMillis() - 20 * AppConfig.MINUTE_MS
            // The walk lands past the end date: the end branch must still record the miss
            // and clear the anchor.
            val endingConfig =
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now(ZoneId.systemDefault()).plusDays(10),
                )
            realPrefs.setCatchUpAnchor(1, armed)
            stubConfigs(endingConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(armed),
                eq(baseOf(endingConfig, armed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            verify(repository).endSchedule(1)
            verify(repository, never()).updateNextSend(eq(1), anyLong())
            verify(armer, never()).armSend(anyInt())
            // Miss recorded and schedule ended: the anchor is fully consumed.
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a crashed recompute's persisted anchor is dropped when a live sending row owns the occurrence`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val now = System.currentTimeMillis()
            val armed = now - 5_000
            // The live attempt owns the occurrence to completion: the anchor must be
            // dropped, not left to re-derive it on a later re-arm.
            realPrefs.setCatchUpAnchor(1, armed)
            stubConfigs(config(enabled = true, nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1))
                .thenReturn(pendingRow(1, armed, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000))

            crashReconciler(realPrefs).reconcileAll()

            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(armer, never()).armSend(anyInt())
            // The fresh attempt's stale check is armed (the resolveSendingRow path).
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a crashed recompute's persisted anchor is dropped when a retrying row owns the occurrence`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val now = System.currentTimeMillis()
            val lastAttempt = now - 60_000
            val occurrence = lastAttempt - 30_000
            // The retry row owns the occurrence: the anchor must be dropped, not left stale.
            realPrefs.setCatchUpAnchor(1, occurrence)
            stubConfigs(config(enabled = true, nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, occurrence, retryCount = 2, outcome = SendOutcome.PENDING)
                        .copy(lastAttemptAtMillis = lastAttempt, firstAttemptAtMillis = occurrence),
                )

            crashReconciler(realPrefs).reconcileAll()

            // Attempt 2 backoff is 20s with +/-10% jitter, anchored to the last attempt.
            val retryAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(
                "retryAt $retryAt not within 18..22s of the last attempt",
                retryAt!! in (lastAttempt + 18_000)..(lastAttempt + 22_000),
            )
            verify(armer).armSend(1)
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    @Test
    fun `a completed recompute consumes the anchor it persisted so no stale anchor survives`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val armed = System.currentTimeMillis() + 30_000
            val armedConfig = config(enabled = true, nextSendAtMillis = armed)
            whenever(repository.getAllConfigs()).thenReturn(listOf(armedConfig))
            whenever(repository.getConfig(1)).thenReturn(armedConfig.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0), null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).recomputeAfterTimezoneChange()

            // The persisted twin the clear half wrote is cleared with the re-arm: a
            // leftover would re-derive the same occurrence on a later re-arm.
            verify(repository).updateNextSend(eq(1), eq(armed))
            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(armedConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            assertEquals(0L, realPrefs.getCatchUpAnchor(1))
        }

    // The anchor holds the last SENT occurrence's base and the engine advanced from it:
    // the re-anchor must start at the NEXT occurrence, or the sent base is re-armed
    // (a duplicate SMS) or recorded as a false miss.
    @Test
    fun `history-clear fallback anchor re-arms the next occurrence after the last sent one when no SENT rows exist`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            // History cleared: the last-sent-occurrence anchor column (written in the
            // finalizeOutcome transaction) still holds the base, 10 min past — inside the grace.
            val lastOccurrence = System.currentTimeMillis() - 10 * AppConfig.MINUTE_MS
            val anchorConfig =
                config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence)
            stubConfigs(anchorConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            // The re-armed occurrence is the one AFTER the anchor (the engine already
            // advanced); with the zero window the armed time is exactly the next base.
            val expected =
                Instant
                    .ofEpochMilli(lastOccurrence)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertNotEquals(lastOccurrence, expected)
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            // The anchor is kept (the re-arm writes a PENDING row, not SENT): a later
            // history-cleared recompute re-derives the rhythm from it.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    @Test
    fun `history-clear fallback anchor past grace arms the next occurrence without recording the sent one as missed`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            // The anchor's occurrence actually went out (sent and advanced): it must never
            // get a "missed occurrence" record — the catch-up starts at the NEXT occurrence.
            val lastOccurrence = System.currentTimeMillis() - 20 * AppConfig.MINUTE_MS
            val anchorConfig =
                config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence)
            stubConfigs(anchorConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            // No false "missed occurrence" for the anchor; the schedule re-arms at the
            // next occurrence after it.
            val expected =
                Instant
                    .ofEpochMilli(lastOccurrence)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            // The anchor is kept (the re-arm writes a PENDING row, not SENT): a later
            // history-cleared recompute re-derives the rhythm from it.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    @Test
    fun `history-clear fallback anchor records only the genuinely elapsed occurrences past the anchor`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            // 40 days past (30-day interval): the occurrence after the anchor genuinely
            // never went out (past grace — it gets its record), but the anchor itself was sent.
            val lastOccurrence = System.currentTimeMillis() - 40L * 24 * 3_600_000L
            val anchorConfig =
                config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence)
            stubConfigs(anchorConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            crashReconciler(realPrefs).reconcileAll()

            val anchorDate = Instant.ofEpochMilli(lastOccurrence).atZone(ZoneId.systemDefault()).toLocalDate()
            val elapsed =
                anchorDate
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val expected =
                anchorDate
                    .plusDays(60)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            // The genuinely elapsed occurrence is recorded, keyed on its exact base ...
            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(elapsed),
                eq(baseOf(anchorConfig, elapsed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            // ... and the anchor's own base is never recorded as missed.
            verify(
                repository,
                never(),
            ).insertSkippedIfNotTerminal(eq(1), anyLong(), eq(baseOf(anchorConfig, lastOccurrence)), any(), any(), any())
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            // The anchor is kept (the re-arm writes a PENDING row, not SENT): a later
            // history-cleared recompute re-derives the rhythm from it.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    @Test
    fun `last-occurrence anchor is a fallback only — SENT rows and catchUpAnchor take precedence`() =
        runBlocking<Unit> {
            val realPrefs = AppPrefs(context)
            val lastOccurrence = System.currentTimeMillis() - 3 * AppConfig.DAY_MS
            val lastSent = System.currentTimeMillis() - AppConfig.DAY_MS
            stubConfigs(config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence))
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)

            crashReconciler(realPrefs).reconcileAll()

            verify(armer).armSend(1)
            // The anchor is kept (the re-arm writes a PENDING row, not SENT): it must
            // survive for a later history-cleared recompute.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    // Without the anchor surviving the retry re-arm, the chain lands in armFreshSchedule
    // with no open row and no SENT row: it falls to firstOccurrence(now) and every
    // elapsed occurrence vanishes without a record.
    @Test
    fun `last-sent anchor survives the retry re-arm so a later history-cleared recompute re-derives the rhythm from it`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // O_n 40 days ago (30-day interval): O_{n+1} (10 days ago) never went out,
            // O_{n+2} is the rhythm's next send.
            val lastOccurrence = now - 40L * 24 * 3_600_000L
            val anchorDate = Instant.ofEpochMilli(lastOccurrence).atZone(ZoneId.systemDefault()).toLocalDate()
            val elapsed =
                anchorDate
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val expected =
                anchorDate
                    .plusDays(60)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            // The config keeps the anchor column across the chain; only the open row
            // changes between the passes.
            val anchorConfig =
                config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence)
            // Pass 1: retry row intact; pass 2: retry failed, history cleared, SIM re-enabled.
            val lastAttempt = now - 60_000
            val retryRow =
                pendingRow(1, lastOccurrence, retryCount = 1, outcome = SendOutcome.PENDING)
                    .copy(lastAttemptAtMillis = lastAttempt, firstAttemptAtMillis = lastOccurrence)
            stubConfigs(anchorConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(retryRow, null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.reconcileAll()
            reconciler.reconcileAll()

            // The retry re-arm restored the retry time (attempt 1 backoff 10s +/- 10%) ...
            val nextSends =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository, times(2)).updateNextSend(eq(1), capture())
                    }.allValues
            assertTrue(
                "retry re-arm ${nextSends[0]} not within 9..11s of the last attempt",
                nextSends[0]!! in (lastAttempt + 9_000)..(lastAttempt + 11_000),
            )
            // ... and the second pass re-armed the rhythm's next occurrence after O_n.
            assertEquals(expected, nextSends[1])
            // The genuinely elapsed occurrence got its SKIPPED record.
            verify(
                repository,
            ).insertSkippedIfNotTerminal(
                eq(1),
                eq(elapsed),
                eq(baseOf(anchorConfig, elapsed)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq("+15550100"),
                eq("keep alive"),
            )
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer, times(2)).armSend(1)
            // Neither pass cleared the anchor.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    @Test
    fun `a live sending row with a last-sent anchor keeps the anchor for a later history-cleared recompute`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val lastOccurrence = now - 40L * 24 * 3_600_000L
            val anchorConfig =
                config(enabled = true, nextSendAtMillis = null).copy(lastSentOccurrenceMillis = lastOccurrence)
            stubConfigs(anchorConfig)
            whenever(repository.getActiveHistory(1))
                .thenReturn(pendingRow(1, now - 5_000, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000))

            reconciler.reconcileAll()

            // The live attempt owns the occurrence to completion: no re-arm ...
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(armer, never()).armSend(anyInt())
            // ... and the anchor stays (the failure outcome writes no SENT row): a later
            // history-cleared recompute re-derives the rhythm from it.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    // Re-arming a consumed occurrence (a terminal row for its base) would send a
    // duplicate SMS: the fresh arm walks past every consumed occurrence.
    @Test
    fun `fresh arm skips a consumed occurrence and arms the next one`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // 10 min past — inside the grace, so the walk does not skip it as a miss.
            val armed = now - 10 * AppConfig.MINUTE_MS
            val freshConfig = config(enabled = true, nextSendAtMillis = null)
            // A terminal row exists for the exact base; the walk judges the jittered
            // anchor instant by its base.
            whenever(repository.countFinalizedByBase(1, baseOf(freshConfig, armed))).thenReturn(1)
            stubConfigs(freshConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.reconcileAll(mapOf(1 to armed))

            val expected =
                Instant
                    .ofEpochMilli(armed)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            // Neither re-armed nor re-recorded: its terminal row is the record, no skip ghost.
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
            verify(prefs).clearCatchUpAnchor(1)
            // The re-arm writes a PENDING row, not SENT: it must not clear the last-sent anchor.
            verify(repository, never()).clearLastSentOccurrence(1)
        }

    @Test
    fun `fresh arm skips a chain of consumed occurrences and arms the one after`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val armed = now - 10 * AppConfig.MINUTE_MS
            val freshConfig = config(enabled = true, nextSendAtMillis = null)
            // Both the walk's occurrence and its successor are consumed: the first is the
            // anchor's jittered instant (judged by its exact base), the successor a clean base.
            val first =
                Instant
                    .ofEpochMilli(armed)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            whenever(repository.countFinalizedByBase(1, baseOf(freshConfig, armed))).thenReturn(1)
            whenever(repository.countFinalizedByBase(1, first)).thenReturn(1)
            stubConfigs(freshConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.reconcileAll(mapOf(1 to armed))

            val expected =
                Instant
                    .ofEpochMilli(first)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository).alignPendingRow(eq(1), eq(expected), eq(expected), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    @Test
    fun `enabled config past its end date is disabled and not armed`() =
        runBlocking<Unit> {
            stubConfigs(
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(1),
                ),
            )

            reconciler.reconcileAll()

            // Ended like the armed-past-end branch, so the dashboard can no longer show
            // "On" for a schedule that will never send.
            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `enabled config with a future end date is armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            stubConfigs(
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().plusDays(5),
                ),
            )

            reconciler.reconcileAll()

            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            verify(repository, never()).saveConfig(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `armed config past its end date is disabled once`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))

            reconciler.reconcileAll()

            // Closed with the end reason in the same transaction as the end: no separate
            // endSchedule write.
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(endedText),
                eq(0),
                isNull(),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).saveConfig(any())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
        }

    // A retry row carries the original occurrence while nextSendAtMillis holds the retry
    // time, so the end's time match misses it: it must still be closed, or it ghosts a
    // "retry in progress" indicator on the disabled SIM.
    @Test
    fun `armed config past its end date with a retrying row closes the orphaned retry as skipped`() =
        runBlocking<Unit> {
            val occurrence = System.currentTimeMillis() - 3_600_000L
            val retryAt = System.currentTimeMillis() + 3 * 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = retryAt).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, occurrence, retryCount = 2, outcome = SendOutcome.PENDING)
                        .copy(lastAttemptAtMillis = occurrence, firstAttemptAtMillis = occurrence),
                )

            reconciler.reconcileAll()

            // Closed with the end reason in the same transaction as the end: no separate
            // endSchedule write.
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(endedText),
                eq(2),
                eq(occurrence),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).saveConfig(any())
        }

    // Cancelling would kill the running worker mid-radio and record a possibly delivered
    // SMS as skipped (uncounted for AFTER_N_SENDS): the end is deferred to the attempt's
    // result write, under the SIM lock against the latest config. A stale SENDING row (no
    // live owner) is still closed with the end reason (see the test below).

    @Test
    fun `armed config past its end date with a live sending row defers the end to the attempt`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000),
                )

            reconciler.reconcileAll()

            verify(repository, never()).endSchedule(1)
            verify(armer, never()).cancelSend(anyInt())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).saveConfig(any())
            // If the attempt dies, the stale check resolves the row when it goes stale and
            // the next reconcile ends the schedule then.
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
            assertTrue(
                "stale check runAfter ${staleCheck.nextScheduleTimeMillis} not within 5s of attempt+stale threshold",
                Math.abs(staleCheck.nextScheduleTimeMillis - (now - 5_000 + AppConfig.SENDING_STALE_MS)) < 5_000,
            )
        }

    @Test
    fun `armed config at its send limit with a live sending row defers the end to the attempt`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now + 3_600_000L
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                ),
            )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000),
                )

            reconciler.reconcileAll()

            // The live attempt's result write records the SENT row and the count completing
            // the limit, and ends the schedule with it — no early end here.
            verify(repository, never()).endSchedule(1)
            verify(armer, never()).cancelSend(anyInt())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(armer, never()).armSend(anyInt())
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
        }

    // Stale (the attempt is long dead, no live owner): the end must still close it with
    // the end reason and its retry state — the fresh variant is deferred to the attempt.
    @Test
    fun `armed config past its end date with a stale sending row claimed from a retry closes it as skipped`() =
        runBlocking<Unit> {
            val occurrence = System.currentTimeMillis() - 3 * 3_600_000L
            val retryAt = System.currentTimeMillis() + 3 * 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = retryAt).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, occurrence, retryCount = 2, outcome = SendOutcome.SENDING)
                        .copy(lastAttemptAtMillis = occurrence, firstAttemptAtMillis = occurrence),
                )

            reconciler.reconcileAll()

            // Closed with the end reason and retry state in the same transaction as the end.
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(occurrence),
                eq(endedText),
                eq(2),
                eq(occurrence),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `armed config past its end date with no open row ends without history writes`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            reconciler.reconcileAll()

            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `armed config past its end date leaves an unrelated pending row for the disabled branch`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            // Not the armed occurrence, so the end branch does not claim it — the disabled
            // branch drops it on the next pass.
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next - 3_600_000L, 0))

            reconciler.reconcileAll()

            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `armed config past its end date closes a concurrently claimed row as in-flight`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            // First read PENDING; the flip misses (a concurrent claim); the follow-up read sees SENDING.
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, 0),
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 1_000),
                )
            // First (PENDING) flip loses the race, the follow-up SENDING flip wins.
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(false, true)

            reconciler.reconcileAll()

            // The winning flip carries the end with it: no separate endSchedule write.
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(now - 1_000),
                eq(endedText),
                eq(0),
                eq(now - 1_000),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(
                repository,
                times(2),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
        }

    // The flip also returns false when a concurrent run already finalized the row: the end
    // must not write over the winner's terminal outcome.
    @Test
    fun `armed config past its end date does not clobber a concurrently finalized row`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = endDate,
                ),
            )
            // First read PENDING; the flip misses (a concurrent finalize); the follow-up read sees no row.
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0), null)
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(false)

            reconciler.reconcileAll()

            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            // The flip lost the race: no write over the winner's outcome, but the end still
            // lands on its own.
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(endedText),
                eq(0),
                isNull(),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `armed config on its end date is not disabled`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().plusDays(1),
                ),
            )
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))

            reconciler.reconcileAll()

            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateHistory(anyLong(), any(), isNull(), isNull(), anyInt(), isNull())
            verify(armer, never()).cancelSend(anyInt())
            verify(armer).armSend(1)
        }

    @Test
    fun `enabled config at its send limit is disabled and not armed`() =
        runBlocking<Unit> {
            stubConfigs(
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                ),
            )

            reconciler.reconcileAll()

            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `enabled config with remaining sends is armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            stubConfigs(
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 1,
                ),
            )

            reconciler.reconcileAll()

            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            verify(repository, never()).saveConfig(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `armed config at its send limit is disabled once`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            stubConfigs(
                config(enabled = true, nextSendAtMillis = next).copy(
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                ),
            )
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))

            reconciler.reconcileAll()

            // Closed with the end reason in the same transaction as the end: no separate
            // endSchedule write.
            val endedText = context.resources.getQuantityString(R.plurals.schedule_ended_after_sends, 3, 3)
            verify(
                repository,
            ).finalizeOutcome(
                anyLong(),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(endedText),
                eq(0),
                isNull(),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).saveConfig(any())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `enabled config with live send work is not re-armed`() =
        runBlocking {
            stubConfigs(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3 * 3_600_000L))
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    "keepalive_send_1",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SendWorker>()
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build(),
                )

            reconciler.reconcileAll()

            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `enabled config without send work is re-armed`() =
        runBlocking {
            stubConfigs(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3 * 3_600_000L))

            reconciler.reconcileAll()

            verify(armer).armSend(1)
            verify(repository, never()).saveConfig(any())
        }

    // The worker fails on purpose: the suite-default WorkManager runs no-delay work
    // synchronously, so the send work settles in a terminal state at the enqueue.
    @Test
    fun `enabled config with a terminal send work is re-armed`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    "keepalive_send_1",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FailingSendWorker>().build(),
                )
            val info = awaitTerminalState("keepalive_send_1")
            assertNotNull("the send work did not settle in a terminal state in time", info)

            reconciler.reconcileAll()

            verify(armer).armSend(1)
        }

    // The re-arm REPLACEs the unique work and would cancel the in-flight send; the hanging
    // worker (reused from the armer test) is the only way to observe RUNNING.
    @Test
    fun `enabled config with a running send work is not re-armed`() =
        runBlocking<Unit> {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
            )
            ScheduleArmerTest.releaseGate = CountDownLatch(1)
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    "keepalive_send_1",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ScheduleArmerTest.HangingSendWorker>().build(),
                )
            assertNotNull(
                "the send work did not reach RUNNING in time",
                awaitWorkState("keepalive_send_1", WorkInfo.State.RUNNING),
            )
            stubConfigs(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3 * 3_600_000L))

            reconciler.reconcileAll()

            verify(armer, never()).armSend(anyInt())

            // Release the worker so the run (and the test WorkManager) can settle.
            ScheduleArmerTest.releaseGate.countDown()
            awaitWorkState("keepalive_send_1", WorkInfo.State.SUCCEEDED)
        }

    // Must never hang or throw (the UI commit runs reconcileSim under the SIM's send
    // lock), and a wrong re-arm would REPLACE a possibly RUNNING send and cancel the
    // in-flight keepalive.
    @Test
    fun `reconcile degrades to assuming live work when the send work state read fails`() =
        runBlocking {
            val next = System.currentTimeMillis() + 3_600_000L
            val armedConfig = config(enabled = true, nextSendAtMillis = next)
            whenever(repository.getConfig(1)).thenReturn(armedConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))
            // The delegate swap (the test init helper's own mechanism) makes the mock
            // visible from every thread, including the reconciler's Dispatchers.IO read.
            val wm = mock<WorkManagerImpl>()
            val failing = ResolvableFuture.create<List<WorkInfo>>()
            failing.setException(RuntimeException("stuck work db"))
            whenever(wm.getWorkInfosForUniqueWork("keepalive_send_1")).thenReturn(failing)
            // No restore needed: @Before re-initializes the test WorkManager for every test.
            WorkManagerImpl.setDelegate(wm)

            reconciler.reconcileSim(1)

            verify(armer, never()).armSend(anyInt())
            // The reconcile continued past the failed check instead of aborting.
            verify(repository).alignPendingRow(eq(1), eq(next), eq(baseOf(armedConfig, next)), eq("+15550100"), eq("keep alive"))
            // The degrade is logged as a warn.
            assertTrue(
                LogBuffer.entries.any {
                    it.level == "W" && it.tag == "SendWorkState" && it.message.contains("treating it as live work")
                },
            )
        }

    @Test
    fun `reconcile degrades to assuming live work when the send work state read times out`() =
        runBlocking {
            val next = System.currentTimeMillis() + 3_600_000L
            val armedConfig = config(enabled = true, nextSendAtMillis = next)
            whenever(repository.getConfig(1)).thenReturn(armedConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))
            // A never-completing future stands in for a stuck work DB (the delegate-swap
            // mechanics are in the read-failure test above).
            val wm = mock<WorkManagerImpl>()
            whenever(wm.getWorkInfosForUniqueWork("keepalive_send_1")).thenReturn(ResolvableFuture.create<List<WorkInfo>>())
            // No restore needed: @Before re-initializes the test WorkManager for every test.
            WorkManagerImpl.setDelegate(wm)

            val startedAt = System.currentTimeMillis()
            reconciler.reconcileSim(1)
            val elapsedMillis = System.currentTimeMillis() - startedAt
            // Bounded by the 2 s read timeout; the lower bound proves the read actually
            // waited on it.
            assertTrue(
                "reconcile took $elapsedMillis ms, the stuck read must be bounded to ~2 s",
                elapsedMillis in 1_500..30_000,
            )

            verify(armer, never()).armSend(anyInt())
            verify(repository).alignPendingRow(eq(1), eq(next), eq(baseOf(armedConfig, next)), eq("+15550100"), eq("keep alive"))
        }

    // Alarms do not survive a reboot, WorkManager work does: active it re-arms from a
    // fresh read, inactive (permission revoked here) it cancels every pending exact alarm.
    @Config(sdk = [33])
    @Test
    fun `reconcileAll cancels the exact alarm while the permission is revoked and re-arms it once granted`() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val exactPrefs = AppPrefs(context)
            exactPrefs.exactWakeEnabled = true
            val exactArmer = ScheduleArmer(context, repository, exactPrefs, PermissionManager(context))
            val exactReconciler =
                ScheduleReconciler(
                    context,
                    repository,
                    exactArmer,
                    prefs,
                    OccurrenceAdvancer(context, repository, exactArmer),
                    sendLock,
                )
            val nextSend = System.currentTimeMillis() + 3 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = nextSend))
            // Live send work: the per-SIM pass must not re-arm, so only the post-pass can
            // touch the exact alarm.
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    "keepalive_send_1",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SendWorker>()
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build(),
                )
            exactArmer.armExactAlarm(1, nextSend)
            assertEquals(1, shadowAlarms().size)

            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            exactReconciler.reconcileAll()
            assertTrue(shadowAlarms().isEmpty())

            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            exactReconciler.reconcileAll()
            assertEquals(1, shadowAlarms().size)
        }

    @Test
    fun `reconcile single sim arms schedule when next send missing`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            whenever(repository.getConfig(1)).thenReturn(config(enabled = true, nextSendAtMillis = null))

            reconciler.reconcileSim(1)

            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            verify(repository, never()).saveConfig(any())
            verify(repository).alignPendingRow(eq(1), eq(next!!), eq(next!!), eq("+15550100"), eq("keep alive"))
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `reconcile single sim cancels work when disabled`() =
        runBlocking<Unit> {
            whenever(repository.getConfig(1))
                .thenReturn(config(enabled = false, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L))

            reconciler.reconcileSim(1)

            verify(armer).cancelSend(1)
            // No open row: only the armed work is cancelled (the disarm lives in the
            // finalizeOutcome transaction).
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `re-saved sim far past its anchor records skipped occurrences`() =
        runBlocking<Unit> {
            val lastSent = System.currentTimeMillis() - 40L * 24 * 3_600_000L
            whenever(repository.getConfig(1))
                .thenReturn(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)

            reconciler.reconcileSim(1)

            // Recorded through the guarded insert (no SENT row for the anchor in a healthy state).
            val skipped =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository, times(1)).insertSkippedIfNotTerminal(
                            eq(1),
                            capture(),
                            anyLong(),
                            eq(context.getString(R.string.error_missed_occurrence)),
                            eq("+15550100"),
                            eq("keep alive"),
                        )
                    }.firstValue
            verify(repository, never()).insertHistory(any())
            val expectedAnchor =
                Instant
                    .ofEpochMilli(lastSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertTrue(
                "skipped $skipped not within 60s of expected anchor $expectedAnchor",
                Math.abs(skipped!! - expectedAnchor) < 60_000,
            )
            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).alignPendingRow(eq(1), capture(), anyLong(), eq("+15550100"), eq("keep alive"))
                    }.firstValue
            assertTrue(next!! > System.currentTimeMillis())
            verify(armer).armSend(1)
        }

    @Test
    fun `recompute anchors past a SENT row newer than the stored last send`() =
        runBlocking<Unit> {
            // The stored last-send column is display-only and may be stale: the recompute
            // must anchor from the SENT row, or it re-arms the occurrence that already went
            // out (a second, charged SMS).
            val now = System.currentTimeMillis()
            val staleLastSent = now - 40L * 24 * 3_600_000L // stale anchor: 10 days ago, past grace
            val provenSent = now - 2L * 24 * 3_600_000L // the occurrence that actually went out
            whenever(repository.getConfig(1))
                .thenReturn(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = staleLastSent))
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(provenSent)

            reconciler.reconcileSim(1)

            verify(repository, never()).insertHistory(any())
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            val expected =
                Instant
                    .ofEpochMilli(provenSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(
                "next $next not within 60s of expected $expected",
                Math.abs(next!! - expected) < 60_000,
            )
            verify(armer).armSend(1)
        }

    @Test
    fun `retention deletes old history rows`() =
        runBlocking {
            whenever(prefs.historyRetentionDays).thenReturn(30)
            whenever(repository.getAllConfigs()).thenReturn(emptyList())
            whenever(repository.deleteOldHistory(anyLong())).thenReturn(2)

            reconciler.reconcileAll()

            val cutoff =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).deleteOldHistory(capture())
                    }.firstValue
            val expected = System.currentTimeMillis() - 30L * 24 * 3_600_000L
            assertTrue("cutoff $cutoff not within 60s of $expected", Math.abs(cutoff - expected) < 60_000)
        }

    @Test
    fun `re-saved active sim schedules next send at the configured time`() =
        runBlocking {
            val lastSent = System.currentTimeMillis() - 10L * 24 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            reconciler.reconcileAll()

            val expected =
                Instant
                    .ofEpochMilli(lastSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(30)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            verify(repository, never()).saveConfig(any())
            assertTrue(
                "next $next not within 60s of expected $expected",
                Math.abs(next!! - expected) < 60_000,
            )
        }

    @Test
    fun `re-saved sim with a last send applies the configured time to the next occurrence`() =
        runBlocking {
            val lastSent = System.currentTimeMillis() - 24L * 3_600_000L
            stubConfigs(
                config(enabled = true, nextSendAtMillis = null)
                    .copy(lastSentAtMillis = lastSent, hour = 19, minute = 0, daysInterval = 2),
            )
            whenever(repository.latestSentMillis(1)).thenReturn(lastSent)
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            reconciler.reconcileAll()

            val expected =
                Instant
                    .ofEpochMilli(lastSent)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(2)
                    .atTime(19, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            verify(repository).updateNextSend(eq(1), eq(expected))
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `stale pending row is replaced to match next send`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val armedConfig = config(enabled = true, nextSendAtMillis = next)
            stubConfigs(armedConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next - 1000L, 0))

            reconciler.reconcileAll()

            verify(repository).alignPendingRow(1, next, baseOf(armedConfig, next), "+15550100", "keep alive")
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `pending row matching next send is kept`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            val armedConfig = config(enabled = true, nextSendAtMillis = next)
            stubConfigs(armedConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, next, 0))

            reconciler.reconcileAll()

            verify(repository).alignPendingRow(1, next, baseOf(armedConfig, next), "+15550100", "keep alive")
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `pending row with retries in flight is left alone but re-armed when no live work`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(pendingRow(1, next - 5000L, retryCount = 2, outcome = SendOutcome.PENDING))

            reconciler.reconcileAll()

            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateHistory(anyLong(), any(), any(), any(), anyInt(), any())
            // No send work exists: the pending retry must be re-armed, not lost.
            verify(armer).armSend(1)
        }

    @Test
    fun `pending row with retries in flight is not re-armed when work is live`() =
        runBlocking<Unit> {
            val next = System.currentTimeMillis() + 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(pendingRow(1, next - 5000L, retryCount = 2, outcome = SendOutcome.PENDING))
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    "keepalive_send_1",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SendWorker>()
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build(),
                )

            reconciler.reconcileAll()

            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `fresh sending row is left alone but the stale check is armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now - 5_000
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000),
                )

            reconciler.reconcileAll()

            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateHistory(anyLong(), any(), any(), any(), anyInt(), any())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())
            // A delayed reconcile is armed for the moment it goes stale, so a dead
            // attempt never stays unowned.
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
            assertTrue(
                "stale check runAfter ${staleCheck.nextScheduleTimeMillis} not within 5s of attempt+stale threshold",
                Math.abs(staleCheck.nextScheduleTimeMillis - (next + AppConfig.SENDING_STALE_MS)) < 5_000,
            )
        }

    @Test
    fun `reconcileAll continues processing remaining configs when one throws`() =
        runBlocking<Unit> {
            val badConfig = config(enabled = true, nextSendAtMillis = null)
            val next = System.currentTimeMillis() + 3_600_000L
            val goodConfig = config(simId = 2, enabled = true, nextSendAtMillis = next)
            stubConfigs(badConfig, goodConfig)
            whenever(repository.getActiveHistory(1)).thenThrow(RuntimeException("sim 1 fault"))
            whenever(repository.getActiveHistory(2)).thenReturn(null)

            reconciler.reconcileAll()

            verify(armer).armSend(2)
        }

    @Test
    fun `reconcileAll judges on the fresh per-SIM read instead of the stale snapshot`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val stale =
                config(enabled = true, nextSendAtMillis = now + 3_600_000L).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now(ZoneId.systemDefault()).minusDays(1),
                )
            val fresh =
                config(enabled = true, nextSendAtMillis = null).copy(
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now(ZoneId.systemDefault()).plusDays(30),
                )
            whenever(repository.getAllConfigs()).thenReturn(listOf(stale))
            whenever(repository.getConfig(1)).thenReturn(fresh)
            whenever(repository.getActiveHistory(1)).thenReturn(null)
            whenever(repository.latestSentMillis(1)).thenReturn(null)

            reconciler.reconcileAll()

            verify(repository, never()).endSchedule(anyInt())
            verify(armer, never()).cancelSend(anyInt())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            val next =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(next!! > now)
            verify(repository).alignPendingRow(eq(1), eq(next), eq(baseOf(fresh, next)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    @Test
    fun `reconcileAll skips a SIM whose row is gone when the per-SIM read happens`() =
        runBlocking<Unit> {
            whenever(repository.getAllConfigs())
                .thenReturn(listOf(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L)))
            whenever(repository.getConfig(1)).thenReturn(null)

            reconciler.reconcileAll()

            verify(armer, never()).cancelSend(anyInt())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
        }

    @Test
    fun `reconcileAll holds the SIM's send lock around its per-SIM writes`() =
        runBlocking<Unit> {
            val armed = System.currentTimeMillis() + 3_600_000L
            val armedConfig = config(enabled = true, nextSendAtMillis = armed)
            var snapshotTaken = false
            whenever(repository.getAllConfigs()).thenAnswer {
                snapshotTaken = true
                listOf(armedConfig)
            }
            whenever(repository.getConfig(1)).thenReturn(armedConfig)
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, armed, 0))

            val lockHeld = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            launch {
                sendLock.withLock(1) {
                    lockHeld.complete(Unit)
                    release.await()
                }
            }
            lockHeld.await()
            val pass = launch { reconciler.reconcileAll() }
            while (!snapshotTaken) {
                yield()
            }
            // The pass is queued behind the holder: its per-SIM writes have not landed yet.
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())

            release.complete(Unit)
            pass.join()

            verify(repository).alignPendingRow(eq(1), eq(armed), eq(baseOf(armedConfig, armed)), eq("+15550100"), eq("keep alive"))
            verify(armer).armSend(1)
        }

    @Test
    fun `stale sending row is skipped silently and next occurrence armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val next = now - 70_000
            stubConfigs(config(enabled = true, nextSendAtMillis = next))
            whenever(repository.getActiveHistory(1))
                .thenReturn(pendingRow(1, next, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 70_000))
            val flipBefore = System.currentTimeMillis()

            reconciler.reconcileAll()

            // The winner's advance lands in the same transaction as the guarded stale flip —
            // no separate write.
            val flipAfter = System.currentTimeMillis()
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            anyLong(),
                            eq(SendOutcome.SENDING),
                            eq(SendOutcome.SKIPPED),
                            eq(now - 70_000),
                            eq(context.getString(R.string.error_send_interrupted)),
                            eq(0),
                            isNull(),
                            argThat { value: Long? ->
                                value != null &&
                                    value in (flipBefore - AppConfig.SENDING_STALE_MS)..(flipAfter - AppConfig.SENDING_STALE_MS)
                            },
                            capture(),
                        )
                    }.firstValue as FinalizeAdvance.AdvanceToNext
            assertTrue(advance.nextSendAtMillis > now)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            // Resolved on the spot: no follow-up check is armed.
            assertTrue(
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .isEmpty(),
            )
        }

    @Test
    fun `in-flight sending row with missing next send is left alone but the stale check is armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            stubConfigs(config(enabled = true, nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, now - 5_000, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 5_000),
                )

            reconciler.reconcileAll()

            // The live attempt owns the row to completion; its result writes the next value.
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).updateHistory(anyLong(), any(), any(), any(), anyInt(), any())
            verify(armer, never()).armSend(anyInt())
            val staleCheck =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .firstOrNull()
            assertNotNull(staleCheck)
            assertEquals(WorkInfo.State.ENQUEUED, staleCheck!!.state)
            assertTrue(
                "stale check runAfter ${staleCheck.nextScheduleTimeMillis} not within 5s of attempt+stale threshold",
                Math.abs(staleCheck.nextScheduleTimeMillis - (now - 5_000 + AppConfig.SENDING_STALE_MS)) < 5_000,
            )
        }

    @Test
    fun `stale sending row with missing next send is skipped and next occurrence armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            stubConfigs(config(enabled = true, nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, now - 70_000, outcome = SendOutcome.SENDING).copy(lastAttemptAtMillis = now - 70_000),
                )
            val flipBefore = System.currentTimeMillis()

            reconciler.reconcileAll()

            // The winner's advance lands in the same transaction as the guarded stale flip.
            val flipAfter = System.currentTimeMillis()
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            anyLong(),
                            eq(SendOutcome.SENDING),
                            eq(SendOutcome.SKIPPED),
                            eq(now - 70_000),
                            eq(context.getString(R.string.error_send_interrupted)),
                            eq(0),
                            isNull(),
                            argThat { value: Long? ->
                                value != null &&
                                    value in (flipBefore - AppConfig.SENDING_STALE_MS)..(flipAfter - AppConfig.SENDING_STALE_MS)
                            },
                            capture(),
                        )
                    }.firstValue as FinalizeAdvance.AdvanceToNext
            assertTrue(advance.nextSendAtMillis > now)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            // Resolved on the spot: no follow-up check is armed.
            assertTrue(
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                    .get()
                    .isEmpty(),
            )
        }

    @Test
    fun `retrying row with missing next send restores the retry time and re-arms`() =
        runBlocking<Unit> {
            val lastAttempt = System.currentTimeMillis() - 60_000
            stubConfigs(config(enabled = true, nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    pendingRow(1, lastAttempt - 30_000, retryCount = 2, outcome = SendOutcome.PENDING)
                        .copy(lastAttemptAtMillis = lastAttempt, firstAttemptAtMillis = lastAttempt - 30_000),
                )

            reconciler.reconcileAll()

            // Attempt 2 backoff is 20s with +/-10% jitter, anchored to the last attempt.
            val retryAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(
                "retryAt $retryAt not within 18..22s of the last attempt",
                retryAt!! in (lastAttempt + 18_000)..(lastAttempt + 22_000),
            )
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `orphan pending row with missing next send is replaced`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val lastSent = now - 10L * 24 * 3_600_000L
            stubConfigs(config(enabled = true, nextSendAtMillis = null).copy(lastSentAtMillis = lastSent))
            whenever(repository.getActiveHistory(1)).thenReturn(pendingRow(1, now - 5_000, 0))

            reconciler.reconcileAll()

            verify(repository).alignPendingRow(eq(1), anyLong(), anyLong(), eq("+15550100"), eq("keep alive"))
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `reconcileAll shows the persistent hint when any keepalive is enabled`() =
        runBlocking<Unit> {
            whenever(prefs.showStickyNotification).thenReturn(true)
            stubConfigs(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L))

            reconciler.reconcileAll()

            assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `reconcileAll dismisses the persistent hint when no keepalive is enabled`() =
        runBlocking<Unit> {
            whenever(prefs.showStickyNotification).thenReturn(true)
            stubConfigs(config(enabled = false, nextSendAtMillis = null))

            reconciler.reconcileAll()

            assertNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `reconcileAll keeps the persistent hint hidden while the show toggle is off`() =
        runBlocking<Unit> {
            whenever(prefs.showStickyNotification).thenReturn(false)
            stubConfigs(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L))

            reconciler.reconcileAll()

            assertNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `syncPersistentHint dismisses a live hint when the show toggle is turned off`() =
        runBlocking<Unit> {
            KeepaliveNotification.show(context)
            assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
            whenever(prefs.showStickyNotification).thenReturn(false)
            whenever(repository.getAllConfigs())
                .thenReturn(listOf(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L)))

            reconciler.syncPersistentHint()

            assertNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `syncPersistentHint shows the hint again after the show toggle is turned on`() =
        runBlocking<Unit> {
            whenever(prefs.showStickyNotification).thenReturn(false)
            whenever(repository.getAllConfigs())
                .thenReturn(listOf(config(enabled = true, nextSendAtMillis = System.currentTimeMillis() + 3_600_000L)))
            reconciler.syncPersistentHint()
            assertNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })

            whenever(prefs.showStickyNotification).thenReturn(true)

            reconciler.syncPersistentHint()

            assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `endScheduleNow ends, cancels and refreshes the persistent hint by default`() =
        runBlocking<Unit> {
            KeepaliveNotification.show(context)
            assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
            whenever(prefs.showStickyNotification).thenReturn(true)
            whenever(repository.getAllConfigs())
                .thenReturn(listOf(config(enabled = false, nextSendAtMillis = null)))

            reconciler.endScheduleNow(1)

            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            assertNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    @Test
    fun `endScheduleNow without notify ends and cancels but leaves the hint untouched`() =
        runBlocking<Unit> {
            KeepaliveNotification.show(context)

            reconciler.endScheduleNow(1, notify = false)

            verify(repository).endSchedule(1)
            verify(armer).cancelSend(1)
            // The reconcile pass (or the UI caller) syncs the hint itself: nothing is
            // read or changed here.
            verify(repository, never()).getAllConfigs()
            assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == HINT_NOTIFICATION_ID })
        }

    private val shadowNotificationManager: ShadowNotificationManager
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    // The WorkManager wake also parks a PendingIntent alarm of its own: count only
    // alarms whose intent targets the receiver.
    private fun shadowAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        (
            shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .getScheduledAlarms()
                .filter { alarm ->
                    val operation = alarm.operation ?: return@filter false
                    shadowOf(operation).getSavedIntent().component?.className == SendAlarmReceiver::class.java.name
                }
        )

    // Fails on purpose: on the synchronous test WorkManager it settles at the enqueue, so
    // the send work can be observed terminal (the re-arm branch must treat it as lost).
    class FailingSendWorker(
        context: Context,
        params: WorkerParameters,
    ) : Worker(context, params) {
        override fun doWork(): Result = Result.failure()
    }

    private fun awaitWorkState(
        uniqueName: String,
        state: WorkInfo.State,
        timeoutMillis: Long = 10_000,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val info =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueName)
                    .get()
                    .firstOrNull { it.state == state }
            if (info != null) return info
            Thread.sleep(50)
        }
        return null
    }

    // The synchronous suite default usually settles at the enqueue; the poll keeps the
    // test safe if it does not.
    private fun awaitTerminalState(
        uniqueName: String,
        timeoutMillis: Long = 10_000,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val info =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueName)
                    .get()
                    .firstOrNull()
            val state = info?.state
            if (
                state == WorkInfo.State.CANCELLED ||
                state == WorkInfo.State.FAILED ||
                state == WorkInfo.State.SUCCEEDED
            ) {
                return info
            }
            Thread.sleep(50)
        }
        return null
    }

    private fun pendingRow(
        simId: Int,
        scheduledForMillis: Long,
        retryCount: Int = 0,
        outcome: SendOutcome = SendOutcome.PENDING,
    ): SendHistoryEntity =
        SendHistoryEntity(
            simId = simId,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
            retryCount = retryCount,
        )

    private companion object {
        const val HINT_NOTIFICATION_ID = 1
    }
}
