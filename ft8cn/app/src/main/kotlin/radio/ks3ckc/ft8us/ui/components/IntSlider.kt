package radio.ks3ckc.ft8us.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Whether the internal raw slider position should be re-synced to an
 * externally-provided integer value. Returns `true` when the two differ
 * (e.g. after a +/- button press) and `false` during a drag where the
 * rounded raw position still matches the controlled value.
 */
internal fun shouldSyncSliderPos(rawPos: Float, externalValue: Int): Boolean =
    rawPos.roundToInt() != externalValue

/**
 * Map a pixel x-coordinate on the track to a value in [rangeStart]..[rangeEnd].
 */
internal fun pixelToValue(
    px: Float,
    trackStartPx: Float,
    trackEndPx: Float,
    rangeStart: Float,
    rangeEnd: Float,
): Float {
    if (trackEndPx <= trackStartPx) return rangeStart
    val fraction = ((px - trackStartPx) / (trackEndPx - trackStartPx)).coerceIn(0f, 1f)
    return rangeStart + fraction * (rangeEnd - rangeStart)
}

/**
 * A custom slider backed by an [Int] value with reliable drag support.
 *
 * Material3 [androidx.compose.material3.Slider] on Compose BOM 2024.02.00
 * prematurely exits its drag state (compose-multiplatform#4366), making
 * sliders tap-only. This implementation handles horizontal drag and tap
 * gestures directly via [pointerInput], bypassing the broken drag handling.
 */
@Composable
internal fun IntSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    thumbColor: Color,
    activeTrackColor: Color,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    inactiveTrackColor: Color = activeTrackColor.copy(alpha = 0.24f),
) {
    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive

    // Raw float position — avoids int snap that kills drag gestures.
    var rawPos by remember { mutableFloatStateOf(value.toFloat()) }

    // Sync when value changes externally (e.g. +/- buttons).
    // Done in SideEffect to avoid state writes during composition.
    SideEffect {
        if (shouldSyncSliderPos(rawPos, value)) {
            rawPos = value.toFloat().coerceIn(rangeStart, rangeEnd)
        }
    }

    val fraction = if (rangeEnd > rangeStart) {
        ((rawPos - rangeStart) / (rangeEnd - rangeStart)).coerceIn(0f, 1f)
    } else 0f

    val thumbRadius = 10.dp
    val trackHeight = 4.dp

    Box(
        modifier = modifier
            .height(48.dp) // Minimum touch target
            .fillMaxWidth()
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = rangeStart..rangeEnd,
                )
                stateDescription = "${value.toFloat().roundToInt()}"
                setProgress { targetValue ->
                    val clamped = targetValue.roundToInt()
                        .coerceIn(rangeStart.roundToInt(), rangeEnd.roundToInt())
                    onValueChange(clamped)
                    onValueChangeFinished?.invoke()
                    true
                }
            }
            .pointerInput(rangeStart, rangeEnd) {
                detectTapGestures { offset ->
                    val padding = thumbRadius.toPx()
                    val newVal = pixelToValue(
                        offset.x, padding, size.width - padding,
                        rangeStart, rangeEnd,
                    )
                    rawPos = newVal
                    onValueChange(newVal.roundToInt())
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(rangeStart, rangeEnd) {
                val padding = thumbRadius.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        rawPos = rawPos.roundToInt().toFloat()
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        rawPos = value.toFloat()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val newVal = pixelToValue(
                            change.position.x, padding, size.width - padding,
                            rangeStart, rangeEnd,
                        )
                        rawPos = newVal
                        onValueChange(newVal.roundToInt())
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val padding = thumbRadius.toPx()
            val trackStartX = padding
            val trackEndX = size.width - padding
            val centerY = size.height / 2f
            val activeEndX = trackStartX + fraction * (trackEndX - trackStartX)
            val trackHeightPx = trackHeight.toPx()

            // Inactive track
            drawLine(
                color = inactiveTrackColor,
                start = Offset(activeEndX, centerY),
                end = Offset(trackEndX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            // Active track
            drawLine(
                color = activeTrackColor,
                start = Offset(trackStartX, centerY),
                end = Offset(activeEndX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            // Thumb
            drawCircle(
                color = thumbColor,
                radius = thumbRadius.toPx(),
                center = Offset(activeEndX, centerY),
            )
        }
    }
}
