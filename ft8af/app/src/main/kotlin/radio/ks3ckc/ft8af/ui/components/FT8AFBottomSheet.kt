package radio.ks3ckc.ft8af.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.animateFloatAsState
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*

@Composable
fun FT8AFBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // A MutableTransitionState lets the exit animation keep the Back handler
    // installed: currentState stays true until the sheet's slide-out completes,
    // even after targetState (visible) has already flipped to false.
    val sheetState = remember { MutableTransitionState(visible) }
    sheetState.targetState = visible

    // Hardware / gesture Back dismisses the sheet while it is on-screen, instead
    // of falling through to the activity's back handler (which would try to exit
    // the app — the bug behind the band picker not closing on Back, #201). The
    // handler stays active through the exit animation so a second Back press
    // during slide-out can't slip past to the app-exit path while the sheet is
    // still visible; once fully hidden it propagates to the app handler again.
    BackHandler(enabled = sheetBackHandlerActive(sheetState.currentState, sheetState.targetState)) {
        onDismiss()
    }

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
        visibleState = sheetState,
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
                // Header row: centered drag handle + explicit close button on
                // the right. A visible tappable close was missing (issue #782:
                // "calling CQ -> 'more' has no back-button"). The drag handle
                // and scrim already dismissed, but neither is discoverable —
                // the close icon gives every sheet a plain Back affordance.
                val closeDescription = stringResource(R.string.sheet_close)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                        .height(28.dp),
                ) {
                    // Drag handle — captures vertical drags to dismiss / minimize.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
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

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0x1F94A3B8))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = closeDescription
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        FT8AFIcons.Close(size = 16.dp, color = TextMuted, strokeWidth = 2f)
                    }
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

/**
 * Whether the sheet's Android-Back handler should be enabled, given the sheet's
 * [MutableTransitionState] components.
 *
 * The handler must stay active not only while the sheet is shown ([targetState])
 * but throughout its exit animation: [currentState] remains true until the
 * slide-out completes. Gating on `currentState || targetState` therefore keeps
 * Back captured during the exit window, so a second press can't fall through to
 * the activity's app-exit handler while the sheet is still on-screen (the
 * follow-up to issue #201). Only once the sheet is fully hidden — both states
 * false — does Back propagate to the app handler again.
 */
internal fun sheetBackHandlerActive(currentState: Boolean, targetState: Boolean): Boolean =
    currentState || targetState
