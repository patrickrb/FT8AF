package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.ft8af.theme.*

/**
 * Visual treatment for the CAT status dot, derived purely from connection state
 * so it can be unit-tested without Compose. The visible label is always "CAT";
 * [contentDescriptionRes] is the TalkBack description that varies by state.
 */
internal data class CatChipVisuals(
    val dotColor: Color,
    val pulsing: Boolean,
    val contentDescriptionRes: Int,
)

/**
 * Map a CAT connection state to its dot color / pulse / accessibility label.
 * grey = disconnected, amber pulsing = connecting, green = connected, red = error.
 */
internal fun catChipVisuals(state: CatConnectionState): CatChipVisuals = when (state) {
    CatConnectionState.DISCONNECTED -> CatChipVisuals(
        dotColor = TextMuted,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_disconnected,
    )
    CatConnectionState.CONNECTING -> CatChipVisuals(
        dotColor = Accent,
        pulsing = true,
        contentDescriptionRes = R.string.cat_status_connecting,
    )
    CatConnectionState.CONNECTED -> CatChipVisuals(
        dotColor = StatusConfirmed,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_connected,
    )
    CatConnectionState.ERROR -> CatChipVisuals(
        dotColor = StatusBad,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_error,
    )
}

/**
 * Whether the CAT status chip should be shown. Shown when a rig-control mode is
 * configured (anything other than VOX, which keys via audio and has no CAT link)
 * or while a connection is in progress / connected / errored — so audio-only
 * setups aren't cluttered with an irrelevant indicator.
 */
internal fun shouldShowCatChip(controlMode: Int, state: CatConnectionState): Boolean =
    controlMode != ControlMode.VOX || state != CatConnectionState.DISCONNECTED

/** What tapping the CAT chip should do, given the current connection state. */
enum class CatTapAction { RECONNECT_EXISTING, CONNECT_SAVED_FLEX, NEEDS_SETUP }

/**
 * Decide what a tap on the CAT chip does. If a connector already exists, just
 * reconnect it (the existing behavior — handy for Bluetooth/Flex retries).
 * Otherwise, on a cold start, if the saved rig is a network Flex with a
 * remembered address, connect straight to it — "my settings are set, just
 * connect" — instead of doing nothing (the old reconnectRig() no-ops with no
 * connector). With nothing saved to go on, the user needs to set the rig up.
 *
 * Pure so the routing is unit-tested without the rig/connect plumbing.
 */
internal fun catTapAction(
    connectorExists: Boolean,
    isFlexNetwork: Boolean,
    savedFlexIp: String,
): CatTapAction = when {
    connectorExists -> CatTapAction.RECONNECT_EXISTING
    isFlexNetwork && savedFlexIp.isNotBlank() -> CatTapAction.CONNECT_SAVED_FLEX
    else -> CatTapAction.NEEDS_SETUP
}

/**
 * A small CAT (rig control) connection indicator for the TX strip. Tappable to
 * re-trigger the connection — Bluetooth often only connects on the second try,
 * so a one-tap retry beats digging back into Settings.
 */
@Composable
fun CatStatusChip(
    state: CatConnectionState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = catChipVisuals(state)
    val description = stringResource(visuals.contentDescriptionRes)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgSurface3)
            .clickable(onClickLabel = description) { onReconnect() }
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusDot(color = visuals.dotColor, pulsing = visuals.pulsing)
        Text(
            text = stringResource(R.string.cat_status_chip_label),
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "catPulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "catPulseAlpha",
        )
        animated
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
