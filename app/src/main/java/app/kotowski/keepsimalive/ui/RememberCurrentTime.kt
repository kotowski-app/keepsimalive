package app.kotowski.keepsimalive.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.kotowski.keepsimalive.util.AppConfig
import kotlinx.coroutines.delay

// The 1 s wall clock for the relative-time text (the dashboard's "sent 5 min ago" lines,
// the SIM details' countdown): it ticks while the screen is on screen (below STARTED the
// loop cancels, so no ticks happen off-screen) and runs its first pass immediately on
// every resume. The value is plain wall-clock time that only the screen displays, so the
// screen owns it.
@Composable
fun rememberCurrentTime(): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                currentTime = now
                delay(tickDelayUntilNextBoundary(now))
            }
        }
    }
    return currentTime
}

// Drift-free tick delay: the time until the next absolute wall-clock second boundary.
// Every tick re-derives its delay from the wall clock instead of a fixed 1 s, so timer
// slack does not accumulate (the error stays bounded by a single slack). The result is
// always in [1, TICK_MS] (never a hot loop).
internal fun tickDelayUntilNextBoundary(now: Long): Long = AppConfig.CURRENT_TIME_TICK_MS - (now % AppConfig.CURRENT_TIME_TICK_MS)
