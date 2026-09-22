package app.kotowski.keepsimalive.ui.simsettings

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.DateUtil
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneId

// Enabling (or saving as enabled) a schedule whose end condition is already met is refused:
// the toggle can never show an enabled state that would never send. The end state is read
// from the database (the editor's pending config does not carry it) and anchored like the
// engine, so these tests cover the toggle and the editor Save entry points of commit().
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimDetailViewModelEndedScheduleTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository: KeepaliveRepository = mock()
    private val armer: ScheduleArmer = mock()
    private val mockReconciler: ScheduleReconciler = mock()
    private val prefs: AppPrefs = mock()

    init {
        // The mocked prefs carry the default late-send grace: the VM reads it for the end
        // state and the state (an unstubbed 0 would skip every past occurrence).
        whenever(prefs.lateSendGraceMinutes).thenReturn(AppPrefs.DEFAULT_LATE_SEND_GRACE_MINUTES)
    }

    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.observeNewest(any(), any())).thenReturn(emptyFlow())
        whenever(repository.observeHistoryCount(any())).thenReturn(flowOf(0))
        whenever(repository.observeConfigs()).thenReturn(emptyFlow())
        // A real active subscription (id 11): the tests load it like any real SIM.
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
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" after every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    private fun viewModel() =
        SimDetailViewModel(context, repository, mockReconciler, prefs, armer, SimSendLock(), OffSchedulePendingRegistry())

    private suspend fun loadSim(
        vm: SimDetailViewModel,
        simId: Int = 11,
        config: SimKeepaliveConfig?,
    ) {
        wheneverBlocking { repository.getConfig(simId) }.thenReturn(config)
        vm.loadSimData(context, simId)
        val deadline = System.currentTimeMillis() + 5000
        while (vm.state.value.isLoading && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private fun epoch(localDate: LocalDate): Long =
        localDate
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `setEnabled on an ended after-n-sends schedule is refused`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            vm.setEnabled(true)
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
            assertEquals(dbConfig, vm.state.value.config)
            assertFalse(vm.state.value.justSaved)
            assertEquals(
                context.getString(R.string.error_schedule_ended_sends, 3),
                ShadowToast.getTextOfLatestToast(),
            )
        }

    @Test
    fun `setEnabled on an ended on-date schedule is refused naming the dates`() =
        runBlocking {
            val vm = viewModel()
            // Every 30 days, last sent occurrence 100 days ago: catch-up lands on the send
            // 20 days from now, deterministically after the end date below. The engine
            // anchor is the newest SENT row's occurrence — seed it at the same instant as
            // the stored last send so the derived next send stays deterministic.
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(10),
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(100)),
                )
            wheneverBlocking { repository.latestSentMillis(11) }
                .thenReturn(epoch(LocalDate.now().minusDays(100)))
            loadSim(vm, config = dbConfig)
            vm.setEnabled(true)
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
            assertEquals(dbConfig, vm.state.value.config)
            assertEquals(
                context.getString(
                    R.string.error_schedule_ended_date,
                    DateUtil.formatDate(context, LocalDate.now().plusDays(20)),
                    DateUtil.formatDate(context, LocalDate.now().minusDays(10)),
                ),
                ShadowToast.getTextOfLatestToast(),
            )
        }

    @Test
    fun `setEnabled on a schedule with remaining sends proceeds`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 5,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            val enabled = dbConfig.copy(enabled = true)
            wheneverBlocking { repository.getConfig(11) }.thenReturn(enabled)
            vm.setEnabled(true)
            verifyBlocking(repository) { saveUserColumns(enabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(enabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `setEnabled on a never-ending schedule proceeds`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.NEVER,
                    sendCount = 999,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            val enabled = dbConfig.copy(enabled = true)
            wheneverBlocking { repository.getConfig(11) }.thenReturn(enabled)
            vm.setEnabled(true)
            verifyBlocking(repository) { saveUserColumns(enabled) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(enabled, vm.state.value.config)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `saveConfig enabling an ended schedule is refused without engine state in the pending config`() =
        runBlocking {
            val vm = viewModel()
            // Every 30 days, last sent occurrence 100 days ago: the next send is
            // deterministically 20 days from now, after the end date below. The engine
            // anchor is the newest SENT row's occurrence — seed it at the same instant as
            // the stored last send.
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(10),
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(100)),
                )
            wheneverBlocking { repository.latestSentMillis(11) }
                .thenReturn(epoch(LocalDate.now().minusDays(100)))
            loadSim(vm, config = dbConfig)
            // The editor's pending config carries no engine state (send count, last send):
            // the guard must read it from the database to see the schedule is over.
            vm.saveConfig(dbConfig.copy(enabled = true, lastSentAtMillis = null, sendCount = 0))
            verifyBlocking(repository, never()) { saveUserColumns(any()) }
            verifyBlocking(repository, never()) { saveConfig(any()) }
            verifyBlocking(mockReconciler, never()) { reconcileSim(any()) }
            assertEquals(dbConfig, vm.state.value.config)
            assertEquals(
                context.getString(
                    R.string.error_schedule_ended_date,
                    DateUtil.formatDate(context, LocalDate.now().plusDays(20)),
                    DateUtil.formatDate(context, LocalDate.now().minusDays(10)),
                ),
                ShadowToast.getTextOfLatestToast(),
            )
        }

    @Test
    fun `saveConfig that fixes the end condition enables the schedule`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(10),
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            val fixed = dbConfig.copy(enabled = true, endDate = LocalDate.now().plusDays(60))
            wheneverBlocking { repository.getConfig(11) }.thenReturn(fixed)
            vm.saveConfig(fixed)
            verifyBlocking(repository) { saveUserColumns(fixed) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(fixed, vm.state.value.config)
            assertTrue(vm.state.value.justSaved)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    @Test
    fun `saveConfig that raises the send limit enables the schedule`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            val raised = dbConfig.copy(enabled = true, maxSends = 5)
            wheneverBlocking { repository.getConfig(11) }.thenReturn(raised)
            vm.saveConfig(raised)
            verifyBlocking(repository) { saveUserColumns(raised) }
            verifyBlocking(mockReconciler) { reconcileSim(11) }
            assertEquals(raised, vm.state.value.config)
            assertTrue(vm.state.value.justSaved)
            assertNull(ShadowToast.getTextOfLatestToast())
        }

    // The schedule-ended banner text and the engine anchor the editor uses: derived on
    // load from the saved config under the engine anchor (the newest SENT row's
    // occurrence — the stored last-send column is display-only).
    @Test
    fun `loadSimData on an ended schedule exposes the reason and the engine anchor`() =
        runBlocking {
            val vm = viewModel()
            // Every 30 days, last sent occurrence 100 days ago: the next send is
            // deterministically 20 days from now, after the end date.
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(10),
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(100)),
                )
            wheneverBlocking { repository.latestSentMillis(11) }
                .thenReturn(epoch(LocalDate.now().minusDays(100)))
            loadSim(vm, config = dbConfig)
            assertEquals(
                context.getString(
                    R.string.error_schedule_ended_date,
                    DateUtil.formatDate(context, LocalDate.now().plusDays(20)),
                    DateUtil.formatDate(context, LocalDate.now().minusDays(10)),
                ),
                vm.state.value.endedReason,
            )
            assertEquals(dbConfig.lastSentAtMillis, vm.state.value.lastSentAnchorMillis)
        }

    @Test
    fun `loadSimData on a running schedule exposes no reason`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 5,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            wheneverBlocking { repository.latestSentMillis(11) }
                .thenReturn(epoch(LocalDate.now().minusDays(5)))
            loadSim(vm, config = dbConfig)
            assertNull(vm.state.value.endedReason)
            assertEquals(dbConfig.lastSentAtMillis, vm.state.value.lastSentAnchorMillis)
        }

    // Regression: below API 33 the app context follows the system language, so the reason
    // the ViewModel derives (the schedule-ended banner) must resolve with the app language
    // the user picked, not the system one.
    @Test
    fun `the ended reason resolves with the app language the user picked`() =
        runBlocking {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 3,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            assertEquals(
                "Увеличьте лимит отправок (сейчас 3), чтобы включить снова. Предыдущие отправки учитываются в этом лимите.",
                vm.state.value.endedReason,
            )
        }

    @Test
    fun `loadSimData on an ended single-send schedule exposes the singular reason`() =
        runBlocking {
            val vm = viewModel()
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 1,
                    sendCount = 1,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            loadSim(vm, config = dbConfig)
            assertEquals(
                "Raise the send limit above 1 to re-enable. Previous sends count towards this limit.",
                vm.state.value.endedReason,
            )
        }

    @Test
    fun `loadSimData anchors the end state on the newest SENT row when the stored last send is missing`() =
        runBlocking {
            val vm = viewModel()
            // The saved config carries no last send, but the history proves a send 100 days
            // ago: the end state must anchor to that row (the engine's anchor), so the next
            // send is 20 days from now, after the end date.
            val dbConfig =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = false,
                    freqType = FrequencyType.EVERY_N_DAYS,
                    daysInterval = 30,
                    hour = 12,
                    minute = 0,
                    endType = EndType.ON_DATE,
                    endDate = LocalDate.now().minusDays(10),
                )
            wheneverBlocking { repository.latestSentMillis(11) }.thenReturn(epoch(LocalDate.now().minusDays(100)))
            loadSim(vm, config = dbConfig)
            assertEquals(
                context.getString(
                    R.string.error_schedule_ended_date,
                    DateUtil.formatDate(context, LocalDate.now().plusDays(20)),
                    DateUtil.formatDate(context, LocalDate.now().minusDays(10)),
                ),
                vm.state.value.endedReason,
            )
            assertEquals(epoch(LocalDate.now().minusDays(100)), vm.state.value.lastSentAnchorMillis)
        }

    @Test
    fun `config observer keeps the ended reason in sync with background writes`() =
        runBlocking {
            val vm = viewModel()
            val configs = MutableStateFlow<List<SimKeepaliveConfig>>(emptyList())
            whenever(repository.observeConfigs()).thenReturn(configs)
            wheneverBlocking { repository.getConfig(11) }.thenReturn(null)
            vm.loadSimData(context, 11)
            waitFor { !vm.state.value.isLoading && vm.state.value.config == null }

            val running =
                SimKeepaliveConfig(
                    simId = 11,
                    enabled = true,
                    endType = EndType.AFTER_N_SENDS,
                    maxSends = 5,
                    sendCount = 3,
                    lastSentAtMillis = epoch(LocalDate.now().minusDays(5)),
                )
            configs.value = listOf(running)
            waitFor { vm.state.value.config == running }
            assertNull(vm.state.value.endedReason)

            // The engine ends the schedule on the final send (limit reached, disabled):
            // the reason shows up without a reload.
            val ended = running.copy(enabled = false, sendCount = 5)
            configs.value = listOf(ended)
            waitFor { vm.state.value.config == ended }
            assertEquals(context.getString(R.string.error_schedule_ended_sends, 5), vm.state.value.endedReason)
        }

    private suspend fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }
}
