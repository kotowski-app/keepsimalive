package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.util.SimPhoneStateData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimDetailOperatorTextTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun data(
        networkOperatorName: String,
        simOperatorName: String,
        isNetworkRoaming: Boolean,
    ) = SimPhoneStateData(
        simId = 1,
        displayName = "SIM",
        carrierName = "SIM",
        slotIndex = 1,
        networkOperatorName = networkOperatorName,
        simOperatorName = simOperatorName,
        isNetworkRoaming = isNetworkRoaming,
    )

    @Test
    fun `not roaming shows visited network name`() {
        assertEquals("T-Mobile", operatorText(context, data("T-Mobile", "T-Mobile", false)))
    }

    @Test
    fun `not roaming with empty name shows placeholder`() {
        assertEquals("\u2014", operatorText(context, data("", "", false)))
    }

    @Test
    fun `not roaming ignores home operator`() {
        assertEquals("T-Mobile", operatorText(context, data("T-Mobile", "Other", false)))
    }

    @Test
    fun `roaming shows home with visited in parentheses`() {
        assertEquals("Vodafone (roaming on AIS)", operatorText(context, data("AIS", "Vodafone", true)))
    }

    @Test
    fun `roaming with unknown home operator shows visited only`() {
        assertEquals("roaming on AIS", operatorText(context, data("AIS", "", true)))
    }

    @Test
    fun `roaming with unknown visited network shows home only`() {
        assertEquals("Vodafone", operatorText(context, data("", "Vodafone", true)))
    }

    @Test
    fun `roaming with both names unknown shows placeholder`() {
        assertEquals("\u2014", operatorText(context, data("", "", true)))
    }
}
