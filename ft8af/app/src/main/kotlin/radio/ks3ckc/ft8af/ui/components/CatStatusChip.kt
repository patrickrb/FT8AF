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
    /**
     * Visible plain-language label for the redesigned strip: "Radio linked" once a link is
     * up, "Radio not linked" otherwise. The dot color still varies per state (amber while
     * connecting, red on error); only the two-word status text is collapsed so non-expert
     * operators aren't shown "CAT connecting…".
     */
    val labelRes: Int,
)

/**
 * Map a CAT connection state to its dot color / pulse / accessibility label / visible label.
 * grey = disconnected, amber pulsing = connecting, green = connected, red = error. The visible
 * label reads "Radio linked" only when CONNECTED, "Radio not linked" in every other state.
 */
internal fun catChipVisuals(state: CatConnectionState): CatChipVisuals = when (state) {
    CatConnectionState.DISCONNECTED -> CatChipVisuals(
        dotColor = TextMuted,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_disconnected,
        labelRes = R.string.cat_status_not_linked,
    )
    CatConnectionState.CONNECTING -> CatChipVisuals(
        dotColor = Accent,
        pulsing = true,
        contentDescriptionRes = R.string.cat_status_connecting,
        labelRes = R.string.cat_status_not_linked,
    )
    CatConnectionState.CONNECTED -> CatChipVisuals(
        dotColor = StatusConfirmed,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_connected,
        labelRes = R.string.cat_status_linked,
    )
    CatConnectionState.ERROR -> CatChipVisuals(
        dotColor = StatusBad,
        pulsing = false,
        contentDescriptionRes = R.string.cat_status_error,
        labelRes = R.string.cat_status_not_linked,
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
            .clip(RoundedCornerShape(8.dp))
            .background(BgSurface3)
            .clickable(onClickLabel = description) { onReconnect() }
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = visuals.dotColor, pulsing = visuals.pulsing)
        Text(
            text = stringResource(visuals.labelRes),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = InterFamily,
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
