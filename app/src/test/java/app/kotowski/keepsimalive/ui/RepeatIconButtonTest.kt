package app.kotowski.keepsimalive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatIconButtonTest {
    @Test
    fun `first repeat interval accelerates from 160ms`() {
        assertEquals(136L, nextRepeatInterval(REPEAT_FIRST_INTERVAL_MS))
    }

    @Test
    fun `interval accelerates by 85 percent per step`() {
        assertEquals(115L, nextRepeatInterval(136L))
        assertEquals(97L, nextRepeatInterval(115L))
        assertEquals(82L, nextRepeatInterval(97L))
    }

    @Test
    fun `interval is floored at minimum`() {
        assertEquals(25L, nextRepeatInterval(25L))
        assertEquals(25L, nextRepeatInterval(29L))
        assertEquals(25L, nextRepeatInterval(30L))
        assertEquals(42L, nextRepeatInterval(50L))
        assertEquals(51L, nextRepeatInterval(60L))
        assertEquals(85L, nextRepeatInterval(100L))
    }

    @Test
    fun `acceleration sequence converges to the floor`() {
        var interval = REPEAT_FIRST_INTERVAL_MS
        var steps = 0
        while (interval > REPEAT_MIN_INTERVAL_MS && steps < 100) {
            interval = nextRepeatInterval(interval)
            steps++
        }
        assertEquals(REPEAT_MIN_INTERVAL_MS, interval)
        assertTrue("should converge quickly, took $steps", steps < 20)
    }

    @Test
    fun `constants are sane`() {
        assertTrue(REPEAT_INITIAL_DELAY_MS > REPEAT_FIRST_INTERVAL_MS)
        assertTrue(REPEAT_FIRST_INTERVAL_MS > REPEAT_MIN_INTERVAL_MS)
        assertTrue(REPEAT_ACCELERATION_FACTOR in 0.5..0.99)
    }
}
