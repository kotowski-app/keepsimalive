package app.kotowski.keepsimalive.ui.simsettings

import android.content.Context
import android.content.SharedPreferences
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SimDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val appContext: Context = mock()
    private val repository: KeepaliveRepository = mock()
    private val scheduleReconciler: ScheduleReconciler = mock()
    private val prefs: AppPrefs = mock()

    init {
        // The mocked prefs carry the default late-send grace: the VM reads it for the end
        // state and the state (an unstubbed 0 would skip every past occurrence).
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
        // The commit's ended-state refusal text resolves through appLocaleContext(), which
        // builds a fresh AppPrefs from this context while the AppCompat state is empty:
        // the mock must answer getSharedPreferences for the AppPrefs file like a real
        // context (an unstubbed null breaks the AppPrefs constructor). Every other name
        // keeps the unstubbed null answer (SimIdentityCache treats it as "nothing cached").
        whenever(appContext.getSharedPreferences(eq(AppPrefs.PREFS_NAME), any()))
            .thenReturn(mock<SharedPreferences>())
    }

    private val armer: ScheduleArmer = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The commit() flow reads getConfig from the DB at save time (row existence + schedule
    // comparison) and calls getActiveHistory to decide whether to clear PENDING rows. Mock
    // these to return null (no existing config, no in-flight occurrence) so the save
    // proceeds; tests that need a specific returned config set up their own mock.
    private fun mockNoExistingConfig() {
        wheneverBlocking { repository.getActiveHistory(any()) }
            .thenReturn(null)
    }

    @Test
    fun `saveConfig persists to repository and marks justSaved`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            verifyBlocking(repository) { saveConfig(any()) }
            assertEquals(config, vm.state.value.config)
            assertTrue(vm.state.value.justSaved)
        }

    @Test
    fun `clearSavedFlag resets justSaved`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 1)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            assertTrue(vm.state.value.justSaved)
            vm.clearSavedFlag()
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `clearSavedFlag keeps stored config`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 2, enabled = true)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            vm.clearSavedFlag()
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `saveConfig with disabled config persists`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 4, enabled = false, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            verifyBlocking(repository) { saveConfig(any()) }
            assertEquals(config, vm.state.value.config)
            assertTrue(vm.state.value.justSaved)
        }

    @Test
    fun `saveConfig repository failure leaves state unchanged`() =
        runBlocking {
            wheneverBlocking { repository.saveConfig(any()) }
                .thenThrow(IllegalStateException("disk full"))
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.saveConfig(SimKeepaliveConfig(simId = 3, enabled = true))
            assertNull(vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `setEnabled without saved config is a no-op`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.setEnabled(true)
            verifyBlocking(repository, times(0)) { saveConfig(any()) }
            assertNull(vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `setEnabled with same value re-persists idempotently`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 5, enabled = true)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config, config, config)
            vm.saveConfig(config)
            vm.setEnabled(true)
            // First save creates the row (upsert), the second updates user columns only.
            verifyBlocking(repository, times(1)) { saveConfig(any()) }
            verifyBlocking(repository, times(1)) { saveUserColumns(any()) }
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `setEnabled rapid toggles keep the last intent`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 8, enabled = false)
            mockNoExistingConfig()
            // The first save creates the row, so the row exists for the toggles below, which
            // go through saveUserColumns (the in-flight gate must be there, not on saveConfig).
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            var saveCalls = 0
            val inFlightGate = CompletableDeferred<Unit>()
            wheneverBlocking { repository.saveUserColumns(any()) }
                .doSuspendableAnswer {
                    saveCalls++
                    if (saveCalls == 1) inFlightGate.await()
                }
            vm.setEnabled(true)
            vm.setEnabled(false)
            assertEquals(config, vm.state.value.config)
            assertEquals(2, saveCalls)
        }

    @Test
    fun `saveConfig while previous save in flight keeps the last intent`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 9, enabled = true)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            var saveCalls = 0
            val inFlightGate = CompletableDeferred<Unit>()
            // The row exists after the first save, so the in-flight save is a
            // saveUserColumns call (the gate must be there, not on saveConfig).
            wheneverBlocking { repository.saveUserColumns(any()) }
                .doSuspendableAnswer {
                    saveCalls++
                    if (saveCalls == 1) inFlightGate.await()
                }
            val updated = config.copy(recipientPhone = "+15550101")
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(updated)
            vm.saveConfig(config)
            vm.saveConfig(updated)
            assertEquals(updated, vm.state.value.config)
            assertTrue(vm.state.value.justSaved)
        }

    @Test
    fun `deleteSchedule cancels an in-flight save so the delete is the last intent`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 12, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            // Delete resolves the reason string even without an open row: the bare mock
            // context needs it stubbed.
            whenever(appContext.getString(R.string.error_schedule_deleted)).thenReturn("Schedule deleted")
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            vm.clearSavedFlag()
            val inFlightGate = CompletableDeferred<Unit>()
            // The row exists after the first save, so the toggle goes through
            // saveUserColumns (hang there to simulate a save still in flight).
            wheneverBlocking { repository.saveUserColumns(any()) }
                .doSuspendableAnswer { inFlightGate.await() }
            vm.setEnabled(false)
            vm.deleteSchedule(12)
            // The in-flight save is cancelled and cannot resurrect the row after the delete;
            // the only writes that may reach the repository are the delete's own.
            verifyBlocking(repository) { deleteConfig(12) }
            verify(armer).cancelSend(12)
            // The SENT history rows outlive the config row: the rhythm-reset marker keeps
            // a re-enable from re-anchoring the rhythm on the pre-delete send (and
            // recording the whole off period as missed occurrences).
            verify(prefs).setRhythmResetAtMillis(eq(12), any())
            // The first save's commit and the delete each sync the hint; the cancelled
            // in-flight save must not add a third.
            verifyBlocking(scheduleReconciler, times(2)) { syncPersistentHint() }
            // The cancelled save must not have applied its (disabled) intent either.
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `forgetSim cancels an in-flight save so the forget is the last intent`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 16, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            vm.clearSavedFlag()
            val inFlightGate = CompletableDeferred<Unit>()
            var completedSaveWrites = 0
            // The row exists after the first save, so the toggle goes through
            // saveUserColumns (hang there to simulate a save still in flight).
            wheneverBlocking { repository.saveUserColumns(any()) }
                .doSuspendableAnswer {
                    inFlightGate.await()
                    completedSaveWrites++
                    Unit
                }
            vm.setEnabled(false)
            vm.forgetSim(16)
            // The in-flight save (holding the SIM's send lock while suspended in its write)
            // is cancelled, so the forget can run and complete; the save's write never
            // completes and cannot re-create the row (and armed work) the forget dropped.
            verifyBlocking(repository) { deleteSim(16) }
            verify(armer).cancelSend(16)
            assertEquals(0, completedSaveWrites)
            // The first save's commit and the forget each sync the hint; the cancelled
            // in-flight save must not add a third.
            verifyBlocking(scheduleReconciler, times(2)) { syncPersistentHint() }
            // The cancelled save must not have applied its (disabled) intent either.
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `cancelled save makes no further repository calls after the cancellation point`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 20, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            vm.clearSavedFlag()
            val inFlightGate = CompletableDeferred<Unit>()
            // Hang the toggle's write so the cancellation (from the forget below) lands
            // inside the lock section, mid-save.
            wheneverBlocking { repository.saveUserColumns(any()) }
                .doSuspendableAnswer { inFlightGate.await() }
            vm.setEnabled(false)
            vm.forgetSim(20)
            // The cancelled save stops at the cancellation point: its remaining steps
            // (pending-row cleanup, post-write reads, per-SIM reconcile) never run, the
            // hung write never completes, and only the forget's own delete reaches the
            // repository (each count below is the first save's single call).
            verifyBlocking(repository) { deleteSim(20) }
            verifyBlocking(repository, times(1)) { saveConfig(any()) }
            verifyBlocking(repository, times(1)) { saveUserColumns(any()) }
            verifyBlocking(repository, times(1)) { clearPendingHistory(20) }
            verifyBlocking(scheduleReconciler, times(1)) { reconcileSim(20) }
            assertFalse(inFlightGate.isCompleted)
            verifyBlocking(scheduleReconciler, times(2)) { syncPersistentHint() }
            assertEquals(config, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `setEnabled persists flipped enabled without justSaved`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 6, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            vm.clearSavedFlag()
            val disabled = config.copy(enabled = false)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(config, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            assertEquals(disabled, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
        }

    @Test
    fun `commit writes the screen state from the post-reconcile read`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val preReconcile = SimKeepaliveConfig(simId = 5, enabled = true, recipientPhone = "+15550100")
            val postReconcile = preReconcile.copy(nextSendAtMillis = 123L)
            mockNoExistingConfig()
            // First enable: the row does not exist yet (the save creates it), the
            // pre-reconcile read therefore carries no next send, and only the read after
            // the reconcile carries the value the reconciler just wrote.
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, preReconcile, postReconcile)
            vm.saveConfig(preReconcile)
            // The screen state must be the post-reconcile read: landing the pre-reconcile
            // read (nextSendAtMillis = null) into state leaves the screen "Not scheduled"
            // until a re-navigation, because it can become the last update the screen
            // receives after the reconcile's own write.
            assertEquals(postReconcile, vm.state.value.config)
            verifyBlocking(scheduleReconciler) { reconcileSim(5) }
        }

    @Test
    fun `setEnabled repository failure leaves state unchanged`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 7, enabled = true)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(config)
            // The row exists, so the toggle writes user columns (the failure is there).
            wheneverBlocking { repository.saveUserColumns(any()) }
                .thenThrow(IllegalStateException("disk full"))
            vm.setEnabled(false)
            assertEquals(config, vm.state.value.config)
        }

    @Test
    fun `commit clears only pending history rows`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null)
            vm.saveConfig(SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100"))
            verifyBlocking(repository) { clearPendingHistory(1) }
        }

    @Test
    fun `commit with an existing row updates user columns only`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 1, enabled = true, sendCount = 3, nextSendAtMillis = 123L)
            val editor = dbConfig.copy(message = "new message")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
        }

    @Test
    fun `commit without an existing row creates it with default engine state`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 2, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            verifyBlocking(repository) { saveConfig(config) }
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
        }

    @Test
    fun `commit that changes the schedule drops the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 3, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            val editor = dbConfig.copy(hour = 14)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository) { updateNextSend(3, null) }
        }

    @Test
    fun `commit that keeps the schedule does not touch the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 4, enabled = true, message = "old", nextSendAtMillis = 123L, sendCount = 2)
            val editor = dbConfig.copy(message = "new")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
        }

    // A schedule change landing while an occurrence is in flight (SENDING or a retry row)
    // must not drop the stored next send: it is the armed work's fire time, and dropping it
    // would make the reconciler recompute a retry with fresh jitter that no work is armed
    // for, so the countdown diverges from the actual fire (the engine re-anchors after
    // completion).

    @Test
    fun `commit that changes the schedule during a pending retry keeps the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 13, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            val editor = dbConfig.copy(hour = 14)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(
                    // A retry row (PENDING, retryCount > 0): the in-flight gate must keep
                    // the stored next send (the retry time) for it.
                    SendHistoryEntity(
                        id = 1,
                        simId = 13,
                        scheduledForMillis = 100L,
                        occurrenceBaseMillis = 100L,
                        lastAttemptAtMillis = 110L,
                        outcome = SendOutcome.PENDING.name,
                        recipient = "+15550100",
                        message = "hi",
                        retryCount = 1,
                    ),
                )
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { clearPendingHistory(any()) }
        }

    @Test
    fun `commit that changes the schedule during an in-flight send keeps the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 14, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            val editor = dbConfig.copy(hour = 14)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(
                    SendHistoryEntity(
                        id = 1,
                        simId = 14,
                        scheduledForMillis = 123L,
                        occurrenceBaseMillis = 123L,
                        lastAttemptAtMillis = 120L,
                        outcome = SendOutcome.SENDING.name,
                        recipient = "+15550100",
                        message = "hi",
                    ),
                )
            vm.saveConfig(editor)
            verifyBlocking(repository) { saveUserColumns(editor) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { clearPendingHistory(any()) }
        }

    @Test
    fun `commit that changes the schedule with a pending row still drops the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 15, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            val editor = dbConfig.copy(hour = 14)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, dbConfig)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(
                    SendHistoryEntity(
                        id = 1,
                        simId = 15,
                        scheduledForMillis = 123L,
                        occurrenceBaseMillis = 123L,
                        outcome = SendOutcome.PENDING.name,
                        recipient = "+15550100",
                        message = "hi",
                    ),
                )
            vm.saveConfig(editor)
            verifyBlocking(repository) { updateNextSend(15, null) }
            verifyBlocking(repository) { clearPendingHistory(15) }
        }

    @Test
    fun `setEnabled false drops the stored next send when the schedule is unchanged`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 17, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, dbConfig)
            vm.saveConfig(dbConfig)
            vm.clearSavedFlag()
            val disabled = dbConfig.copy(enabled = false)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, disabled)
            vm.setEnabled(false)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(repository) { updateNextSend(17, null) }
            verifyBlocking(scheduleReconciler, times(2)) { reconcileSim(17) }
            assertEquals(disabled, vm.state.value.config)
        }

    @Test
    fun `setEnabled false is refused while a fresh send is in flight and clears nothing`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 21, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, dbConfig)
            vm.saveConfig(dbConfig)
            vm.clearSavedFlag()
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 21, System.currentTimeMillis(), SendOutcome.SENDING))
            vm.setEnabled(false)
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, times(1)) { saveConfig(any()) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(scheduleReconciler, times(1)) { reconcileSim(21) }
            assertEquals(dbConfig, vm.state.value.config)
        }

    @Test
    fun `setEnabled true keeps the stored next send when the schedule is unchanged`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 18, enabled = false, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, dbConfig)
            vm.saveConfig(dbConfig)
            vm.clearSavedFlag()
            val enabled = dbConfig.copy(enabled = true)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, enabled)
            vm.setEnabled(true)
            verifyBlocking(repository) { saveUserColumns(enabled) }
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(scheduleReconciler, times(2)) { reconcileSim(18) }
            assertEquals(enabled, vm.state.value.config)
        }

    @Test
    fun `commit that disables with a changed schedule drops the stored next send`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val dbConfig = SimKeepaliveConfig(simId = 19, enabled = true, hour = 9, nextSendAtMillis = 123L, sendCount = 2)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, dbConfig)
            vm.saveConfig(dbConfig)
            vm.clearSavedFlag()
            val disabled = dbConfig.copy(enabled = false, hour = 14)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(dbConfig, disabled)
            vm.saveConfig(disabled)
            verifyBlocking(repository) { saveUserColumns(disabled) }
            verifyBlocking(repository) { updateNextSend(19, null) }
            verifyBlocking(scheduleReconciler, times(2)) { reconcileSim(19) }
            assertEquals(disabled, vm.state.value.config)
        }

    @Test
    fun `saveConfig with enabled config reconciles the send schedule`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            verifyBlocking(scheduleReconciler) { reconcileSim(1) }
            verifyBlocking(scheduleReconciler) { syncPersistentHint() }
        }

    @Test
    fun `saveConfig with disabled config reconciles the send schedule`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 2, enabled = false)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            verifyBlocking(scheduleReconciler) { reconcileSim(2) }
            verifyBlocking(scheduleReconciler) { syncPersistentHint() }
        }

    @Test
    fun `setEnabled false reconciles the schedule again`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 3, enabled = true, recipientPhone = "+15550100")
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            val disabled = config.copy(enabled = false)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(config, disabled)
            vm.setEnabled(false)
            verifyBlocking(scheduleReconciler, times(2)) { reconcileSim(3) }
        }

    @Test
    fun `setEnabled false after repository failure does not reconcile`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            val config = SimKeepaliveConfig(simId = 4, enabled = true)
            mockNoExistingConfig()
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(null, config)
            vm.saveConfig(config)
            wheneverBlocking { repository.getConfig(any()) }
                .thenReturn(config)
            // The row exists, so the toggle writes user columns (the failure is there).
            wheneverBlocking { repository.saveUserColumns(any()) }
                .thenThrow(IllegalStateException("disk full"))
            vm.setEnabled(false)
            verify(scheduleReconciler, times(1)).reconcileSim(4)
        }

    @Test
    fun `retryNow arms an immediate send run for the given sim`() =
        runBlocking {
            val vm =
                SimDetailViewModel(
                    appContext,
                    repository,
                    scheduleReconciler,
                    prefs,
                    armer,
                    SimSendLock(),
                    OffSchedulePendingRegistry(),
                )
            vm.retryNow(1)
            verify(armer).armSendNow(1)
        }

    // Sets up the VM with a saved (state-carrying) config: the off-schedule gate reads the
    // screen state, which the commit writes. The two getConfig reads of the commit (row
    // existence, post-reconcile) get null then the saved row.
    private fun vmWithSavedConfig(config: SimKeepaliveConfig): SimDetailViewModel {
        val vm =
            SimDetailViewModel(
                appContext,
                repository,
                scheduleReconciler,
                prefs,
                armer,
                SimSendLock(),
                OffSchedulePendingRegistry(),
            )
        mockNoExistingConfig()
        wheneverBlocking { repository.getConfig(any()) }
            .thenReturn(null, config)
        vm.saveConfig(config)
        return vm
    }

    private fun activeRow(
        id: Long,
        simId: Int,
        scheduledForMillis: Long,
        outcome: SendOutcome,
        retryCount: Int = 0,
    ): SendHistoryEntity =
        SendHistoryEntity(
            id = id,
            simId = simId,
            scheduledForMillis = scheduledForMillis,
            occurrenceBaseMillis = scheduledForMillis,
            lastAttemptAtMillis = if (outcome == SendOutcome.SENDING) System.currentTimeMillis() else null,
            outcome = outcome.name,
            recipient = "+15550100",
            message = "keep alive",
            retryCount = retryCount,
        )

    @Test
    fun `sendOffSchedule arms the one-off occurrence with the configured delay`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 1, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            val before = System.currentTimeMillis()
            vm.sendOffSchedule(1)
            val tCaptor = argumentCaptor<Long>()
            verifyBlocking(repository) { updateNextSend(eq(1), tCaptor.capture()) }
            val t = tCaptor.firstValue
            assertTrue(
                "armed fire time must be now + the configured delay",
                t in (before + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS)..(before + AppConfig.OFF_SCHEDULE_SEND_DELAY_MS + 5_000L),
            )
            verifyBlocking(repository) { alignPendingRow(eq(1), eq(t), eq(t), eq("+15550100"), eq("keep alive")) }
            verify(armer).armSend(1)
            assertEquals(t, vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `sendOffSchedule is refused while an attempt is in flight`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 2, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 2, System.currentTimeMillis(), SendOutcome.SENDING))
            vm.sendOffSchedule(2)
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { alignPendingRow(any(), any(), any(), any(), any()) }
            verify(armer, never()).armSend(any())
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `sendOffSchedule is refused while a retry is pending`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 3, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            // A retry row (PENDING, retryCount > 0) is engine-owned: the one-off is refused.
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 3, System.currentTimeMillis(), SendOutcome.PENDING, retryCount = 1))
            vm.sendOffSchedule(3)
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { alignPendingRow(any(), any(), any(), any(), any()) }
            verify(armer, never()).armSend(any())
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `sendOffSchedule without an enabled config is a no-op`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 4, enabled = false, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            vm.sendOffSchedule(4)
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            verifyBlocking(repository, never()) { alignPendingRow(any(), any(), any(), any(), any()) }
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `sendOffSchedule with a blank message is a no-op`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 5, enabled = true, recipientPhone = "+15550100", message = "")
            val vm = vmWithSavedConfig(config)
            vm.sendOffSchedule(5)
            verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    // An off-schedule send armed while a distant regular send is pending: the one-off's
    // re-anchoring moves the next send to a different occurrence, so the dropped pending
    // is recorded as SKIPPED with the user-facing reason before the align deletes it (a
    // skip is never unexplained).
    @Test
    fun `sendOffSchedule records the dropped pending occurrence as skipped`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 1,
                    enabled = true,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                )
            val vm = vmWithSavedConfig(config)
            // The pending's occurrence is 29 days out (the last send was 31 days ago):
            // the one-off today re-anchors the rhythm 30 days from today, so the pending's
            // occurrence is the one that gets moved.
            val zone = ZoneId.systemDefault()
            val pendingDate = ZonedDateTime.now(zone).plusDays(29).toLocalDate()
            val pendingAt = pendingDate.atTime(12, 0).atZone(zone)
            val pendingMillis = pendingAt.toInstant().toEpochMilli()
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 1, pendingMillis, SendOutcome.PENDING))
            whenever(appContext.getString(R.string.error_replaced_by_off_schedule))
                .thenReturn("Replaced by an off-schedule send")
            vm.sendOffSchedule(1)
            verifyBlocking(repository) {
                finalizeOccurrence(1L, SendOutcome.SKIPPED, null, "Replaced by an off-schedule send", 0, null)
            }
            verifyBlocking(repository) { alignPendingRow(eq(1), any(), any(), eq("+15550100"), eq("keep alive")) }
            verify(armer).armSend(1)
            assertNotNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    // The stored next send fires within the delay: the one-off supersedes it (one send,
    // no duplicate) — nothing is dropped, so no skip record.
    @Test
    fun `sendOffSchedule records no skip when the pending is within the delay`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 2,
                    enabled = true,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                )
            val vm = vmWithSavedConfig(config)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 2, System.currentTimeMillis() + 5_000L, SendOutcome.PENDING))
            vm.sendOffSchedule(2)
            verifyBlocking(repository, never()) { finalizeOccurrence(any(), any(), any(), any(), any(), any()) }
            verifyBlocking(repository) { alignPendingRow(eq(2), any(), any(), eq("+15550100"), eq("keep alive")) }
            assertNotNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    // MONTHLY: the one-off lands in the last send's month, so the re-anchor lands exactly
    // on the pending's own occurrence (next month's) — nothing is dropped, no skip record.
    @Test
    fun `sendOffSchedule records no skip when the one-off lands in the last send's month`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 3,
                    enabled = true,
                    freqType = FrequencyType.MONTHLY,
                    monthsInterval = 1,
                    dayOfMonth = 10,
                    hour = 12,
                    minute = 0,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                )
            val vm = vmWithSavedConfig(config)
            val zone = ZoneId.systemDefault()
            // The one-off's date (now + delay) must not cross a month boundary, or the
            // scenario (one-off in the last send's month) no longer holds.
            val oneOffDate = ZonedDateTime.now(zone).plusSeconds(AppConfig.OFF_SCHEDULE_SEND_DELAY_MS / 1000).toLocalDate()
            Assume.assumeTrue(YearMonth.from(oneOffDate) == YearMonth.from(ZonedDateTime.now(zone)))
            val pendingMonth = YearMonth.from(oneOffDate).plusMonths(1)
            val pendingAt = pendingMonth.atDay(10).atTime(12, 0).atZone(zone)
            val pendingMillis = pendingAt.toInstant().toEpochMilli()
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 3, pendingMillis, SendOutcome.PENDING))
            vm.sendOffSchedule(3)
            verifyBlocking(repository, never()) { finalizeOccurrence(any(), any(), any(), any(), any(), any()) }
            verifyBlocking(repository) { alignPendingRow(eq(3), any(), any(), eq("+15550100"), eq("keep alive")) }
            assertNotNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    // MONTHLY: the one-off lands in the pending's own month before its day, so the
    // re-anchor moves to the following month — the pending's occurrence is dropped and
    // recorded as SKIPPED.
    @Test
    fun `sendOffSchedule records the dropped occurrence when the one-off lands in the pending's month`() =
        runBlocking {
            val config =
                SimKeepaliveConfig(
                    simId = 4,
                    enabled = true,
                    freqType = FrequencyType.MONTHLY,
                    monthsInterval = 1,
                    dayOfMonth = 31,
                    hour = 23,
                    minute = 59,
                    recipientPhone = "+15550100",
                    message = "keep alive",
                )
            val vm = vmWithSavedConfig(config)
            val zone = ZoneId.systemDefault()
            val oneOffDate = ZonedDateTime.now(zone).plusSeconds(AppConfig.OFF_SCHEDULE_SEND_DELAY_MS / 1000).toLocalDate()
            // The pending (the month's last day, dayOfMonth 31 clamped) must still be in
            // the future: on the last day of the month past its armed time the scenario
            // degenerates into the within-delay supersession, so the test skips there.
            val pendingMonth = YearMonth.from(oneOffDate)
            val pendingAt = pendingMonth.atEndOfMonth().atTime(23, 59).atZone(zone)
            val pendingMillis = pendingAt.toInstant().toEpochMilli()
            Assume.assumeTrue(pendingMillis - System.currentTimeMillis() > AppConfig.OFF_SCHEDULE_SEND_DELAY_MS)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(1, 4, pendingMillis, SendOutcome.PENDING))
            whenever(appContext.getString(R.string.error_replaced_by_off_schedule))
                .thenReturn("Replaced by an off-schedule send")
            vm.sendOffSchedule(4)
            verifyBlocking(repository) {
                finalizeOccurrence(1L, SendOutcome.SKIPPED, null, "Replaced by an off-schedule send", 0, null)
            }
            verifyBlocking(repository) { alignPendingRow(eq(4), any(), any(), eq("+15550100"), eq("keep alive")) }
            assertNotNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `cancelOffSchedule finalizes the pending row and restores the regular schedule`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 6, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            whenever(appContext.getString(R.string.error_send_cancelled)).thenReturn("Cancelled by user")
            vm.sendOffSchedule(6)
            val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(7, 6, t, SendOutcome.PENDING))
            vm.cancelOffSchedule(6)
            verifyBlocking(repository) {
                finalizeOccurrence(7L, SendOutcome.SKIPPED, null, "Cancelled by user", 0, null)
            }
            verify(armer).cancelSend(6)
            verifyBlocking(repository) { updateNextSend(6, null) }
            // Two times each: the first-save commit in vmWithSavedConfig drops PENDING
            // rows and reconciles as well.
            verifyBlocking(repository, times(2)) { clearPendingHistory(6) }
            verifyBlocking(scheduleReconciler, times(2)) { reconcileSim(6) }
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `cancelOffSchedule is refused while an attempt is in flight`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 7, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            vm.sendOffSchedule(7)
            val t = requireNotNull(vm.state.value.offScheduleSendPendingAtMillis)
            wheneverBlocking { repository.getActiveHistory(any()) }
                .thenReturn(activeRow(8, 7, t, SendOutcome.SENDING))
            vm.cancelOffSchedule(7)
            verifyBlocking(repository, never()) { finalizeOccurrence(any(), any(), any(), any(), any(), any()) }
            verify(armer, never()).cancelSend(any())
            // The dialog still closes: the pending state is cleared with the row.
            assertNull(vm.state.value.offScheduleSendPendingAtMillis)
        }

    @Test
    fun `cancelOffSchedule without a pending send is a no-op`() =
        runBlocking {
            val config = SimKeepaliveConfig(simId = 8, enabled = true, recipientPhone = "+15550100", message = "keep alive")
            val vm = vmWithSavedConfig(config)
            vm.cancelOffSchedule(8)
            verifyBlocking(repository, never()) { finalizeOccurrence(any(), any(), any(), any(), any(), any()) }
            verify(armer, never()).cancelSend(any())
        }
}
