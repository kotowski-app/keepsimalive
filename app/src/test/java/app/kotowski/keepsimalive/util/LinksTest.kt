package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    @Test
    fun `the donate link points at the kotowski Buy Me a Coffee page`() {
        assertEquals("https://www.buymeacoffee.com/kotowski", Links.DONATE)
    }

    @Test
    fun `the repo link points at the keepsimalive repository`() {
        assertEquals("https://github.com/kotowski-app/keepsimalive", Links.REPO)
    }

    @Test
    fun `the bug link opens the new-issue page of the repo without a pre-selected type`() {
        assertEquals("https://github.com/kotowski-app/keepsimalive/issues/new", Links.REPORT_BUG)
    }
}
