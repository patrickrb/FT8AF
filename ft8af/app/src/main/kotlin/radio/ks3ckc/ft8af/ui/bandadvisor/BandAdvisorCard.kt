package radio.ks3ckc.ft8af.ui.bandadvisor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface3
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.InterFamily
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.formatMhz

/**
 * The compact Band Advisor card shown at the top of the Band & Mode sheet.
 *
 * Collapsed contract (see docs/band-advisor.md):
 *   Best band now: 20m
 *   Excellent • 87% confidence
 *   Europe and South America are open
 *   [Switch to 20m]  (or "Set radio to 14.074 MHz" when CAT is unavailable)
 *
 * Tapping the card body opens the detail sheet; only the explicit button
 * tunes. Renders nothing in the Hidden state (flag off) — callers may also
 * skip it entirely.
 */
@Composable
fun BandAdvisorCard(
    state: BandAdvisorUiState,
    catAvailable: Boolean,
    onOpenDetails: () -> Unit,
    onTune: (frequencyHz: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is BandAdvisorUiState.Hidden) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onOpenDetails() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.band_advisor_card_title),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.08.sp,
            )
            if (state is BandAdvisorUiState.Ready && state.freshness != Freshness.FRESH) {
                Text(
                    text = stringResource(
                        if (state.freshness == Freshness.STALE) R.string.band_advisor_stale
                        else R.string.band_advisor_expired,
                    ),
                    color = StatusWarn,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
            }
        }

        when (state) {
            is BandAdvisorUiState.Ready -> {
                val rec = state.recommendation
                Text(
                    text = stringResource(R.string.band_advisor_best_band, rec.recommendedBand),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFamily,
                )
                Text(
                    text = stringResource(
                        R.string.band_advisor_rating_confidence,
                        stringResource(ratingLabelRes(rec.score)),
                        (rec.confidence * 100).toInt(),
                    ),
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
                if (rec.destinations.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.band_advisor_destinations_open,
                            rec.destinations.joinToString(", "),
                        ),
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = InterFamily,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TuneButton(
                    band = rec.recommendedBand,
                    frequencyHz = rec.recommendedFrequencyHz,
                    catAvailable = catAvailable,
                    onTune = { onTune(rec.recommendedFrequencyHz) },
                )
            }
            is BandAdvisorUiState.Loading -> CardBodyLine(stringResource(R.string.band_advisor_loading))
            is BandAdvisorUiState.NoGrid -> CardBodyLine(stringResource(R.string.band_advisor_no_grid))
            is BandAdvisorUiState.Unavailable ->
                CardBodyLine(stringResource(R.string.band_advisor_unavailable_short))
            is BandAdvisorUiState.Hidden -> Unit
        }
    }
}

@Composable
private fun CardBodyLine(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 13.sp,
        fontFamily = InterFamily,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * The tuning affordance. With CAT it is an action ("Switch to 20m"); without,
 * it degrades to the dial the operator should set by hand — still tappable so
 * the app's own band state follows the recommendation.
 */
@Composable
internal fun TuneButton(
    band: String,
    frequencyHz: Long,
    catAvailable: Boolean,
    onTune: () -> Unit,
) {
    val label = if (catAvailable) {
        stringResource(R.string.band_advisor_switch_to, band)
    } else {
        stringResource(R.string.band_advisor_set_radio_to, formatMhz(frequencyHz))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.16f))
            .clickable { onTune() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
        )
    }
}
