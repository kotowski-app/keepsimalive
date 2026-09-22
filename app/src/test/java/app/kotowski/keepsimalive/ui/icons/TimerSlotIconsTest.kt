package app.kotowski.keepsimalive.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSlotIconsTest {
    @Test
    fun `timer 1 icon builds with expected metadata`() {
        assertIcon(Timer1Icon, "timer_1")
    }

    @Test
    fun `timer 2 icon builds with expected metadata`() {
        assertIcon(Timer2Icon, "timer_2")
    }

    @Test
    fun `icons are stable singletons`() {
        assertSame(Timer1Icon, Timer1Icon)
        assertSame(Timer2Icon, Timer2Icon)
    }

    @Test
    fun `icons are distinct`() {
        val first = Timer1Icon
        val second = Timer2Icon
        assertTrue(first !== second)
    }

    private fun assertIcon(
        icon: ImageVector,
        name: String,
    ) {
        assertEquals(name, icon.name)
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
        assertTrue(icon.root.size >= 1)
        icon.root.forEach { node ->
            assertTrue(node is VectorPath && node.pathData.isNotEmpty())
        }
    }
}
