package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowToast

// The pending-enable toast: switching the toggle on in the editor only updates the draft
// (no commit), so the moment the user toggles it on a toast says the enable only takes
// effect when the user saves. Toggling off instead commits the disable and closes the
// editor. No other path toasts in the editor: opening it and the commit on toggle-off
// (which cannot hit an error path in this fixture) stay silent.
@RunWith(RobolectricTestRunner::class)
class EnablePendingToastTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext = RuntimeEnvironment.getApplication()
    private val repository: KeepaliveRepository = mock()
    private val prefs: AppPrefs = mock()

    init {
        // The mocked prefs carry the default late-send grace: the VM reads it for the end
        // state and the state (an unstubbed 0 would skip every past occurrence).
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
    }

    private val configFlow = MutableStateFlow<List<SimKeepaliveConfig>>(emptyList())
    private val historyFlow = MutableStateFlow<List<SendHistoryEntity>>(emptyList())
    private lateinit var viewModel: SimDetailViewModel

    private val willBeEnabledText = appContext.getString(R.string.sim_config_will_be_enabled_on_save)

    @Before
    fun setup() {
        whenever(repository.observeConfigs()).thenReturn(configFlow)
        whenever(repository.observeNewest(anyInt(), anyInt())).thenReturn(historyFlow)
        whenever(repository.observeHistoryCount(anyInt())).thenReturn(flowOf(0))
        // A real active subscription (id 11): the tests load it like any real SIM.
        shadowOf(appContext).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
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
            SimDetailViewModel(appContext, repository, mock(), prefs, mock(), SimSendLock(), OffSchedulePendingRegistry())
    }

    private fun render(enabled: Boolean) {
        val config =
            SimKeepaliveConfig(
                simId = 11,
                enabled = enabled,
                recipientPhone = "+15550100",
                message = "keep alive",
            )
        configFlow.value = listOf(config)
        wheneverBlocking { repository.getConfig(config.simId) }.thenReturn(config)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                SimDetailScreen(
                    viewModel = viewModel,
                    simId = config.simId,
                    onNavigateBack = {},
                    onNavigateToHistory = {},
                )
            }
        }
    }

    // The screen content is a scrollable column and the test viewport is small: wait for the
    // card to be composed, then scroll it into view before asserting visibility.
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

    // The toggle card sits right below the SIM info card, and the small test viewport (plus
    // the Save/Cancel bar while editing) can push it off-screen: Robolectric then reports
    // zero bounds and a touch-based performClick misses. Scroll it into view first.
    private fun scrollToToggleCard() {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Keep Alive", false))
    }

    private fun openEditor() {
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun assertSaveBarGone() {
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText("Cancel", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    @Test
    fun togglingOnInTheEditorShowsTheWillBeEnabledToast() {
        render(enabled = false)
        assertSettingsCardDisplayed()
        openEditor()
        // The draft still mirrors the saved (disabled) config and no user action happened
        // in the editor: no toast yet.
        assertNull(ShadowToast.getTextOfLatestToast())
        // Toggling on updates the draft only (no commit)...
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        // ...and the pending enable is announced with a toast.
        assertEquals(willBeEnabledText, ShadowToast.getTextOfLatestToast())
        // Toggling off commits the disable and closes the editor: the Save bar leaves
        // the screen.
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        assertSaveBarGone()
    }

    // The commit cannot hit an error path in this fixture (no in-flight send, the schedule
    // never ends, the mock save cannot fail), so no toast is ever shown in this flow.
    @Test
    fun savedEnabledConfigTogglingOffNeverShowsTheWillBeEnabledToast() {
        render(enabled = true)
        assertSettingsCardDisplayed()
        openEditor()
        assertNull(ShadowToast.getTextOfLatestToast())
        scrollToToggleCard()
        composeTestRule.onNodeWithTag("toggle_card").performClick()
        assertSaveBarGone()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
