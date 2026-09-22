package app.kotowski.keepsimalive.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.telephony.SubscriptionManager
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FinalizeAdvance
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.PermissionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowSubscriptionManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SendOrchestratorTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var repository: KeepaliveRepository
    private lateinit var sender: SmsSender
    private lateinit var armer: ScheduleArmer
    private lateinit var prefs: AppPrefs
    private lateinit var permissionManager: PermissionManager
    private lateinit var reconciler: ScheduleReconciler
    private lateinit var orchestrator: SendOrchestrator

    @Before
    fun setup() {
        repository = mock()
        sender = mock()
        armer = mock()
        prefs = mock()
        permissionManager = mock()
        reconciler = mock()
        // Real advancer on the same mocks: skipInterrupted is verifiable, hold is zero for speed.
        orchestrator =
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
                SimSendLock(),
            )
        // Both permissions are granted by default; individual tests deny what they cover.
        // The Phone permission is also read by SimInfoFetcher via the real context, and
        // subId 1 must be in the active list for the real SIM to count as present.
        whenever(permissionManager.isGranted(Manifest.permission.SEND_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE)).thenReturn(true)
        whenever(prefs.getSimNotPresentFailures(anyInt())).thenReturn(0)
        whenever(prefs.setSimNotPresentFailures(anyInt(), anyInt())).then { }
        // An unstubbed 0 would skip every past occurrence in the catch-up.
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .buildSubscriptionInfo(),
            )
        // The claim succeeds by default (a real claim is an atomic "exactly one winner"
        // update). Unstubbed suspend calls return null on the JVM (NPE on unbox): every
        // value-returning stub needs an explicit default.
        runBlocking {
            whenever(repository.claimOccurrence(anyLong(), anyLong(), anyLong())).thenReturn(1)
            whenever(repository.finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())).thenReturn(1)
            // The terminal finalizes win by default (the row was still open/SENDING); the
            // grace-skip finalize lands (the anchor flip won).
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(true)
            whenever(
                repository.finalizeGraceSkips(any(), anyOrNull(), any(), any(), anyLong()),
            ).thenReturn(true)
            // The in-flight flips apply by default (the row was still SENDING); the
            // stale-attempt flip wins (exactly one resolver resolves the row).
            whenever(
                repository.finalizeSending(anyLong(), any(), anyOrNull(), any(), anyInt(), anyOrNull(), anyOrNull()),
            ).thenReturn(1)
            // The mocked endScheduleNow performs the real end effects on the mocks, so the
            // end-path tests verify the end state instead of the wiring. The nested
            // runBlocking is needed because the answer is not a coroutine body itself.
            whenever(reconciler.endScheduleNow(anyInt(), any())).thenAnswer { inv ->
                runBlocking {
                    repository.endSchedule(inv.arguments[0] as Int)
                    armer.cancelSend(inv.arguments[0] as Int)
                    if (inv.arguments[1] as Boolean) {
                        reconciler.syncPersistentHint()
                    }
                }
            }
        }
    }

    private fun baseConfig(nextSendAtMillis: Long? = null): SimKeepaliveConfig =
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
            nextSendAtMillis = nextSendAtMillis,
        )

    private fun activeRow(
        scheduledForMillis: Long,
        outcome: SendOutcome = SendOutcome.PENDING,
        firstAttemptAtMillis: Long? = null,
        retryCount: Int = 0,
        lastAttemptAtMillis: Long? = null,
    ) = SendHistoryEntity(
        id = 42L,
        simId = 1,
        scheduledForMillis = scheduledForMillis,
        // Window-0 config in this suite: the base is the delivery instant.
        occurrenceBaseMillis = scheduledForMillis,
        outcome = outcome.name,
        recipient = "+15550100",
        message = "keep alive",
        firstAttemptAtMillis = firstAttemptAtMillis,
        retryCount = retryCount,
        lastAttemptAtMillis = lastAttemptAtMillis,
    )

    private fun expectedNext(
        base: Long,
        days: Long,
    ): Long =
        Instant
            .ofEpochMilli(base)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(days)
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `disabled config at the top read triggers a reconcile instead of a bare return`() =
        runBlocking<Unit> {
            // A kill between the UI disable commit and its reconcile leaves a disabled
            // config that still carries an armed schedule: the ghost state must not survive
            // until the next reconcile trigger.
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(System.currentTimeMillis()).copy(enabled = false))

            orchestrator.process(1)

            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
            verify(
                repository,
                never(),
            ).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            // The cancel belongs to the reconciler's disabled branch, not to this path.
            verify(armer, never()).cancelSend(anyInt())
        }

    @Test
    fun `cleared next send at process start triggers a reconcile instead of a bare return`() =
        runBlocking<Unit> {
            // A crash or a race can leave an enabled config with a cleared next send: the
            // schedule must self-heal right away instead of waiting for the next sweep.
            whenever(repository.getConfig(1)).thenReturn(baseConfig())

            orchestrator.process(1)

            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).armSendNow(anyInt())
            verify(armer, never()).cancelSend(anyInt())
        }

    @Test
    fun `next send cleared between the reads triggers a reconcile instead of running the engine decisions`() =
        runBlocking<Unit> {
            val top = baseConfig(System.currentTimeMillis() - 60_000)
            whenever(repository.getConfig(1)).thenReturn(top, top.copy(nextSendAtMillis = null))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(top.nextSendAtMillis!!))

            orchestrator.process(1)

            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).armSendNow(anyInt())
            verify(armer, never()).cancelSend(anyInt())
        }

    @Test
    fun `missing SEND_SMS permission at send time skips the occurrence and advances`() =
        runBlocking<Unit> {
            whenever(permissionManager.isGranted(Manifest.permission.SEND_SMS)).thenReturn(false)
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The skip and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.SKIPPED),
                            isNull(),
                            eq(context.getString(R.string.error_no_permission_at_send)),
                            eq(0),
                            isNull(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            // The schedule must not stall: the next occurrence is armed right away.
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            // A skip is silent (the dashboard already highlights the missing permission).
        }

    @Test
    fun `missing Phone permission at send time skips the occurrence with its own reason`() =
        runBlocking<Unit> {
            whenever(permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE)).thenReturn(false)
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The skip and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.SKIPPED),
                            isNull(),
                            eq(context.getString(R.string.error_no_phone_permission)),
                            eq(0),
                            isNull(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `absent SIM at send time schedules a retry like a no-service failure`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            shadowOf(context.getSystemService(SubscriptionManager::class.java))
                .setActiveSubscriptionInfos()

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The row stays open (PENDING, retryCount + 1): a flapping SIM recovers on the
            // next retry, a removed one burns the 14-day window.
            verify(repository).finalizeOccurrence(
                eq(42L),
                eq(SendOutcome.PENDING),
                anyLong(),
                eq(context.getString(R.string.error_sim_not_present)),
                eq(1),
                anyLong(),
            )
            // attempt 1 backoff is 10s with +/-10% jitter.
            val retryAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(
                "retryAt $retryAt outside [now+9s, now+11s]",
                retryAt!! in (now + 9_000)..(System.currentTimeMillis() + 11_000),
            )
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            // Below the threshold the occurrence just retries: no auto-disable alert.
            assertNull(
                shadowNotificationManager.activeNotifications
                    .find { it.id == AUTO_DISABLED_NOTIFICATION_ID },
            )
        }

    @Test
    fun `absent SIM auto-disables after reaching consecutive failure threshold`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            // Counter is at threshold - 1: next failure triggers auto-disable.
            whenever(prefs.getSimNotPresentFailures(1)).thenReturn(AppConfig.MAX_SIM_NOT_PRESENT_FAILURES - 1)
            shadowOf(context.getSystemService(SubscriptionManager::class.java))
                .setActiveSubscriptionInfos()

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The flip, the disable (in-lock config's columns) and the schedule clear land
            // together (finalizeOutcome).
            val capturedAdvance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(
                                context.resources.getQuantityString(
                                    R.plurals.error_sim_not_present_auto_disabled,
                                    AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                                    AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                                ),
                            ),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(capturedAdvance is FinalizeAdvance.AutoDisable)
            // The variant carries the in-lock config: the repository writes enabled = false
            // with these columns in the same transaction.
            assertEquals(baseConfig(scheduled), (capturedAdvance as FinalizeAdvance.AutoDisable).config)
            // No split writes: a re-enable must recompute from the last send, not re-run it.
            verify(repository, never()).saveUserColumns(any())
            verify(repository, never()).clearPendingHistory(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer).cancelSend(1)
            // Counter is reset after auto-disable.
            verify(prefs).setSimNotPresentFailures(eq(1), eq(0))
            // The engine auto-disabled the keepalive: the persistent hint is refreshed right away.
            verify(reconciler).syncPersistentHint()
            // The SIM is absent and never cached here, so the alert's name falls back to
            // the i18n subscription-id name (the same chain as the UI titles).
            val posted =
                shadowNotificationManager.activeNotifications
                    .find { it.id == AUTO_DISABLED_NOTIFICATION_ID }
            assertNotNull(posted)
            assertEquals(
                context.getString(
                    R.string.notification_auto_disabled_title,
                    context.getString(R.string.sim_name_fallback, 1),
                ),
                posted!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
        }

    @Test
    fun `absent SIM retry backs off when a concurrent run finalized the row`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(repository.finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())).thenReturn(0)
            shadowOf(context.getSystemService(SubscriptionManager::class.java))
                .setActiveSubscriptionInfos()

            orchestrator.process(1)

            // The concurrent owner drives the occurrence: no re-arm.
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `invalid recipient at send time fails the occurrence permanently without sending`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(recipientPhone = "+12345"))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The failure and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(context.getString(R.string.error_invalid_recipient)),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `empty recipient at send time fails the occurrence permanently without sending`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(recipientPhone = ""))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            // The failure and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(context.getString(R.string.error_invalid_recipient)),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `empty message at send time fails the occurrence permanently without sending`() =
        runBlocking<Unit> {
            // The editor cannot save an empty message, so only a legacy or tampered row
            // reaches this: fail immediately, not burn the 14-day retry window.
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(message = ""))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The failure and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(context.getString(R.string.error_empty_message)),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `whitespace-only message at send time fails the occurrence permanently without sending`() =
        runBlocking<Unit> {
            // isBlank(), matching the editor's validity rule: whitespace-only is invalid
            // the same way empty is.
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(message = "   "))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The failure and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(context.getString(R.string.error_empty_message)),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `over-length message at send time fails the occurrence permanently without sending`() =
        runBlocking<Unit> {
            // The editor caps the message at the single-segment limit, so only a legacy or
            // tampered row reaches this: fail immediately instead of letting the radio API
            // truncate device-dependently.
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(message = "a".repeat(AppConfig.SMS_MAX_CHARS + 1)))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The failure and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq(context.getString(R.string.error_message_too_long)),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertEquals(expectedNext(scheduled, 30), (advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis)
            verify(armer).armSend(1)
        }

    @Test
    fun `message of exactly the SMS limit at send time proceeds to the send path`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            val atLimit = "a".repeat(AppConfig.SMS_MAX_CHARS)
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled).copy(message = atLimit))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq(atLimit), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(sender).send(eq(1), eq("+15550100"), eq(atLimit), anyOrNull())
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        scheduled,
                        1,
                        expectedNext(scheduled, 30),
                        expectedNext(scheduled, 30),
                        "+15550100",
                        atLimit,
                    ),
                ),
            )
            verify(
                repository,
                never(),
            ).finalizeOccurrence(anyLong(), eq(SendOutcome.FAILED), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer).armSend(1)
        }

    @Test
    fun `early fire with missing SMS permission re-arms instead of skipping`() =
        runBlocking<Unit> {
            whenever(permissionManager.isGranted(Manifest.permission.SEND_SMS)).thenReturn(false)
            val scheduled = System.currentTimeMillis() + 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // The test's armed instant is not the configured 12:00, so the re-arm writes
            // the exact pre-jitter base instead of the instant.
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(repository).updateNextSend(eq(1), eq(scheduled))
            verify(repository).alignPendingRow(eq(1), eq(scheduled), eq(expectedNext(scheduled, 0)), eq("+15550100"), eq("keep alive"))
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
        }

    @Test
    fun `early fire against a reset schedule backs off without re-arming`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() + 3_600_000L
            // Pre-lock reads see the live schedule; the in-lock re-read sees the reset that
            // landed in between.
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled), baseConfig(scheduled), baseConfig(null))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), any())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `early fire against a deleted config backs off without writing`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() + 3_600_000L
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled), baseConfig(scheduled), null)
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), any())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `early fire against a disabled config backs off without re-arming`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() + 3_600_000L
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled), baseConfig(scheduled), baseConfig(null).copy(enabled = false))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), any())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
        }

    // The claim section's twin of the early-fire re-read: the reset is the case the
    // row-level re-checks cannot always see — the config stays enabled and no terminal row
    // exists at the send time.

    @Test
    fun `late run against a reset schedule backs off without sending`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000L
            // Pre-lock reads (top, hoisted, pre-flight) see the live schedule; the in-lock
            // re-read sees the reset that landed in between.
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled), baseConfig(scheduled), baseConfig(scheduled), baseConfig(null))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).updateNextSend(anyInt(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `success advances schedule`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            val next = expectedNext(scheduled, 30)
            // One atomic write: the SENT row, the counters, the anchor and the next open
            // row land together (finalizeOutcome).
            verify(repository).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(FinalizeAdvance.Success(1, scheduled, 1, next, next, "+15550100", "keep alive")),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).saveConfig(any())
            // The next row lands inside the transaction: no separate alignment.
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())

            verify(armer).armSend(1)
            // The schedule continues: the persistent hint is left untouched.
            verify(reconciler, never()).syncPersistentHint()
        }

    // A process death in that window resolves the occurrence as an interrupted skip: the
    // radio answer is logged so a misrecorded skip can be matched against the carrier/
    // recipient records.
    @Test
    fun `radio answer is logged for the death window between the answer and the result write`() =
        runBlocking<Unit> {
            LogBuffer.clear()
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            assertTrue(
                LogBuffer.entries.any {
                    it.level == "I" && it.tag == "SendOrchestrator" &&
                        it.message.contains("radio answered at") && it.message.contains("(Success)")
                },
            )
        }

    @Test
    fun `success resets SIM-not-present failure counter`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(prefs).setSimNotPresentFailures(eq(1), eq(0))
        }

    @Test
    fun `success reaching max sends ends keepalive`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 2))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            // Atomic: the SENT row, the counter and the end land in the single write.
            verify(repository).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(FinalizeAdvance.Success(1, scheduled, 3, null, null, "+15550100", "keep alive")),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).endSchedule(1)
            verify(repository, never()).saveConfig(any())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).insertHistory(any())
            // The engine ended the keepalive: the persistent hint is refreshed right away.
            verify(reconciler).syncPersistentHint()
        }

    // The concurrent finalizer — the stale check armed at claim time — owns the occurrence:
    // the engine writes nothing and re-arms nothing.
    @Test
    fun `superseded result leaves the row and the schedule to the concurrent owner`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Superseded)

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())
            // The stale check armed at claim time is the owner of the row; nothing else runs.
            verify(reconciler).armStaleCheck(eq(1), anyLong())
            verify(reconciler, never()).syncPersistentHint()
            verify(reconciler, never()).reconcileSim(anyInt())
        }

    // The mock sender invokes the validator the engine passes (like the real sender in the
    // IO context); the validator's re-read sees the row the stale check just resolved.
    @Test
    fun `a row that went stale during the send aborts before the radio call`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            // The pre-lock and in-lock reads see the open row; the validator's re-read sees
            // the row the stale check just resolved.
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(scheduled),
                    activeRow(scheduled),
                    activeRow(
                        scheduled,
                        outcome = SendOutcome.SENDING,
                        lastAttemptAtMillis = now - AppConfig.SENDING_STALE_MS - 1,
                    ),
                )
            // The mock sender runs the validator like the real one and reports the real
            // radio outcome.
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .doSuspendableAnswer {
                    val validate = it.arguments[3] as (suspend () -> Boolean)
                    if (validate()) SmsSendResult.Success else SmsSendResult.Superseded
                }

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            // The validator saw a stale row: no radio, no result write, no schedule move.
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())
            // The stale check armed at claim time still owns the row; nothing re-arms it.
            verify(reconciler).armStaleCheck(eq(1), anyLong())
            verify(reconciler, never()).syncPersistentHint()
        }

    // When the row is no longer SENDING (the stale check resolved it meanwhile), the write
    // applies nothing: no schedule advance on top.
    @Test
    fun `a success whose write is not applied defers to the concurrent owner`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)
            whenever(
                repository.finalizeOutcome(
                    eq(42L),
                    eq(SendOutcome.SENDING),
                    eq(SendOutcome.SENT),
                    anyLong(),
                    isNull(),
                    eq(0),
                    anyLong(),
                    isNull(),
                    any(),
                ),
            ).thenReturn(false)

            orchestrator.process(1)

            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                any(),
            )
            // No schedule advance for a write that never landed: no pending row, no re-arm.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
            verify(reconciler, never()).syncPersistentHint()
        }

    @Test
    fun `an ending success whose write is not applied does not end the schedule`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 2))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)
            whenever(
                repository.finalizeOutcome(
                    eq(42L),
                    eq(SendOutcome.SENDING),
                    eq(SendOutcome.SENT),
                    anyLong(),
                    isNull(),
                    eq(0),
                    anyLong(),
                    isNull(),
                    eq(FinalizeAdvance.Success(1, scheduled, 3, null, null, "+15550100", "keep alive")),
                ),
            ).thenReturn(false)

            orchestrator.process(1)

            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(FinalizeAdvance.Success(1, scheduled, 3, null, null, "+15550100", "keep alive")),
            )
            // The concurrent owner's outcome stands: no end, no hint sync, no re-arm.
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(reconciler, never()).syncPersistentHint()
        }

    // The failure writes are conditional, like the success write: when the row is no longer
    // SENDING the write applies nothing — no clobber, no advance on top, no retry re-arm.
    @Test
    fun `a permanent failure whose write is not applied defers to the concurrent owner`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("Radio is off", permanent = true))
            whenever(
                repository.finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any()),
            ).thenReturn(false)

            orchestrator.process(1)

            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.FAILED),
                anyLong(),
                eq("Radio is off"),
                eq(0),
                anyLong(),
                isNull(),
                any(),
            )
            // No schedule advance for a write that never landed: the concurrent owner's
            // advance stands and its skipped outcome is never clobbered.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `a retryable failure whose write is not applied defers without arming a retry`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("No service", permanent = false))
            whenever(repository.finalizeSending(anyLong(), any(), anyLong(), any(), anyInt(), anyLong(), anyOrNull())).thenReturn(0)

            orchestrator.process(1)

            // The retryable failure lands as the SENDING->PENDING flip (retryCount + 1).
            verify(repository).finalizeSending(eq(42L), eq(SendOutcome.PENDING), anyLong(), eq("No service"), eq(1), anyLong(), isNull())
            // A retry armed for a consumed occurrence would be re-claimed and re-sent: no
            // retry write, no re-arm.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `a disable-skip whose write is not applied defers to the concurrent owner`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            val enabled = baseConfig(scheduled)
            // Enabled for every pre-radio read; disabled by the time the in-flight attempt
            // reports its result.
            whenever(repository.getConfig(1))
                .thenReturn(enabled, enabled, enabled, enabled, enabled, enabled.copy(enabled = false))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("No service", permanent = false))
            whenever(repository.finalizeSending(anyLong(), any(), anyLong(), any(), anyInt(), anyLong(), anyOrNull())).thenReturn(0)

            orchestrator.process(1)

            verify(repository).finalizeSending(
                eq(42L),
                eq(SendOutcome.SKIPPED),
                anyLong(),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                anyLong(),
                isNull(),
            )
            verify(
                repository,
                never(),
            ).finalizeSending(anyLong(), eq(SendOutcome.PENDING), anyLong(), any(), anyInt(), anyLong(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `occurrence past end date ends keepalive without sending`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val endDate = LocalDate.now().minusDays(1)
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.ON_DATE, endDate = endDate))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            // The skip and the end land together (finalizeOutcome): a skip is never unexplained.
            verify(repository).finalizeOutcome(
                eq(42L),
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
            verify(repository, never()).insertHistory(any())
            // The engine ended the keepalive: the persistent hint is refreshed right away.
            verify(reconciler).syncPersistentHint()
        }

    // The retry is judged at the original occurrence (before the end date) while the retry
    // itself lands after it: the end must be judged at the time the SMS would go out, or
    // one SMS lands past the user's end date.
    @Test
    fun `retrying row landing past the end date ends keepalive without sending`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val occurrence = now - 3L * 24 * 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(occurrence + 1_000).copy(endType = EndType.ON_DATE, endDate = endDate))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 5),
                )

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            // The skip and the end land together (finalizeOutcome).
            verify(repository).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(endedText),
                eq(5),
                eq(occurrence),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).saveConfig(any())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).insertHistory(any())
            verify(reconciler).syncPersistentHint()
        }

    // The end date is inclusive: a retry sending on the end date itself still goes out
    // (and ends the schedule with it).
    @Test
    fun `retrying row landing on the end date still sends`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val occurrence = now - 3L * 24 * 3_600_000L
            val endDate = LocalDate.now()
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(occurrence + 1_000).copy(endType = EndType.ON_DATE, endDate = endDate))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 1),
                )
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            // The next occurrence is past the end date: the send ends the keepalive in the
            // same atomic write.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(1),
                eq(occurrence),
                isNull(),
                eq(FinalizeAdvance.Success(1, occurrence, 1, null, null, "+15550100", "keep alive")),
            )
            verify(reconciler).syncPersistentHint()
        }

    // A save that just set a past end date lands after the top read: the end check must use
    // the fresh config, or one SMS goes out past it.
    @Test
    fun `fresh config from the hoisted re-read is used for the end check`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val endDate = LocalDate.now().minusDays(1)
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(endType = EndType.ON_DATE, endDate = endDate),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            val endedText =
                context.getString(R.string.schedule_ended_on_date, DateUtil.formatDate(context, endDate))
            // The skip and the end land together (finalizeOutcome).
            verify(repository).finalizeOutcome(
                eq(42L),
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
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).insertHistory(any())
            verify(reconciler).syncPersistentHint()
        }

    // The mirror: a save that just extended the end condition (stale read says ended,
    // fresh says continue) must not end the schedule.
    @Test
    fun `fresh config from the hoisted re-read keeps a just-extended schedule running`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val endDate = LocalDate.now().minusDays(1)
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled).copy(endType = EndType.ON_DATE, endDate = endDate),
                    baseConfig(scheduled),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // The stale end condition must not end the schedule: the occurrence sends as usual.
            verify(repository, never()).endSchedule(1)
            verify(armer, never()).cancelSend(anyInt())
            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        scheduled,
                        1,
                        expectedNext(scheduled, 30),
                        expectedNext(scheduled, 30),
                        "+15550100",
                        "keep alive",
                    ),
                ),
            )
            verify(armer).armSend(1)
        }

    @Test
    fun `occurrence at send limit ends keepalive without sending`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 3))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            val endedText = context.resources.getQuantityString(R.plurals.schedule_ended_after_sends, 3, 3)
            // The skip and the end land together (finalizeOutcome).
            verify(repository).finalizeOutcome(
                eq(42L),
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
            verify(repository, never()).insertHistory(any())
            // The engine ended the keepalive: the persistent hint is refreshed right away.
            verify(reconciler).syncPersistentHint()
        }

    // The end flip can lose the race (a concurrent run claimed or finalized the row):
    // the end still lands on its own (endScheduleNow), and this run arms nothing.
    @Test
    fun `end condition whose flip is not applied still ends the schedule`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.AFTER_N_SENDS, maxSends = 3, sendCount = 3))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(
                repository.finalizeOutcome(
                    eq(42L),
                    eq(SendOutcome.PENDING),
                    eq(SendOutcome.SKIPPED),
                    isNull(),
                    anyOrNull(),
                    eq(0),
                    isNull(),
                    isNull(),
                    any(),
                ),
            ).thenReturn(false)

            orchestrator.process(1)

            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.SKIPPED),
                isNull(),
                anyOrNull(),
                eq(0),
                isNull(),
                isNull(),
                eq(FinalizeAdvance.End(1)),
            )
            // The flip lost the race: the end lands on its own (the mock answer runs
            // endSchedule, cancelSend and the hint refresh).
            verify(reconciler).endScheduleNow(eq(1), eq(true))
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `early fire past end date ends keepalive instead of re-arming`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now + 3_600_000L
            val endDate = LocalDate.now().minusDays(1)
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(endType = EndType.ON_DATE, endDate = endDate))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository).endSchedule(1)
            verify(repository, never()).saveConfig(any())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).insertHistory(any())
            // The engine ended the keepalive: the persistent hint is refreshed right away.
            verify(reconciler).syncPersistentHint()
        }

    @Test
    fun `temporary failure on first attempt schedules retry`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("No service", permanent = false))

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            // The retryable failure lands as the SENDING->PENDING flip (retryCount + 1).
            verify(repository).finalizeSending(eq(42L), eq(SendOutcome.PENDING), anyLong(), eq("No service"), eq(1), anyLong(), isNull())
            // attempt 1 backoff is 10s capped with +/-10% jitter
            val minRetryAt = now + 9_000
            val maxRetryAt = System.currentTimeMillis() + 11_000
            val retryAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).updateNextSend(eq(1), capture())
                    }.firstValue
            assertTrue(
                "retryAt $retryAt outside [$minRetryAt, $maxRetryAt]",
                retryAt!! in minRetryAt..maxRetryAt,
            )
            verify(repository, never()).saveConfig(any())
            verify(armer).armSend(1)
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `present SIM on a failing send resets the not-present streak`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(prefs.getSimNotPresentFailures(1)).thenReturn(5)
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("No service", permanent = false))

            orchestrator.process(1)

            // The present reading breaks the streak: the "consecutive" auto-disable counter
            // starts fresh from this attempt.
            verify(prefs).setSimNotPresentFailures(eq(1), eq(0))
            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(repository).finalizeSending(eq(42L), eq(SendOutcome.PENDING), anyLong(), eq("No service"), eq(1), anyLong(), isNull())
            verify(repository).updateNextSend(eq(1), anyLong())
            verify(armer).armSend(1)
        }

    @Test
    fun `temporary failure after a disable finalizes the occurrence as skipped`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val enabled = baseConfig(scheduled)
            // Enabled for the pre-radio reads and the claim section's in-lock re-read
            // (under the lock no UI section can interrupt): the disable lands during the
            // radio call. Disabled by the time the in-flight attempt reports its result.
            whenever(repository.getConfig(1))
                .thenReturn(enabled, enabled, enabled, enabled, enabled, enabled.copy(enabled = false))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("No service", permanent = false))

            orchestrator.process(1)

            // The radio call still happened (the attempt was already in flight), but the
            // retry is dropped: the row is finalized as skipped with the disable reason.
            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(repository).finalizeSending(
                eq(42L),
                eq(SendOutcome.SKIPPED),
                anyLong(),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                anyLong(),
                isNull(),
            )
            verify(
                repository,
                never(),
            ).finalizeSending(anyLong(), eq(SendOutcome.PENDING), anyLong(), any(), anyInt(), anyLong(), anyOrNull())
            // No retry is armed: the row stays skipped, nothing is re-queued.
            verify(repository, never()).updateNextSend(anyInt(), any())
            verify(armer, never()).armSend(anyInt())
            verify(repository, never()).insertHistory(any())
        }

    @Test
    fun `retrying row past the retry window fails occurrence without sending`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val firstAttempt = now - AppConfig.MAX_RETRY_PERIOD_MS - 1
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(
                        scheduled,
                        outcome = SendOutcome.PENDING,
                        firstAttemptAtMillis = firstAttempt,
                        retryCount = 5,
                    ),
                )

            orchestrator.process(1)

            // No lastAttemptAtMillis: falls back to the first attempt. retryCount 5 =
            // 4 executed retries + 1 pending: the count is 4. The expiry flip and the
            // advance land together (finalizeOutcome), never the unconditional update.
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.PENDING),
                            eq(SendOutcome.FAILED),
                            eq(firstAttempt),
                            eq(context.getString(R.string.error_retry_period_exceeded)),
                            eq(4),
                            eq(firstAttempt),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            // The schedule must not stall: the next occurrence is armed right away.
            assertTrue((advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis > System.currentTimeMillis())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(prefs).setSimNotPresentFailures(eq(1), eq(0))
        }

    @Test
    fun `retrying row with the minimum retry count expires with a zero count`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            val firstAttempt = now - AppConfig.MAX_RETRY_PERIOD_MS - 1
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(
                        scheduled,
                        outcome = SendOutcome.PENDING,
                        firstAttemptAtMillis = firstAttempt,
                        retryCount = 1,
                    ),
                )

            orchestrator.process(1)

            // A retry row always carries retryCount >= 1: the minimum row's pending retry
            // never runs, so the -1 lands exactly on 0.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.FAILED),
                eq(firstAttempt),
                eq(context.getString(R.string.error_retry_period_exceeded)),
                eq(0),
                eq(firstAttempt),
                isNull(),
                any(),
            )
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
        }

    @Test
    fun `permanent failure fails occurrence and advances`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure("Radio is off", permanent = true))

            orchestrator.process(1)

            // The flip and the advance land together (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.SENDING),
                            eq(SendOutcome.FAILED),
                            anyLong(),
                            eq("Radio is off"),
                            eq(0),
                            anyLong(),
                            isNull(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertTrue((advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis > System.currentTimeMillis())
            verify(armer).armSend(1)
        }

    @Test
    fun `fresh sending row is left alone but the stale check is armed`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(scheduled, outcome = SendOutcome.SENDING, lastAttemptAtMillis = now))

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).saveConfig(any())
            verify(repository, never()).insertHistory(any())
            verify(armer, never()).armSend(anyInt())
            // Bailing on a fresh row must not leave it unowned: a follow-up reconcile is
            // armed for when the attempt goes stale.
            val dueAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(reconciler).armStaleCheck(eq(1), capture())
                    }.firstValue
            assertTrue(
                "stale check due $dueAt outside [now+55s, now+65s]",
                dueAt!! in (now + 55_000)..(System.currentTimeMillis() + 65_000),
            )
        }

    @Test
    fun `stale sending row is skipped silently and schedule advances`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 120_000
            val attempt = now - 120_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(scheduled, outcome = SendOutcome.SENDING, lastAttemptAtMillis = attempt))

            orchestrator.process(1)

            // Resolved through the atomic stale flip (only the winner advances); the
            // advance lands with it (finalizeOutcome).
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(repository).finalizeOutcome(
                            eq(42L),
                            eq(SendOutcome.SENDING),
                            eq(SendOutcome.SKIPPED),
                            eq(attempt),
                            eq(context.getString(R.string.error_send_interrupted)),
                            eq(0),
                            isNull(),
                            anyLong(),
                            capture(),
                        )
                    }.firstValue
            assertTrue(advance is FinalizeAdvance.AdvanceToNext)
            assertTrue((advance as FinalizeAdvance.AdvanceToNext).nextSendAtMillis > now)
            verify(repository, never()).insertHistory(any())
            verify(armer).armSend(1)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            // A stale row is resolved right away: no follow-up check is needed.
            verify(reconciler, never()).armStaleCheck(anyInt(), anyLong())
        }

    // A save that just changed the schedule lands after the top read: the stale-SENDING
    // skip must advance with the fresh config.
    @Test
    fun `fresh config from the hoisted re-read drives the stale sending skip advance`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 120_000
            val attempt = now - 120_000
            val freshRecipient = "+15550101"
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(daysInterval = 5, recipientPhone = freshRecipient),
                )
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(scheduled, outcome = SendOutcome.SENDING, lastAttemptAtMillis = attempt))

            orchestrator.process(1)

            // The advance lands with the flip (finalizeOutcome): the fresh config drives both.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SKIPPED),
                eq(attempt),
                eq(context.getString(R.string.error_send_interrupted)),
                eq(0),
                isNull(),
                anyLong(),
                eq(FinalizeAdvance.AdvanceToNext(1, expectedNext(scheduled, 5), expectedNext(scheduled, 5), freshRecipient, "keep alive")),
            )
            verify(armer).armSend(1)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(reconciler, never()).armStaleCheck(anyInt(), anyLong())
        }

    @Test
    fun `catch up marks old occurrence skipped and re-arms for next one`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 2 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // No active row: the miss gets its guarded SKIPPED record and the re-armed next
            // occurrence its PENDING row — all in one transaction (finalizeGraceSkips).
            verify(
                repository,
            ).finalizeGraceSkips(
                eq(baseConfig(scheduled)),
                isNull(),
                eq(listOf(scheduled)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq(expectedNext(scheduled, 30)),
            )
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).saveConfig(any())

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    // The catch-up re-arm must carry the time-window jitter: the walk returns the clean
    // base, and arming it verbatim would send the first occurrence after a miss exactly on
    // the configured time.
    @Test
    fun `catch-up re-arm lands inside the time window`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 2 * 3_600_000L
            whenever(repository.getConfig(1))
                .thenReturn(baseConfig(scheduled).copy(timeWindowMinutes = 30))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // The re-armed instant is a random point of [base, base + window], not the
            // clean base itself.
            val base = expectedNext(scheduled, 30)
            val reArm =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).finalizeGraceSkips(
                            eq(baseConfig(scheduled).copy(timeWindowMinutes = 30)),
                            isNull(),
                            eq(listOf(scheduled)),
                            eq(context.getString(R.string.error_missed_occurrence)),
                            capture(),
                        )
                    }.firstValue
            assertTrue(
                "re-arm $reArm outside [base, base + 30min]",
                reArm!! in base..(base + 30 * 60_000L),
            )
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    // A save that just changed the schedule lands after the top read: the catch-up must
    // step with the fresh frequency and carry the fresh recipient.
    @Test
    fun `fresh config from the hoisted re-read drives the catch-up`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 2L * 3_600_000L
            val freshRecipient = "+15550101"
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(daysInterval = 5, recipientPhone = freshRecipient),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // The send time steps with the fresh interval; the skip record and the re-arm
            // land with it (finalizeGraceSkips).
            verify(
                repository,
            ).finalizeGraceSkips(
                eq(baseConfig(scheduled).copy(daysInterval = 5, recipientPhone = freshRecipient)),
                isNull(),
                eq(listOf(scheduled)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq(expectedNext(scheduled, 5)),
            )
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    @Test
    fun `past-grace run of a sent-but-unadvanced occurrence skips through the guarded insert and advances`() =
        runBlocking<Unit> {
            // Crash state from pre-fix builds: the row is SENT but the schedule still points
            // at it (the advance never landed) and no open row remains. The run must record
            // the skip through the guarded insert (a no-op over the SENT row in the real
            // DAO) and move on, not re-send.
            val now = System.currentTimeMillis()
            val scheduled = now - 2 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // Over the SENT row the real DAO records nothing, so T is never shown twice.
            verify(
                repository,
            ).finalizeGraceSkips(
                eq(baseConfig(scheduled)),
                isNull(),
                eq(listOf(scheduled)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq(expectedNext(scheduled, 30)),
            )
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    @Test
    fun `grace skip of an occurrence past the grace records the missed reason`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 2 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            // The active row of the skipped occurrence is the anchor: it flips to SKIPPED
            // in place inside the grace-skip finalize.
            verify(
                repository,
            ).finalizeGraceSkips(
                eq(baseConfig(scheduled)),
                eq(activeRow(scheduled)),
                eq(emptyList<Long>()),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq(expectedNext(scheduled, 30)),
            )
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).saveConfig(any())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    @Test
    fun `within-grace occurrence sends late`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // 90s in the past: inside the 15-min grace, so the occurrence is attempted even
            // though the work fired late.
            val scheduled = now - 90_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull()))
                .thenReturn(SmsSendResult.Failure(context.getString(R.string.error_radio_off), permanent = false))

            orchestrator.process(1)

            // The simulated radio-off failure is reached (not consumed as a skip) and lands
            // in a pending retry.
            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(
                repository,
            ).finalizeSending(
                eq(42L),
                eq(SendOutcome.PENDING),
                anyLong(),
                eq(context.getString(R.string.error_radio_off)),
                eq(1),
                anyLong(),
                isNull(),
            )
            verify(
                repository,
                never(),
            ).finalizeOccurrence(anyLong(), eq(SendOutcome.SKIPPED), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository).updateNextSend(eq(1), anyLong())
            verify(armer).armSend(1)
        }

    @Test
    fun `an occurrence past the default grace but within the extended grace sends`() =
        runBlocking<Unit> {
            // The user-configurable late-send grace (the 2-hour preset): a 90-minute-late
            // fire is missed under the 15-min default but attempted here.
            whenever(prefs.lateSendGraceMinutes).thenReturn(120)
            val now = System.currentTimeMillis()
            val scheduled = now - 90 * 60_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // The late fire reaches the send (not consumed as a skip).
            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            verify(
                repository,
                never(),
            ).finalizeGraceSkips(any(), anyOrNull(), any(), any(), anyLong())
            verify(armer).armSend(1)
        }

    @Test
    fun `on-time occurrence of a daily schedule sends`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            // The work fired 5s after the scheduled time (a normal WorkManager delay).
            val scheduled = now - 5_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(repository).claimOccurrence(eq(42L), anyLong(), anyLong())
            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        scheduled,
                        1,
                        expectedNext(scheduled, 30),
                        expectedNext(scheduled, 30),
                        "+15550100",
                        "keep alive",
                    ),
                ),
            )
            verify(repository, never()).insertHistory(any())
            verify(
                repository,
                never(),
            ).finalizeGraceSkips(any(), anyOrNull(), any(), any(), anyLong())
            verify(armer).armSend(1)
        }

    @Test
    fun `success after retries anchors the next occurrence to the original occurrence`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val occurrence = now - 3L * 24 * 3_600_000L
            // nextSendAtMillis was rewritten to the last retry time by the retry path
            val retryAt = occurrence + 2L * 24 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(retryAt))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 2),
                )
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // Anchored to the original occurrence, not to the retry time (no drift)
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(2),
                eq(occurrence),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        occurrence,
                        1,
                        expectedNext(occurrence, 30),
                        expectedNext(occurrence, 30),
                        "+15550100",
                        "keep alive",
                    ),
                ),
            )
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `late retry within the window still sends`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val occurrence = now - 3L * 24 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(occurrence + 1_000))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 1),
                )
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(1),
                eq(occurrence),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        occurrence,
                        1,
                        expectedNext(occurrence, 30),
                        expectedNext(occurrence, 30),
                        "+15550100",
                        "keep alive",
                    ),
                ),
            )
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `late retry beyond the retry window fails the occurrence without sending`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val occurrence = now - 15L * 24 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(now - 60_000))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 3),
                )

            orchestrator.process(1)

            // No lastAttemptAtMillis: falls back to the first attempt. retryCount 3 =
            // 2 executed retries + 1 pending: the count is 2. The flip and the advance land
            // together (finalizeOutcome), advancing to the next occurrence after the original.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.FAILED),
                eq(occurrence),
                eq(context.getString(R.string.error_retry_period_exceeded)),
                eq(2),
                eq(occurrence),
                isNull(),
                eq(FinalizeAdvance.AdvanceToNext(1, expectedNext(occurrence, 30), expectedNext(occurrence, 30), "+15550100", "keep alive")),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    @Test
    fun `late retry beyond the retry window keeps the last attempt time`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val occurrence = now - 15L * 24 * 3_600_000L
            val lastAttempt = occurrence + 13L * 24 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(now - 60_000))
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(
                        occurrence,
                        outcome = SendOutcome.PENDING,
                        firstAttemptAtMillis = occurrence,
                        retryCount = 3,
                        lastAttemptAtMillis = lastAttempt,
                    ),
                )

            orchestrator.process(1)

            // retryCount 3 = 2 executed retries + 1 pending (never runs): the count is 2.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.FAILED),
                eq(lastAttempt),
                eq(context.getString(R.string.error_retry_period_exceeded)),
                eq(2),
                eq(occurrence),
                isNull(),
                any(),
            )
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
        }

    // A save that just changed the schedule lands after the top read: the expiry advance
    // must use the fresh config, or the next occurrence and pending row carry the pre-save values.
    @Test
    fun `fresh config from the hoisted re-read drives the retry-window expiry advance`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val occurrence = now - 15L * 24 * 3_600_000L
            val freshRecipient = "+15550101"
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(occurrence + 1_000),
                    baseConfig(occurrence + 1_000).copy(daysInterval = 5, recipientPhone = freshRecipient),
                )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(occurrence, outcome = SendOutcome.PENDING, firstAttemptAtMillis = occurrence, retryCount = 3),
                )

            orchestrator.process(1)

            // retryCount 3 = 2 executed retries + 1 pending: the count is 2. The advance
            // lands with the flip (finalizeOutcome) and carries the fresh schedule.
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.PENDING),
                eq(SendOutcome.FAILED),
                eq(occurrence),
                eq(context.getString(R.string.error_retry_period_exceeded)),
                eq(2),
                eq(occurrence),
                isNull(),
                eq(
                    FinalizeAdvance.AdvanceToNext(
                        1,
                        expectedNext(occurrence, 5),
                        expectedNext(occurrence, 5),
                        freshRecipient,
                        "keep alive",
                    ),
                ),
            )
            verify(armer).armSend(1)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
        }

    @Test
    fun `disabled at the hoisted re-read routes through the reconciler's disabled cleanup`() =
        runBlocking<Unit> {
            // The same cleanup as the top-level path (cancel the work, resolve the open
            // row, clear the next send) — the engine itself writes nothing.
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(enabled = false),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            // The user's OFF must stand: no full-row write, no targeted re-arm, no new row.
            verify(repository, never()).saveConfig(any())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(armer, never()).cancelSend(anyInt())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disable landing after the hoisted re-read is caught by the pre-flight before the radio call`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            // The disable lands after the late-run in-lock re-read: the pre-flight re-read
            // of the claim section still sees it.
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled),
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(enabled = false),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            // The pre-flight re-read still guards the send: the row is skipped with the
            // disable reason, nothing re-arms.
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository).finalizeOccurrence(
                eq(42L),
                eq(SendOutcome.SKIPPED),
                isNull(),
                eq(context.getString(R.string.error_disabled_before_send)),
                eq(0),
                isNull(),
            )
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(armer).cancelSend(1)
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disabled at the hoisted re-read routes the open retrying row through the reconciler`() =
        runBlocking<Unit> {
            // The open retry is the reconciler's to close: the engine writes nothing itself.
            val now = System.currentTimeMillis()
            val occurrence = now - 2L * 3_600_000L
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(occurrence + 1_000),
                    baseConfig(occurrence + 1_000).copy(enabled = false),
                )
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(
                        occurrence,
                        outcome = SendOutcome.PENDING,
                        firstAttemptAtMillis = occurrence,
                        retryCount = 2,
                    ),
                )

            orchestrator.process(1)

            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            // Nothing advances for a disabled SIM: the re-enable recomputes from the last send.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).endSchedule(anyInt())
            verify(repository, never()).insertHistory(any())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer, never()).cancelSend(anyInt())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `disabled at the hoisted re-read leaves the sending row to the reconciler`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 120_000
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(enabled = false),
                )
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(scheduled, outcome = SendOutcome.SENDING, lastAttemptAtMillis = scheduled))

            orchestrator.process(1)

            // No write to the SENDING row: it has no live owner, and the reconciler's
            // disabled branch resolves it (a SENDING row needs the direct update, not finalizeOccurrence).
            verify(reconciler).reconcileSim(1)
            verifyNoMoreInteractions(reconciler)
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateHistory(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer, never()).cancelSend(anyInt())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `save dropping the next send after the top read bails without engine decisions`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(null),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))

            orchestrator.process(1)

            // The recompute after a dropped next send is owned by the reconciler: the engine
            // decisions must not run on the stale anchor.
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(repository, never()).endSchedule(anyInt())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())
        }

    @Test
    fun `config deleted after the top read bails silently at the hoisted re-read`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled), null)
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(armer, never()).cancelSend(anyInt())
        }

    @Test
    fun `recipient changed mid-flight is used for the send and not reverted`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            val freshRecipient = "+15550102"
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled).copy(recipientPhone = freshRecipient),
                )
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq(freshRecipient), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            verify(sender).send(eq(1), eq(freshRecipient), eq("keep alive"), anyOrNull())
            val advance =
                argumentCaptor<FinalizeAdvance>()
                    .apply {
                        verify(
                            repository,
                        ).finalizeOutcome(
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
                    }.firstValue
            // The success record carries the fresh recipient from the in-lock config.
            assertTrue(advance is FinalizeAdvance.Success)
            assertEquals(freshRecipient, (advance as FinalizeAdvance.Success).recipient)
            // The success path must not full-row-upsert: the stale top-read recipient can
            // never clobber the user's just-saved value.
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `concurrent runs claim the occurrence only once`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            // First run claims the occurrence, the concurrent loser must back off.
            whenever(repository.claimOccurrence(anyLong(), anyLong(), anyLong())).thenReturn(1, 0)
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            val first = async { orchestrator.process(1) }
            val second = async { orchestrator.process(1) }
            first.await()
            second.await()

            verify(sender, times(1)).send(any(), any(), any(), anyOrNull())
            verify(
                repository,
                times(1),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
        }

    // The owner must arm the stale check itself, before the radio call: a process that dies
    // mid-send arms nothing, and the orphaned SENDING row would wait for the next unrelated
    // trigger (12 h sweep, launch, boot).
    @Test
    fun `claiming the occurrence arms the stale check before the radio call`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 5_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // Due the moment the attempt goes stale (the claim wrote lastAttemptAtMillis = now).
            val dueAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(reconciler).armStaleCheck(eq(1), capture())
                    }.firstValue
            assertTrue(
                "stale check due $dueAt outside [now+55s, now+65s]",
                dueAt!! in (now + 55_000)..(System.currentTimeMillis() + 65_000),
            )
            // A death during the send must find the check already queued, not a row with no owner.
            val order = inOrder(reconciler, sender)
            order.verify(reconciler).armStaleCheck(anyInt(), anyLong())
            order.verify(sender).send(any(), any(), any(), anyOrNull())
        }

    @Test
    fun `a run that loses the claim does not arm the stale check`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            // The other run owns the claim: this one must back off without touching the owner's stale check.
            whenever(repository.claimOccurrence(anyLong(), anyLong(), anyLong())).thenReturn(0)

            orchestrator.process(1)

            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(reconciler, never()).armStaleCheck(anyInt(), anyLong())
            verify(armer, never()).armSend(anyInt())
        }

    @Test
    fun `stale run backs off when a concurrent run already finalized the occurrence`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            // The pre-lock reads see the schedule still pointing at the occurrence; the
            // in-lock re-read sees the terminal row at T and the next send already moved past it.
            whenever(repository.getConfig(1))
                .thenReturn(
                    baseConfig(scheduled),
                    baseConfig(scheduled),
                    baseConfig(scheduled),
                    baseConfig(expectedNext(scheduled, 30)),
                )
            // Pre-lock read sees the open row; the in-lock and post-align re-reads see only
            // the next occurrence's row, which takeIf filters to the back-off.
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(scheduled))
                .thenReturn(activeRow(expectedNext(scheduled, 30)))
                .thenReturn(activeRow(expectedNext(scheduled, 30)))

            orchestrator.process(1)

            // The consumed occurrence must not be re-created and re-sent: the winner owns the future.
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(
                repository,
                never(),
            ).finalizeOutcome(anyLong(), any(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull(), anyOrNull(), any())
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(repository, never()).endSchedule(anyInt())
            verifyNoInteractions(armer)
        }

    @Test
    fun `legacy crash state within the grace backs off without re-sending or advancing`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            // No open row: the real DAO's base guard refused the align insert over the terminal row.
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // The align is the only schedule touch; the terminal row at T stays the only
            // record. The test's armed instant is not the configured 12:00, so the align
            // carries the exact pre-jitter base.
            verify(repository).alignPendingRow(eq(1), eq(scheduled), eq(expectedNext(scheduled, 0)), eq("+15550100"), eq("keep alive"))
            verify(repository, never()).updateNextSend(anyInt(), anyLong())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).endSchedule(anyInt())
            verifyNoInteractions(armer)
        }

    // The same crash state past the grace: the catch-up consumes the missed anchor through
    // the guarded skip insert (blocked by the terminal row in the real DAO, so no second
    // record for T) and re-arms — never a re-send.
    @Test
    fun `crash state past the grace advances without a duplicate record and without a re-send`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val scheduled = now - 2 * 3_600_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            orchestrator.process(1)

            // Over the terminal row the real DAO records nothing, so T is never shown twice.
            verify(
                repository,
            ).finalizeGraceSkips(
                eq(baseConfig(scheduled)),
                isNull(),
                eq(listOf(scheduled)),
                eq(context.getString(R.string.error_missed_occurrence)),
                eq(expectedNext(scheduled, 30)),
            )
            verify(repository, never()).insertSkippedIfNotTerminal(anyInt(), anyLong(), anyLong(), any(), any(), any())
            verify(repository, never()).insertHistory(any())
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(repository, never()).claimOccurrence(anyLong(), anyLong(), anyLong())
            // The schedule advances inside the transaction, no split advance.
            verify(repository, never()).updateNextSend(anyInt(), anyOrNull())
            verify(repository, never()).alignPendingRow(anyInt(), anyLong(), anyLong(), any(), any())
            verify(sender, never()).send(any(), any(), any(), anyOrNull())
            verify(armer).armSend(1)
            verify(armer, never()).cancelSend(anyInt())
            verify(repository, never()).endSchedule(anyInt())
            verify(reconciler, never()).syncPersistentHint()
        }

    @Test
    fun `missing row at fire time inserts and sends when nothing was finalized`() =
        runBlocking<Unit> {
            val scheduled = System.currentTimeMillis() - 60_000
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            // Top and in-lock reads see no row; the post-align re-read sees the row the align created.
            whenever(repository.getActiveHistory(1))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // No terminal row: the row is created and claimed as usual. The test's armed
            // instant is not the configured 12:00, so the row carries the exact pre-jitter base.
            verify(sender).send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())
            verify(repository).alignPendingRow(eq(1), eq(scheduled), eq(expectedNext(scheduled, 0)), eq("+15550100"), eq("keep alive"))
            verify(repository).claimOccurrence(anyLong(), anyLong(), anyLong())
            verify(
                repository,
            ).finalizeOutcome(
                eq(42L),
                eq(SendOutcome.SENDING),
                eq(SendOutcome.SENT),
                anyLong(),
                isNull(),
                eq(0),
                anyLong(),
                isNull(),
                eq(
                    FinalizeAdvance.Success(
                        1,
                        scheduled,
                        1,
                        expectedNext(scheduled, 30),
                        expectedNext(scheduled, 30),
                        "+15550100",
                        "keep alive",
                    ),
                ),
            )
            verify(repository, never()).saveConfig(any())
        }

    @Test
    fun `stale sending threshold stays above the worst-case live attempt`() {
        // A live attempt (radio timeout + display hold) must finish well before the
        // stale-SENDING skip, or a live send would be marked "Send interrupted".
        assertTrue(
            AppConfig.SEND_TIMEOUT_MS + AppConfig.DEFAULT_SENDING_HOLD_MS < AppConfig.SENDING_STALE_MS,
        )
    }

    @Test
    fun `a crash on an unclaimed row is routed into the retry engine`() =
        runBlocking<Unit> {
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(System.currentTimeMillis() - 60_000))
            val t0 = System.currentTimeMillis()

            val recovered = orchestrator.recoverFromException(1)

            assertTrue(recovered)
            val reason = context.getString(R.string.error_send_failed)
            val lastAttempt =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository)
                            .finalizeOccurrence(eq(42L), eq(SendOutcome.PENDING), capture(), eq(reason), eq(1), capture())
                    }.firstValue
            // A fresh row has no first attempt: both anchors are the crash time.
            val retryAt =
                argumentCaptor<Long>()
                    .apply { verify(repository).updateNextSend(eq(1), capture()) }
                    .firstValue
            assertTrue(
                "retry $retryAt outside [t0+9s, t1+11s] (retry #1 backoff 10s +/- 10%)",
                retryAt!! in (t0 + 9_000)..(System.currentTimeMillis() + 11_000),
            )
            verify(armer).armSend(1)
            verify(armer, never()).cancelSend(anyInt())
            verifyNoInteractions(reconciler)
            assertNotNull(lastAttempt)
        }

    @Test
    fun `a crash on an in-flight row arms the stale check and never re-sends`() =
        runBlocking<Unit> {
            val attemptAt = System.currentTimeMillis() - 5_000
            whenever(repository.getActiveHistory(1))
                .thenReturn(activeRow(System.currentTimeMillis() - 60_000, SendOutcome.SENDING, lastAttemptAtMillis = attemptAt))

            val recovered = orchestrator.recoverFromException(1)

            assertTrue(recovered)
            verify(reconciler).armStaleCheck(1, attemptAt + AppConfig.SENDING_STALE_MS)
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(reconciler, never()).reconcileSim(anyInt())
        }

    @Test
    fun `a crash on a retrying row counts the attempt and backs off further`() =
        runBlocking<Unit> {
            val first = System.currentTimeMillis() - 3_600_000
            whenever(repository.getActiveHistory(1))
                .thenReturn(
                    activeRow(
                        System.currentTimeMillis() - 60_000,
                        SendOutcome.PENDING,
                        firstAttemptAtMillis = first,
                        retryCount = 1,
                        lastAttemptAtMillis = System.currentTimeMillis() - 30_000,
                    ),
                )
            val t0 = System.currentTimeMillis()

            val recovered = orchestrator.recoverFromException(1)

            assertTrue(recovered)
            val reason = context.getString(R.string.error_send_failed)
            verify(repository).finalizeOccurrence(eq(42L), eq(SendOutcome.PENDING), anyLong(), eq(reason), eq(2), eq(first))
            val retryAt =
                argumentCaptor<Long>()
                    .apply { verify(repository).updateNextSend(eq(1), capture()) }
                    .firstValue
            assertTrue(
                "retry $retryAt outside [t0+18s, t1+22s] (retry #2 backoff 20s +/- 10%)",
                retryAt!! in (t0 + 18_000)..(System.currentTimeMillis() + 22_000),
            )
            verify(armer).armSend(1)
            verifyNoInteractions(reconciler)
        }

    @Test
    fun `a crash with no open row defers to the reconciler`() =
        runBlocking<Unit> {
            whenever(repository.getActiveHistory(1)).thenReturn(null)

            val recovered = orchestrator.recoverFromException(1)

            assertTrue(recovered)
            verify(reconciler).reconcileSim(1)
            verify(repository, never()).finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())
            verify(armer, never()).armSend(anyInt())
            verify(reconciler, never()).armStaleCheck(anyInt(), anyLong())
        }

    @Test
    fun `a crash whose recovery hits a broken database leaves the work to fail`() =
        runBlocking<Unit> {
            whenever(repository.getActiveHistory(1)).thenThrow(RuntimeException("db down"))

            val recovered = orchestrator.recoverFromException(1)

            assertFalse(recovered)
            verify(armer, never()).armSend(anyInt())
            verify(reconciler, never()).reconcileSim(anyInt())
            verify(reconciler, never()).armStaleCheck(anyInt(), anyLong())
        }

    @Test
    fun `a crash on a row a concurrent run owns defers to the reconciler`() =
        runBlocking<Unit> {
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(System.currentTimeMillis() - 60_000))
            whenever(repository.finalizeOccurrence(anyLong(), any(), anyOrNull(), anyOrNull(), anyInt(), anyOrNull())).thenReturn(0)

            val recovered = orchestrator.recoverFromException(1)

            assertTrue(recovered)
            verify(reconciler).reconcileSim(1)
            verify(armer, never()).armSend(1)
        }

    // Claim timestamps and the stale-check due must use a fresh clock read at claim time:
    // with the stale `now` from the top of process(), a long pre-claim stall makes the stale
    // check fire early, potentially misrecording a delivered SMS as "send interrupted".
    @Test
    fun `claim timestamp and stale-check due use fresh clock at claim time not stale now from process top`() =
        runBlocking<Unit> {
            // Simulate pre-claim skew: the scheduled time is far in the past, so `now`
            // captured at the top of process() is artificially old.
            val staleNow = System.currentTimeMillis() - 30_000L // 30 s "stale"
            val scheduled = staleNow - 60_000L
            whenever(repository.getConfig(1)).thenReturn(baseConfig(scheduled))
            whenever(repository.getActiveHistory(1)).thenReturn(activeRow(scheduled))
            whenever(sender.send(eq(1), eq("+15550100"), eq("keep alive"), anyOrNull())).thenReturn(SmsSendResult.Success)

            orchestrator.process(1)

            // The claim's lastAttemptAtMillis must be fresh, not the stale `now` from the top of process().
            val claimTimestamp =
                argumentCaptor<Long>()
                    .apply {
                        verify(repository).claimOccurrence(eq(42L), capture(), anyLong())
                    }.firstValue
            val realNow = System.currentTimeMillis()
            assertTrue(
                "claim timestamp $claimTimestamp is stale (>5s old), expected close to $realNow",
                claimTimestamp!! > realNow - 5_000L,
            )

            // The stale-check due must be claim time + SENDING_STALE_MS, not stale now + SENDING_STALE_MS.
            val dueAt =
                argumentCaptor<Long>()
                    .apply {
                        verify(reconciler).armStaleCheck(eq(1), capture())
                    }.firstValue
            assertTrue(
                "stale check due $dueAt is based on stale now (too early), expected >${realNow + AppConfig.SENDING_STALE_MS - 5_000L}",
                dueAt!! > realNow + AppConfig.SENDING_STALE_MS - 5_000L,
            )
        }

    private val shadowNotificationManager: ShadowNotificationManager
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private companion object {
        // The one-shot auto-disable alert id (KeepaliveNotification.AUTO_DISABLED_NOTIFICATION_ID).
        const val AUTO_DISABLED_NOTIFICATION_ID = 2
    }
}
