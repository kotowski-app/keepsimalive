package app.kotowski.keepsimalive.util

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class SimInfoFetcherTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `real sim above the old stub range is absent when not in the active list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        // Presence must come from the active list, not a stub id range: the SIM-removed
        // auto-disable protection relies on it.
        assertFalse(SimInfoFetcher.isSimPresent(context, 19))
    }

    @Test
    fun `real sim above the old stub range is present when in the active list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(19)
                    .buildSubscriptionInfo(),
            )
        }
        assertTrue(SimInfoFetcher.isSimPresent(context, 19))
    }

    @Test
    fun `getSimPhoneState returns live data for a real sim above the old stub range`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(19)
                    .setDisplayName("High Id SIM")
                    .setCarrierName("High Id Carrier")
                    .buildSubscriptionInfo(),
            )
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(19, tm)
        shadowOf(tm).setSimOperatorName("High Id Carrier")
        val data = SimInfoFetcher.getSimPhoneState(context, 19)
        assertNotNull(data)
        assertEquals(19, data!!.simId)
        // Live identity, not a stub card's.
        assertEquals("High Id SIM", data.displayName)
        assertEquals("High Id Carrier", data.carrierName)
    }

    @Test
    fun `getSimPhoneState returns null for a real sim above the old range that left the active list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(19, tm)
        assertNull(SimInfoFetcher.getSimPhoneState(context, 19))
    }

    @Test
    fun `real sim counts as present when the phone permission is missing`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertTrue(SimInfoFetcher.isSimPresent(context, 1))
    }

    @Test
    fun `real sim is absent when not in the active subscription list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        assertFalse(SimInfoFetcher.isSimPresent(context, 1))
    }

    @Test
    fun `real sim is present when in the active subscription list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .buildSubscriptionInfo(),
            )
        }
        assertTrue(SimInfoFetcher.isSimPresent(context, 1))
    }

    @Test
    fun `getSimPhoneState returns live data for an active sim`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("Work SIM")
                    .setCarrierName("Test Carrier")
                    .buildSubscriptionInfo(),
            )
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        shadowOf(tm).setSimOperatorName("Test Carrier")
        val data = SimInfoFetcher.getSimPhoneState(context, 1)
        assertNotNull(data)
        assertEquals(1, data!!.simId)
        assertEquals("Work SIM", data.displayName)
        assertEquals("Test Carrier", data.carrierName)
    }

    @Test
    fun `getSimPhoneState returns null when the sim left the active list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        // The radio is still alive for this subscription id, but the SIM is switched off
        // (not in the active list): it must be reported absent, not with default data.
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        assertNull(SimInfoFetcher.getSimPhoneState(context, 1))
    }

    @Test
    @Config(sdk = [24])
    fun `getSimPhoneState reports a present sim with unknown radio state below API 26`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("Work SIM")
                    .setCarrierName("Test Carrier")
                    .buildSubscriptionInfo(),
            )
        }
        // 7.0/7.1 (API 24/25) OEM telephony is unstable, so the per-SIM radio read is
        // restricted to API 26+. The SIM is in the active list, so it must be reported
        // present — with unknown (empty) radio fields, not absent (null) and not with
        // the default SIM's data.
        val data = SimInfoFetcher.getSimPhoneState(context, 1)
        assertNotNull(data)
        assertEquals("Work SIM", data!!.displayName)
        assertEquals("Test Carrier", data.carrierName)
        assertEquals("", data.networkOperatorName)
        assertEquals("", data.simOperatorName)
        assertFalse(data.isNetworkRoaming)
    }

    @Test
    fun `getSimPhoneState caches the sim identity on success`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("Work SIM")
                    .setCarrierName("Test Carrier")
                    .buildSubscriptionInfo(),
            )
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        shadowOf(tm).setSimOperatorName("Test Carrier")

        assertNotNull(SimInfoFetcher.getSimPhoneState(context, 1))

        val cached = SimIdentityCache.get(context, 1)
        assertNotNull(cached)
        assertEquals("Work SIM", cached!!.displayName)
        assertEquals("Test Carrier", cached.carrierName)
        assertTrue(cached.lastSeenAtMillis > 0)
    }

    @Test
    fun `getSimPhoneState does not cache an absent sim`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        assertNull(SimInfoFetcher.getSimPhoneState(context, 1))
        assertNull(SimIdentityCache.get(context, 1))
    }

    @Test
    fun `getSimPhoneState falls back to i18n strings when the system names are missing`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        // The radio operator fields are non-null in the data class: like the other tests,
        // set them explicitly (the shadow's default is null).
        shadowOf(tm).setSimOperatorName("Test Carrier")

        // A null display/carrier name (rare on real devices) must fall back to the
        // user-facing i18n strings: the name names the subscription id, not a tray slot.
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName(null)
                    .setCarrierName(null)
                    .buildSubscriptionInfo(),
            )
        }
        val data = SimInfoFetcher.getSimPhoneState(context, 1)
        assertNotNull(data)
        assertEquals(context.getString(R.string.sim_name_fallback, 1), data!!.displayName)
        assertEquals(context.getString(R.string.sim_carrier_unknown), data.carrierName)

        // An empty (not just null) system name falls back the same way: without it the
        // empty name would reach the SIM Details header and the cached identity.
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfos(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("")
                    .setCarrierName("")
                    .buildSubscriptionInfo(),
            )
        }
        val emptyData = SimInfoFetcher.getSimPhoneState(context, 1)
        assertNotNull(emptyData)
        assertEquals(context.getString(R.string.sim_name_fallback, 1), emptyData!!.displayName)
        assertEquals(context.getString(R.string.sim_carrier_unknown), emptyData.carrierName)
    }

    @Test
    fun `getSimPhoneState returns null when the phone permission is missing`() {
        // The list read throws without READ_PHONE_STATE: it must degrade to absent (null),
        // not propagate the SecurityException.
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        assertNull(SimInfoFetcher.getSimPhoneState(context, 1))
    }

    @Test
    fun `getSimPhoneState does not overwrite a cached identity for an absent sim`() {
        // The identity cached while the SIM was present (real name, real slot) must survive
        // an absent read: a check-then-use race could serve and cache a fake "SIM 1" /
        // "Unknown" / slot-1 identity.
        SimIdentityCache.save(
            context,
            CachedSimIdentity(
                simId = 1,
                displayName = "Work SIM",
                carrierName = "Test Carrier",
                slotIndex = 2,
                networkOperatorName = "Net",
                simOperatorName = "SimOp",
                isNetworkRoaming = false,
                lastSeenAtMillis = 123L,
            ),
        )
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        assertNull(SimInfoFetcher.getSimPhoneState(context, 1))
        val cached = SimIdentityCache.get(context, 1)
        assertEquals("Work SIM", cached?.displayName)
        assertEquals(2, cached?.slotIndex)
    }
}
