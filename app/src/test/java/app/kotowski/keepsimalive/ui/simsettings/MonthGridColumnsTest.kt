package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

// Thresholds for the 124dp minimum chip width (which reserves the check icon): a slot
// needs availableWidth/columns >= 124, so 6 columns need >= 744, 4 need >= 496, 3 need
// >= 372, 2 need >= 248, and anything narrower falls back to one column.
class MonthGridColumnsTest {
    @Test
    fun `the column count is always a divisor of 12 so every row is equal`() {
        val widths = listOf(0.dp, 247.dp, 248.dp, 296.dp, 371.dp, 372.dp, 495.dp, 496.dp, 528.dp, 743.dp, 744.dp, 1200.dp)
        widths.forEach { width ->
            val columns = monthGridColumns(width)
            assertEquals(0, 12 % columns)
        }
    }

    @Test
    fun `a phone width gets two columns`() {
        // 360dp phone minus the screen and card padding: 296dp. The wider chips (which
        // reserve the check) drop the phone from three to two columns.
        assertEquals(2, monthGridColumns(296.dp))
    }

    @Test
    fun `a large phone or small tablet width gets three columns`() {
        assertEquals(3, monthGridColumns(372.dp))
        assertEquals(3, monthGridColumns(495.dp))
    }

    @Test
    fun `a tablet width gets four columns`() {
        assertEquals(4, monthGridColumns(496.dp))
        assertEquals(4, monthGridColumns(528.dp))
        assertEquals(4, monthGridColumns(743.dp))
    }

    @Test
    fun `a wide tablet gets the six column cap`() {
        assertEquals(6, monthGridColumns(744.dp))
        // Wider still: the cap stays at 6 (a 6-6 grid), never 12 columns.
        assertEquals(6, monthGridColumns(1200.dp))
    }

    @Test
    fun `narrow widths fall back to two then one column`() {
        assertEquals(2, monthGridColumns(248.dp))
        assertEquals(1, monthGridColumns(247.dp))
        assertEquals(1, monthGridColumns(0.dp))
    }

    @Test
    fun `just below a step stays on the smaller grid`() {
        assertEquals(2, monthGridColumns(371.dp))
        assertEquals(3, monthGridColumns(495.dp))
        assertEquals(4, monthGridColumns(743.dp))
    }
}
