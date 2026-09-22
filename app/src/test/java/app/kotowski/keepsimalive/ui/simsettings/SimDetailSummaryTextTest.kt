package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.data.EndType
import app.kotowski.keepsimalive.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimDetailSummaryTextTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `endConditionText never`() {
        assertEquals("Never", endConditionText(context, EndType.NEVER, 1, null))
    }

    @Test
    fun `endConditionText after n sends uses plurals`() {
        assertEquals("After 5 sends", endConditionText(context, EndType.AFTER_N_SENDS, 5, null))
        assertEquals("After 1 send", endConditionText(context, EndType.AFTER_N_SENDS, 1, null))
    }

    @Test
    fun `endConditionText on date formats the date`() {
        val date = DateUtil.localDateOf(1704067200000L)
        assertEquals(DateUtil.formatDate(context, date), endConditionText(context, EndType.ON_DATE, 1, date))
    }

    @Test
    fun `endConditionText on date without date shows placeholder`() {
        val expected = context.getString(R.string.sim_config_end_on_date_option)
        assertEquals(expected, endConditionText(context, EndType.ON_DATE, 1, null))
    }
}
