package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.decode.QrzAvatar

/**
 * Operator identity card with amber gradient background, callsign avatar,
 * and mini stats for rig, antenna, and power.
 */
@Composable
fun OperatorCard(
    callsign: String,
    grid: String,
    modifier: Modifier = Modifier,
    rigName: String = "--",
    antenna: String = "--",
    power: String = "--",
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val initials = callsign
        .filter { it.isLetterOrDigit() }
        .take(2)
        .uppercase()
        .ifEmpty { "?" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        AccentSoft,
                        Color(0x0AFFAF5E), // fades toward transparent amber
                    ),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
                shape = shape,
            )
            .border(1.dp, BorderAmber, shape)
            .drawBehind {
                // Decorative radial glow in the top-right corner
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Accent.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.92f, size.height * 0.08f),
                        radius = size.minDimension * 0.55f,
                    ),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.92f, size.height * 0.08f),
                )
            }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // -- Top row: avatar + identity --
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Callsign avatar (QRZ photo with initials fallback)
                QrzAvatar(callsign = callsign, size = 56.dp, fallbackText = initials)

                // Callsign + grid
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = callsign.uppercase().ifEmpty { stringResource(R.string.op_no_call) },
                        color = TextPrimary,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = grid.uppercase().ifEmpty { stringResource(R.string.op_no_grid) },
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    if (onClick != null) {
                        Text(
                            text = stringResource(R.string.op_tap_to_edit),
                            color = TextFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // -- Divider --
            HorizontalDivider(
                color = BorderAmber,
                thickness = 1.dp,
            )

            // -- Mini stats row --
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MiniStat(label = stringResource(R.string.op_stat_rig), value = rigName)
                MiniStat(label = stringResource(R.string.op_stat_antenna), value = antenna)
                MiniStat(label = stringResource(R.string.op_stat_power), value = power)
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.sp,
        )
        Text(
            text = value,
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
