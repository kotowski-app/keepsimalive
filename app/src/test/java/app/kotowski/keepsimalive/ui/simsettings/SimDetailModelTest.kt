package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.SimPhoneStateData
import app.kotowski.keepsimalive.work.OffSchedulePendingRegistry
import app.kotowski.keepsimalive.work.ScheduleArmer
import app.kotowski.keepsimalive.work.ScheduleReconciler
import app.kotowski.keepsimalive.work.SimSendLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SimDetailModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `SimDetailUiState default values`() {
        val state = SimDetailUiState()
        assertFalse(state.isLoading)
        assertNull(state.data)
        assertNull(state.error)
    }

    @Test
    fun `SimDetailUiState loading state`() {
        val state = SimDetailUiState(isLoading = true)
        assertTrue(state.isLoading)
    }

    @Test
    fun `SimDetailUiState with data`() {
        val data =
            SimPhoneStateData(
                simId = 1,
                displayName = "Test SIM",
                carrierName = "Test Carrier",
                slotIndex = 1,
                networkOperatorName = "Orange",
                simOperatorName = "T-Mobile",
                isNetworkRoaming = false,
            )
        val state = SimDetailUiState(data = data)
        assertFalse(state.isLoading)
        assertNotNull(state.data)
        assertNull(state.error)
        assertEquals("Test SIM", state.data!!.displayName)
    }

    @Test
    fun `SimDetailUiState with error`() {
        val state = SimDetailUiState(error = "Permission denied")
        assertFalse(state.isLoading)
        assertNull(state.data)
        assertEquals("Permission denied", state.error)
    }

    @Test
    fun `SimPhoneStateData has all fields`() {
        val data =
            SimPhoneStateData(
                simId = 42,
                displayName = "My SIM",
                carrierName = "Carrier",
                slotIndex = 1,
                networkOperatorName = "Op",
                simOperatorName = "Home",
                isNetworkRoaming = true,
            )
        assertEquals(42, data.simId)
        assertEquals("My SIM", data.displayName)
        assertEquals("Carrier", data.carrierName)
        assertEquals(1, data.slotIndex)
        assertEquals("Op", data.networkOperatorName)
        assertEquals("Home", data.simOperatorName)
        assertTrue(data.isNetworkRoaming)
    }

    @Test
    fun `SimPhoneStateData equality`() {
        val d1 = SimPhoneStateData(1, "SIM", "Carrier", 1, "Op", "Home", false)
        val d2 = SimPhoneStateData(1, "SIM", "Carrier", 1, "Op", "Home", false)
        assertEquals(d1, d2)
    }

    @Test
    fun `SimPhoneStateData roaming true`() {
        val data = SimPhoneStateData(11, "Vodafone", "Vodafone", 101, "AIS", "Vodafone", true)
        assertTrue(data.isNetworkRoaming)
        assertEquals("AIS", data.networkOperatorName)
        assertEquals("Vodafone", data.simOperatorName)
    }

    @Test
    fun `SimPhoneStateData roaming false`() {
        val data = SimPhoneStateData(12, "T-Mobile", "T-Mobile", 102, "T-Mobile", "T-Mobile", false)
        assertFalse(data.isNetworkRoaming)
    }

    // Thresholds shared with statusText (see SimDetailStatusTextTest).
    @Test
    fun `nextSend remaining large positive shows duration`() {
        val nextSendAtMillis = System.currentTimeMillis() + 90061000
        val currentTime = System.currentTimeMillis()
        val remaining = nextSendAtMillis - currentTime
        assertTrue("Expected > 3000 but got $remaining", remaining > 3000)
    }

    @Test
    fun `nextSend remaining 2 seconds shows in a moment`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime + 2000
        val remaining = nextSendAtMillis - currentTime
        assertEquals(2000, remaining)
        assertTrue("Expected 0-3000 range but got $remaining", remaining in 0..3000)
    }

    @Test
    fun `nextSend remaining exactly 3001 shows duration`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime + 3001
        val remaining = nextSendAtMillis - currentTime
        assertTrue("Expected > 3000 but got $remaining", remaining > 3000)
    }

    @Test
    fun `nextSend remaining exactly 3000 shows in a moment`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime + 3000
        val remaining = nextSendAtMillis - currentTime
        assertTrue("Expected 0-3000 range but got $remaining", remaining in 0..3000)
    }

    @Test
    fun `nextSend remaining zero shows in a moment`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime
        val remaining = nextSendAtMillis - currentTime
        assertEquals(0, remaining)
        assertTrue("Expected 0 to be in in a moment range", remaining in -3000..3000)
    }

    @Test
    fun `nextSend remaining negative 2 seconds shows in a moment`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime - 2000
        val remaining = nextSendAtMillis - currentTime
        assertEquals(-2000, remaining)
        assertTrue("Expected -3000 to 0 range but got $remaining", remaining in -3000..0)
    }

    @Test
    fun `nextSend remaining negative 3 seconds is the overdue boundary`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime - 3000
        val remaining = nextSendAtMillis - currentTime
        assertEquals(-3000, remaining)
        assertTrue("Expected -3000 to 0 range but got $remaining", remaining in -3000..0)
    }

    @Test
    fun `nextSend remaining negative 4 seconds shows overdue`() {
        val currentTime = System.currentTimeMillis()
        val nextSendAtMillis = currentTime - 4000
        val remaining = nextSendAtMillis - currentTime
        assertEquals(-4000, remaining)
        assertTrue("Expected < -3000 but got $remaining", remaining < -3000)
    }

    @Test
    fun `SimDetailUiState defaults history to empty`() {
        val state = SimDetailUiState()
        assertTrue(state.history.isEmpty())
    }
}
