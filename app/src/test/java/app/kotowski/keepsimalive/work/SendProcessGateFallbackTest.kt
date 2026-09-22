package app.kotowski.keepsimalive.work

import app.kotowski.keepsimalive.util.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

// Runs on the plain JVM, without Robolectric: the android.os.Process stub is unusable there
// (the probe finds the signature at most, and the call throws), so the gate must fall back
// to /proc/self/cmdline — the same situation as the OEM-patched frameworks that strip
// Process.myProcessName.
class SendProcessGateFallbackTest {
    @Before
    fun setup() {
        LogBuffer.clear()
    }

    @Test
    fun `the cmdline fallback resolves the name and is logged when the API is unusable`() {
        val raw = runCatching { File("/proc/self/cmdline").readText() }.getOrNull()
        val name = SendProcessGate.currentProcessName()
        val refusal = SendProcessGate.processViolation("app.example", name)

        assertTrue(
            LogBuffer.entries.any { it.level == "I" && it.message.contains("/proc/self/cmdline") },
        )

        if (raw == null) {
            // Hosts without /proc (e.g. Windows) leave the fallback nothing to read: the
            // name stays null and the gate warns instead of refusing.
            assertNull(name)
            assertNull(refusal)
            assertTrue(
                LogBuffer.entries.any { it.level == "W" && it.message.contains("undeterminable") },
            )
            return
        }
        assertEquals(SendProcessGate.parseCmdline(raw), name)
        // The host process name never matches the fictional "app.example", so the
        // fallback-resolved name must produce a foreign-process refusal naming it.
        assertNotNull(refusal)
        assertNotNull(name)
        assertTrue(refusal!!.contains(name!!))
    }
}
