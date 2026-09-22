package app.kotowski.keepsimalive.work

import app.kotowski.keepsimalive.util.Logger
import java.io.File

// The per-SIM send lock (SimSendLock) is an in-process Mutex: if SendWorker ever runs in a
// separate process, the exclusion between the worker and the UI funnels breaks silently.
// This gate pins that default-executor premise at worker startup.
//
// Process.myProcessName (API 28) is missing from some OEM-patched frameworks, and a worker
// dying on its first line takes the whole send path down silently, so the API is probed
// once via reflection, with /proc/self/cmdline (present on every Android device) as
// fallback. A genuine wrong-process verdict fails the work loudly — through the app log,
// so it shows in the LogViewer instead of only in system logcat.
internal object SendProcessGate {
    // Probed once per process: a framework that lacks the method (or a failing probe) must
    // never throw into the worker.
    private val apiName: (() -> String?)? =
        runCatching {
            val method = Class.forName("android.os.Process").getMethod("myProcessName")
            val lookup: () -> String? = { runCatching { method.invoke(null) as? String }.getOrNull() }
            lookup
        }.getOrNull()

    fun currentProcessName(): String? {
        val fromApi = apiName?.invoke()
        if (fromApi != null) return fromApi
        // The framework has no usable Process.myProcessName (OEM-patched builds strip it):
        // fall back to /proc/self/cmdline and leave a trace — the case hardest to confirm
        // on a real device.
        val fromCmdline = cmdlineName()
        Logger.i("SendProcessGate", "Process.myProcessName unavailable, using /proc/self/cmdline: $fromCmdline")
        return fromCmdline
    }

    fun cmdlineName(): String? = runCatching { File("/proc/self/cmdline").readText() }.getOrNull()?.let { parseCmdline(it) }

    fun parseCmdline(raw: String?): String? = raw?.split('\u0000')?.firstOrNull { it.isNotBlank() }?.trim()

    // Non-null when the worker provably runs in a foreign process (the in-process
    // send-lock premise is broken); null in the app process; null — with a warning — when
    // the name cannot be determined at all: a last-resort undeterminability must not
    // delete the send path.
    fun processViolation(
        appPackageName: String,
        actualProcessName: String?,
    ): String? =
        when {
            actualProcessName == null -> {
                Logger.w(
                    "SendProcessGate",
                    "process name undeterminable (expected '$appPackageName'): the in-process " +
                        "send-lock premise is not verified",
                )
                null
            }

            actualProcessName == appPackageName -> {
                null
            }

            else -> {
                "SendWorker runs in process '$actualProcessName' (expected '$appPackageName'): " +
                    "the per-SIM send lock is in-process only and would be silently broken"
            }
        }
}
