package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoggerTest {
    @Before
    fun setup() {
        LogBuffer.clear()
    }

    @Test
    fun `Logger i adds info entry to LogBuffer`() {
        Logger.i("Test", "info message")
        val entries = LogBuffer.entries
        assertEquals(1, entries.size)
        assertEquals("I", entries[0].level)
        assertEquals("Test", entries[0].tag)
        assertEquals("info message", entries[0].message)
    }

    @Test
    fun `Logger w adds warn entry to LogBuffer`() {
        Logger.w("Test", "warn message")
        assertEquals("W", LogBuffer.entries[0].level)
    }

    @Test
    fun `Logger e without throwable adds error entry`() {
        Logger.e("Test", "error message")
        assertEquals("E", LogBuffer.entries[0].level)
        assertEquals("error message", LogBuffer.entries[0].message)
    }

    @Test
    fun `Logger e with throwable includes exception message`() {
        Logger.e("Test", "error", RuntimeException("boom"))
        assertEquals("E", LogBuffer.entries[0].level)
        assertTrue(
            "Expected 'boom' in message but got: ${LogBuffer.entries[0].message}",
            LogBuffer.entries[0].message.contains("boom"),
        )
    }

    @Test
    fun `Logger i with throwable adds entry`() {
        Logger.i("Test", "info", NullPointerException("test"))
        assertEquals("I", LogBuffer.entries[0].level)
    }

    @Test
    fun `Logger w with throwable adds entry`() {
        Logger.w("Test", "warn", SecurityException("test"))
        assertEquals("W", LogBuffer.entries[0].level)
    }

    @Test
    fun `multiple log entries are all captured`() {
        Logger.i("T", "i msg")
        Logger.w("T", "w msg")
        Logger.e("T", "e msg")
        assertEquals(3, LogBuffer.entries.size)
        assertEquals(listOf("I", "W", "E"), LogBuffer.entries.map { it.level })
    }
}
