package app.kotowski.keepsimalive.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val REPEAT_INITIAL_DELAY_MS: Long = 400
const val REPEAT_FIRST_INTERVAL_MS: Long = 160
const val REPEAT_MIN_INTERVAL_MS: Long = 25
const val REPEAT_ACCELERATION_FACTOR: Double = 0.85

fun nextRepeatInterval(current: Long): Long = (current * REPEAT_ACCELERATION_FACTOR).toLong().coerceAtLeast(REPEAT_MIN_INTERVAL_MS)

// Fires [onClick] on touch-down and, while held, auto-repeats with accelerating
// speed (see nextRepeatInterval). The inner IconButton's clickable consumes the down
// event, so the down is observed with awaitFirstDown(requireUnconsumed = false); the
// inner clickable also drives the pressed visual.
@Composable
fun RepeatIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String?,
    icon: ImageVector,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {},
        modifier =
            modifier.pointerInput(enabled) {
                if (!enabled) {
                    return@pointerInput
                }
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val job: Job =
                        scope.launch {
                            onClick()
                            delay(REPEAT_INITIAL_DELAY_MS)
                            var interval = REPEAT_FIRST_INTERVAL_MS
                            while (isActive) {
                                onClick()
                                delay(interval)
                                interval = nextRepeatInterval(interval)
                            }
                        }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        // The Final pass comes after Main, so it reveals consumption by
                        // outer gestures (e.g. scroll stealing the pointer).
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any { it.isConsumed }) break
                    }
                    job.cancel()
                }
            },
        enabled = enabled,
        interactionSource = interactionSource,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
