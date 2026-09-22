package app.kotowski.keepsimalive.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimIdentityCacheTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun identity(
        simId: Int = 1,
        displayName: String = "Work SIM",
    ) = CachedSimIdentity(
        simId = simId,
        displayName = displayName,
        carrierName = "Test Carrier",
        slotIndex = 2,
        networkOperatorName = "AIS",
        simOperatorName = "Test Carrier",
        isNetworkRoaming = true,
        lastSeenAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `save then get returns the same identity`() {
        SimIdentityCache.save(context, identity())
        val cached = SimIdentityCache.get(context, 1)
        assertEquals(identity(), cached)
    }

    @Test
    fun `get returns null for a sim that was never cached`() {
        assertNull(SimIdentityCache.get(context, 1))
    }

    @Test
    fun `get returns an identity with an empty display name`() {
        SimIdentityCache.save(context, identity(displayName = ""))
        val cached = SimIdentityCache.get(context, 1)
        assertEquals("", cached?.displayName)
    }

    @Test
    fun `save overwrites the previous identity of the same sim`() {
        SimIdentityCache.save(context, identity())
        SimIdentityCache.save(context, identity(displayName = "Renamed"))
        assertEquals("Renamed", SimIdentityCache.get(context, 1)?.displayName)
    }

    @Test
    fun `save with a seen refresh inside the minimum interval is skipped`() {
        SimIdentityCache.save(context, identity())
        SimIdentityCache.save(context, identity().copy(lastSeenAtMillis = 1_700_000_005_000L))
        // 5 s is inside the 60 s minimum interval: the stored entry, seen included, stays as is.
        assertEquals(
            identity(),
            SimIdentityCache.get(context, 1),
        )
    }

    @Test
    fun `save with a seen refresh beyond the minimum interval updates the stored seen`() {
        SimIdentityCache.save(context, identity())
        SimIdentityCache.save(context, identity().copy(lastSeenAtMillis = 1_700_001_200_000L))
        // 20 min beyond the interval: seen moves, the identity fields are kept.
        assertEquals(
            identity().copy(lastSeenAtMillis = 1_700_001_200_000L),
            SimIdentityCache.get(context, 1),
        )
    }

    @Test
    fun `save with a changed field rewrites the whole entry including seen`() {
        SimIdentityCache.save(context, identity())
        SimIdentityCache.save(
            context,
            identity().copy(
                carrierName = "New Carrier",
                lastSeenAtMillis = 1_700_000_005_000L,
            ),
        )
        val cached = SimIdentityCache.get(context, 1)
        assertEquals("New Carrier", cached?.carrierName)
        assertEquals(1_700_000_005_000L, cached?.lastSeenAtMillis)
        assertEquals("Work SIM", cached?.displayName)
    }

    @Test
    fun `save of an unseen sim writes the full entry`() {
        SimIdentityCache.save(context, identity(simId = 3))
        assertEquals(identity(simId = 3), SimIdentityCache.get(context, 3))
    }

    @Test
    fun `identities of different sims do not mix`() {
        SimIdentityCache.save(context, identity(simId = 1))
        SimIdentityCache.save(context, identity(simId = 2, displayName = "Other SIM"))
        assertEquals("Work SIM", SimIdentityCache.get(context, 1)?.displayName)
        assertEquals("Other SIM", SimIdentityCache.get(context, 2)?.displayName)
    }

    @Test
    fun `forget removes the identity of the sim only`() {
        SimIdentityCache.save(context, identity(simId = 1))
        SimIdentityCache.save(context, identity(simId = 2))
        SimIdentityCache.forget(context, 1)
        assertNull(SimIdentityCache.get(context, 1))
        assertEquals("Work SIM", SimIdentityCache.get(context, 2)?.displayName)
    }

    @Test
    fun `prewarm does not throw on empty cache`() {
        SimIdentityCache.prewarm(context)
    }

    @Test
    fun `prewarm warms the prefs file so subsequent get is fast`() {
        SimIdentityCache.prewarm(context)
        SimIdentityCache.save(context, identity())
        assertNotNull(SimIdentityCache.get(context, 1))
    }

    @Test
    fun `toSimPhoneStateData carries every field over`() {
        val data = identity().toSimPhoneStateData()
        assertEquals(1, data.simId)
        assertEquals("Work SIM", data.displayName)
        assertEquals("Test Carrier", data.carrierName)
        assertEquals(2, data.slotIndex)
        assertEquals("AIS", data.networkOperatorName)
        assertEquals("Test Carrier", data.simOperatorName)
        assertTrue(data.isNetworkRoaming)
    }
}
