package app.kotowski.keepsimalive.ui.simsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The editor's numeric-field holder: the typed text may be invalid (the user is
// mid-edit), the value can never be (the steppers wrap at the bounds), and every
// change publishes exactly once, synchronously.
class NumberDraftTest {
    @Test
    fun `seeds the value text and no error from the initial value`() {
        val draft = NumberDraft(30, 7, 90)
        assertEquals(30, draft.value)
        assertEquals("30", draft.text)
        assertFalse(draft.error)
    }

    @Test
    fun `a valid typed number updates the value and publishes once`() {
        var published = 0
        val draft = NumberDraft(30, 7, 90)
        draft.onChanged = { published++ }
        draft.onTextChange("45")
        assertEquals(45, draft.value)
        assertEquals("45", draft.text)
        assertFalse(draft.error)
        assertEquals(1, published)
    }

    @Test
    fun `empty text keeps the value and clears the error`() {
        // The "in edit" state: nothing typed yet is not an error, the last valid value
        // stands.
        val draft = NumberDraft(30, 7, 90)
        draft.error = true
        draft.onTextChange("")
        assertEquals(30, draft.value)
        assertEquals("", draft.text)
        assertFalse(draft.error)
    }

    @Test
    fun `non-numeric text keeps the value and sets the error`() {
        var published = 0
        val draft = NumberDraft(30, 7, 90)
        draft.onChanged = { published++ }
        draft.onTextChange("3a")
        assertEquals(30, draft.value)
        assertEquals("3a", draft.text)
        assertTrue(draft.error)
        assertEquals(1, published)
    }

    @Test
    fun `out-of-range text keeps the value and sets the error at both bounds`() {
        val draft = NumberDraft(30, 7, 90)
        draft.onTextChange("91")
        assertEquals(30, draft.value)
        assertTrue(draft.error)
        draft.onTextChange("6")
        assertEquals(30, draft.value)
        assertTrue(draft.error)
    }

    @Test
    fun `stepping down decrements, syncs the text and clears the error`() {
        var published = 0
        val draft = NumberDraft(30, 7, 90)
        draft.error = true
        draft.onChanged = { published++ }
        draft.stepDown()
        assertEquals(29, draft.value)
        assertEquals("29", draft.text)
        assertFalse(draft.error)
        assertEquals(1, published)
    }

    @Test
    fun `stepping up increments and syncs the text`() {
        val draft = NumberDraft(30, 7, 90)
        draft.stepUp()
        assertEquals(31, draft.value)
        assertEquals("31", draft.text)
        assertFalse(draft.error)
    }

    @Test
    fun `the stepper wraps at both bounds`() {
        val draft = NumberDraft(30, 7, 90)
        draft.value = 7
        draft.stepDown()
        assertEquals(90, draft.value)
        assertEquals("90", draft.text)
        draft.stepUp()
        assertEquals(7, draft.value)
        assertEquals("7", draft.text)
    }
}
