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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.bandadvisor.UnavailableReason
import radio.ks3ckc.ft8af.bandadvisor.model.AdvisorTargetRegion
import radio.ks3ckc.ft8af.bandadvisor.model.BandRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.EvidenceType
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.bandadvisor.model.PersonalAnalyticsSummary
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface3
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.InterFamily
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet
import radio.ks3ckc.ft8af.ui.components.formatMhz

/** Goal labels for the selector chips. */
internal fun goalLabelRes(goal: OperatingGoal): Int = when (goal) {
    OperatingGoal.MAKE_CONTACT -> R.string.band_advisor_goal_contact
    OperatingGoal.DX -> R.string.band_advisor_goal_dx
    OperatingGoal.TARGET -> R.string.band_advisor_goal_target
    OperatingGoal.POTA -> R.string.band_advisor_goal_pota
}

internal fun targetRegionLabelRes(region: AdvisorTargetRegion): Int =
    when (region) {
        AdvisorTargetRegion.EUROPE -> R.string.band_advisor_region_europe
        AdvisorTargetRegion.NORTH_AMERICA_EAST -> R.string.band_advisor_region_na_east
        AdvisorTargetRegion.NORTH_AMERICA_WEST -> R.string.band_advisor_region_na_west
        AdvisorTargetRegion.SOUTH_AMERICA -> R.string.band_advisor_region_south_america
        AdvisorTargetRegion.AFRICA -> R.string.band_advisor_region_africa
        AdvisorTargetRegion.ASIA -> R.string.band_advisor_region_asia
        AdvisorTargetRegion.OCEANIA -> R.string.band_advisor_region_oceania
    }

/**
 * Band Advisor detail sheet: recommendation + dial + confidence, goal chips,
 * destinations, freshness, expandable evidence ("Why this band"), alternatives
 * with their tune buttons, the personal PSK Reporter panel (flag-gated by the
 * caller passing null), and explicit partial/stale/offline states. The UI
 * leads with the recommendation; the propagation detail hides behind the
 * expandable explanation.
 */
@Composable
fun BandAdvisorSheet(
    visible: Boolean,
    state: BandAdvisorUiState,
    goal: OperatingGoal,
    targetRegion: AdvisorTargetRegion,
    onSelectTargetRegion: (AdvisorTargetRegion) -> Unit,
    catAvailable: Boolean,
    nowMs: Long,
    /** Null hides the personal section entirely (flag off / no callsign). */
    personalPanel: PersonalPanelState?,
    onDismiss: () -> Unit,
    onSelectGoal: (OperatingGoal) -> Unit,
    onRefresh: () -> Unit,
    onTune: (frequencyHz: Long) -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.band_advisor_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
                Text(
                    text = stringResource(R.string.band_advisor_refresh),
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // ---- Goal selector ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (g in OperatingGoal.entries) {
                    GoalChip(
                        label = stringResource(goalLabelRes(g)),
                        selected = g == goal,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectGoal(g) },
                    )
                }
            }

            if (goal == OperatingGoal.TARGET) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(stringResource(targetRegionLabelRes(targetRegion)), color = Accent)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (region in AdvisorTargetRegion.entries) {
                            DropdownMenuItem(
                                text = { Text(stringResource(targetRegionLabelRes(region))) },
                                onClick = {
                                    expanded = false
                                    onSelectTargetRegion(region)
                                },
                            )
                        }
                    }
                }
            }

            when (state) {
                is BandAdvisorUiState.Ready -> ReadyBody(
                    recommendation = state.recommendation,
                    freshness = state.freshness,
                    catAvailable = catAvailable,
                    nowMs = nowMs,
                    onTune = onTune,
                )
                is BandAdvisorUiState.Loading -> StateLine(
                    stringResource(R.string.band_advisor_loading),
                )
                is BandAdvisorUiState.NoGrid -> StateLine(
                    stringResource(R.string.band_advisor_no_grid_detail),
                )
                is BandAdvisorUiState.Unavailable -> StateLine(
                    when (state.reason) {
                        UnavailableReason.OFFLINE ->
                            stringResource(R.string.band_advisor_offline)
                        UnavailableReason.RATE_LIMITED ->
                            stringResource(R.string.band_advisor_rate_limited)
                        else -> stringResource(R.string.band_advisor_unavailable)
                    },
                )
                is BandAdvisorUiState.Hidden -> Unit
            }

            // ---- Personal PSK Reporter analytics ----
            if (personalPanel != null) {
                PersonalAnalyticsPanel(panel = personalPanel, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun ReadyBody(
    recommendation: BandRecommendation,
    freshness: Freshness,
    catAvailable: Boolean,
    nowMs: Long,
    onTune: (Long) -> Unit,
) {
    val rec = recommendation
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Recommendation ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Accent.copy(alpha = 0.08f))
                .border(1.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rec.recommendedBand,
                    color = Accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                )
                Text(
                    text = "${formatMhz(rec.recommendedFrequencyHz)} MHz",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                )
            }
            Text(
                text = stringResource(
                    R.string.band_advisor_rating_confidence,
                    stringResource(ratingLabelRes(rec.score)),
                    (rec.confidence * 100).toInt(),
                ),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
            )
            if (rec.summary.isNotBlank()) {
                Text(
                    text = rec.summary,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = InterFamily,
                )
            }
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
            Spacer(modifier = Modifier.height(2.dp))
            TuneButton(
                band = rec.recommendedBand,
                frequencyHz = rec.recommendedFrequencyHz,
                catAvailable = catAvailable,
                onTune = { onTune(rec.recommendedFrequencyHz) },
            )
        }

        // ---- Freshness / partial-data status ----
        val minutes = ageMinutes(rec.generatedAtMs, nowMs)
        val freshnessText = when (freshness) {
            Freshness.FRESH -> stringResource(R.string.band_advisor_updated_min, minutes)
            Freshness.STALE -> stringResource(R.string.band_advisor_stale_min, minutes)
            Freshness.EXPIRED -> stringResource(R.string.band_advisor_expired_detail)
        }
        Text(
            text = freshnessText,
            color = if (freshness == Freshness.FRESH) TextFaint else StatusWarn,
            fontSize = 11.sp,
            fontFamily = InterFamily,
        )
        if (rec.sources.isPartial) {
            val missing = buildList {
                if (!rec.sources.voacapAvailable) add(stringResource(R.string.band_advisor_src_voacap))
                if (!rec.sources.regionalPskReporterAvailable) {
                    add(stringResource(R.string.band_advisor_src_regional))
                }
            }
            if (missing.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.band_advisor_partial_sources,
                        missing.joinToString(", "),
                    ),
                    color = StatusWarn,
                    fontSize = 11.sp,
                    fontFamily = InterFamily,
                )
            }
        }

        // ---- Why this band (expandable evidence + score components) ----
        var showWhy by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showWhy = !showWhy }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.band_advisor_why),
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (showWhy) "△" else "▽",
                color = TextFaint,
                fontSize = 11.sp,
            )
        }
        if (showWhy) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgSurface3)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (evidence in rec.evidence) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = when (evidence.type) {
                                EvidenceType.VOACAP ->
                                    stringResource(R.string.band_advisor_ev_voacap)
                                EvidenceType.REGIONAL_PSK_REPORTER ->
                                    stringResource(R.string.band_advisor_ev_regional)
                                EvidenceType.PERSONAL_PSK_REPORTER ->
                                    stringResource(R.string.band_advisor_ev_personal)
                                EvidenceType.OTHER -> ""
                            },
                            color = Accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistMonoFamily,
                            modifier = Modifier.width(76.dp),
                        )
                        Text(
                            text = evidence.message,
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontFamily = InterFamily,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (rec.scoreComponents.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    for (component in rec.scoreComponents) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = component.name,
                                color = TextFaint,
                                fontSize = 10.sp,
                                fontFamily = GeistMonoFamily,
                            )
                            Text(
                                text = String.format(
                                    java.util.Locale.US,
                                    "%.2f × %.2f = %.2f",
                                    component.value, component.weight, component.contribution,
                                ),
                                color = TextFaint,
                                fontSize = 10.sp,
                                fontFamily = GeistMonoFamily,
                            )
                        }
                    }
                }
            }
        }

        // ---- Alternatives ----
        if (rec.alternatives.isNotEmpty()) {
            Text(
                text = stringResource(R.string.band_advisor_alternatives),
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
            )
            for (alt in rec.alternatives) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface3)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable { onTune(alt.frequencyHz) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = alt.band,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                        modifier = Modifier.width(42.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${formatMhz(alt.frequencyHz)} MHz",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontFamily = GeistMonoFamily,
                        )
                        if (alt.reason.isNotBlank()) {
                            Text(
                                text = alt.reason,
                                color = TextFaint,
                                fontSize = 11.sp,
                                fontFamily = InterFamily,
                            )
                        }
                    }
                    Text(
                        text = "${(alt.score * 100).toInt()}",
                        color = TextFaint,
                        fontSize = 12.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateLine(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 13.sp,
        fontFamily = InterFamily,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun GoalChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Accent else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = InterFamily,
        )
    }
}

// ---------------------------------------------------------------------------
// Personal PSK Reporter panel
// ---------------------------------------------------------------------------

/** State for the personal analytics panel. */
sealed interface PersonalPanelState {
    object Loading : PersonalPanelState

    /** No recent reports — the honest empty state. */
    object Empty : PersonalPanelState

    object Error : PersonalPanelState

    data class Ready(
        val reports: Int,
        val uniqueReceivers: Int,
        val uniqueGridFields: Int,
        val countriesReached: Int,
        val maxDistanceKm: Int,
        val medianDistanceKm: Int,
        val bestSnrDb: Int?,
        val medianSnrDb: Int?,
        val directionOctants: Int,
        val mostRecentReportEpochSec: Long?,
        /** Null = suppressed (small sample) — never shown as zero. */
        val vsBaseline: Double?,
    ) : PersonalPanelState
}

/** Build panel state from a server-provided summary (detail payload). Pure. */
internal fun personalPanelFromSummary(summary: PersonalAnalyticsSummary?): PersonalPanelState? {
    summary ?: return null
    if (!summary.enabled) return null
    if (summary.reportsReceived == 0) return PersonalPanelState.Empty
    return PersonalPanelState.Ready(
        reports = summary.reportsReceived,
        uniqueReceivers = summary.uniqueReceivers,
        uniqueGridFields = 0,
        countriesReached = summary.countriesReached,
        maxDistanceKm = summary.maximumDistanceKm,
        medianDistanceKm = 0,
        bestSnrDb = summary.bestSnrDb,
        medianSnrDb = summary.medianSnrDb,
        directionOctants = 0,
        mostRecentReportEpochSec = null,
        vsBaseline = summary.performanceComparedToBaseline,
    )
}

/**
 * "Your signal" panel. Every line is explicit that these are stations that
 * HEARD the operator (reception reports), never contacts. The baseline
 * comparison renders only when the sample was big enough for the computation
 * to produce one (small samples arrive as null and show nothing).
 */
@Composable
internal fun PersonalAnalyticsPanel(panel: PersonalPanelState, nowMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.band_advisor_personal_title),
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.08.sp,
        )
        when (panel) {
            is PersonalPanelState.Loading -> Text(
                text = stringResource(R.string.band_advisor_personal_loading),
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = InterFamily,
            )
            is PersonalPanelState.Empty -> Text(
                text = stringResource(R.string.band_advisor_personal_empty),
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = InterFamily,
            )
            is PersonalPanelState.Error -> Text(
                text = stringResource(R.string.band_advisor_personal_error),
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = InterFamily,
            )
            is PersonalPanelState.Ready -> {
                Text(
                    text = stringResource(
                        R.string.band_advisor_personal_heard_by,
                        panel.uniqueReceivers,
                        panel.reports,
                    ),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
                StatRow(
                    label = stringResource(R.string.band_advisor_personal_reach),
                    value = if (panel.countriesReached > 0) {
                        stringResource(
                            R.string.band_advisor_personal_reach_value,
                            panel.countriesReached,
                            panel.maxDistanceKm,
                        )
                    } else {
                        stringResource(
                            R.string.band_advisor_personal_reach_km_only,
                            panel.maxDistanceKm,
                        )
                    },
                )
                if (panel.bestSnrDb != null && panel.medianSnrDb != null) {
                    StatRow(
                        label = stringResource(R.string.band_advisor_personal_snr),
                        value = stringResource(
                            R.string.band_advisor_personal_snr_value,
                            panel.medianSnrDb,
                            panel.bestSnrDb,
                        ),
                    )
                }
                if (panel.vsBaseline != null) {
                    val pct = (panel.vsBaseline * 100).toInt()
                    StatRow(
                        label = stringResource(R.string.band_advisor_personal_vs_baseline),
                        value = if (pct >= 0) "+$pct%" else "$pct%",
                    )
                }
                if (panel.mostRecentReportEpochSec != null) {
                    val minutes = ageMinutes(panel.mostRecentReportEpochSec * 1000, nowMs)
                    Text(
                        text = stringResource(R.string.band_advisor_personal_last_report, minutes),
                        color = TextFaint,
                        fontSize = 11.sp,
                        fontFamily = InterFamily,
                    )
                }
                Text(
                    text = stringResource(R.string.band_advisor_personal_disclaimer),
                    color = TextFaint,
                    fontSize = 10.sp,
                    fontFamily = InterFamily,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = InterFamily,
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
        )
    }
}
