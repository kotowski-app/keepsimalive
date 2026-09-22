package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.util.SimPhoneStateData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimDetailHeaderTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun data(
        displayName: String,
        slotIndex: Int,
    ) = SimPhoneStateData(
        simId = 1,
        displayName = displayName,
        carrierName = "Carrier",
        slotIndex = slotIndex,
        networkOperatorName = "Op",
        simOperatorName = "Op",
        isNetworkRoaming = false,
    )

    @Test
    fun `header appends slot in parentheses after the sim name on multi sim devices`() {
        assertEquals("MySIM (SIM 2)", simDetailHeader(context, data("MySIM", 2), true))
    }

    @Test
    fun `header shows slot 1 on multi sim devices`() {
        assertEquals("Vodafone (SIM 1)", simDetailHeader(context, data("Vodafone", 1), true))
    }

    @Test
    fun `header falls back to default title with slot when name is empty`() {
        assertEquals("SIM details (SIM 1)", simDetailHeader(context, data("", 1), true))
    }

    @Test
    fun `header works with stub slot values`() {
        assertEquals("Vodafone (SIM 101)", simDetailHeader(context, data("Vodafone", 101), true))
    }

    @Test
    fun `header omits slot on single sim devices`() {
        assertEquals("MySIM", simDetailHeader(context, data("MySIM", 2), false))
    }

    @Test
    fun `header omits slot and falls back to default title on single sim devices`() {
        assertEquals("SIM details", simDetailHeader(context, data("", 1), false))
    }

    @Test
    fun `header shows SIM Missing instead of the slot while the sim is absent on multi sim devices`() {
        assertEquals("Vodafone (SIM missing)", simDetailHeader(context, data("Vodafone", 1), true, simMissing = true))
    }

    @Test
    fun `header keeps the slot while the sim is present`() {
        assertEquals("Vodafone (SIM 1)", simDetailHeader(context, data("Vodafone", 1), true, simMissing = false))
    }

    @Test
    fun `header keeps the plain name while the sim is absent on single sim devices`() {
        assertEquals("Vodafone", simDetailHeader(context, data("Vodafone", 1), false, simMissing = true))
    }
}
