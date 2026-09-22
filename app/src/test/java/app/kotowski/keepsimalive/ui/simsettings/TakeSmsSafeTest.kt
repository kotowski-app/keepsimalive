package app.kotowski.keepsimalive.ui.simsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeSmsSafeTest {
    // One astral character: a single user-visible character, two UTF-16 code units.
    private val emoji = "😀"

    @Test
    fun `input at or below the cap is returned unchanged`() {
        assertEquals("", "".takeSmsSafe(70))
        assertEquals("abc", "abc".takeSmsSafe(70))
        assertEquals("a".repeat(70), "a".repeat(70).takeSmsSafe(70))
    }

    @Test
    fun `input above a zero cap is empty`() {
        assertEquals("", "abc".takeSmsSafe(0))
    }

    @Test
    fun `ascii past the cap is cut to the cap`() {
        assertEquals("a".repeat(70), "a".repeat(100).takeSmsSafe(70))
    }

    @Test
    fun `emoji fitting exactly at the cap is kept whole`() {
        // 68 ascii (68 units) + emoji (2 units) = 70 units: nothing to cut.
        val message = "a".repeat(68) + emoji
        assertEquals(message, message.takeSmsSafe(70))
        assertEquals(70, message.length)
    }

    @Test
    fun `emoji at the boundary is dropped whole instead of split`() {
        // 69 ascii (69 units) + emoji (2 units) = 71 units: a cut at unit 70 would land
        // between the emoji's halves and store an orphan surrogate.
        val message = "a".repeat(69) + emoji
        val cut = message.takeSmsSafe(70)
        assertEquals("a".repeat(69), cut)
        assertFalse(cut.any { Character.isSurrogate(it) })
    }

    @Test
    fun `cut never leaves an orphan surrogate for any boundary shape`() {
        // 69 ascii + two emoji = 72 units: the cut at unit 70 lands inside the first emoji.
        val message = "a".repeat(69) + emoji + emoji
        val cut = message.takeSmsSafe(70)
        assertEquals("a".repeat(69), cut)
        cut.forEach { assertFalse(Character.isSurrogate(it)) }
    }

    @Test
    fun `pre-existing orphan surrogate at the cut is left untouched`() {
        // Malformed input (a pre-existing orphan high surrogate): the helper avoids creating
        // new damage, it does not repair existing orphans.
        val message = "a".repeat(69) + "\uD83D" + "b"
        val cut = message.takeSmsSafe(70)
        assertEquals("a".repeat(69) + "\uD83D", cut)
        assertEquals(70, cut.length)
    }

    @Test
    fun `lone low surrogate at the cut is cut at the cap`() {
        // An orphan low surrogate is not a pair start, so nothing is split: cut at the cap.
        val message = "a".repeat(70) + "\uDC00"
        val cut = message.takeSmsSafe(70)
        assertEquals("a".repeat(70), cut)
    }

    @Test
    fun `result never exceeds the cap`() {
        val message = "a".repeat(69) + emoji
        assertTrue(message.takeSmsSafe(70).length <= 70)
    }
}
