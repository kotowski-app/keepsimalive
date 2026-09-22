package app.kotowski.keepsimalive.work

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class ScheduleSafetyWorkerTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun seedAnchor(offsetSeconds: Int) {
        context
            .getSharedPreferences(ScheduleSafetyWorker.CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(ScheduleSafetyWorker.KEY_ANCHORED_OFFSET_SECONDS, offsetSeconds)
            .putString(ScheduleSafetyWorker.KEY_ANCHORED_ZONE_ID, ZoneId.systemDefault().id)
            .apply()
    }

    private fun worker(
        reconciler: ScheduleReconciler,
        afterTimezoneChange: Boolean = false,
        staleCheckSimId: Int = -1,
    ): ScheduleSafetyWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ScheduleSafetyWorker(appContext, workerParameters, reconciler)
            }
        val builder = TestListenableWorkerBuilder.from(context, ScheduleSafetyWorker::class.java)
        val inputs =
            buildList {
                if (afterTimezoneChange) {
                    add(ScheduleSafetyWorker.KEY_TIMEZONE_CHANGED to true)
                }
                if (staleCheckSimId >= 0) {
                    add(ScheduleSafetyWorker.KEY_STALE_CHECK_SIM_ID to staleCheckSimId)
                }
            }
        if (inputs.isNotEmpty()) {
            builder.setInputData(workDataOf(*inputs.toTypedArray()))
        }
        builder.setWorkerFactory(factory)
        return builder.build()
    }

    @Test
    fun `the timezone flag routes to the recompute path`() {
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler, afterTimezoneChange = true).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).recomputeAfterTimezoneChange()
            verify(reconciler, never()).reconcileAll()
        }
    }

    @Test
    fun `without the flag the plain reconcile runs`() {
        // A fresh anchor is required: an unset anchor reads as stale and would route to
        // the recompute path.
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).reconcileAll()
            verify(reconciler, never()).recomputeAfterTimezoneChange()
        }
    }

    // Per-SIM scope: a stale check carries its SIM id in the input data, so a
    // fresh-anchor run resolves only that SIM (reconcileSim + the caller-side hint
    // sync) instead of the global pass.
    @Test
    fun `a stale check scopes the run to its own SIM`() {
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler, staleCheckSimId = 7).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).reconcileSim(7)
            verify(reconciler).syncPersistentHint()
            verify(reconciler, never()).reconcileAll()
            verify(reconciler, never()).recomputeAfterTimezoneChange()
            verify(reconciler, never()).reconcileSim(8)
        }
    }

    // A clock change landing before a stale check runs stays a global recompute: the
    // anchor self-carry takes priority over the per-SIM scope.
    @Test
    fun `a stale anchor routes a stale check to the global recompute`() {
        seedAnchor(ScheduleSafetyWorker.currentOffsetSeconds(context) + 3600)
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler, staleCheckSimId = 7).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).recomputeAfterTimezoneChange()
            verify(reconciler, never()).reconcileAll()
            verify(reconciler, never()).reconcileSim(7)
            verify(reconciler, never()).syncPersistentHint()
        }
    }

    // The per-SIM scope shares the plain path's cancellation contract: a superseding
    // enqueue cancelling the run mid-reconcile reports success (the replacement work
    // re-runs the reconcile), not the generic catch's retry() and its ERROR log.
    @Test
    fun `a stale check cancelled by a superseding enqueue reports success`() {
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        val reconciler =
            mock<ScheduleReconciler> {
                onBlocking { reconcileSim(7) } doSuspendableAnswer {
                    throw CancellationException("job was cancelled")
                }
            }

        val result = runBlocking { worker(reconciler, staleCheckSimId = 7).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `a stale anchor routes to the recompute path without the flag`() {
        seedAnchor(ScheduleSafetyWorker.currentOffsetSeconds(context) + 3600)
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).recomputeAfterTimezoneChange()
            verify(reconciler, never()).reconcileAll()
        }
        // The recompute path records the anchor, so the run is not repeated by the next sweep.
        assertFalse(ScheduleSafetyWorker.anchorStale(context))
    }

    // Control: in a stable zone the clock captured right before the recompute equals the
    // current one, so the anchor stays fresh and no corrective recompute is demanded.
    @Test
    fun `a flagged run in a stable zone records the current clock and stays fresh`() {
        val expected = ScheduleSafetyWorker.captureClock()
        val reconciler = mock<ScheduleReconciler>()

        val result = runBlocking { worker(reconciler, afterTimezoneChange = true).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            verify(reconciler).recomputeAfterTimezoneChange()
            verify(reconciler, never()).reconcileAll()
        }
        val prefs =
            context.getSharedPreferences(ScheduleSafetyWorker.CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
        val storedOffset = prefs.getInt(ScheduleSafetyWorker.KEY_ANCHORED_OFFSET_SECONDS, ScheduleSafetyWorker.ANCHOR_UNSET)
        assertEquals(expected.offsetSeconds, storedOffset)
        assertEquals(expected.zoneId, prefs.getString(ScheduleSafetyWorker.KEY_ANCHORED_ZONE_ID, null))
        assertFalse(ScheduleSafetyWorker.anchorStale(context))
    }

    @Test
    fun `the anchor is stale when unset and fresh after recording`() {
        assertTrue(ScheduleSafetyWorker.anchorStale(context))
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        assertFalse(ScheduleSafetyWorker.anchorStale(context))
    }

    @Test
    fun `a stored offset an hour from the current one is stale`() {
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        assertFalse(ScheduleSafetyWorker.anchorStale(context))
        seedAnchor(ScheduleSafetyWorker.currentOffsetSeconds(context) + 3600)
        assertTrue(ScheduleSafetyWorker.anchorStale(context))
        seedAnchor(ScheduleSafetyWorker.currentOffsetSeconds(context) - 3600)
        assertTrue(ScheduleSafetyWorker.anchorStale(context))
    }

    // Regression: with an offset-only anchor, a zone-ID change to a zone with the same
    // current offset reads as fresh and the recompute is dropped.
    @Test
    fun `a same-offset zone change makes the anchor stale`() {
        val oldZone = ZoneId.of("Asia/Karachi") // permanent UTC+5
        val newZone = ZoneId.of("Etc/GMT-5") // permanent UTC+5 (Etc/GMT sign is inverted)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(oldZone))
            val offsetInOldZone = ScheduleSafetyWorker.currentOffsetSeconds(context)
            ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
            assertFalse(ScheduleSafetyWorker.anchorStale(context))

            // The zone changes with the current offset unchanged: the anchor must go
            // stale on the zone ID alone.
            TimeZone.setDefault(TimeZone.getTimeZone(newZone))
            assertEquals(offsetInOldZone, ScheduleSafetyWorker.currentOffsetSeconds(context))
            assertTrue(ScheduleSafetyWorker.anchorStale(context))

            ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
            assertFalse(ScheduleSafetyWorker.anchorStale(context))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // Regression: a second clock change landing between the recompute and the anchor
    // write must not be recorded as the anchor — the divergent schedules would then read
    // as fresh and the next sweep would take the plain reconcile path and never re-run the
    // recompute. The anchor must hold the clock captured BEFORE the recompute.
    @Test
    fun `a zone change between the recompute and the anchor write keeps the anchor stale`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Karachi"))) // permanent UTC+5
            val preChange = ScheduleSafetyWorker.captureClock()
            val reconciler =
                mock<ScheduleReconciler> {
                    onBlocking { recomputeAfterTimezoneChange() } doSuspendableAnswer {
                        // A second clock change lands while the recompute runs.
                        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Etc/GMT-5"))) // permanent UTC+5
                    }
                }

            val result = runBlocking { worker(reconciler, afterTimezoneChange = true).doWork() }

            assertEquals(ListenableWorker.Result.success(), result)
            runBlocking {
                verify(reconciler).recomputeAfterTimezoneChange()
            }
            // The anchor holds the clock captured before the recompute, not the zone
            // that landed during it...
            val prefs =
                context.getSharedPreferences(ScheduleSafetyWorker.CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
            val storedOffset = prefs.getInt(ScheduleSafetyWorker.KEY_ANCHORED_OFFSET_SECONDS, ScheduleSafetyWorker.ANCHOR_UNSET)
            assertEquals(preChange.offsetSeconds, storedOffset)
            assertEquals(preChange.zoneId, prefs.getString(ScheduleSafetyWorker.KEY_ANCHORED_ZONE_ID, null))
            // ...so it reads stale and the next sweep re-runs the corrective recompute.
            assertTrue(ScheduleSafetyWorker.anchorStale(context))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // A superseding enqueueOnce (app start, dashboard resume, time-change flag) REPLACE-
    // cancels a running instance: the cancellation must report success (the replacing
    // work re-runs the reconcile), not the generic catch's retry() — a no-op for an
    // already-cancelled work — and its ERROR log.
    @Test
    fun `a plain reconcile cancelled by a superseding enqueue reports success`() {
        // A fresh anchor is required: an unset anchor reads as stale and would route to
        // the recompute path.
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        val reconciler =
            mock<ScheduleReconciler> {
                onBlocking { reconcileAll() } doSuspendableAnswer {
                    throw CancellationException("job was cancelled")
                }
            }

        val result = runBlocking { worker(reconciler).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
    }

    // The anchor is written only after a successful recompute: a cancelled flagged
    // recompute must leave it stale so the next sweep re-runs the corrective recompute.
    @Test
    fun `a cancelled flagged recompute does not record the anchor`() {
        seedAnchor(ScheduleSafetyWorker.currentOffsetSeconds(context) + 3600)
        val reconciler =
            mock<ScheduleReconciler> {
                onBlocking { recomputeAfterTimezoneChange() } doSuspendableAnswer {
                    throw CancellationException("job was cancelled")
                }
            }

        val result = runBlocking { worker(reconciler, afterTimezoneChange = true).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(ScheduleSafetyWorker.anchorStale(context))
    }

    @Test
    fun `enqueueStaleCheck arms a delayed reconcile due at the given time`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dueAt = System.currentTimeMillis() + 60_000L

        ScheduleSafetyWorker.enqueueStaleCheck(context, 1, dueAt)

        val info =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                .get()
                .firstOrNull()
        assertNotNull(info)
        assertEquals(WorkInfo.State.ENQUEUED, info!!.state)
        assertTrue(
            "stale check runAfter ${info.nextScheduleTimeMillis} not within 5s of $dueAt",
            Math.abs(info.nextScheduleTimeMillis - dueAt) < 5_000,
        )
    }

    // The SIM id must ride in the input data: it is the per-SIM scope's only carrier
    // (doWork reads it to route to reconcileSim instead of the global pass). Full chain:
    // enqueueStaleCheck -> the real (test) WorkManager -> ScheduleSafetyWorker.
    @Test
    fun `enqueueStaleCheck routes the run to the SIM's own reconcile`() {
        // A fresh anchor is required: an unset anchor reads as stale and would route to
        // the global recompute path.
        ScheduleSafetyWorker.recordAnchor(context, ScheduleSafetyWorker.captureClock())
        val reconciler = mock<ScheduleReconciler>()
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ScheduleSafetyWorker(appContext, workerParameters, reconciler)
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )

        // Past due: the check runs right away.
        ScheduleSafetyWorker.enqueueStaleCheck(context, 3, System.currentTimeMillis() - 1_000L)
        awaitUniqueWorkState("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_3", WorkInfo.State.SUCCEEDED)

        runBlocking {
            verify(reconciler).reconcileSim(3)
            verify(reconciler).syncPersistentHint()
            verify(reconciler, never()).reconcileAll()
            verify(reconciler, never()).recomputeAfterTimezoneChange()
        }
    }

    // WorkManager posts the worker start-up chain to the main-thread executor, which
    // Robolectric leaves paused: idle it while waiting, or the enqueued check never runs.
    private fun awaitUniqueWorkState(
        uniqueName: String,
        state: WorkInfo.State,
        timeoutMillis: Long = 10_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val actual =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueName)
                    .get()
                    .firstOrNull()
                    ?.state
            if (actual == state) return
            Thread.sleep(50)
        }
        fail("the $uniqueName work did not reach $state in time")
    }

    @Test
    fun `enqueueStaleCheck with a past due time runs immediately and replaces the pending check`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ScheduleSafetyWorker.enqueueStaleCheck(context, 1, System.currentTimeMillis() + 300_000L)

        ScheduleSafetyWorker.enqueueStaleCheck(context, 1, System.currentTimeMillis() - 1_000L)

        // REPLACE within one SIM: one unique work, the second (past-due) enqueue wins.
        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                .get()
        assertEquals(1, infos.size)
        // A past-due check must run right away: zero initial delay (the test WorkManager
        // executes it immediately; the run itself fails without a Hilt worker factory, which
        // is irrelevant here).
        assertEquals(0L, infos.first().initialDelayMillis)
    }

    // Per-SIM isolation: the stale check carries a per-SIM name suffix, so one SIM's fresh
    // SENDING row must not replace another SIM's pending check.
    @Test
    fun `stale checks for different SIMs are distinct works keeping their own due times`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val sim1DueAt = System.currentTimeMillis() + 60_000L
        val sim2DueAt = System.currentTimeMillis() + 300_000L

        ScheduleSafetyWorker.enqueueStaleCheck(context, 1, sim1DueAt)
        ScheduleSafetyWorker.enqueueStaleCheck(context, 2, sim2DueAt)

        // Both checks survive: the second enqueue did not replace the first.
        val sim1Check =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_1")
                .get()
                .firstOrNull()
        val sim2Check =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork("${ScheduleSafetyWorker.WORK_NAME_STALE_CHECK}_2")
                .get()
                .firstOrNull()
        assertNotNull(sim1Check)
        assertEquals(WorkInfo.State.ENQUEUED, sim1Check!!.state)
        assertNotNull(sim2Check)
        assertEquals(WorkInfo.State.ENQUEUED, sim2Check!!.state)
        // SIM 1 keeps its own (earlier) due time instead of being pushed to SIM 2's.
        assertTrue(
            "SIM 1 stale check runAfter ${sim1Check.nextScheduleTimeMillis} not within 5s of $sim1DueAt",
            Math.abs(sim1Check.nextScheduleTimeMillis - sim1DueAt) < 5_000,
        )
    }
}
