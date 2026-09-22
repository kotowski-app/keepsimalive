package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

class LogBufferTest {
    @Before
    fun setup() {
        LogBuffer.clear()
    }

    @Test
    fun `add adds entry to buffer`() {
        LogBuffer.addInfo("Test", "test message")
        val entries = LogBuffer.entries
        assertEquals(1, entries.size)
        assertEquals("I", entries[0].level)
        assertEquals("Test", entries[0].tag)
        assertEquals("test message", entries[0].message)
    }

    @Test
    fun `addInfo adds info level entry`() {
        LogBuffer.addInfo("Test", "info message")
        assertEquals("I", LogBuffer.entries[0].level)
    }

    @Test
    fun `addWarn adds warn level entry`() {
        LogBuffer.addWarn("Test", "warn message")
        assertEquals("W", LogBuffer.entries[0].level)
    }

    @Test
    fun `addError adds error level entry`() {
        LogBuffer.addError("Test", "error message")
        assertEquals("E", LogBuffer.entries[0].level)
    }

    @Test
    fun `addError with throwable includes message`() {
        LogBuffer.addError("Test", "error", RuntimeException("boom"))
        assertTrue(LogBuffer.entries[0].message.contains("boom"))
    }

    @Test
    fun `addError with throwable includes the stack trace`() {
        val e = RuntimeException("boom")
        LogBuffer.addError("Test", "error", e)
        val message = LogBuffer.entries[0].message
        // The in-app LogViewer is the primary diagnostic channel: the stack must ride in
        // the message, not only in logcat. The exception is created in this test method,
        // so its top frame anchors on the test class name.
        assertTrue(
            "Expected the stack trace in: $message",
            message.contains("LogBufferTest"),
        )
    }

    @Test
    fun `clear removes all entries`() {
        LogBuffer.addInfo("Test", "msg1")
        LogBuffer.addWarn("Test", "msg2")
        LogBuffer.clear()
        assertTrue(LogBuffer.entries.isEmpty())
    }

    @Test
    fun `entries respects max limit`() {
        repeat(600) {
            LogBuffer.addInfo("Test", "message $it")
        }
        assertTrue(LogBuffer.entries.size <= 500)
    }

    @Test
    fun `toString returns formatted string`() {
        LogBuffer.addInfo("Test", "msg")
        val entry = LogBuffer.entries[0]
        val str = entry.toString()
        assertTrue(str.contains("["))
        assertTrue(str.contains("]"))
        assertTrue(str.contains("I"))
        assertTrue(str.contains("Test"))
        assertTrue(str.contains("msg"))
    }

    @Test
    fun `refresh trigger advances exactly once per add under concurrent writes`() {
        val threads = 8
        val perThread = 50
        val before = LogBuffer.refreshTrigger.value
        val start = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.countDown()
                start.await()
                repeat(perThread) { i ->
                    LogBuffer.addInfo("Test", "t$t-$i")
                }
                done.countDown()
            }.start()
        }
        done.await()
        assertEquals(before + threads * perThread, LogBuffer.refreshTrigger.value)
        assertEquals(threads * perThread, LogBuffer.entries.size)
    }
}
