package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.bandadvisor.BandAdvisor
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlags
import radio.ks3ckc.ft8af.flags.FlagEvaluation
import radio.ks3ckc.ft8af.flags.FlagOverride
import radio.ks3ckc.ft8af.flags.FlagValueSource
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.InterFamily
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested without Compose)
// ---------------------------------------------------------------------------

/** "Effective: ON · from remote config" — the transparency line per flag. */
internal fun effectiveValueLine(evaluation: FlagEvaluation): Pair<Int, Int> {
    val valueRes = if (evaluation.enabled) R.string.flags_value_on else R.string.flags_value_off
    val sourceRes = when (evaluation.source) {
        FlagValueSource.DEBUG_OVERRIDE -> R.string.flags_source_override
        FlagValueSource.REMOTE_CONFIG -> R.string.flags_source_remote
        FlagValueSource.BUILD_DEFAULT -> R.string.flags_source_default
    }
    return valueRes to sourceRes
}

internal fun overrideOptionLabelRes(override: FlagOverride): Int = when (override) {
    FlagOverride.DEFAULT -> R.string.flags_option_default
    FlagOverride.FORCED_ON -> R.string.flags_option_on
    FlagOverride.FORCED_OFF -> R.string.flags_option_off
}

/**
 * Developer feature-flag screen. Reached only from a BuildConfig.DEBUG-gated
 * row in Advanced settings, so it does not exist in release builds (and the
 * flag repository additionally ignores overrides there — defense in depth).
 *
 * Per flag: a tri-state selector (Default / Force on / Force off) plus the
 * effective value and which layer supplied it. Overrides persist across app
 * restarts. Also hosts the fixture-data toggle for offline UI development.
 */
@Composable
internal fun FeatureFlagScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Bumped after every mutation so the effective-value lines recompute.
    var revision by remember { mutableIntStateOf(0) }
    var useFixtures by remember { mutableStateOf(BandAdvisor.useFixtures(context)) }

    SettingsDetailScaffold(
        title = stringResource(R.string.flags_screen_title),
        onBack = onBack,
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                for (flag in FeatureFlag.entries) {
                    androidx.compose.runtime.key(flag, revision) {
                        FlagRow(
                            flag = flag,
                            onChanged = { revision++ },
                        )
                    }
                    if (flag != FeatureFlag.entries.last()) SectionDivider()
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                label = stringResource(R.string.flags_use_fixtures),
                description = stringResource(R.string.flags_use_fixtures_desc),
                toggle = useFixtures,
                onToggleChange = { enabled ->
                    useFixtures = enabled
                    BandAdvisor.setUseFixtures(context, enabled)
                },
            )
        }
    }
}

@Composable
private fun FlagRow(flag: FeatureFlag, onChanged: () -> Unit) {
    val evaluation = FeatureFlags.evaluate(flag)
    val store = FeatureFlags.overrideStore
    val current = store?.overrideFor(flag) ?: FlagOverride.DEFAULT
    val (valueRes, sourceRes) = effectiveValueLine(evaluation)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = flag.name,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
        )
        Text(
            text = stringResource(
                R.string.flags_row_effective,
                stringResource(valueRes),
                stringResource(sourceRes),
            ),
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = InterFamily,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(BgSurface)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in FlagOverride.entries) {
                val selected = option == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable {
                            store?.setOverride(flag, option)
                            onChanged()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(overrideOptionLabelRes(option)),
                        color = if (selected) Accent else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = InterFamily,
                    )
                }
            }
        }
    }
}
