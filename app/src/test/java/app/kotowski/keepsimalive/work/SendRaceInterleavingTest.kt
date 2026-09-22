package app.kotowski.keepsimalive.work

import android.Manifest
import android.app.Application
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.schedule.ScheduleCalculator
import app.kotowski.keepsimalive.ui.simsettings.SimDetailViewModel
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.PermissionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

// The deterministic proof for the forget/delete/disable vs. concurrently-claiming-worker race:
// a real SimSendLock shared by the real SendOrchestrator and the real SimDetailViewModel, a
// scripted repository that forces the dangerous interleavings (a claim landing while the user
// action runs, and the delete committing between the worker's last config re-read and its
// in-lock re-read), and the in-memory stand-in for the DAO's config guard (an occurrence row
// can only exist under a live enabled schedule).
//
// Acceptance per path: the sender is invoked only before the user's write commits (the worker
// either finishes first and the user action cleans up the advanced state, or it enters the
// lock after the write and backs off), no open row survives for a dead/disabled SIM, no
// schedule advance lands into a dead/disabled config, and the armed work is cancelled.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SendRaceInterleavingTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository: KeepaliveRepository = mock()
    private val sender: SmsSender = mock()
    private val armer: ScheduleArmer = mock()
    private val prefs: AppPrefs = mock()
    private val permissionManager: PermissionManager = mock()
    private val engineReconciler: ScheduleReconciler = mock()
    private val lock = SimSendLock()

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    private fun MutableList<String>.record(marker: String) {
        add(marker)
    }

    // The in-memory stand-in for the DAO's config guard: whether a live enabled config exists
    // for the SIM at the moment an insert is attempted.
    private var configExists = true
    private var configEnabled = true

    private val simId = 11
    private val phone = "+15550100"
    private val message = "keep alive"
    private val phoneB = "+15550101"
    private val messageB = "still alive"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        configExists = true
        configEnabled = true
        events.clear()
        whenever(repository.observeNewest(any(), any())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(any())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
        // The real reconciler's persistent-hint sync re-reads all configs.
        wheneverBlocking { repository.getAllConfigs() }.thenReturn(emptyList())
        whenever(permissionManager.isGranted(Manifest.permission.SEND_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE)).thenReturn(true)
        // An unstubbed 0 would skip every past occurrence in the catch-up.
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
        // The transactional finalize flips win by default: an unstubbed suspend value call
        // would NPE on unbox (suspend functions bridge to Object on the JVM), so the stub
        // needs a coroutine context. A test stubs false for the race it drives.
        runBlocking {
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(true)
        }
        // A real active subscription (id 11): the VM loads it like any real SIM.
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(11, tm)
        shadowOf(tm).setSimOperatorName("Vodafone")
        shadowOf(context.getSystemService(android.telephony.SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(11)
                        .setSimSlotIndex(0)
                        .setDisplayName("Vodafone")
                        .setCarrierName("Vodafone")
                        .buildSubscriptionInfo(),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config(
        nextSendAtMillis: Long,
        enabled: Boolean = true,
        recipient: String = phone,
        msg: String = message,
        daysInterval: Int = 30,
    ) = SimKeepaliveConfig(
        simId = simId,
        enabled = enabled,
        recipientPhone = recipient,
        message = msg,
        hour = 12,
        minute = 0,
        freqType = FrequencyType.EVERY_N_DAYS,
        daysInterval = daysInterval,
        endType = EndType.NEVER,
        timeWindowMinutes = 0,
        nextSendAtMillis = nextSendAtMillis,
    )

    // The exact pre-jitter base the engine derives for the occurrence at `millis`
    // (ScheduleCalculator.baseOf, anchored in the system-default zone like production).
    private fun baseOf(
        config: SimKeepaliveConfig,
        millis: Long,
    ): Long =
        ScheduleCalculator
            .baseOf(config, ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
            .toInstant()
            .toEpochMilli()

    private fun pendingRow(
        id: Long,
        scheduledForMillis: Long,
    ) = SendHistoryEntity(
        id = id,
        simId = simId,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = scheduledForMillis,
        outcome = SendOutcome.PENDING.name,
        recipient = phone,
        message = message,
    )

    // A retry row: PENDING with retryCount > 0, carrying the occurrence time.
    private fun retryingRow(
        id: Long,
        scheduledForMillis: Long,
        lastAttemptAtMillis: Long,
    ) = SendHistoryEntity(
        id = id,
        simId = simId,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = scheduledForMillis,
        lastAttemptAtMillis = lastAttemptAtMillis,
        outcome = SendOutcome.PENDING.name,
        recipient = phone,
        message = message,
        firstAttemptAtMillis = lastAttemptAtMillis,
        retryCount = 1,
    )

    private fun orchestrator(reconciler: ScheduleReconciler = engineReconciler) =
        SendOrchestrator(
            context,
            repository,
            sender,
            armer,
            OccurrenceAdvancer(context, repository, armer),
            0L,
            prefs,
            permissionManager,
            reconciler,
            lock,
        )

    private fun viewModel(reconciler: ScheduleReconciler) =
        SimDetailViewModel(context, repository, reconciler, prefs, armer, lock, OffSchedulePendingRegistry())

    private suspend fun loadSim(
        vm: SimDetailViewModel,
        simConfig: SimKeepaliveConfig?,
    ) {
        wheneverBlocking { repository.getConfig(simId) }.thenReturn(simConfig)
        vm.loadSimData(context, simId)
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.state.value.isLoading && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private suspend fun waitFor(marker: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (marker in events) return
            delay(10)
        }
        error("timed out waiting for '$marker'; events=$events")
    }

    private fun tailAfter(marker: String): List<String> = events.dropWhile { it != marker }.drop(1)

    // The worker reaches the claim (holding the lock) and waits there, so the user action is
    // queued behind the in-flight send and lands only after the full result write.
    private suspend fun workerHoldingLockAtClaim(claimGate: CompletableDeferred<Unit>) {
        wheneverBlocking { repository.claimOccurrence(eq(42L), anyLong(), anyLong()) }
            .doSuspendableAnswer {
                events.record("claim")
                claimGate.await()
                1
            }
        whenever(sender.send(eq(simId), eq(phone), eq(message), anyOrNull())).thenAnswer {
            events.record("send")
            SmsSendResult.Success
        }
        // The SENT row, the success counters and the next open row land in one
        // transaction (the Success consequence), so no separate align write follows.
        wheneverBlocking {
            repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
        }.then {
            events.record("sent-row")
            true
        }
        wheneverBlocking { armer.armSend(anyInt()) }.then {
            events.record("arm-next")
            true
        }
    }

    // The worker reaches its last config re-read (still outside the lock) and waits there, so
    // the user action commits in between that re-read and the in-lock re-read (the
    // self-resurrection ordering): the in-lock section must find no live config and back off.
    // `inLockConfig` models what the in-lock re-read (the 5th config read) sees after the
    // user action committed: the default is the stale pre-action config (a config guard
    // backstop scenario); delete passes null (the config row is gone) and reset passes the
    // cleared schedule (the user action's own commit is visible to the fresh in-lock read).
    private suspend fun workerHoldingAtFreshRead(
        freshGate: CompletableDeferred<Unit>,
        freshConfig: SimKeepaliveConfig,
        inLockConfig: SimKeepaliveConfig? = freshConfig,
    ) {
        var configReads = 0
        wheneverBlocking { repository.getConfig(simId) }
            .doSuspendableAnswer {
                configReads++
                if (configReads == 4) {
                    events.record("fresh-read")
                    freshGate.await()
                }
                if (configReads > 4) inLockConfig else freshConfig
            }
    }

    // The in-memory stand-in for the guarded insert (see SimHistoryDao): the row is created
    // only while a live enabled config exists.
    private suspend fun guardedAlignPendingRow() {
        wheneverBlocking { repository.alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any()) }
            .then {
                events.record("align-attempt")
                if (configExists && configEnabled) {
                    events.record("row-created")
                }
            }
    }

    @Test
    fun `forget queued behind a live send - the send completes before the forget, nothing outlives it`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val row = pendingRow(42L, t)
            val nextRow = pendingRow(43L, t + 30L * 24 * 3_600_000L)
            val claimGate = CompletableDeferred<Unit>()

            whenever(repository.getConfig(simId)).thenReturn(freshConfig, freshConfig, freshConfig)
            // Worker top read, worker in-lock read, then the VM's gate read after the send
            // completed and advanced the schedule.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, nextRow)
            workerHoldingLockAtClaim(claimGate)
            wheneverBlocking { repository.deleteSim(simId) }
                .then {
                    events.record("forget-delete")
                    configExists = false
                }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("forget-cancel")
            }
            wheneverBlocking { engineReconciler.syncPersistentHint() }
                .then {
                    events.record("forget-hint")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("claim")
            val vm = viewModel(engineReconciler)
            vm.forgetSim(simId)
            // The worker still holds the lock: the forget's delete has not committed yet.
            assertFalse("forget-delete" in events)
            claimGate.complete(Unit)
            worker.await()
            waitFor("forget-delete")
            waitFor("forget-hint")

            assertTrue("send before forget: $events", events.indexOf("send") < events.indexOf("forget-delete"))
            assertTrue("delete last: $events", tailAfter("forget-delete") == listOf("forget-cancel", "forget-hint"))
            verify(sender).send(eq(simId), eq(phone), eq(message), anyOrNull())
            verifyBlocking(repository) { deleteSim(simId) }
        }

    @Test
    fun `forget before the worker's claim - the worker backs off, no send, no ghost row`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val row = pendingRow(42L, t)
            val freshGate = CompletableDeferred<Unit>()

            workerHoldingAtFreshRead(freshGate, freshConfig)
            // Worker top read, VM gate read (the row is still there), then the worker's
            // in-lock re-reads after the forget committed: nothing left.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, null, null)
            wheneverBlocking { repository.deleteSim(simId) }
                .then {
                    events.record("forget-delete")
                    configExists = false
                }
            guardedAlignPendingRow()
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("forget-cancel")
            }
            wheneverBlocking { engineReconciler.syncPersistentHint() }
                .then {
                    events.record("forget-hint")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            val vm = viewModel(engineReconciler)
            vm.forgetSim(simId)
            // The forget committed while the worker was still between re-reads.
            assertTrue("forget-delete" in events)
            freshGate.complete(Unit)
            worker.await()

            // The worker's in-lock re-read found no live config: no send, no claim, no
            // re-arm, and the (guarded) re-creation attempt landed after the forget without
            // creating a row.
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            assertTrue("align-attempt" in events)
            assertTrue(
                "forget before the in-lock re-creation attempt: $events",
                events.indexOf("forget-delete") < events.indexOf("align-attempt"),
            )
            assertFalse("no ghost row for the forgotten SIM: $events", "row-created" in events)
            verifyBlocking(repository) { deleteSim(simId) }
        }

    @Test
    fun `delete queued behind a live send - the send completes first, the advanced row is finalized`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val row = pendingRow(42L, t)
            val nextRow = pendingRow(43L, t + 30L * 24 * 3_600_000L)
            val claimGate = CompletableDeferred<Unit>()

            whenever(repository.getConfig(simId)).thenReturn(freshConfig, freshConfig, freshConfig)
            // Worker top + in-lock reads, then the VM's gate read and its active-row read
            // after the send completed and armed the next occurrence's row.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, nextRow, nextRow)
            workerHoldingLockAtClaim(claimGate)
            wheneverBlocking { repository.finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull()) }
                .then {
                    events.record("delete-finalize")
                    1
                }
            wheneverBlocking { repository.deleteConfig(simId) }
                .then {
                    events.record("delete-commit")
                    configExists = false
                }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("delete-cancel")
            }
            wheneverBlocking { engineReconciler.syncPersistentHint() }
                .then {
                    events.record("delete-hint")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("claim")
            val vm = viewModel(engineReconciler)
            vm.deleteSchedule(simId)
            assertFalse("delete-commit" in events)
            claimGate.complete(Unit)
            worker.await()
            waitFor("delete-commit")
            waitFor("delete-hint")

            // The delete then finalized the row the send just armed (the advanced
            // occurrence), not the sent one.
            assertTrue("send before delete: $events", events.indexOf("send") < events.indexOf("delete-commit"))
            assertTrue(
                "finalize after the success write: $events",
                events.indexOf("sent-row") < events.indexOf("delete-finalize"),
            )
            verifyBlocking(repository) {
                finalizeOccurrence(eq(43L), eq(SendOutcome.SKIPPED), isNull(), any(), eq(0), isNull())
            }
            verifyBlocking(repository) { deleteConfig(simId) }
            assertTrue(
                "no engine writes after the delete: $events",
                tailAfter("delete-commit") == listOf("delete-cancel", "delete-hint"),
            )
        }

    @Test
    fun `delete before the worker's claim - the worker backs off on the cleared schedule, no send`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val row = pendingRow(42L, t)
            val freshGate = CompletableDeferred<Unit>()

            // The in-lock re-read (the 4th config read) runs after the delete commit: the
            // config row is gone, so the engine backs off on the cleared schedule before
            // the row-level re-checks.
            workerHoldingAtFreshRead(freshGate, freshConfig, inLockConfig = null)
            // Worker top read, VM gate read, VM active-row read, then the worker's in-lock
            // re-read after the delete committed: the row is finalized, none open.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, row, null)
            wheneverBlocking { repository.finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull()) }
                .then {
                    events.record("delete-finalize")
                    1
                }
            wheneverBlocking { repository.deleteConfig(simId) }
                .then {
                    events.record("delete-commit")
                    configExists = false
                }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("delete-cancel")
            }
            wheneverBlocking { engineReconciler.syncPersistentHint() }
                .then {
                    events.record("delete-hint")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            val vm = viewModel(engineReconciler)
            vm.deleteSchedule(simId)
            assertTrue("delete-commit" in events)
            freshGate.complete(Unit)
            worker.await()

            // The in-lock re-read ran after the delete commit and backed off: the
            // occurrence was already consumed.
            assertTrue(
                "no engine writes after the delete: $events",
                tailAfter("delete-commit") == listOf("delete-cancel", "delete-hint"),
            )
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            verifyBlocking(repository, never()) { alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any()) }
            verifyBlocking(repository) {
                finalizeOccurrence(eq(42L), eq(SendOutcome.SKIPPED), isNull(), any(), eq(0), isNull())
            }
            verifyBlocking(repository) { deleteConfig(simId) }
        }

    @Test
    fun `disable queued behind a live send - the send completes first, the disabled branch cleans up`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val disabledConfig = freshConfig.copy(enabled = false)
            val row = pendingRow(42L, t)
            val nextRow = pendingRow(43L, t + 30L * 24 * 3_600_000L)
            val claimGate = CompletableDeferred<Unit>()

            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, nextRow, nextRow, null)
            workerHoldingLockAtClaim(claimGate)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("disable-save")
                    configEnabled = (it.arguments[0] as SimKeepaliveConfig).enabled
                }
            wheneverBlocking { repository.clearPendingHistory(simId) }
                .then {
                    events.record("clear-pending")
                }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("reconcile-cancel")
            }

            val realReconciler =
                ScheduleReconciler(context, repository, armer, prefs, OccurrenceAdvancer(context, repository, armer), lock)
            val vm = viewModel(realReconciler)
            loadSim(vm, freshConfig)
            // getConfig: worker top, hoisted, the late-run in-lock re-read, fresh, the
            // claim section's in-lock re-read, then the disable's own reads (the commit's
            // pre-save, the commit's post-save, the reconciler's re-read, the commit's
            // final read).
            whenever(repository.getConfig(simId))
                .thenReturn(freshConfig, freshConfig, freshConfig, freshConfig, freshConfig, disabledConfig, disabledConfig)

            val worker = async { orchestrator().process(simId) }
            waitFor("claim")
            vm.setEnabled(false)
            assertFalse("disable-save" in events)
            claimGate.complete(Unit)
            worker.await()
            waitFor("disable-save")
            waitFor("reconcile-cancel")

            // The send committed before the disable; the reconciler's disabled branch then
            // ran under the lock, after the send's writes: no open row left to flip (the
            // commit dropped the pending row and the send's transaction owns the next), so
            // the branch only cancels the armed work — the disarm (next-send clear) lives
            // in the flip's transaction and ran with the flip when a row existed.
            assertTrue("send before disable: $events", events.indexOf("send") < events.indexOf("disable-save"))
            assertTrue(
                "disabled-branch cleanup after the save: $events",
                events.indexOf("disable-save") < events.indexOf("reconcile-cancel"),
            )
            verify(sender).send(eq(simId), eq(phone), eq(message), anyOrNull())
            val saved = argumentCaptor<SimKeepaliveConfig>()
            verifyBlocking(repository) { saveUserColumns(saved.capture()) }
            assertFalse(saved.firstValue.enabled)
            verifyBlocking(repository, never()) { updateNextSend(anyInt(), anyOrNull()) }
            verify(armer).cancelSend(simId)
        }

    @Test
    fun `disable before the worker's claim - the worker backs off, no send, no ghost row`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val disabledConfig = freshConfig.copy(enabled = false)
            val row = pendingRow(42L, t)
            val freshGate = CompletableDeferred<Unit>()

            val realReconciler =
                ScheduleReconciler(context, repository, armer, prefs, OccurrenceAdvancer(context, repository, armer), lock)
            val vm = viewModel(realReconciler)
            loadSim(vm, freshConfig)
            // Worker top, hoisted, late-run in-lock and pre-flight reads, then the
            // disable's own reads: the commit's pre-save read still sees the enabled
            // config, and the post-save read (and the reconciler's) see the disabled
            // one. The gate holds the worker at its last pre-lock read - the stale
            // observation the write lands on. The observed value is captured before the
            // wait: the read saw the pre-write state. The worker's in-lock schedule
            // re-read (the last one) sees the disabled config and backs off before
            // touching any row.
            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    val observed = if (configReads >= 6) disabledConfig else freshConfig
                    if (configReads == 4) {
                        events.record("fresh-read")
                        freshGate.await()
                    }
                    observed
                }
            // Worker top read, the commit's in-flight gate, the commit's in-flight re-read,
            // then the reconciler's read after the pending row is cleared.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, row, null)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("disable-save")
                    configEnabled = (it.arguments[0] as SimKeepaliveConfig).enabled
                }
            wheneverBlocking { repository.clearPendingHistory(simId) }
                .then {
                    events.record("clear-pending")
                }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("reconcile-cancel")
            }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            vm.setEnabled(false)
            assertTrue("disable-save" in events)
            freshGate.complete(Unit)
            worker.await()

            // The stale pre-lock read observed the enabled config, so the worker's in-lock
            // schedule re-read ran only after the disable committed: it saw the cleared
            // schedule and backed off before even attempting the row re-creation — no
            // send, no claim, no re-arm. The commit dropped the pending row and the stored
            // next send, so the reconciler's disabled branch had no row to flip and only
            // cancelled the armed work.
            assertTrue(
                "stale read before the write: $events",
                events.indexOf("fresh-read") < events.indexOf("disable-save"),
            )
            // The back off happened at the in-lock schedule re-read, after the disable: no
            // row re-creation was ever attempted, so no ghost row for the disabled SIM.
            assertFalse("no re-creation after the disable: $events", "align-attempt" in events)
            assertFalse("no ghost row for the disabled SIM: $events", "row-created" in events)
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            verify(armer).cancelSend(simId)
            verifyBlocking(repository) { updateNextSend(simId, null) }
        }

    @Test
    fun `disable landing between the top read and the hoisted read - the hoisted path runs the disabled cleanup`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val disabledConfig = freshConfig.copy(enabled = false)
            val row = pendingRow(42L, t)
            val hoistedGate = CompletableDeferred<Unit>()

            val realReconciler =
                ScheduleReconciler(context, repository, armer, prefs, OccurrenceAdvancer(context, repository, armer), lock)
            // The disabling writer is modeled as interrupted: its enabled=0 write landed
            // (the hoisted read sees the disabled config) but its own next-send cleanup
            // never ran, so the worker's hoisted path must perform it.
            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    // Worker top read sees the enabled config; the hoisted read (the
                    // reconciler's own read, read #3) see the disabled one.
                    val observed = if (configReads >= 2) disabledConfig else freshConfig
                    if (configReads == 2) {
                        events.record("hoisted-read")
                        hoistedGate.await()
                    }
                    observed
                }
            // The worker's pre-hoist read and the reconciler's disabled-branch read: the
            // open PENDING row.
            whenever(repository.getActiveHistory(simId)).thenReturn(row)
            wheneverBlocking {
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            }.then {
                events.record("reconcile-finalize")
                true
            }
            whenever(armer.cancelSend(anyInt())).thenAnswer {
                events.record("reconcile-cancel")
            }

            val worker = async { orchestrator(realReconciler).process(simId) }
            waitFor("hoisted-read")
            hoistedGate.complete(Unit)
            worker.await()

            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            // The open PENDING row is flipped to skipped (a skip is never unexplained) and
            // the disarm (pending drop + next-send clear) lands in the same transaction:
            // the full disabled cleanup ran in the worker's hoisted path, after the
            // hoisted read (no writer was involved).
            verifyBlocking(repository) {
                finalizeOutcome(
                    eq(42L),
                    eq(SendOutcome.PENDING),
                    eq(SendOutcome.SKIPPED),
                    isNull(),
                    any(),
                    eq(0),
                    isNull(),
                    isNull(),
                    eq(FinalizeAdvance.Disable(simId)),
                )
            }
            verify(armer).cancelSend(simId)
            verifyBlocking(repository, never()) { clearPendingHistory(anyInt()) }
            verifyBlocking(repository, never()) { updateNextSend(anyInt(), anyOrNull()) }
            assertTrue(
                "disabled cleanup ran in the hoisted path: $events",
                tailAfter("hoisted-read") == listOf("reconcile-cancel", "reconcile-finalize"),
            )
        }

    // A save that lands between the worker's last pre-lock config read and its in-lock read
    // commits fully first (same per-SIM lock). The in-lock section must then run the send on
    // the in-lock read: a field-only save is honored (the send goes to the new recipient),
    // a schedule-changing save owns the future (the worker backs off instead of firing the
    // old occurrence stale), and a pending retry of the occurrence in flight is the one
    // exception (backing off would strand it until the next safety sweep).

    @Test
    fun `save changing only the recipient between the worker's fresh read and its in-lock read - the send goes to the new recipient`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val configA = config(t)
            val configB = config(t, recipient = phoneB, msg = messageB)
            val row = pendingRow(42L, t)
            val freshGate = CompletableDeferred<Unit>()

            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    // Worker top, hoisted, late-run in-lock and fresh reads and the
                    // commit's pre-save read see the pre-save state; the commit's
                    // post-save reads and the worker's in-lock read see the saved one.
                    val observed = if (configReads >= 6) configB else configA
                    if (configReads == 4) {
                        events.record("fresh-read")
                        freshGate.await()
                    }
                    observed
                }
            // Worker top read, the commit's in-flight check, then the worker's in-lock read.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, row)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("save")
                }
            wheneverBlocking { repository.claimOccurrence(anyLong(), anyLong(), anyLong()) }
                .then {
                    events.record("claim")
                    1
                }
            whenever(sender.send(anyInt(), any(), any(), anyOrNull())).thenAnswer { invocation ->
                events.record("send:${invocation.arguments[1]}")
                SmsSendResult.Success
            }
            wheneverBlocking {
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            }.then {
                events.record("sent-row")
                true
            }
            wheneverBlocking { armer.armSend(anyInt()) }.then {
                events.record("arm-next")
                true
            }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            val vm = viewModel(engineReconciler)
            vm.saveConfig(configB)
            // The save committed while the worker was still between re-reads.
            assertTrue("save" in events)
            freshGate.complete(Unit)
            worker.await()

            // The stale pre-lock read observed the old recipient, so the in-lock re-read ran
            // only after the save: the send and the next row (the Success consequence, in
            // the same transaction as the SENT row) carry the new recipient/message.
            assertTrue(
                "stale read before the save: $events",
                events.indexOf("fresh-read") < events.indexOf("save"),
            )
            assertTrue("send to the new recipient: $events", events.contains("send:$phoneB"))
            verify(sender).send(eq(simId), eq(phoneB), eq(messageB), anyOrNull())
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verifyBlocking(repository) {
                            finalizeOutcome(
                                eq(42L),
                                eq(SendOutcome.SENDING),
                                eq(SendOutcome.SENT),
                                anyLong(),
                                isNull(),
                                eq(0),
                                anyLong(),
                                isNull(),
                                capture(),
                            )
                        }
                    }.firstValue as FinalizeAdvance.Success
            assertEquals(phoneB, advance.recipient)
            assertEquals(messageB, advance.message)
        }

    @Test
    fun `save changing the schedule between the worker's fresh read and its in-lock read - the worker backs off, no send`() =
        runBlocking {
            val t = System.currentTimeMillis() - 60_000
            val configA = config(t)
            // The commit's re-anchor moved the next send to the new rhythm's occurrence; the
            // stored value is non-null, so the cleared-schedule check alone cannot stop the
            // worker - the schedule-change back-off must.
            val configB = config(t + 60L * 24 * 3_600_000L, recipient = phoneB, msg = messageB, daysInterval = 60)
            val row = pendingRow(42L, t)
            val freshGate = CompletableDeferred<Unit>()

            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    // Worker top, hoisted, late-run in-lock and fresh reads and the
                    // commit's pre-save read see the pre-save state; the commit's
                    // post-save reads and the worker's in-lock read see the saved one.
                    val observed = if (configReads >= 6) configB else configA
                    if (configReads == 4) {
                        events.record("fresh-read")
                        freshGate.await()
                    }
                    observed
                }
            // Worker top read and the commit's in-flight check; the worker backs off before
            // its in-lock row read.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("save")
                }
            wheneverBlocking { repository.updateNextSend(eq(simId), isNull()) }
                .then {
                    events.record("commit-nextsend-null")
                }
            wheneverBlocking { repository.clearPendingHistory(simId) }
                .then {
                    events.record("commit-clear")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            val vm = viewModel(engineReconciler)
            vm.saveConfig(configB)
            // The save committed (clear + re-anchor) while the worker was between re-reads.
            assertTrue("save" in events)
            assertTrue("commit-nextsend-null" in events)
            freshGate.complete(Unit)
            worker.await()

            // The in-lock re-read saw the re-anchored schedule: the old occurrence is the
            // save's to drop, so the worker backs off - no send, no claim, no row
            // re-creation, no re-arm of the old time.
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            verifyBlocking(repository, never()) { alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any()) }
            verifyBlocking(repository, never()) { updateNextSend(eq(simId), anyLong()) }
        }

    @Test
    fun `save changing the schedule while a retry is pending - the retry sends with the latest fields`() =
        runBlocking {
            val t = System.currentTimeMillis() - 120_000
            val attempt = t + 60_000
            // The pending retry keeps its retry time: an in-flight occurrence is
            // engine-owned, so the commit neither clears the next send nor the row.
            val retryTime = attempt + 30_000
            val configA = config(retryTime)
            val configB = config(retryTime, recipient = phoneB, msg = messageB, daysInterval = 60)
            val row = retryingRow(42L, t, attempt)
            val freshGate = CompletableDeferred<Unit>()

            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    // Worker top, hoisted, late-run in-lock and fresh reads and the
                    // commit's pre-save read see the pre-save state; the commit's
                    // post-save reads and the worker's in-lock read see the saved one.
                    val observed = if (configReads >= 6) configB else configA
                    if (configReads == 4) {
                        events.record("fresh-read")
                        freshGate.await()
                    }
                    observed
                }
            // Worker top read, the commit's in-flight check, then the worker's in-lock read.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row, row)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("save")
                }
            wheneverBlocking { repository.claimOccurrence(anyLong(), anyLong(), anyLong()) }
                .then {
                    events.record("claim")
                    1
                }
            whenever(sender.send(anyInt(), any(), any(), anyOrNull())).thenAnswer { invocation ->
                events.record("send:${invocation.arguments[1]}")
                SmsSendResult.Success
            }
            wheneverBlocking {
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            }.then {
                events.record("sent-row")
                true
            }
            wheneverBlocking { armer.armSend(anyInt()) }.then {
                events.record("arm-next")
                true
            }

            val worker = async { orchestrator().process(simId) }
            waitFor("fresh-read")
            val vm = viewModel(engineReconciler)
            vm.saveConfig(configB)
            assertTrue("save" in events)
            freshGate.complete(Unit)
            worker.await()

            // A schedule change normally makes the worker back off, but a pending retry must
            // not be stranded (nothing re-arms it before the next safety sweep): the retry
            // sends with the latest fields and the next row (the Success consequence, in
            // the same transaction as the SENT row) carries them too.
            assertTrue(
                "stale read before the save: $events",
                events.indexOf("fresh-read") < events.indexOf("save"),
            )
            assertTrue("retry sent to the new recipient: $events", events.contains("send:$phoneB"))
            verify(sender).send(eq(simId), eq(phoneB), eq(messageB), anyOrNull())
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verifyBlocking(repository) {
                            finalizeOutcome(
                                eq(42L),
                                eq(SendOutcome.SENDING),
                                eq(SendOutcome.SENT),
                                anyLong(),
                                isNull(),
                                eq(1),
                                eq(attempt),
                                isNull(),
                                capture(),
                            )
                        }
                    }.firstValue as FinalizeAdvance.Success
            assertEquals(phoneB, advance.recipient)
            assertEquals(messageB, advance.message)
            // The in-flight save kept the retry time: the commit cleared nothing.
            verifyBlocking(repository, never()) { updateNextSend(eq(simId), isNull()) }
            verifyBlocking(repository, never()) { clearPendingHistory(simId) }
        }

    @Test
    fun `save landing between the worker's hoisted read and the early-fire lock - the re-arm carries the new fields`() =
        runBlocking {
            val t = System.currentTimeMillis() + 60_000
            val configA = config(t)
            val configB = config(t, recipient = phoneB, msg = messageB)
            val row = pendingRow(42L, t)
            val hoistedGate = CompletableDeferred<Unit>()

            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    // Worker top and hoisted reads and the commit's pre-save read see the
                    // pre-save state; the commit's post-save reads and the worker's in-lock
                    // read see the saved one.
                    val observed = if (configReads >= 4) configB else configA
                    if (configReads == 2) {
                        events.record("hoisted-read")
                        hoistedGate.await()
                    }
                    observed
                }
            // Worker top read and the commit's in-flight check.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("save")
                }
            wheneverBlocking { repository.updateNextSend(eq(simId), anyLong()) }
                .then {
                    events.record("re-arm")
                }
            wheneverBlocking { repository.alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any()) }
                .then {
                    events.record("align-next")
                }
            wheneverBlocking { armer.armSend(anyInt()) }.then {
                events.record("arm-next")
                true
            }

            val worker = async { orchestrator().process(simId) }
            waitFor("hoisted-read")
            val vm = viewModel(engineReconciler)
            vm.saveConfig(configB)
            assertTrue("save" in events)
            hoistedGate.complete(Unit)
            worker.await()

            // The stale hoisted read observed the old recipient, so the early fire re-armed
            // the pending row with the latest save's fields instead of the pre-lock ones.
            // The armed instant is not a clean base, so the row carries the exact
            // pre-jitter base derived from the in-lock config.
            assertTrue(
                "stale read before the save: $events",
                events.indexOf("hoisted-read") < events.indexOf("save"),
            )
            assertTrue("re-arm: $events", "re-arm" in events)
            verifyBlocking(repository) {
                alignPendingRow(eq(simId), eq(t), eq(baseOf(configB, t)), eq(phoneB), eq(messageB))
            }
            verify(armer).armSend(simId)
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository) { updateNextSend(simId, t) }
        }

    @Test
    fun `save changing the schedule between the worker's hoisted read and the early-fire lock - the early fire backs off, no re-arm`() =
        runBlocking {
            val t = System.currentTimeMillis() + 60_000
            val configA = config(t)
            // The commit's re-anchor moved the next send to the new rhythm's occurrence.
            val configB = config(t + 60L * 24 * 3_600_000L, recipient = phoneB, msg = messageB, daysInterval = 60)
            val row = pendingRow(42L, t)
            val hoistedGate = CompletableDeferred<Unit>()

            var configReads = 0
            wheneverBlocking { repository.getConfig(simId) }
                .doSuspendableAnswer {
                    configReads++
                    val observed = if (configReads >= 4) configB else configA
                    if (configReads == 2) {
                        events.record("hoisted-read")
                        hoistedGate.await()
                    }
                    observed
                }
            // Worker top read and the commit's in-flight check.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row)
            wheneverBlocking { repository.saveUserColumns(any()) }
                .then {
                    events.record("save")
                }
            wheneverBlocking { repository.updateNextSend(eq(simId), isNull()) }
                .then {
                    events.record("commit-nextsend-null")
                }
            wheneverBlocking { repository.clearPendingHistory(simId) }
                .then {
                    events.record("commit-clear")
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("hoisted-read")
            val vm = viewModel(engineReconciler)
            vm.saveConfig(configB)
            assertTrue("save" in events)
            assertTrue("commit-nextsend-null" in events)
            hoistedGate.complete(Unit)
            worker.await()

            // The in-lock re-read saw the re-anchored schedule: the save's re-arm owns the
            // future, so the early fire backs off instead of resurrecting the old armed
            // time - no re-arm, no row re-creation, no send.
            verify(sender, never()).send(anyInt(), any(), any(), anyOrNull())
            verifyBlocking(repository, never()) { claimOccurrence(anyLong(), anyLong(), anyLong()) }
            verify(armer, never()).armSend(anyInt())
            verifyBlocking(repository, never()) { alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any()) }
            verifyBlocking(repository, never()) { updateNextSend(eq(simId), anyLong()) }
        }

    // The deterministic proof for the duplicate-SMS race: the worker claims the occurrence
    // and parks in the radio wait (a frozen process); the stale check (a separate work
    // without the send lock) resolves the stalled attempt as an interrupted skip and
    // advances the schedule; the thawed radio result must then find the row no longer
    // SENDING and defer - no clobber, no schedule advance on top, no retry re-arm (a
    // re-armed retry would re-claim the consumed occurrence and re-send it).

    @Test
    fun `stale check resolving the row while the radio is in flight - the failure write defers, no clobber, no re-arm`() =
        runBlocking<Unit> {
            val t = System.currentTimeMillis() - 60_000
            val freshConfig = config(t)
            val row = pendingRow(42L, t)
            val sendGate = CompletableDeferred<Unit>()

            whenever(repository.getConfig(simId)).thenReturn(freshConfig)
            // Worker top read and the in-lock row read: the open row, as claimed.
            whenever(repository.getActiveHistory(simId)).thenReturn(row, row)
            wheneverBlocking { repository.claimOccurrence(eq(42L), anyLong(), anyLong()) }
                .then {
                    events.record("claim")
                    1
                }
            // The worker parks in the radio wait (the frozen process): the result lands only
            // after the gate opens.
            whenever(sender.send(eq(simId), eq(phone), eq(message), anyOrNull())).doSuspendableAnswer {
                events.record("send-start")
                sendGate.await()
                SmsSendResult.Failure("No service", permanent = false)
            }
            // The stale check's own writes: the atomic skip flip with the schedule advance
            // in one transaction (this check wins it — the non-null staleness bound
            // distinguishes it from the worker's plain flips), then the re-arm.
            wheneverBlocking {
                repository.finalizeOutcome(
                    anyLong(),
                    any(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    anyInt(),
                    anyOrNull(),
                    anyLong(),
                    any(),
                )
            }.then {
                events.record("stale-skip")
                true
            }
            wheneverBlocking { armer.armSend(anyInt()) }.then {
                events.record("stale-arm")
                true
            }
            // The thawed result's conditional write: the row is no longer SENDING, so it
            // applies nothing (the null staleness bound distinguishes it from the stale
            // check's flip).
            wheneverBlocking { repository.finalizeSending(anyLong(), any(), anyLong(), any(), anyInt(), anyLong(), isNull()) }
                .then {
                    events.record("retry-write")
                    0
                }

            val worker = async { orchestrator().process(simId) }
            waitFor("send-start")
            // The stale check runs as a separate work without the send lock.
            val sendingRow =
                SendHistoryEntity(
                    id = 42L,
                    simId = simId,
                    scheduledForMillis = t,
                    occurrenceBaseMillis = t,
                    outcome = SendOutcome.SENDING.name,
                    recipient = phone,
                    message = message,
                    firstAttemptAtMillis = t,
                    lastAttemptAtMillis = t,
                )
            OccurrenceAdvancer(context, repository, armer)
                .skipInterrupted(simId, freshConfig, sendingRow, Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
            waitFor("stale-skip")
            // Thaw: the radio result lands on the row the stale check took.
            sendGate.complete(Unit)
            worker.await()
            waitFor("retry-write")

            // The conditional write ran only after the stale check's resolution and
            // applied nothing: no clobber, no schedule advance on top, no retry re-arm
            // (a retry would re-claim the consumed occurrence and re-send it).
            assertTrue(
                "stale resolution before the result write: $events",
                events.indexOf("stale-skip") < events.indexOf("retry-write"),
            )
            assertTrue(
                "the engine wrote nothing after the conditional write: $events",
                tailAfter("retry-write").isEmpty(),
            )
            // The skip and the advance to the next occurrence landed together (the
            // AdvanceToNext consequence of the winning flip): no separate advance write.
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verifyBlocking(repository) {
                            finalizeOutcome(
                                eq(42L),
                                eq(SendOutcome.SENDING),
                                eq(SendOutcome.SKIPPED),
                                eq(t),
                                any(),
                                eq(0),
                                eq(t),
                                anyLong(),
                                capture(),
                            )
                        }
                    }.firstValue as FinalizeAdvance.AdvanceToNext
            assertTrue(advance.nextSendAtMillis > t)
            verifyBlocking(repository, never()) { updateNextSend(anyInt(), anyOrNull()) }
            verifyBlocking(
                repository,
                never(),
            ) { finalizeSending(anyLong(), eq(SendOutcome.FAILED), anyLong(), any(), anyInt(), anyLong(), isNull()) }
            verifyBlocking(
                repository,
                never(),
            ) { finalizeSending(anyLong(), eq(SendOutcome.SKIPPED), anyLong(), any(), anyInt(), anyLong(), isNull()) }
            verify(sender).send(eq(simId), eq(phone), eq(message), anyOrNull())
        }
}
