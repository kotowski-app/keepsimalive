package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSubscriptionManager

// Native graphics: the default (legacy) mode does not measure text widths, so a long message
// never wraps and a two-line clip cannot be observed.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimDetailLongMessageTest {
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

    // A message at the SMS max length must render in full in the Settings card, not be
    // clipped to the DetailRow default of two lines (the ellipsis would hide the rest).
    @Test
    fun settingsCardShowsLongMessageInFull() {
        val longMessage = "a".repeat(AppConfig.SMS_MAX_CHARS)
        val config =
            SimKeepaliveConfig(
                simId = 11,
                enabled = true,
                recipientPhone = "+15550100",
                message = longMessage,
            )
        configFlow.value = listOf(config)
        wheneverBlocking { repository.getConfig(11) }.thenReturn(config)
        composeTestRule.setContent {
            KeepSimAliveTheme {
                Box {
                    SimDetailScreen(
                        viewModel = viewModel,
                        simId = 11,
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
        // The card is below the small test viewport: scroll it into view, otherwise the
        // bounds clipped to the root collapse to zero height.
        composeTestRule.waitUntil(10_000) {
            composeTestRule
                .onAllNodesWithText(longMessage, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(longMessage))
        val message = composeTestRule.onNodeWithText(longMessage, useUnmergedTree = true)
        val reference = composeTestRule.onNodeWithText("x", useUnmergedTree = true)
        val messageHeight = message.getBoundsInRoot().height
        val lineHeight = reference.getBoundsInRoot().height
        assertTrue(
            "long message must not be clipped to two lines: $messageHeight vs line height $lineHeight",
            messageHeight > lineHeight * 2,
        )
    }
}
