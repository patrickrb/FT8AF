package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [tuneChipLabel] — the TUNE chip's label logic (issue #408).
 * While the carrier is up, the label carries the live countdown of the
 * code-enforced safety timeout so the operator sees it running. Pure logic,
 * no runner needed.
 */
class TuneChipLabelTest {

    @Test
    fun idle_isJustTheLabel() {
        assertThat(tuneChipLabel("TUNE", isTuning = false, remainingSec = 0))
            .isEqualTo("TUNE")
        // Whatever remaining value is left over from the last run is ignored.
        assertThat(tuneChipLabel("TUNE", isTuning = false, remainingSec = 7))
            .isEqualTo("TUNE")
    }

    @Test
    fun active_appendsTheCountdown() {
        assertThat(tuneChipLabel("TUNE", isTuning = true, remainingSec = 10))
            .isEqualTo("TUNE 10s")
        assertThat(tuneChipLabel("TUNE", isTuning = true, remainingSec = 1))
            .isEqualTo("TUNE 1s")
    }

    @Test
    fun active_negativeRemaining_clampsToZero() {
        // A racing final post must never render "-1s".
        assertThat(tuneChipLabel("TUNE", isTuning = true, remainingSec = -1))
            .isEqualTo("TUNE 0s")
    }
}
