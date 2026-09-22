package app.kotowski.keepsimalive.util

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SimDetectorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        // The modem count cache is static; a stale value from another test would mask the
        // shadowed phone count this test sets.
        SimDetector.cachedModemCount = null
    }

    @Test
    fun `isMultiSimDevice is false for one or fewer modems`() {
        assertFalse(SimDetector.isMultiSimDevice(0))
        assertFalse(SimDetector.isMultiSimDevice(1))
    }

    @Test
    fun `isMultiSimDevice is true for two or more modems`() {
        assertTrue(SimDetector.isMultiSimDevice(2))
        assertTrue(SimDetector.isMultiSimDevice(3))
    }

    @Test
    @Config(sdk = [29])
    fun `getSupportedModemCount reads phone count below API 30`() {
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setPhoneCount(2)
        assertEquals(2, SimDetector.getSupportedModemCount(context))
        assertTrue(SimDetector.isMultiSimDevice(context))
    }

    @Test
    @Config(sdk = [29])
    fun `getSupportedModemCount falls back to one when modem count is unavailable`() {
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setPhoneCount(0)
        assertEquals(1, SimDetector.getSupportedModemCount(context))
        assertFalse(SimDetector.isMultiSimDevice(context))
    }

    @Test
    @Config(sdk = [29])
    fun `modem count is cached after the first read`() {
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setPhoneCount(2)
        assertEquals(2, SimDetector.getSupportedModemCount(context))
        shadowOf(tm).setPhoneCount(9)
        assertEquals(2, SimDetector.getSupportedModemCount(context))
    }
}
