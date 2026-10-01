package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FlagEvaluation
import radio.ks3ckc.ft8af.flags.FlagOverride
import radio.ks3ckc.ft8af.flags.FlagValueSource

class FeatureFlagScreenTest {

    @Test
    fun `effective line reflects value and source`() {
        val onFromRemote = effectiveValueLine(
            FlagEvaluation(FeatureFlag.BAND_ADVISOR, true, FlagValueSource.REMOTE_CONFIG),
        )
        assertThat(onFromRemote.first).isEqualTo(R.string.flags_value_on)
        assertThat(onFromRemote.second).isEqualTo(R.string.flags_source_remote)

        val offFromOverride = effectiveValueLine(
            FlagEvaluation(FeatureFlag.BAND_ADVISOR, false, FlagValueSource.DEBUG_OVERRIDE),
        )
        assertThat(offFromOverride.first).isEqualTo(R.string.flags_value_off)
        assertThat(offFromOverride.second).isEqualTo(R.string.flags_source_override)

        val fromDefault = effectiveValueLine(
            FlagEvaluation(FeatureFlag.PROPAGATION_ALERTS, false, FlagValueSource.BUILD_DEFAULT),
        )
        assertThat(fromDefault.second).isEqualTo(R.string.flags_source_default)
    }

    @Test
    fun `every tri-state option has a distinct label`() {
        val labels = FlagOverride.entries.map { overrideOptionLabelRes(it) }
        assertThat(labels.toSet()).hasSize(FlagOverride.entries.size)
    }
}
