package app.kotowski.keepsimalive.ui.simsettings

import android.Manifest
import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.height
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.util.SimPhoneStateData
import app.kotowski.keepsimalive.util.TimeWindowSteps
import app.kotowski.keepsimalive.util.scheduleSentence
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneId

// Renders the whole screen with a real VM and mocked data layer; the phone state comes
// from the shadowed subscription (id 11), so the screen loads without a device.
@RunWith(RobolectricTestRunner::class)
class SimDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext = RuntimeEnvironment.getApplication()
    private val repository: KeepaliveRepository = mock()
    private val prefs: AppPrefs = mock()

    // Held as fields: the off-schedule tests verify the disarm and the restore the
    // ViewModel funnels run against them.
    private val scheduleReconciler: ScheduleReconciler = mock()
    private val armer: ScheduleArmer = mock()

    init {
        // An unstubbed 0 would skip every past occurrence in the VM's catch-up.
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
    }

    private val configFlow = MutableStateFlow<List<SimKeepaliveConfig>>(emptyList())
    private val historyFlow = MutableStateFlow<List<SendHistoryEntity>>(emptyList())
    private lateinit var viewModel: SimDetailViewModel

    @Before
    fun setup() {
        whenever(repository.observeConfigs()).thenReturn(configFlow)
        whenever(repository.observeNewest(anyInt(), anyInt())).thenReturn(historyFlow)
        whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(0))
        shadowOf(appContext).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = appContext.getSystemService(android.telephony.TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(11, tm)
        shadowOf(tm).setSimOperatorName("Vodafone")
        shadowOf(appContext.getSystemService(android.telephony.SubscriptionManager::class.java)).apply {
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
        viewModel =
            SimDetailViewModel(
                appContext,
                repository,
                scheduleReconciler,
                prefs,
                armer,
                SimSendLock(),
                OffSchedulePendingRegistry(),
            )
    }

    private fun render(enabled: Boolean) {
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = enabled,
                recipientPhone = "+15550100",
                message = "keep alive",
            ),
        )
    }

    private fun renderConfig(
        config: SimKeepaliveConfig,
        onNavigateToHistory: () -> Unit = {},
    ) {
        configFlow.value = listOf(config)
        wheneverBlocking { repository.getConfig(config.simId) }.thenReturn(config)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = viewModel,
                    simId = config.simId,
                    onNavigateBack = {},
                    onNavigateToHistory = onNavigateToHistory,
                )
            }
        }
    }

    // The screen is a scrollable column and the test viewport is small: wait for the card,
    // then scroll it into view before asserting visibility.
    private fun assertSettingsCardDisplayed() {
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Settings", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Settings", false))
        val settings = composeTestRule.onNodeWithText("Settings", useUnmergedTree = true)
        settings.assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
    }

    @Test
    fun settingsCardShownWhileKeepaliveDisabled() {
        render(enabled = false)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithText("Keep Alive", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun settingsCardShownWhileKeepaliveEnabled() {
        render(enabled = true)
        assertSettingsCardDisplayed()
    }

    // Native graphics: the legacy mode does not measure text widths, so the clip cannot be
    // observed; the one-line reference Text gives the line height.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun policyRowLaysOutTheWholeSentenceForTheLongestSchedule() {
        val config =
            SimKeepaliveConfig(
                simId = 11,
                enabled = false,
                recipientPhone = "+15550100",
                message = "keep alive",
                freqType = FrequencyType.SELECTED_MONTHS,
                dayOfMonth = 1,
                selectedMonths = (1..12).toSet(),
                hour = 21,
                minute = 0,
                timeWindowMinutes = 10_080,
                endType = EndType.AFTER_N_SENDS,
                maxSends = 999,
            )
        configFlow.value = listOf(config)
        wheneverBlocking { repository.getConfig(config.simId) }.thenReturn(config)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box {
                    SimDetailScreen(
                        viewModel = viewModel,
                        simId = config.simId,
                        onNavigateBack = {},
                        onNavigateToHistory = {},
                    )
                    Text(
                        text = "x",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
        val sentence = scheduleSentence(appContext, config)
        // The card is below the small viewport: scroll it into view, otherwise the
        // root-clipped bounds collapse to zero height.
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(sentence, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(sentence))
        val policy = composeTestRule.onNodeWithText(sentence, useUnmergedTree = true)
        val reference = composeTestRule.onNodeWithText("x", useUnmergedTree = true)
        val policyHeight = policy.getBoundsInRoot().height
        val lineHeight = reference.getBoundsInRoot().height
        assertTrue(
            "policy row must wrap past the old five-line cap: $policyHeight vs line height $lineHeight",
            policyHeight > lineHeight * 5,
        )
    }

    // The history entry is a tonal button (navigation, not an action), so it does not
    // compete with the card's Send, the screen's one primary.
    private fun scrollToHistoryButton() {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("History", substring = true))
    }

    @Test
    fun historyButtonShowsTheExactCount() {
        historyFlow.value =
            listOf(
                SendHistoryEntity(
                    simId = 11,
                    scheduledForMillis = 3_600_000L,
                    occurrenceBaseMillis = 3_600_000L,
                    lastAttemptAtMillis = 3_600_000L,
                    outcome = SendOutcome.SENT.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
                // A live (non-terminal) row: the label counts finished sends only.
                SendHistoryEntity(
                    simId = 11,
                    scheduledForMillis = 7_200_000L,
                    occurrenceBaseMillis = 7_200_000L,
                    outcome = SendOutcome.PENDING.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
        whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(1))
        render(enabled = false)
        assertSettingsCardDisplayed()
        scrollToHistoryButton()
        composeTestRule.onNodeWithText("History (1)", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun historyButtonOpensTheHistoryScreen() {
        var navigated = false
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = false,
                recipientPhone = "+15550100",
                message = "keep alive",
            ),
            onNavigateToHistory = { navigated = true },
        )
        assertSettingsCardDisplayed()
        scrollToHistoryButton()
        composeTestRule.onNodeWithText("History (0)", useUnmergedTree = true).performClick()
        assertTrue(navigated)
    }

    @Test
    fun historyEntryIsAClickableChevronRowWithTheCount() {
        render(enabled = false)
        assertSettingsCardDisplayed()
        scrollToHistoryButton()
        composeTestRule
            .onNode(hasTestTag("history_button") and hasText("History (0)"))
            .assertHasClickAction()
            .assertIsDisplayed()
    }

    // The send slot is engine-owned to the attempt's result write: the button would be a
    // dead-end tap.
    @Test
    fun sendOffScheduleButtonHiddenWhileAnAttemptIsInFlight() {
        val now = System.currentTimeMillis()
        historyFlow.value =
            listOf(
                SendHistoryEntity(
                    simId = 11,
                    scheduledForMillis = now,
                    occurrenceBaseMillis = now,
                    outcome = SendOutcome.SENDING.name,
                    lastAttemptAtMillis = now,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
        render(enabled = true)
        assertSettingsCardDisplayed()
        // The status row is below the small viewport: scroll it into view before asserting.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Trying now", false))
        composeTestRule.onNodeWithText("Trying now", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).assertDoesNotExist()
    }

    // The button sits at the bottom of the SIM info card, below the small viewport:
    // scroll it into view first.
    private fun scrollToOffScheduleButton() {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Send off schedule", false))
    }

    @Test
    fun sendOffScheduleButtonShownWhileKeepaliveOn() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        scrollToOffScheduleButton()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sendOffScheduleButtonHiddenWhileKeepaliveDisabled() {
        render(enabled = false)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun sendOffScheduleButtonHiddenForAnEndedSchedule() {
        renderConfig(endedConfig())
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun offScheduleConfirmDialogCancelArmsNothing() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        scrollToOffScheduleButton()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).performClick()
        composeTestRule
            .onNodeWithText("Send off schedule?", useUnmergedTree = true)
            .assertIsDisplayed()
        // The delay is formatted from the config constant, never hardcoded in the UI.
        val delayText = DateUtil.formatDurationMillis(appContext, AppConfig.OFF_SCHEDULE_SEND_DELAY_MS)
        composeTestRule
            .onNodeWithText(
                appContext.getString(R.string.sim_send_off_schedule_confirm_detail, delayText),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("Send off schedule?", useUnmergedTree = true)
            .assertDoesNotExist()
        verifyBlocking(repository, never()) { updateNextSend(any(), any()) }
        verifyBlocking(armer, never()) { armSend(anyInt()) }
        assertNull(viewModel.state.value.offScheduleSendPendingAtMillis)
    }

    @Test
    fun offScheduleConfirmArmsTheDelayedSendAndCancelRestoresTheSchedule() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
        scrollToOffScheduleButton()
        composeTestRule.onNodeWithText("Send off schedule", useUnmergedTree = true).performClick()
        // The dialog's confirm shares the card's label: the dialog is composed last, so
        // the last node is the confirm.
        composeTestRule
            .onAllNodesWithText("Send off schedule", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.waitUntil(10_000) { viewModel.state.value.offScheduleSendPendingAtMillis != null }
        val t = requireNotNull(viewModel.state.value.offScheduleSendPendingAtMillis)
        verifyBlocking(repository) { updateNextSend(eq(11), eq(t)) }
        verifyBlocking(repository) { alignPendingRow(eq(11), eq(t), eq(t), eq("+15550100"), eq("keep alive")) }
        verifyBlocking(armer) { armSend(11) }
        composeTestRule
            .onNodeWithText("Send off schedule?", useUnmergedTree = true)
            .assertDoesNotExist()
        wheneverBlocking { repository.getActiveHistory(11) }
            .thenReturn(
                SendHistoryEntity(
                    id = 5,
                    simId = 11,
                    scheduledForMillis = t,
                    occurrenceBaseMillis = t,
                    outcome = SendOutcome.PENDING.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Cancel", false))
        composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        verifyBlocking(repository) {
            finalizeOccurrence(5L, SendOutcome.SKIPPED, null, appContext.getString(R.string.error_send_cancelled), 0, null)
        }
        verify(armer).cancelSend(11)
        verifyBlocking(repository) { updateNextSend(eq(11), eq(null)) }
        verifyBlocking(scheduleReconciler) { reconcileSim(11) }
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Send off schedule", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertNull(viewModel.state.value.offScheduleSendPendingAtMillis)
    }

    // A real SIM (id 1) not in the active list but with a cached identity: the missing
    // view (banner + last known info) must render instead of an error.
    private fun renderMissingSim(onNavigateBack: () -> Unit = {}) {
        shadowOf(appContext).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        SimIdentityCache.save(
            appContext,
            CachedSimIdentity(
                simId = 1,
                displayName = "Vodafone",
                carrierName = "Vodafone",
                slotIndex = 1,
                networkOperatorName = "AIS",
                simOperatorName = "Vodafone",
                isNetworkRoaming = false,
                lastSeenAtMillis = System.currentTimeMillis() - 86_400_000L,
            ),
        )
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = viewModel,
                    simId = 1,
                    onNavigateBack = onNavigateBack,
                    onNavigateToHistory = {},
                )
            }
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("SIM disabled or unplugged", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun missingSimShowsTheBannerAboveTheSimInfoCard() {
        renderMissingSim()
        composeTestRule.onNodeWithText("SIM disabled or unplugged", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Showing last known details, not live data.", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Last seen", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Forget", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("forget_button", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Forget this SIM", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("SIM info", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Operator", useUnmergedTree = true).assertIsDisplayed()
        // The banner's title precedes the card's title in the unmerged tree (a banner
        // inside the card would not).
        val nodes =
            composeTestRule
                .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
        val index =
            { text: String ->
                nodes.indexOfFirst {
                    it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { t -> t.text == text }
                }
            }
        assertTrue(index("SIM disabled or unplugged") in 0 until index("SIM info"))
    }

    @Test
    fun permissionMissingRendersTheErrorTextInsteadOfANeverEndingSpinner() {
        shadowOf(appContext).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        viewModel.loadSimData(appContext, 1)
        val errorText = appContext.getString(R.string.sim_detail_permission_required)
        assertEquals(errorText, viewModel.state.value.error)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = viewModel,
                    simId = 1,
                    onNavigateBack = {},
                    onNavigateToHistory = {},
                )
            }
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(errorText, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(errorText, useUnmergedTree = true).assertIsDisplayed()
        // The error icon is composed only by the error branch: its presence proves the
        // error state rendered.
        composeTestRule
            .onNodeWithContentDescription(appContext.getString(R.string.sim_detail_error_icon), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun firstFrameWithoutDataOrErrorRendersTheSpinner() {
        val frozen = mock<SimDetailViewModel>()
        whenever(frozen.state).thenReturn(MutableStateFlow(SimDetailUiState()))
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = frozen,
                    simId = 1,
                    onNavigateBack = {},
                    onNavigateToHistory = {},
                )
            }
        }
        // Wait for the top bar (rendered in every branch), then prove no content and no
        // error is composed.
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("SIM details", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            composeTestRule
                .onAllNodesWithText("SIM info", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            composeTestRule
                .onAllNodesWithText("Settings", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    // A delay-first order leaves a 5 s dead zone after every resume, during which a
    // re-granted permission or a re-inserted SIM is not noticed.
    @Test
    fun firstRefreshPassRunsImmediatelyNotAfterTheFiveSecondDelay() {
        val frozen = mock<SimDetailViewModel>()
        whenever(frozen.state).thenReturn(
            MutableStateFlow(
                SimDetailUiState(
                    data =
                        SimPhoneStateData(
                            simId = 11,
                            displayName = "Vodafone",
                            carrierName = "Vodafone",
                            slotIndex = 2,
                            networkOperatorName = "AIS",
                            simOperatorName = "Vodafone",
                            isNetworkRoaming = false,
                        ),
                ),
            ),
        )
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = frozen,
                    simId = 11,
                    onNavigateBack = {},
                    onNavigateToHistory = {},
                )
            }
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("SIM info", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // No test-clock time has passed: any call here proves the first pass ran immediately.
        // editing=false: the editor is closed, so the refresh reports no open draft.
        verify(frozen, atLeastOnce()).refreshSimData(11, false)
    }

    @Test
    fun forgettingTheSimFromTheMissingSimBannerConfirmsDeletesAndNavigatesBack() {
        var navigatedBack = false
        renderMissingSim(onNavigateBack = { navigatedBack = true })

        composeTestRule.onNodeWithText("Forget", useUnmergedTree = true).performClick()
        composeTestRule
            .onNodeWithText("Forget this SIM?", useUnmergedTree = true)
            .assertIsDisplayed()
        val forgetDetail =
            composeTestRule.onNodeWithText(
                "Its settings and history will be deleted, and it will be removed from the dashboard. If reinserted, it will be treated as a new SIM.",
                useUnmergedTree = true,
            )
        forgetDetail.assertIsDisplayed()
        // The banner has its own "Forget" button; the dialog is composed last, so the
        // last node is the confirm.
        composeTestRule
            .onAllNodesWithText("Forget", useUnmergedTree = true)
            .onLast()
            .performClick()

        // The screen leaves only once the forget has committed (the justForgot signal).
        composeTestRule.waitUntil(10_000) { navigatedBack }
        assertTrue(navigatedBack)
        verifyBlocking(repository) { deleteSim(1) }
    }

    // A navigation earlier would cancel the forget's coroutine and the delete would never run.
    @Test
    fun confirmingForgetDoesNotNavigateWhileTheDeleteIsInFlight() {
        var navigatedBack = false
        val gate = CompletableDeferred<Unit>()
        renderMissingSim(onNavigateBack = { navigatedBack = true })
        wheneverBlocking { repository.deleteSim(1) }
            .doSuspendableAnswer { gate.await() }

        composeTestRule.onNodeWithText("Forget", useUnmergedTree = true).performClick()
        composeTestRule
            .onAllNodesWithText("Forget", useUnmergedTree = true)
            .onLast()
            .performClick()

        // The dialog closed, the delete is still in flight: the screen must stay...
        composeTestRule.waitForIdle()
        assertFalse(navigatedBack)
        composeTestRule
            .onNodeWithText("Forget this SIM?", useUnmergedTree = true)
            .assertDoesNotExist()
        // ...and leave only once the delete commits.
        gate.complete(Unit)
        composeTestRule.waitUntil(10_000) { navigatedBack }
        assertTrue(navigatedBack)
        verifyBlocking(repository) { deleteSim(1) }
    }

    // A refused forget (a fresh send in flight) toasts and keeps the user on the screen:
    // no delete, no navigation — the old contract kicked the user to the dashboard with
    // the SIM still there.
    @Test
    fun aRefusedForgetKeepsTheUserOnTheScreenWithoutNavigating() {
        var navigatedBack = false
        renderMissingSim(onNavigateBack = { navigatedBack = true })
        val now = System.currentTimeMillis()
        wheneverBlocking { repository.getActiveHistory(1) }
            .thenReturn(
                SendHistoryEntity(
                    simId = 1,
                    scheduledForMillis = now,
                    occurrenceBaseMillis = now,
                    lastAttemptAtMillis = now,
                    outcome = SendOutcome.SENDING.name,
                    recipient = "+15550100",
                    message = "keep alive",
                ),
            )
        composeTestRule.onNodeWithText("Forget", useUnmergedTree = true).performClick()
        composeTestRule
            .onAllNodesWithText("Forget", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.waitForIdle()
        // Refused: the toast explains, the screen stays with its Forget banner, no delete.
        assertFalse(navigatedBack)
        assertFalse(viewModel.state.value.justForgot)
        verifyBlocking(repository, never()) { deleteSim(1) }
        assertEquals(appContext.getString(R.string.error_send_in_progress), ShadowToast.getTextOfLatestToast())
        composeTestRule.onNodeWithText("Forget", useUnmergedTree = true).assertIsDisplayed()
    }

    // Journey: Settings card -> Delete -> dialog -> Cancel: nothing is deleted.
    @Test
    fun deleteFromTheSettingsCardCancelKeepsTheConfig() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule
            .onNodeWithText("Delete this SIM's schedule?", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The schedule will be deleted and the send count reset. History is kept.", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        verifyBlocking(repository, never()) { deleteConfig(any()) }
        assertSettingsCardDisplayed()
    }

    // Journey: Settings card -> Delete -> confirm: the config is deleted and the summary
    // card disappears with it; the screen stays (the SIM is back in its new-SIM state).
    @Test
    fun deleteFromTheSettingsCardConfirmsDeletesAndClearsTheSummary() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        // The screen calls viewModel.deleteSchedule, which funnels into the repository's
        // deleteConfig; stub that to drop the config from the observed flow (as a real
        // delete would).
        wheneverBlocking { repository.getActiveHistory(11) }.thenReturn(null)
        wheneverBlocking { repository.deleteConfig(11) }
            .thenAnswer { configFlow.value = emptyList() }
        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule
            .onNodeWithText("Delete this SIM's schedule?", useUnmergedTree = true)
            .assertIsDisplayed()
        // The card's Delete is an icon now, so the dialog's confirm button is the only
        // "Delete" text on screen.
        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        verifyBlocking(repository) { deleteConfig(11) }
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Settings", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    // The app runs edge-to-edge: the Scaffold places the bottom bar at the very bottom of
    // the layout, so while editing the bar must lift itself above the navigation bar
    // (simulated at 100px here) instead of sitting behind it.
    @Test
    @Config(sdk = [33])
    fun bottomBarSitsAboveNavigationBarWhileEditing() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val navBarHeight = 100
        val displayHeight = appContext.resources.displayMetrics.heightPixels
        val cancelBottom = {
            composeTestRule
                .onNodeWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
                .toInt()
        }

        // Without insets the bar hugs the bottom screen edge (edge-to-edge).
        assertTrue(cancelBottom() > displayHeight - navBarHeight)

        val navBarsType = WindowInsets.Type.navigationBars()
        val navInsetsBuilder = WindowInsets.Builder().setInsets(navBarsType, Insets.of(0, 0, 0, navBarHeight))
        val rule = composeTestRule as AndroidComposeTestRule<*, *>
        rule.activity.window.decorView
            .dispatchApplyWindowInsets(navInsetsBuilder.build())
        composeTestRule.waitForIdle()

        // The buttons must now sit above the navigation bar area.
        assertTrue(cancelBottom() <= displayHeight - navBarHeight)
    }

    // The journey: Edit -> Ends "On date" -> dismiss the date picker without a date -> Save.
    // The button stays tappable and explains what is missing instead of silently doing
    // nothing.
    @Test
    fun `save without an end date shows the fix errors toast and saves nothing`() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        // The Ends dropdown anchor shows "Never" (the default end condition); the SIM info
        // card's Last sent row shows "Never" as well on a never-sent SIM, so address the
        // anchor by the row's "Ends" label (merged into the anchor node) instead.
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Ends", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Open the Ends dropdown via the anchor's accessibility action (the M3 anchor
        // exposes OnClick semantics; its raw pointer handling does not fire under Robolectric).
        composeTestRule.onNodeWithText("Ends").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("On date", useUnmergedTree = true).performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("OK", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Dismiss the date picker without picking a date: the dialog's Cancel (the bottom
        // bar also has a Cancel, the dialog's one is composed last).
        composeTestRule
            .onAllNodesWithText("Cancel", useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("Fix errors before saving", ShadowToast.getTextOfLatestToast())
        verifyBlocking(repository, never()) { saveConfig(any()) }
    }

    // The toggle card sits right below the SIM info card, and the small test viewport (plus
    // the Save/Cancel bar while editing) can push it off-screen: Robolectric then reports
    // zero bounds and a touch-based performClick misses. Scroll it into view first.
    private fun scrollToToggleCard() {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Keep Alive", false))
    }

    // Journey: enabled schedule -> Edit -> tap the Keep Alive card off in the editor: the
    // disable is committed right away (like the summary toggle) and the editor closes.
    // Closing the editor alone used to silently drop the intent: the pending config is a
    // draft read only by Save, and Save leaves the screen with the bar.
    @Test
    fun togglingKeepaliveOffInTheEditorDisablesTheScheduleAndClosesTheEditor() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        // Make the mocked repository behave like a real write: saving the user columns
        // flips the enabled flag in the observed flow, and the commit's re-read sees it.
        wheneverBlocking { repository.saveUserColumns(any()) }
            .thenAnswer { configFlow.value = configFlow.value.map { it.copy(enabled = false) } }
        wheneverBlocking { repository.getConfig(11) }
            .thenAnswer { configFlow.value.firstOrNull { it.simId == 11 } }
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        // The editor closes...
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        // ...the disable reaches the repository (only the enabled flag flips, the saved
        // schedule fields survive)...
        val captor = argumentCaptor<SimKeepaliveConfig>()
        verifyBlocking(repository) { saveUserColumns(captor.capture()) }
        assertEquals(false, captor.firstValue.enabled)
        assertEquals("+15550100", captor.firstValue.recipientPhone)
        assertEquals("keep alive", captor.firstValue.message)
        // ...and the state driving the summary toggle is disabled (OFF, not bouncing back
        // to ON): the card's click owns the state (the Switch is wired to the same
        // handler, so tapping either place toggles).
        composeTestRule.waitUntil(10_000) {
            viewModel.state.value.config
                ?.enabled == false
        }
        assertEquals(
            false,
            viewModel.state.value.config
                ?.enabled,
        )
    }

    // Journey (a11y): tapping the Switch itself toggles the keepalive, not only the card
    // row: the Switch is wired to the row's handler, so screen readers get a working
    // toggle instead of a dead control.
    @Test
    fun tappingTheSwitchTogglesTheKeepaliveLikeTheCardRow() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        wheneverBlocking { repository.saveUserColumns(any()) }
            .thenAnswer { configFlow.value = configFlow.value.map { it.copy(enabled = false) } }
        wheneverBlocking { repository.getConfig(11) }
            .thenAnswer { configFlow.value.firstOrNull { it.simId == 11 } }
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_switch").performClick()
        val captor = argumentCaptor<SimKeepaliveConfig>()
        verifyBlocking(repository) { saveUserColumns(captor.capture()) }
        assertEquals(false, captor.firstValue.enabled)
    }

    // Renders the screen for a SIM that has never been configured (no config row).
    private fun renderNoConfig() {
        configFlow.value = emptyList()
        wheneverBlocking { repository.getConfig(11) }.thenReturn(null)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = viewModel,
                    simId = 11,
                    onNavigateBack = {},
                    onNavigateToHistory = {},
                )
            }
        }
    }

    // Journey (0.1.147): a never-configured SIM: the summary toggle opens the editor, and
    // toggling off inside the editor closes it again — without writing anything, since
    // there is no saved config to disable.
    @Test
    fun togglingOffInTheEditorOfANeverConfiguredSimClosesItWithoutWriting() {
        renderNoConfig()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Keep Alive", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The Save/Cancel bar shrank the viewport and pushed the card off-screen again.
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        verifyBlocking(repository, never()) { saveConfig(any()) }
        verifyBlocking(repository, never()) { saveUserColumns(any()) }
    }

    // Journey: a never-configured SIM: the editor opens with the new-schedule proposal —
    // the selected-months rhythm (the leading segment) with every month picked, so the
    // user narrows the months down instead of starting from an empty grid.
    @Test
    fun `new schedule opens in the editor with selected months and every month picked`() {
        renderNoConfig()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Keep Alive", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The frequency row sits deep in the tall editor: scroll the leading segment into
        // view first (merged tree: the segment node carries the label and the selection).
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Selected months", false))
        composeTestRule.onNodeWithText("Selected months").assertIsSelected()
        composeTestRule.onNodeWithText("Months").assertIsNotSelected()
        composeTestRule.onNodeWithText("Days").assertIsNotSelected()
        // Every month chip starts picked.
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("January", false))
        composeTestRule.onNodeWithText("January").assertIsSelected()
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("December", false))
        composeTestRule.onNodeWithText("December").assertIsSelected()
    }

    // An ended on-date schedule (the next send falls after the end date): every 30 days,
    // last send 100 days ago, so the next send is deterministically 20 days from now.
    private fun endedConfig() =
        SimKeepaliveConfig(
            simId = 11,
            enabled = false,
            recipientPhone = "+15550100",
            message = "keep alive",
            freqType = FrequencyType.EVERY_N_DAYS,
            daysInterval = 30,
            hour = 12,
            minute = 0,
            endType = EndType.ON_DATE,
            endDate = LocalDate.now().minusDays(10),
            lastSentAtMillis =
                LocalDate
                    .now()
                    .minusDays(100)
                    .atTime(12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
        )

    private fun endedReason() =
        appContext.getString(
            R.string.error_schedule_ended_date,
            DateUtil.formatDate(appContext, LocalDate.now().plusDays(20)),
            DateUtil.formatDate(appContext, LocalDate.now().minusDays(10)),
        )

    // The engine anchor is the newest SENT row's occurrence (the stored last-send column
    // is display-only): seed a SENT row at the config's last send so the derived next
    // send stays deterministically 20 days out.
    private fun stubSentRow(config: SimKeepaliveConfig) {
        wheneverBlocking { repository.latestSentMillis(config.simId) }
            .thenReturn(config.lastSentAtMillis)
    }

    // Journey: a schedule whose end date is already past its next send: the detail screen
    // shows the schedule-ended banner (the title and the reason as description, directly
    // on the screen — no card, no button) and the toggle cannot be enabled.
    @Test
    fun `ended schedule shows the ended banner and disables the toggle`() {
        val config = endedConfig()
        stubSentRow(config)
        renderConfig(config)
        val reason = endedReason()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(reason, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(reason, false))
        composeTestRule.onNodeWithText("Schedule ended", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(reason, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_card").assertHasNoClickAction()
    }

    // Journey: ended schedule -> Edit: the editor disables the toggle and explains why
    // (the same reason as the detail screen's card) until the end condition is fixed.
    @Test
    fun `editor of an ended schedule explains why and disables the toggle`() {
        val config = endedConfig()
        stubSentRow(config)
        renderConfig(config)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        val reason = endedReason()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(reason, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The warning sits at the bottom of the schedule rules card, below the test
        // viewport: off-screen nodes here report no bounds, so assert existence (the
        // content and its values) rather than display.
        composeTestRule.onNodeWithText(reason, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("toggle_card").assertHasNoClickAction()
    }

    // Journey: ended schedule -> Edit -> change the end condition to "Never": the warning
    // goes away and the toggle can be enabled again (the impossible state is unreachable,
    // not rejected at save time).
    @Test
    fun `editor enables the toggle once the end condition is fixed`() {
        val config = endedConfig()
        stubSentRow(config)
        renderConfig(config)
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        val reason = endedReason()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(reason, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Open the Ends dropdown via the anchor's accessibility action (the M3 anchor
        // exposes OnClick semantics; its raw pointer handling does not fire under
        // Robolectric) and switch the end condition to "Never".
        composeTestRule
            .onNodeWithText(DateUtil.formatDate(appContext, LocalDate.now().minusDays(10)))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Never", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(reason, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("toggle_card").assertHasClickAction()
    }

    // Journey (B-06): an "After N sends" schedule at N-1 consumes its last allowed send
    // while the editor is open: the engine finalizes the row (send count N, ended,
    // disabled) in the DB, and the editor must follow the DB truth — the frozen checked
    // state turns off with the end reason instead of staying ON, and Save persists the
    // draft as disabled instead of being rejected by the commit backstop.
    @Test
    fun `editor follows the engine when the last send ends the schedule mid-edit`() {
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = true,
                recipientPhone = "+15550100",
                message = "keep alive",
                endType = EndType.AFTER_N_SENDS,
                maxSends = 3,
                sendCount = 2,
                lastSentAtMillis = System.currentTimeMillis() - 86_400_000L,
            ),
        )
        assertSettingsCardDisplayed()
        // Make the mocked repository behave like a real write: the commit's re-read sees
        // the observed flow.
        wheneverBlocking { repository.getConfig(11) }
            .thenAnswer { configFlow.value.firstOrNull { it.simId == 11 } }
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").assertHasClickAction()

        // The engine's final send lands: the SENT row, the send count and the end are one
        // transaction (finalizeOutcome's ending Success consequence).
        configFlow.value =
            configFlow.value.map { it.copy(sendCount = 3, enabled = false, nextSendAtMillis = null) }
        val reason = appContext.getString(R.string.error_schedule_ended_sends, 3)
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(reason, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The checked-state sync runs in a LaunchedEffect right after this recomposition.
        composeTestRule.waitForIdle()
        // The toggle is disabled by the end reason...
        composeTestRule.onNodeWithTag("toggle_card").assertHasNoClickAction()
        // ...and the frozen checked state followed the DB (OFF, not still ON).
        composeTestRule.onNodeWithText("Save").performClick()
        // A successful commit closes the editor (justSaved); a backstop refusal would keep
        // it open with a toast.
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        val captor = argumentCaptor<SimKeepaliveConfig>()
        verifyBlocking(repository) { saveUserColumns(captor.capture()) }
        val saved = captor.firstValue
        assertEquals(false, saved.enabled)
        assertEquals("+15550100", saved.recipientPhone)
        assertEquals("keep alive", saved.message)
        assertEquals(EndType.AFTER_N_SENDS, saved.endType)
        assertEquals(3, saved.maxSends)
    }

    private fun windowValueText(minutes: Int) =
        appContext.getString(R.string.sim_config_time_window_value, TimeWindowSteps.formatValue(appContext, minutes))

    // Journey: Edit -> Random send window on a 30-day schedule at the default 12:00: the
    // stepper's cap is the minutes left in the day (8h). Steps past the cap land exactly on
    // it and stop there.
    // Tall viewport: the editor is tall, and in the default small test viewport off-screen
    // nodes report zero bounds here, so the pointer-driven stepper could never be clicked.
    @Config(sdk = [33], qualifiers = "h1600dp")
    @Test
    fun `the window stepper stops at the rhythm cap`() {
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = false,
                recipientPhone = "+15550100",
                message = "keep alive",
                freqType = FrequencyType.EVERY_N_DAYS,
                daysInterval = 30,
            ),
        )
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Random send window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // 14 steps from zero reach the 8h cap; the extra taps must not climb past it.
        repeat(24) {
            composeTestRule.onNodeWithContentDescription("Increase send window").performClick()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(windowValueText(480), useUnmergedTree = true).assertIsDisplayed()
    }

    // Journey: Edit -> Random send window on a day-31 monthly schedule: the window is
    // forced to zero (no shift can stay in the month, February has 28 days) and the editor
    // explains it instead of showing a dead control.
    @Test
    fun `day 31 monthly schedule shows the no-window explanation`() {
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = false,
                recipientPhone = "+15550100",
                message = "keep alive",
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 31,
            ),
        )
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Random send window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Random send window", false))
        // Off-screen nodes in the small test viewport report no bounds: assert existence.
        composeTestRule.onNodeWithText(windowValueText(0), useUnmergedTree = true).assertExists()
        composeTestRule
            .onNodeWithText(appContext.getString(R.string.sim_config_time_window_unavailable), useUnmergedTree = true)
            .assertExists()
    }

    // Journey: Edit a day-5 monthly schedule holding a 7d window -> raise the day of month
    // to 31: the shrinking cap (zero for day 31) clamps the selected window and the
    // explanation appears in the same row.
    @Test
    fun `shrinking the rhythm clamps the selected window`() {
        renderConfig(
            SimKeepaliveConfig(
                simId = 11,
                enabled = false,
                recipientPhone = "+15550100",
                message = "keep alive",
                freqType = FrequencyType.MONTHLY,
                dayOfMonth = 5,
                timeWindowMinutes = 10080,
            ),
        )
        assertSettingsCardDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Random send window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Random send window", false))
        // The day-5 cap is 7d: the loaded selection shows at the cap.
        composeTestRule.onNodeWithText(windowValueText(10080), useUnmergedTree = true).assertExists()
        // Raise the day of month from 5 to 31 (whose cap is zero).
        composeTestRule
            .onNodeWithText("5", useUnmergedTree = true)
            .performTextReplacement("31")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(windowValueText(0), useUnmergedTree = true).assertExists()
        composeTestRule
            .onNodeWithText(appContext.getString(R.string.sim_config_time_window_unavailable), useUnmergedTree = true)
            .assertExists()
    }
}
