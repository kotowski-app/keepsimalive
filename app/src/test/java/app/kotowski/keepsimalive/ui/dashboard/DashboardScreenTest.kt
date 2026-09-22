package app.kotowski.keepsimalive.ui.dashboard

import android.app.Application
import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.kotowski.keepsimalive.BuildConfig
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.Links
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.PermissionState
import app.kotowski.keepsimalive.util.PermissionsAutoResetStatus
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.ScheduleSafetyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

// Renders the dashboard with a real view model, real prefs and the shadowed telephony: the
// empty card is asserted for the missing-permission (permission text) and the SIM-less
// device ("No SIMs detected") cases, and its absence for a detected SIM.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class DashboardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application = RuntimeEnvironment.getApplication()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // The dashboard's resume observer enqueues the one-time reconcile through
        // WorkManager, which needs the test instance in a unit test.
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mockPermissionManager(
        autoResetStatus: PermissionsAutoResetStatus = PermissionsAutoResetStatus.NOT_AVAILABLE,
        batteryOptimizationsGranted: Boolean = false,
    ): PermissionManager {
        val permissionManager: PermissionManager = mock()
        // The row state and the row tap share the manager's guarded check (the mock's
        // context can't reach the framework's, so the state is stubbed instead).
        whenever(permissionManager.isIgnoringBatteryOptimizations(any())).thenReturn(batteryOptimizationsGranted)
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(autoResetStatus)
        }
        return permissionManager
    }

    private fun createViewModel(permissionManager: PermissionManager = mockPermissionManager()): DashboardViewModel {
        val repository: KeepaliveRepository = mock()
        whenever(repository.observeConfigs()).thenReturn(flowOf(emptyList()))
        whenever(repository.observeInFlightStates()).thenReturn(MutableStateFlow(emptyMap()))
        return DashboardViewModel(
            appContext,
            permissionManager,
            repository,
            mock<ScheduleReconciler>(),
        )
    }

    private fun render(
        viewModel: DashboardViewModel,
        context: Context = appContext,
    ) {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    context = context,
                    onNavigateToSettings = {},
                    onSimCardClick = {},
                )
            }
        }
    }

    @Test
    fun `the permissions card shows the sms, phone and battery rows without the alarms row`() {
        val viewModel = createViewModel()
        render(viewModel)
        viewModel.updatePermissions(appContext)
        composeTestRule.onNodeWithText("Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("SMS").assertIsDisplayed()
        composeTestRule.onNodeWithText("Phone (to detect SIM cards)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background battery usage").assertIsDisplayed()
        // The "Alarms & reminders" row is gone with the permission the app no longer requests here.
        composeTestRule.onNodeWithText("Alarms", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33])
    fun `the non critical permission rows show recommended while the required rows show missing`() {
        val viewModel = createViewModel()
        render(viewModel)
        viewModel.updatePermissions(appContext)
        composeTestRule.waitForIdle()
        // Notifications, battery usage and the permissions auto-reset are not critical for the
        // keepalives to work: while not granted they show "Recommended" (Android 13+ renders
        // the notifications row and Android 11+ the auto-reset row), while SMS and Phone stay
        // "Missing".
        assertEquals(3, composeTestRule.onAllNodesWithText("Recommended").fetchSemanticsNodes().size)
        assertEquals(2, composeTestRule.onAllNodesWithText("Missing").fetchSemanticsNodes().size)
        // While any permission (required or recommended) is missing, the hint is shown right
        // under the card header, above the first (SMS) row.
        composeTestRule.onNodeWithText("Tap a row to grant the permission.").assertIsDisplayed()
        val topOf: (String) -> Float =
            { text ->
                composeTestRule
                    .onNodeWithText(text)
                    .fetchSemanticsNode()
                    .positionInRoot
                    .y
            }
        assertTrue(topOf("Permissions") < topOf("Tap a row to grant the permission."))
        assertTrue(topOf("Tap a row to grant the permission.") < topOf("SMS"))
    }

    @Test
    @Config(sdk = [33])
    fun `the notifications row shows granted once the permission is granted`() {
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // The row state reads the permission through the manager's single isGranted path:
        // the shadow grant is stubbed through it (a bare mock answers false for everything).
        val permissionManager = mockPermissionManager()
        whenever(permissionManager.isGranted(android.Manifest.permission.POST_NOTIFICATIONS)).thenReturn(true)
        val viewModel = createViewModel(permissionManager)
        render(viewModel)
        viewModel.updatePermissions(appContext)
        composeTestRule.waitForIdle()
        // The granted notifications row shows the check icon: only battery usage and the
        // permissions auto-reset stay "Recommended", SMS and Phone stay "Missing" — the hint
        // stays, some permission is still not granted.
        assertEquals(2, composeTestRule.onAllNodesWithText("Recommended").fetchSemanticsNodes().size)
        assertEquals(2, composeTestRule.onAllNodesWithText("Missing").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("Tap a row to grant the permission.").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33])
    fun `the auto reset row stays recommended while the system toggle is active`() {
        val permissionManager = mockPermissionManager(PermissionsAutoResetStatus.ACTIVE)
        val viewModel = createViewModel(permissionManager)
        render(viewModel)
        viewModel.updatePermissions(appContext)
        composeTestRule.waitForIdle()
        // Even while the device resets unused permissions itself, the row is recommended, not
        // missing.
        assertEquals(3, composeTestRule.onAllNodesWithText("Recommended").fetchSemanticsNodes().size)
        assertEquals(2, composeTestRule.onAllNodesWithText("Missing").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("Tap a row to grant the permission.").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33])
    fun `the permissions card hides the hint while every permission is granted`() {
        shadowOf(appContext).grantPermissions(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        // The battery state comes through the shared guarded check on the manager
        // (the row tap and the row state do not read the framework directly).
        val permissionManager = mockPermissionManager(PermissionsAutoResetStatus.DISABLED, batteryOptimizationsGranted = true)
        // The row states read the framework permissions through the manager's single
        // isGranted path: all three are stubbed granted (a bare mock answers false).
        whenever(permissionManager.isGranted(any())).thenReturn(true)
        val viewModel = createViewModel(permissionManager)
        render(viewModel)
        viewModel.updatePermissions(appContext)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Tap a row to grant the permission.").assertDoesNotExist()
        assertEquals(0, composeTestRule.onAllNodesWithText("Recommended").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("Missing").fetchSemanticsNodes().size)
    }

    @Test
    fun `the empty card names the missing phone permission while it is missing`() {
        val viewModel = createViewModel()
        render(viewModel)
        // The permission is not granted on the test context, so the SIM list is unreadable
        // and the card names the permission instead of a missing SIM.
        viewModel.updateSimCards(appContext)
        composeTestRule.onNodeWithText("Grant Phone permission to see the SIMs").assertIsDisplayed()
        composeTestRule.onNodeWithText("No SIMs detected").assertDoesNotExist()
    }

    @Test
    fun `tapping the empty card while the phone permission is missing requests the permission`() {
        val permissionManager = mockPermissionManager()
        val viewModel = createViewModel(permissionManager)
        // The request flow needs the Activity (the permission launcher lives on it), so the
        // screen gets the rule's activity instead of the bare application context.
        val activity = (composeTestRule as AndroidComposeTestRule<*, *>).activity
        whenever(
            permissionManager.getState(activity, android.Manifest.permission.READ_PHONE_STATE),
        ).thenReturn(PermissionState.DENIED_CAN_ASK)
        // The screen routes the request through the shared manager: run its real body so
        // the dialog path (getState -> markRequested -> launcher) is the one under test.
        whenever(
            permissionManager.request(
                eq(activity),
                eq(android.Manifest.permission.READ_PHONE_STATE),
                any(),
            ),
        ).thenCallRealMethod()
        render(viewModel, activity)
        viewModel.updateSimCards(appContext)
        composeTestRule.onNodeWithTag("no_sims_card").performClick()
        verify(permissionManager).markRequested(android.Manifest.permission.READ_PHONE_STATE)
        // The launcher routes through Activity.requestPermissions(): the recorded intent is
        // the platform's permission request, not the framework contract's own.
        val started = shadowOf(activity).nextStartedActivity
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", started?.action)
        assertArrayEquals(
            arrayOf(android.Manifest.permission.READ_PHONE_STATE),
            started?.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"),
        )
    }

    @Test
    fun `the no sims card is shown while the device has no sim`() {
        shadowOf(appContext).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(emptyList())

        // A real manager here (not the default mock): the empty-card text is chosen from
        // the state's phoneGranted, which now flows through the manager's isGranted — a
        // bare mock answers false and the card would name the granted permission as missing.
        val viewModel = createViewModel(PermissionManager(appContext))
        render(viewModel)
        viewModel.updateSimCards(appContext)
        composeTestRule.onNodeWithText("No SIMs detected").assertIsDisplayed()
    }

    @Test
    fun `the no sims card is hidden while a sim is detected`() {
        shadowOf(appContext).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val simA =
            ShadowSubscriptionManager.SubscriptionInfoBuilder
                .newBuilder()
                .setId(1)
                .setDisplayName("SIM A")
                .setCarrierName("Carrier A")
                .buildSubscriptionInfo()
        val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(simA))

        val viewModel = createViewModel()
        render(viewModel)
        viewModel.updateSimCards(appContext)
        composeTestRule.onNodeWithText("SIM A").assertIsDisplayed()
        composeTestRule.onNodeWithText("No SIMs detected").assertDoesNotExist()
    }

    // The flavor label is plain meta text like the version next to it: it must sit on the
    // same row, directly after the version label (the muted meta color both share is bound
    // to one theme color in the composable, so the label can never read as a status). In
    // debug builds the red DEBUG marker sits after the flavor.
    @Test
    fun `the flavor label sits on the version row, right after the version`() {
        val viewModel = createViewModel()
        render(viewModel)
        composeTestRule.waitForIdle()
        val version =
            composeTestRule
                .onNodeWithText("Version ${BuildConfig.VERSION_NAME.split("-")[0]}")
                .fetchSemanticsNode()
                .boundsInRoot
        val flavor =
            composeTestRule
                .onNodeWithText("FOSS")
                .fetchSemanticsNode()
                .boundsInRoot
        // Same row: identical vertical extent.
        assertEquals(version.top, flavor.top, 1f)
        assertEquals(version.bottom, flavor.bottom, 1f)
        // Directly to the right of the version, only the small spacer apart.
        assertTrue(flavor.left > version.right)
        assertTrue(flavor.left - version.right <= 32)
        if (BuildConfig.DEBUG) {
            // The debug marker: same row, directly after the flavor.
            val debug = composeTestRule.onNodeWithText("DEBUG").fetchSemanticsNode().boundsInRoot
            assertEquals(version.top, debug.top, 1f)
            assertTrue(debug.left > flavor.right)
            assertTrue(debug.left - flavor.right <= 32)
        } else {
            composeTestRule.onNodeWithText("DEBUG").assertDoesNotExist()
        }
    }

    // Journey: the sponsor link leaves the app, so it carries the open-in-new icon the
    // Settings' about rows use as the "goes somewhere" cue; tapping the row opens the
    // Buy Me a Coffee page (no URL is shown on the dashboard, unlike settings).
    @Test
    fun `tapping the sponsor row opens the buy me a coffee page`() {
        val viewModel = createViewModel()
        val activity = (composeTestRule as AndroidComposeTestRule<*, *>).activity
        render(viewModel, activity)
        composeTestRule.onNodeWithTag("dashboard_sponsor_coffee", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_sponsor").performClick()
        assertEquals(Uri.parse(Links.DONATE), shadowOf(activity).nextStartedActivity?.data)
    }

    // Journey: the coffee icon and the open-in-new icon are the two visual ends of the
    // "leaves the app" cue, so the coffee icon renders at the icon's size.
    @Test
    fun `the coffee icon matches the open-in-new icon size`() {
        val viewModel = createViewModel()
        render(viewModel)
        // The row is clickable, so in the merged tree the row's children collapse into the
        // row's node — the sizes are measured in the unmerged tree.
        val coffee =
            composeTestRule
                .onNodeWithTag("dashboard_sponsor_coffee", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val icon =
            composeTestRule
                .onNodeWithTag("dashboard_sponsor_icon", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(icon.width, coffee.width, 1f)
        assertEquals(icon.height, coffee.height, 1f)
    }

    // Journey: returning to the dashboard from the background (an app switch) — the
    // ON_RESUME effect re-reads the live permissions and re-enqueues the one-time safety
    // reconcile, so a clock change that happened away gets corrected without waiting for
    // the 24 h sweep. (The first resume — the rule resumes the activity right after the
    // content is set, like the app's first launch — already enqueued it; the enqueued
    // worker fails to run in the test WorkManager, which has no Hilt worker factory, so
    // only the enqueues are asserted.)
    @Test
    fun `returning to the dashboard re-enqueues the safety reconcile`() {
        val viewModel = createViewModel()
        render(viewModel)
        composeTestRule.waitForIdle()
        val workManager = WorkManager.getInstance(appContext)
        val uniqueWork = ScheduleSafetyWorker.WORK_NAME_ONCE
        val idsBefore =
            workManager
                .getWorkInfosForUniqueWork(uniqueWork)
                .get()
                .map { it.id }
                .toSet()
        assertTrue(idsBefore.isNotEmpty())
        // An app switch: the activity's lifecycle (the one the screen's effect observes)
        // goes down to CREATED and back up, firing ON_RESUME on the return.
        val lifecycleRegistry = (composeTestRule as AndroidComposeTestRule<*, *>).activity.lifecycle as LifecycleRegistry
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeTestRule.waitForIdle()
        val idsAfter =
            workManager
                .getWorkInfosForUniqueWork(uniqueWork)
                .get()
                .map { it.id }
                .toSet()
        // The resume enqueued a new work (REPLACE): at least one id that was not there before.
        assertTrue(idsAfter.any { it !in idsBefore })
    }
}
