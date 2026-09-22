package app.kotowski.keepsimalive.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TimeWindowStepsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `ladder starts at zero and ends at 28 days`() {
        assertEquals(0, TimeWindowSteps.STEPS.first())
        assertEquals(40320, TimeWindowSteps.STEPS.last())
    }

    @Test
    fun `ladder tail is hourly doubling then week aligned days`() {
        assertArrayEquals(
            intArrayOf(120, 240, 480, 960, 1440, 2880, 5760, 10080, 20160, 40320),
            TimeWindowSteps.STEPS.copyOfRange(13, TimeWindowSteps.STEPS.size),
        )
    }

    @Test
    fun `ladder is strictly increasing`() {
        for (i in 1 until TimeWindowSteps.STEPS.size) {
            assertTrue(TimeWindowSteps.STEPS[i] > TimeWindowSteps.STEPS[i - 1])
        }
    }

    @Test
    fun `values up to one hour keep five minute granularity`() {
        for (v in 0..60 step 5) {
            assertEquals(v, TimeWindowSteps.STEPS[TimeWindowSteps.indexFor(v)])
        }
    }

    @Test
    fun `indexFor returns exact index for ladder values`() {
        for (i in TimeWindowSteps.STEPS.indices) {
            assertEquals(i, TimeWindowSteps.indexFor(TimeWindowSteps.STEPS[i]))
        }
    }

    @Test
    fun `indexFor maps off ladder values to the nearest step`() {
        assertEquals(TimeWindowSteps.indexFor(120), TimeWindowSteps.indexFor(100))
        assertEquals(TimeWindowSteps.indexFor(35), TimeWindowSteps.indexFor(37))
        assertEquals(TimeWindowSteps.indexFor(0), TimeWindowSteps.indexFor(1))
    }

    @Test
    fun `indexAtMost returns the index of the largest step at or below the bound`() {
        assertEquals(0, TimeWindowSteps.indexAtMost(-1))
        assertEquals(0, TimeWindowSteps.indexAtMost(0))
        assertEquals(0, TimeWindowSteps.indexAtMost(4))
        assertEquals(1, TimeWindowSteps.indexAtMost(5))
        assertEquals(11, TimeWindowSteps.indexAtMost(59))
        assertEquals(12, TimeWindowSteps.indexAtMost(60))
        // Off-ladder bounds pick the step below, where indexFor's "nearest" would pick above.
        assertEquals(1, TimeWindowSteps.indexAtMost(7))
        assertEquals(12, TimeWindowSteps.indexAtMost(80))
        assertEquals(TimeWindowSteps.STEPS.lastIndex, TimeWindowSteps.indexAtMost(999_999))
    }

    @Test
    fun `indexAtMost agrees with valueAtMost on the ladder`() {
        for (v in 0..40320 step 23) {
            assertEquals(TimeWindowSteps.indexFor(TimeWindowSteps.valueAtMost(v)), TimeWindowSteps.indexAtMost(v))
        }
    }

    @Test
    fun `valueAtMost returns the largest step at or below the bound`() {
        assertEquals(0, TimeWindowSteps.valueAtMost(-1))
        assertEquals(0, TimeWindowSteps.valueAtMost(0))
        assertEquals(0, TimeWindowSteps.valueAtMost(4))
        assertEquals(5, TimeWindowSteps.valueAtMost(5))
        assertEquals(55, TimeWindowSteps.valueAtMost(59))
        assertEquals(60, TimeWindowSteps.valueAtMost(60))
        assertEquals(10080, TimeWindowSteps.valueAtMost(10800))
        assertEquals(40320, TimeWindowSteps.valueAtMost(40320))
        assertEquals(40320, TimeWindowSteps.valueAtMost(999_999))
    }

    @Test
    fun `next walks the whole ladder from zero to max`() {
        var v = 0
        val seen = mutableListOf(v)
        repeat(TimeWindowSteps.STEPS.size - 1) {
            v = TimeWindowSteps.next(v)
            seen.add(v)
        }
        assertEquals(TimeWindowSteps.STEPS.toList(), seen)
    }

    @Test
    fun `next stays at max and previous stays at min`() {
        assertEquals(40320, TimeWindowSteps.next(40320))
        assertEquals(40320, TimeWindowSteps.next(20160))
        assertEquals(1440, TimeWindowSteps.next(960))
        assertEquals(0, TimeWindowSteps.previous(0))
        assertEquals(5, TimeWindowSteps.previous(10))
    }

    @Test
    fun `previous reverses next below max`() {
        for (v in TimeWindowSteps.STEPS) {
            if (v > 0 && v < TimeWindowSteps.STEPS.last()) {
                assertEquals(v, TimeWindowSteps.previous(TimeWindowSteps.next(v)))
            }
        }
    }

    @Test
    fun `valueFor clamps out of range indices`() {
        assertEquals(0, TimeWindowSteps.valueFor(-3))
        assertEquals(40320, TimeWindowSteps.valueFor(99))
    }

    @Test
    fun `formatValue shows minutes hours and days for every step`() {
        val expected =
            mapOf(
                0 to "0 min",
                5 to "5 min",
                10 to "10 min",
                15 to "15 min",
                20 to "20 min",
                25 to "25 min",
                30 to "30 min",
                35 to "35 min",
                40 to "40 min",
                45 to "45 min",
                50 to "50 min",
                55 to "55 min",
                60 to "1 h",
                120 to "2 h",
                240 to "4 h",
                480 to "8 h",
                960 to "16 h",
                1440 to "1 d",
                2880 to "2 d",
                5760 to "4 d",
                10080 to "7 d",
                20160 to "14 d",
                40320 to "28 d",
            )
        assertEquals(expected.keys, TimeWindowSteps.STEPS.toSet())
        for ((minutes, text) in expected) {
            assertEquals("$minutes", text, TimeWindowSteps.formatValue(context, minutes))
        }
    }
}
