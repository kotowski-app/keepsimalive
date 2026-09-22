package app.kotowski.keepsimalive.navigation

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Pins simIdOf: the sim_detail destination renders only for real subscription ids
// (starting at 1). A missing or corrupted argument (e.g. a back stack restored from
// state saved without the argument) must yield null, so the destination renders
// nothing instead of showing the removed-SIM card for a SIM that never existed.
@RunWith(RobolectricTestRunner::class)
class NavGraphSimIdTest {
    @Test
    fun `a valid subscription id is returned`() {
        val arguments = Bundle().apply { putInt("simId", 5) }

        assertEquals(5, simIdOf(arguments))
    }

    @Test
    fun `a missing argument yields null`() {
        assertNull(simIdOf(Bundle()))
    }

    @Test
    fun `null arguments yield null`() {
        assertNull(simIdOf(null))
    }

    @Test
    fun `the zero default yields null`() {
        val arguments = Bundle().apply { putInt("simId", 0) }

        assertNull(simIdOf(arguments))
    }

    @Test
    fun `a negative id yields null`() {
        val arguments = Bundle().apply { putInt("simId", -1) }

        assertNull(simIdOf(arguments))
    }
}
