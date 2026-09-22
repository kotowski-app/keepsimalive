package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberValidationResultTest {
    @Test
    fun `validateConfigNumber valid value in range`() {
        val result = validateConfigNumber("30", 1, 1, 365)
        assertTrue(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber valid value at min boundary`() {
        val result = validateConfigNumber("1", 30, 1, 365)
        assertTrue(result.isValid)
        assertEquals(1, result.value)
    }

    @Test
    fun `validateConfigNumber valid value at max boundary`() {
        val result = validateConfigNumber("365", 30, 1, 365)
        assertTrue(result.isValid)
        assertEquals(365, result.value)
    }

    @Test
    fun `validateConfigNumber value below min is invalid`() {
        val result = validateConfigNumber("0", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber value above max is invalid`() {
        val result = validateConfigNumber("366", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber negative value is invalid`() {
        val result = validateConfigNumber("-5", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber non numeric text is invalid`() {
        val result = validateConfigNumber("abc", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber empty string preserves current value`() {
        val result = validateConfigNumber("", 30, 1, 365)
        assertTrue(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber monthly day range 1-31`() {
        val result = validateConfigNumber("31", 1, 1, 31)
        assertTrue(result.isValid)
        assertEquals(31, result.value)

        val invalid = validateConfigNumber("32", 1, 1, 31)
        assertFalse(invalid.isValid)
    }

    @Test
    fun `validateConfigNumber monthly interval range 1-12`() {
        val result = validateConfigNumber("12", 1, 1, 12)
        assertTrue(result.isValid)
        assertEquals(12, result.value)

        val invalid = validateConfigNumber("13", 1, 1, 12)
        assertFalse(invalid.isValid)
    }

    @Test
    fun `validateConfigNumber max sends range 1-999`() {
        val result = validateConfigNumber("500", 1, 1, 999)
        assertTrue(result.isValid)
        assertEquals(500, result.value)

        val atMax = validateConfigNumber("999", 1, 1, 999)
        assertTrue(atMax.isValid)
        assertEquals(999, atMax.value)

        val invalid = validateConfigNumber("1000", 1, 1, 999)
        assertFalse(invalid.isValid)
    }

    @Test
    fun `validateConfigNumber whitespace only is invalid`() {
        val result = validateConfigNumber("  ", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `validateConfigNumber decimal number is invalid`() {
        val result = validateConfigNumber("3.14", 30, 1, 365)
        assertFalse(result.isValid)
        assertEquals(30, result.value)
    }

    @Test
    fun `NumberValidationResult equality`() {
        val r1 = NumberValidationResult(30, true)
        val r2 = NumberValidationResult(30, true)
        assertEquals(r1, r2)
    }
}
