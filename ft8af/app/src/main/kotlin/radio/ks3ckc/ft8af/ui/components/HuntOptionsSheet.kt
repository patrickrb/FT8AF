package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import radio.ks3ckc.ft8af.hunt.HuntPriority
import radio.ks3ckc.ft8af.theme.*

// ---- Pure logic (extracted for unit testing) ----

/**
 * The min-SNR floor choices offered in the sheet; null = no floor. Values follow
 * the practical FT8 range: −10 dB is "armchair copy", −20 dB is near the decode
 * limit where a completed QSO becomes a coin flip.
 */
internal val HUNT_MIN_SNR_CHOICES: List<Int?> = listOf(null, -10, -15, -20)

/**
 * The short tag shown on the HUNT button while a non-default priority is armed
 * (mirrors the CQ button's FREE/FD subtitle). Null for the default so the
 * button stays clean when Hunt behaves classically.
 */
internal fun huntStripSubtitle(priority: HuntPriority): String? = when (priority) {
    HuntPriority.LATEST -> null
    HuntPriority.STRONGEST -> "STRONG"
    HuntPriority.WEAKEST -> "WEAK"
    HuntPriority.FARTHEST -> "DX"
    HuntPriority.POTA_FIRST -> "POTA"
    HuntPriority.NEW_DXCC_FIRST -> "DXCC"
    HuntPriority.NEW_GRID_FIRST -> "GRID"
}

/** Chip label for a min-SNR choice, e.g. "−15 dB"; null (= off) is localized by the caller. */
internal fun huntMinSnrChipLabel(minSnrDb: Int): String = "−${-minSnrDb} dB"

// ---- Composable ----

@Composable
private fun priorityChipLabel(priority: HuntPriority): String = stringResource(
    when (priority) {
        HuntPriority.LATEST -> R.string.hunt_priority_latest
        HuntPriority.STRONGEST -> R.string.hunt_priority_strongest
        HuntPriority.WEAKEST -> R.string.hunt_priority_weakest
        HuntPriority.FARTHEST -> R.string.hunt_priority_farthest
        HuntPriority.POTA_FIRST -> R.string.hunt_priority_pota
        HuntPriority.NEW_DXCC_FIRST -> R.string.hunt_priority_new_dxcc
        HuntPriority.NEW_GRID_FIRST -> R.string.hunt_priority_new_grid
    },
)

@Composable
private fun priorityDescription(priority: HuntPriority): String = stringResource(
    when (priority) {
        HuntPriority.LATEST -> R.string.hunt_priority_desc_latest
        HuntPriority.STRONGEST -> R.string.hunt_priority_desc_strongest
        HuntPriority.WEAKEST -> R.string.hunt_priority_desc_weakest
        HuntPriority.FARTHEST -> R.string.hunt_priority_desc_farthest
        HuntPriority.POTA_FIRST -> R.string.hunt_priority_desc_pota
        HuntPriority.NEW_DXCC_FIRST -> R.string.hunt_priority_desc_new_dxcc
        HuntPriority.NEW_GRID_FIRST -> R.string.hunt_priority_desc_new_grid
    },
)

/**
 * Hunt options — opened from the notch on the HUNT button (mirroring the CQ
 * options sheet). Lets the operator pick which CQ Hunt answers first when
 * several are decoded in the same cycle, plus two "smart filter" preferences
 * (pileup avoidance, minimum-signal floor).
 */
@Composable
fun HuntOptionsSheet(
    visible: Boolean,
    huntEnabled: Boolean,
    priority: HuntPriority,
    avoidPileups: Boolean,
    minSnrDb: Int?,
    onDismiss: () -> Unit,
    onSelectPriority: (HuntPriority) -> Unit,
    onAvoidPileupsChange: (Boolean) -> Unit,
    onMinSnrChange: (Int?) -> Unit,
    onStartHunt: () -> Unit,
) {
    FT8AFBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ---- Section: HUNT PRIORITY ----
            SheetSectionHeader(stringResource(R.string.hunt_priority_title))

            Spacer(modifier = Modifier.height(8.dp))

            val chipShape = RoundedCornerShape(999.dp)
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in HuntPriority.entries) {
                    val isSelected = priority == p
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(chipShape)
                            .background(if (isSelected) AccentSoft else BgSurface2, chipShape)
                            .border(1.dp, if (isSelected) BorderAmber else Border, chipShape)
                            .clickable { onSelectPriority(p) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = priorityChipLabel(p),
                            color = if (isSelected) Accent else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                            letterSpacing = 0.02.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // What the selected priority actually does — keeps the short chip
            // labels honest without cluttering the row.
            Text(
                text = priorityDescription(priority),
                color = TextFaint,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Section: SMART FILTERS ----
            SheetSectionHeader(stringResource(R.string.hunt_filters_title))

            Spacer(modifier = Modifier.height(4.dp))

            // Avoid pileups
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.hunt_avoid_pileups),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                    )
                    Text(
                        text = stringResource(R.string.hunt_avoid_pileups_desc),
                        color = TextFaint,
                        fontSize = 11.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }
                Switch(
                    checked = avoidPileups,
                    onCheckedChange = onAvoidPileupsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = AccentSoft,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = BgSurface3,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Min-signal floor
            Text(
                text = stringResource(R.string.hunt_min_snr),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
            )
            Text(
                text = stringResource(R.string.hunt_min_snr_desc),
                color = TextFaint,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (choice in HUNT_MIN_SNR_CHOICES) {
                    val isSelected = minSnrDb == choice
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(chipShape)
                            .background(if (isSelected) AccentSoft else BgSurface2, chipShape)
                            .border(1.dp, if (isSelected) BorderAmber else Border, chipShape)
                            .clickable { onMinSnrChange(choice) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = choice?.let { huntMinSnrChipLabel(it) }
                                ?: stringResource(R.string.hunt_min_snr_off),
                            color = if (isSelected) Accent else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                            letterSpacing = 0.02.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.hunt_footer_note),
                color = TextFaint,
                fontSize = 10.sp,
                fontFamily = GeistMonoFamily,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Action button: start Hunt with these options (or just close) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent)
                    .clickable {
                        if (!huntEnabled) onStartHunt()
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FT8AFIcons.Target(size = 18.dp, color = BgApp, strokeWidth = 1.8f)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (huntEnabled) stringResource(R.string.hunt_done)
                    else stringResource(R.string.hunt_start),
                    color = BgApp,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.04.sp,
                )
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = GeistMonoFamily,
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}
