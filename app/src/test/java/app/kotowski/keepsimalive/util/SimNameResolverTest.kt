package app.kotowski.keepsimalive.util

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class SimNameResolverTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `getActiveSims carries the per sub radio state`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(1)
                        .setDisplayName("SIM A")
                        .setCarrierName("Carrier A")
                        .buildSubscriptionInfo(),
                ),
            )
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(1, tm)
        shadowOf(tm).setSimOperatorName("Home Carrier")

        val sims = SimNameResolver.getActiveSims(context)
        assertEquals(1, sims.size)
        assertEquals("SIM A", sims[0].displayName)
        assertEquals("Carrier A", sims[0].carrierName)
        // The identity fields feed the SimIdentityCache: a removed SIM must be shown with
        // its last known operator.
        assertEquals("Home Carrier", sims[0].simOperatorName)
    }

    @Test
    fun `getActiveSims falls back to the i18n unknown carrier when the carrier name is missing`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(1)
                        .setDisplayName(null)
                        .setCarrierName(null)
                        .buildSubscriptionInfo(),
                ),
            )

        val sims = SimNameResolver.getActiveSims(context)
        assertEquals(1, sims.size)
        // The carrier fallback is user-facing (Dashboard card), for a missing (null or empty) name.
        assertEquals(context.getString(R.string.sim_carrier_unknown), sims[0].carrierName)
        assertEquals(context.getString(R.string.sim_carrier_unknown), sims[0].displayName)
    }

    @Test
    fun `getActiveSims returns empty without phone permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertTrue(SimNameResolver.getActiveSims(context).isEmpty())
    }

    @Test
    @Config(sdk = [24])
    fun `getActiveSims degrades to unknown radio state below API 26 instead of crashing`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(1)
                        .setDisplayName("SIM A")
                        .setCarrierName("Carrier A")
                        .buildSubscriptionInfo(),
                ),
            )
        }
        // 7.0/7.1 (API 24/25) OEM telephony is unstable, so the per-SIM radio read is
        // restricted to API 26+: below 26 it is skipped and the radio fields must
        // degrade to "unknown" — no exception, no default-SIM data served as this SIM's.
        val sims = SimNameResolver.getActiveSims(context)
        assertEquals(1, sims.size)
        // The identity still comes from the subscription list (API 22, present on 24/25).
        assertEquals("SIM A", sims[0].displayName)
        assertEquals("Carrier A", sims[0].carrierName)
        // The radio fields degrade to "unknown": the default SIM's data must not be
        // served as this SIM's.
        assertFalse(sims[0].isInService)
        assertEquals("", sims[0].networkOperatorName)
        assertEquals("", sims[0].simOperatorName)
        assertFalse(sims[0].isNetworkRoaming)
    }

    @Test
    fun `getActiveSims re-reads the live list on every call`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val subManager = context.getSystemService(SubscriptionManager::class.java)
        shadowOf(subManager).setActiveSubscriptionInfoList(
            listOf(
                ShadowSubscriptionManager.SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(1)
                    .setDisplayName("SIM A")
                    .setCarrierName("Carrier A")
                    .buildSubscriptionInfo(),
            ),
        )
        assertEquals("SIM A", SimNameResolver.getActiveSims(context)[0].displayName)

        // A SIM switched off in system settings must show up on the very next call: the
        // resolver keeps no cache, so nothing can serve a stale list.
        shadowOf(subManager).setActiveSubscriptionInfoList(emptyList())
        assertTrue(SimNameResolver.getActiveSims(context).isEmpty())
    }

    @Test
    fun `resolveDisplayName returns the live name for an in-tray SIM`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).apply {
            setActiveSubscriptionInfoList(
                listOf(
                    ShadowSubscriptionManager.SubscriptionInfoBuilder
                        .newBuilder()
                        .setId(1)
                        .setDisplayName("SIM A")
                        .setCarrierName("Carrier A")
                        .buildSubscriptionInfo(),
                ),
            )
        }

        assertEquals("SIM A", SimNameResolver.resolveDisplayName(context, 1))
    }

    @Test
    fun `resolveDisplayName falls back to the cached identity when the SIM is out of the tray`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(emptyList())
        SimIdentityCache.save(
            context,
            CachedSimIdentity(
                simId = 7,
                displayName = "Home SIM",
                carrierName = "Carrier A",
                slotIndex = 1,
                networkOperatorName = "",
                simOperatorName = "",
                isNetworkRoaming = false,
                lastSeenAtMillis = 123L,
            ),
        )

        assertEquals("Home SIM", SimNameResolver.resolveDisplayName(context, 7))
    }

    @Test
    fun `resolveDisplayName skips an empty cached name for the i18n fallback`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(emptyList())
        SimIdentityCache.save(
            context,
            CachedSimIdentity(
                simId = 7,
                displayName = "",
                carrierName = "Carrier A",
                slotIndex = 1,
                networkOperatorName = "",
                simOperatorName = "",
                isNetworkRoaming = false,
                lastSeenAtMillis = 123L,
            ),
        )

        assertEquals(
            context.getString(R.string.sim_name_fallback, 7),
            SimNameResolver.resolveDisplayName(context, 7),
        )
    }

    @Test
    fun `resolveDisplayName uses the i18n fallback for a SIM that is unknown and never cached`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(emptyList())

        assertEquals(
            context.getString(R.string.sim_name_fallback, 999),
            SimNameResolver.resolveDisplayName(context, 999),
        )
    }
}
