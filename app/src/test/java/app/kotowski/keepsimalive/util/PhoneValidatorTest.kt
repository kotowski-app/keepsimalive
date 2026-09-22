package app.kotowski.keepsimalive.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneValidatorTest {
    @Test
    fun `valid reserved range number at the lower bound`() {
        // 555-01XX is reserved for fictional use, so the examples can never be real subscribers.
        assertTrue(isValidE164("+15550100"))
    }

    @Test
    fun `valid reserved range number in the middle`() {
        assertTrue(isValidE164("+15550150"))
    }

    @Test
    fun `valid reserved range number at the upper bound`() {
        assertTrue(isValidE164("+15550199"))
    }

    @Test
    fun `valid minimum length 7 digits`() {
        assertTrue(isValidE164("+1234567"))
    }

    @Test
    fun `valid maximum length 15 digits`() {
        assertTrue(isValidE164("+123456789012345"))
    }

    @Test
    fun `empty string is invalid`() {
        assertFalse(isValidE164(""))
    }

    @Test
    fun `missing plus sign is invalid`() {
        assertFalse(isValidE164("79001234567"))
    }

    @Test
    fun `plus only is invalid`() {
        assertFalse(isValidE164("+"))
    }

    @Test
    fun `non digit characters are invalid`() {
        assertFalse(isValidE164("+15550100a"))
        assertFalse(isValidE164("+1555 0100"))
        assertFalse(isValidE164("+1-555-0100"))
    }

    @Test
    fun `leading zero after plus is invalid`() {
        assertFalse(isValidE164("+09001234567"))
    }

    @Test
    fun `too short is invalid`() {
        assertFalse(isValidE164("+123456"))
    }

    @Test
    fun `too long is invalid`() {
        assertFalse(isValidE164("+1234567890123456"))
    }

    @Test
    fun `negative numbers are invalid`() {
        assertFalse(isValidE164("+15550101-"))
    }

    @Test
    fun `spaces and formatting are invalid`() {
        assertFalse(isValidE164("+1555 0100"))
        assertFalse(isValidE164("  +15550150"))
        assertFalse(isValidE164("+15550199 "))
    }

    @Test
    fun `unicode fullwidth digits are invalid`() {
        assertFalse(isValidE164("+１２３４５６７８"))
    }

    @Test
    fun `unicode arabic-indic digits are invalid`() {
        assertFalse(isValidE164("+٥٥٥٠١٠٠"))
    }

    @Test
    fun `unicode devanagari digits are invalid`() {
        assertFalse(isValidE164("+५५५०१००"))
    }

    @Test
    fun `mixed ascii and unicode digits are invalid`() {
        assertFalse(isValidE164("+5５550100"))
    }
}
