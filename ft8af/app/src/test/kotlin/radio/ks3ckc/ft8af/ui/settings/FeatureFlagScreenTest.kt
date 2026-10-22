package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FlagEvaluation
import radio.ks3ckc.ft8af.flags.FlagOverride
import radio.ks3ckc.ft8af.flags.FlagOverrideStore
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

    private class RecordingOverrides : FlagOverrideStore {
        val saved = mutableMapOf<FeatureFlag, FlagOverride>()
        override fun overrideFor(flag: FeatureFlag) = saved[flag] ?: FlagOverride.DEFAULT
        override fun setOverride(flag: FeatureFlag, override: FlagOverride) {
            saved[flag] = override
        }
    }

    @Test
    fun `applying an override persists it then reconciles`() {
        val store = RecordingOverrides()
        val events = mutableListOf<String>()
        applyFlagOverride(store, FeatureFlag.PROPAGATION_ALERTS, FlagOverride.FORCED_ON) {
            // The reconcile must observe the override already saved.
            events += "reconcile:${store.overrideFor(FeatureFlag.PROPAGATION_ALERTS)}"
        }
        assertThat(events).containsExactly("reconcile:FORCED_ON")

        applyFlagOverride(store, FeatureFlag.PROPAGATION_ALERTS, FlagOverride.FORCED_OFF) {
            events += "reconcile:${store.overrideFor(FeatureFlag.PROPAGATION_ALERTS)}"
        }
        assertThat(events).containsExactly("reconcile:FORCED_ON", "reconcile:FORCED_OFF").inOrder()
    }

    @Test
    fun `no override store means nothing to save or reconcile`() {
        var reconciled = false
        applyFlagOverride(null, FeatureFlag.PROPAGATION_ALERTS, FlagOverride.FORCED_ON) {
            reconciled = true
        }
        assertThat(reconciled).isFalse()
    }

    @Test
    fun `every tri-state option has a distinct label`() {
        val labels = FlagOverride.entries.map { overrideOptionLabelRes(it) }
        assertThat(labels.toSet()).hasSize(FlagOverride.entries.size)
    }
}
