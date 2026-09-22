package app.kotowski.keepsimalive.work

import android.app.Application
import app.kotowski.keepsimalive.util.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File

// The gate replaces the raw Process.myProcessName check: it must resolve the name through
// the API when the framework has it, fall back to /proc/self/cmdline when it does not,
// refuse only a proven foreign process, and never let an undeterminable name kill the
// send path.
@RunWith(RobolectricTestRunner::class)
class SendProcessGateTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        LogBuffer.clear()
    }

    @Test
    @Config(sdk = [34])
    fun `the name resolves through the myProcessName API when the framework has it`() {
        ShadowProcess.setProcessName(context.packageName)
        assertEquals(context.packageName, SendProcessGate.currentProcessName())
    }

    @Test
    fun `the cmdline parse takes the first NUL-terminated field`() {
        assertEquals("app.example", SendProcessGate.parseCmdline("app.example\u0000"))
        assertEquals("a", SendProcessGate.parseCmdline("a\u0000b\u0000"))
        assertNull(SendProcessGate.parseCmdline(null))
        assertNull(SendProcessGate.parseCmdline(""))
        assertNull(SendProcessGate.parseCmdline("  \u0000"))
    }

    @Test
    fun `cmdlineName agrees with a direct read of the proc cmdline file when the host has it`() {
        // Skipped on hosts without /proc (e.g. Windows): the fallback is a Linux/Android path.
        val raw = runCatching { File("/proc/self/cmdline").readText() }.getOrNull() ?: return
        assertEquals(SendProcessGate.parseCmdline(raw), SendProcessGate.cmdlineName())
    }

    @Test
    fun `running in the app process is not a violation`() {
        assertNull(SendProcessGate.processViolation("app.example", "app.example"))
    }

    @Test
    fun `a foreign process is refused naming both sides`() {
        val refusal = SendProcessGate.processViolation("app.example", "app.example:worker")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("app.example:worker"))
        assertTrue(refusal.contains("(expected 'app.example')"))
    }

    @Test
    fun `an undeterminable name warns without refusing`() {
        assertNull(SendProcessGate.processViolation("app.example", null))
        assertTrue(
            LogBuffer.entries.any { it.level == "W" && it.message.contains("undeterminable") },
        )
    }
}
