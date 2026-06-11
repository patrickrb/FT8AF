package radio.ks3ckc.ft8us.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.animateFloatAsState
import radio.ks3ckc.ft8us.theme.*

@Composable
fun FT8USBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Hardware / gesture back dismisses the sheet while it is shown, instead of
    // falling through to the activity's back handler (which would try to exit
    // the app — the bug behind the band picker not closing on Back). Disabled
    // when hidden so Back still propagates to the app handler then.
    BackHandler(enabled = visible) { onDismiss() }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }

    // Reset drag offset whenever the sheet hides/shows so a reopened
    // sheet always starts at rest.
    LaunchedEffect(visible) { dragOffset = 0f }

    // Snap-back / commit animation: while dragging, dragOffset tracks the
    // finger; when released past the threshold the consumer (onDismiss)
    // hides the sheet, and the next time it shows we reset above.
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        label = "ft8-sheet-drag-offset",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // Scrim overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB805080E)) // rgba(5,8,14,0.72)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, animatedOffset.toInt()) }
                    .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(BgSurface2)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle — captures vertical drags to dismiss / minimize.
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(72.dp)
                        .height(20.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > dismissThresholdPx) {
                                        onDismiss()
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                                onDragCancel = { dragOffset = 0f },
                                onVerticalDrag = { _, dy ->
                                    val maxOffset = if (sheetHeightPx > 0f) sheetHeightPx else Float.MAX_VALUE
                                    dragOffset = (dragOffset + dy).coerceIn(0f, maxOffset)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0x6694A3B8)) // rgba(148,163,184,0.40)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            }
        }
    }
}
