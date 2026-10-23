package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [txRxSplitFromSynFrequency] and [synFrequencyFromTxRxSplit] —
 * the mapping between the "TX/RX Split" toggle in [TransmissionSettings] and
 * `GeneralVariables.synFrequency` (TX locked to RX). The toggle used to bind the
 * flag directly, so turning split on locked TX to RX and froze the audio
 * frequency at its stored offset (#947).
 */
class TxRxSplitToggleTest {

    @Test
    fun `split toggle shows on when TX is not locked to RX`() {
        assertThat(txRxSplitFromSynFrequency(synFrequency = false)).isTrue()
    }

    @Test
    fun `split toggle shows off when TX is locked to RX`() {
        assertThat(txRxSplitFromSynFrequency(synFrequency = true)).isFalse()
    }

    @Test
    fun `turning split on unlocks TX from RX`() {
        assertThat(synFrequencyFromTxRxSplit(split = true)).isFalse()
    }

    @Test
    fun `turning split off locks TX to RX`() {
        assertThat(synFrequencyFromTxRxSplit(split = false)).isTrue()
    }

    @Test
    fun `toggle round-trips the stored flag`() {
        for (stored in listOf(true, false)) {
            assertThat(synFrequencyFromTxRxSplit(txRxSplitFromSynFrequency(stored)))
                .isEqualTo(stored)
        }
    }

    @Test
    fun `hold TX freq toggle is enabled when TX is locked to RX`() {
        assertThat(holdTxFreqToggleEnabled(synFrequency = true)).isTrue()
    }

    @Test
    fun `hold TX freq toggle is disabled in split mode`() {
        // In split mode the offset never follows the answered station, so
        // holding it has no effect.
        assertThat(holdTxFreqToggleEnabled(synFrequency = false)).isFalse()
    }
}
