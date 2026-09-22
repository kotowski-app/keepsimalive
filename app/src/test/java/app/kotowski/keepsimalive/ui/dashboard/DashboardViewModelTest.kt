package app.kotowski.keepsimalive.ui.dashboard

import android.content.Context
import app.kotowski.keepsimalive.data.InFlightOccurrence
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SendOutcome
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.work.ScheduleReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val appContext: Context = mock()
    private val permissionManager: PermissionManager = mock()
    private val repository: KeepaliveRepository = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `in flight states flow lands in state`() =
        runBlocking {
            val sending = InFlightOccurrence(1, SendOutcome.SENDING.name, 0, 1_700_000_000_000L, 1_700_000_000_000L)
            // A retry row is a PENDING row with retryCount > 0.
            val retrying = InFlightOccurrence(2, SendOutcome.PENDING.name, 1, 1_700_000_001_000L, null)
            val states = MutableStateFlow(mapOf(1 to sending, 2 to retrying))
            whenever(repository.observeConfigs()).thenReturn(flowOf(emptyList()))
            whenever(repository.observeInFlightStates()).thenReturn(states)
            val vm =
                DashboardViewModel(
                    appContext,
                    permissionManager,
                    repository,
                    mock<ScheduleReconciler>(),
                )
            assertEquals(mapOf(1 to sending, 2 to retrying), vm.state.value.inFlightStates)
            states.value = mapOf(2 to retrying)
            assertEquals(mapOf(2 to retrying), vm.state.value.inFlightStates)
        }
}
