package app.kotowski.keepsimalive.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import app.kotowski.keepsimalive.util.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The 1 s wall clock the dashboard's relative-time text and the SIM details' countdown
// read: the tick writes real wall-clock time, while the delay parks on the test's virtual
// main clock — so a real-time gap (the sleeps below) makes "a tick happened" provable:
// any tick after the gap carries a wall-clock value past it.
@RunWith(RobolectricTestRunner::class)
class RememberCurrentTimeTest {
    @get:Rule
    val composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var observed: Long = 0L

    private fun render() {
        observed = 0L
        composeTestRule.setContent {
            KeepSimAliveTheme {
                observed = rememberCurrentTime()
            }
        }
    }

    @Test
    fun startsAtTheCurrentWallClock() {
        val before = System.currentTimeMillis()
        render()
        val after = System.currentTimeMillis()
        assertTrue(
            "the first tick runs immediately, so the initial value is the wall-clock now (got $observed)",
            observed in before..after,
        )
    }

    @Test
    fun ticksEverySecondWhileOnScreen() {
        render()
        val initial = observed
        Thread.sleep(1_200)
        val mid = System.currentTimeMillis()
        composeTestRule.mainClock.advanceTimeBy(2_500)
        assertTrue(
            "a tick must re-read the wall clock while on screen (initial $initial, got $observed, mid $mid)",
            observed >= mid,
        )
    }

    @Test
    fun ticksStopBelowStarted() {
        render()
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_200)
        val mid = System.currentTimeMillis()
        // A broken (still running) loop would tick during this advance and carry a value
        // past the gap; a correctly stopped one keeps the value from before it.
        composeTestRule.mainClock.advanceTimeBy(5_000)
        assertTrue(
            "no tick may happen below STARTED (got $observed, mid $mid)",
            observed < mid - 500,
        )
    }

    @Test
    fun ticksResumeAfterReturn() {
        render()
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_200)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val mid = System.currentTimeMillis()
        composeTestRule.mainClock.advanceTimeBy(2_500)
        assertTrue(
            "the tick must resume on return (got $observed, mid $mid)",
            observed >= mid,
        )
    }

    // The drift fix, pinned on the pure delay: whatever wall-clock instant a tick reads,
    // parking for the returned delay lands the next tick exactly on a second boundary, so
    // the per-tick timer slack no longer carries over (the old fixed 1 s delay let it
    // accumulate). The delay stays in [1, 1000] so the loop can never spin.
    @Test
    fun delayAlwaysLandsOnTheNextSecondBoundary() {
        val tick = AppConfig.CURRENT_TIME_TICK_MS
        for (offset in 0L until tick) {
            val now = 1_700_000_000_000L + offset
            val delay = tickDelayUntilNextBoundary(now)
            val landed = now + delay
            assertTrue(
                "the tick after the delay must sit exactly on a second boundary (now $now, delay $delay, landed $landed)",
                landed % tick == 0L,
            )
            assertTrue(
                "the delay must be a positive sub-second wait, never zero (now $now, delay $delay)",
                delay in 1L..tick,
            )
        }
    }
}
